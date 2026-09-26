/**
 * mixer-plugin integration for Tomato6966/lavalink-client (v2.9+).
 *
 * What this wires up:
 *  - Gapless + crossfade: keeps ONE track preloaded server-side ("1-ahead").
 *    The server starts it the instant the current track ends — the client
 *    must NOT send `play` on trackEnd (see `autoSkip: false` below).
 *  - Leading/trailing silence skipping via the plugin (pre-analysis +
 *    realtime tail cut). Nothing extra needed client-side beyond preloading.
 *  - TTS/announcement overlay without touching the music queue.
 *
 * REQUIRED manager setup (do this where you create the LavalinkManager):
 *
 * ```ts
 * const lavalink = new LavalinkManager({
 *   nodes: [{ authorization, host, port, id: "main" }],
 *   sendToShard,
 *   autoSkip: false, // REQUIRED: the server advances tracks itself
 *   client: { id: process.env.CLIENT_ID, username: "MyBot" },
 * });
 * attachMixer(lavalink, { crossfade: { enabled: true, durationMs: 5000 } });
 * ```
 *
 * Notes / gotchas (all verified against lavalink-client source):
 *  - `player.filterManager.applyPlayerFilters()` passes unknown
 *    `pluginFilters` keys (like `mixer`) through untouched. But
 *    `resetFilters()` wipes them — re-run `enableMixerFilter()` after it.
 *    `setEQ()` only sends the equalizer (partial update), so it is safe.
 *  - `player.node.request()` prefixes `/v4/`, which cannot reach `/mixer/*`,
 *    so this file uses plain `fetch` against the node's host/port/auth.
 *  - guildIds are ALWAYS sent as strings (snowflakes exceed 2^53).
 *  - Queue identifiers prefer `track.encoded` (exact, no re-resolution);
 *    unresolved upcoming tracks fall back to `info.uri` / `info.identifier`.
 */

const MIXER_FILTER_KEY = "mixer";

export interface MixerIntegrationOptions {
  crossfade?: { enabled?: boolean; durationMs?: number };
  fadeInMs?: number;
  silence?: {
    enabled?: boolean;
    thresholdDb?: number;
    minSoundMs?: number;
    headScanMs?: number;
    tailScanMs?: number;
    tailConfirmMs?: number;
  };
  /** Watchdog: if the server did not auto-start the next track within this
   *  time after trackEnd, resume manually. Default 5000ms. */
  confirmTimeoutMs?: number;
}

// ---------------------------------------------------------------------------
// Low-level REST (plain fetch — node.request() can't reach /mixer/*)
// ---------------------------------------------------------------------------

function nodeRest(player: any): { base: string; auth: string } {
  const o = player?.node?.options ?? {};
  const proto = o.secure ? "https" : "http";
  return { base: `${proto}://${o.host}:${o.port}`, auth: o.authorization };
}

async function mixerCall(player: any, path: string, init?: RequestInit): Promise<any> {
  const { base, auth } = nodeRest(player);
  const res = await fetch(`${base}${path}`, {
    ...init,
    headers: {
      Authorization: auth,
      "Content-Type": "application/json",
      ...(init?.headers ?? {}),
    },
  });
  if (res.status === 204) return null;
  const text = await res.text();
  let body: any = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = { message: text };
  }
  if (!res.ok) {
    const err = new Error(`mixer ${path} -> ${res.status}: ${body?.message ?? text}`) as any;
    err.status = res.status;
    err.body = body;
    throw err;
  }
  return body;
}

/** One-time per guild: put the mixer into the filter chain (survives track changes). */
export async function enableMixerFilter(player: any): Promise<void> {
  const fm = player.filterManager;
  fm.data.pluginFilters = {
    ...(fm.data.pluginFilters ?? {}),
    [MIXER_FILTER_KEY]: { guildId: String(player.guildId) },
  } as any;
  await fm.applyPlayerFilters();
}

// ---------------------------------------------------------------------------
// Track references (prefer exact base64, fall back for unresolved tracks)
// ---------------------------------------------------------------------------

export function trackRef(track: any): { encodedTrack?: string; identifier?: string } | null {
  const encoded = track?.encoded;
  if (typeof encoded === "string" && encoded.length > 0) return { encodedTrack: encoded };
  const uri = track?.info?.uri;
  if (typeof uri === "string" && uri.length > 0) return { identifier: uri };
  const id = track?.info?.identifier;
  if (typeof id === "string" && id.length > 0) return { identifier: id };
  return null;
}

function upcomingTrack(player: any): any | null {
  const tracks = player?.queue?.tracks;
  return Array.isArray(tracks) && tracks.length > 0 ? tracks[0] : null;
}

/** Mark the TrackStart track as current WITHOUT sending play (server already did). */
function syncQueueToNowPlaying(player: any, track: any): void {
  const q = player?.queue;
  if (!q) return;
  const enc = track?.encoded;
  if (Array.isArray(q.tracks) && typeof enc === "string") {
    const idx = q.tracks.findIndex((t: any) => t?.encoded === enc);
    if (idx >= 0) {
      const [now] = q.tracks.splice(idx, 1);
      if (q.current && q.current?.encoded !== enc) {
        if (Array.isArray(q.previous)) {
          q.previous.unshift(q.current);
          while (q.previous.length > 25) q.previous.pop();
        }
      }
      q.current = now;
      return;
    }
  }
  if (q.current?.encoded !== enc) {
    if (q.current && Array.isArray(q.previous)) {
      q.previous.unshift(q.current);
      while (q.previous.length > 25) q.previous.pop();
    }
    q.current = track;
  }
}

// ---------------------------------------------------------------------------
// Main wiring
// ---------------------------------------------------------------------------

export interface MixerHandle {
  /** User skip: clears the stale server slot, then skips. ALWAYS use this instead of player.skip(). */
  skip(player: any): Promise<void>;
  /** Overlay audio (TTS clip URL, station ID jingle, ...) over the music. Throws on 409 (busy). */
  announce(player: any, ref: { identifier?: string; encodedTrack?: string }, duckLevel?: number): Promise<any>;
  cancelAnnounce(player: any): Promise<void>;
  state(player: any): Promise<any>;
  /** Force (re)preload of the upcoming client-queue track. */
  preloadUpcoming(player: any): Promise<void>;
}

export function attachMixer(manager: any, opts: MixerIntegrationOptions = {}): MixerHandle {
  // The whole design breaks if the client races the server on trackEnd.
  if (manager?.options) manager.options.autoSkip = false;

  const confirmTimeoutMs = opts.confirmTimeoutMs ?? 5000;
  const serverManaged = new Set<string>(); // guilds with a preloaded next track
  const filterReady = new Set<string>();
  const watchdogs = new Map<string, ReturnType<typeof setTimeout>>();

  async function pushStaticConfig(player: any): Promise<void> {
    const gid = String(player.guildId);
    try {
      if (opts.crossfade) {
        await mixerCall(player, "/mixer/crossfade", {
          method: "POST",
          body: JSON.stringify({ guildId: gid, enabled: opts.crossfade.enabled ?? true, durationMs: opts.crossfade.durationMs }),
        });
      }
      if (typeof opts.fadeInMs === "number") {
        await mixerCall(player, "/mixer/fade-in", {
          method: "POST",
          body: JSON.stringify({ guildId: gid, durationMs: opts.fadeInMs }),
        });
      }
      if (opts.silence) {
        await mixerCall(player, "/mixer/silence", {
          method: "POST",
          body: JSON.stringify({ guildId: gid, ...opts.silence }),
        });
      }
    } catch (e) {
      console.warn(`[mixer] static config failed for guild ${gid}:`, (e as Error)?.message);
    }
  }

  async function ensureFilter(player: any): Promise<void> {
    const gid = String(player.guildId);
    if (filterReady.has(gid)) return;
    try {
      await enableMixerFilter(player);
      filterReady.add(gid);
    } catch (e) {
      // Node may not be ready yet — trackStart will retry.
      console.warn(`[mixer] enableFilter failed for guild ${gid}, will retry:`, (e as Error)?.message);
    }
  }

  async function preloadUpcoming(player: any): Promise<void> {
    const gid = String(player.guildId);
    try {
      // Repeat modes own the transitions — stay out of the way.
      if (String((player as any)?.repeatMode ?? "off") !== "off") {
        serverManaged.delete(gid);
        return;
      }
      const next = upcomingTrack(player);
      if (!next) {
        serverManaged.delete(gid);
        return;
      }
      const ref = trackRef(next);
      if (!ref) {
        console.warn(`[mixer] cannot reference upcoming track, skipping preload (guild ${gid})`);
        return;
      }
      await mixerCall(player, "/mixer/queue/next", {
        method: "POST",
        body: JSON.stringify({ guildId: gid, ...ref }),
      });
      serverManaged.add(gid);
    } catch (e) {
      console.warn(`[mixer] preload failed (guild ${gid}):`, (e as Error)?.message);
    }
  }

  function armWatchdog(player: any): void {
    const gid = String(player.guildId);
    clearTimeout(watchdogs.get(gid));
    watchdogs.set(
      gid,
      setTimeout(() => {
        watchdogs.delete(gid);
        serverManaged.delete(gid);
        // Server never auto-advanced (e.g. preload failed silently) — resume manually.
        Promise.resolve()
          .then(() => (player as any)?.skip?.())
          .catch(() => {});
      }, confirmTimeoutMs),
    );
  }

  manager.on("playerCreate", async (player: any) => {
    await ensureFilter(player);
    await pushStaticConfig(player);
  });

  manager.on("trackStart", async (player: any, track: any) => {
    const gid = String(player.guildId);
    clearTimeout(watchdogs.get(gid));
    watchdogs.delete(gid);
    await ensureFilter(player);
    syncQueueToNowPlaying(player, track);
    await preloadUpcoming(player);
  });

  manager.on("trackEnd", async (player: any) => {
    const gid = String(player.guildId);
    if (!serverManaged.has(gid)) return; // client owns what happens next (queueEnd flow)
    serverManaged.delete(gid);
    armWatchdog(player);
  });

  // Server also advances on LOAD_FAILED — same treatment as trackEnd.
  const onProblem = async (player: any) => {
    const gid = String(player.guildId);
    if (!serverManaged.has(gid)) return;
    serverManaged.delete(gid);
    armWatchdog(player);
  };
  manager.on("trackError", onProblem);
  manager.on("trackStuck", onProblem);

  manager.on("playerDestroy", (player: any) => {
    const gid = String(player.guildId);
    serverManaged.delete(gid);
    filterReady.delete(gid);
    clearTimeout(watchdogs.get(gid));
    watchdogs.delete(gid);
  });

  return {
    async skip(player: any): Promise<void> {
      const gid = String(player.guildId);
      serverManaged.delete(gid);
      clearTimeout(watchdogs.get(gid));
      watchdogs.delete(gid);
      // Drop the slot preloaded for the SKIPPED track's successor first,
      // otherwise the server would replay a stale track at the next end.
      await mixerCall(player, `/mixer/queue/next?guildId=${encodeURIComponent(gid)}`, {
        method: "DELETE",
      }).catch(() => {});
      await player.skip();
      // The coming trackStart re-preloads the new successor.
    },

    async announce(
      player: any,
      ref: { identifier?: string; encodedTrack?: string },
      duckLevel?: number,
    ): Promise<any> {
      return mixerCall(player, "/mixer/announce", {
        method: "POST",
        body: JSON.stringify({ guildId: String(player.guildId), ...ref, duckLevel }),
      });
    },

    async cancelAnnounce(player: any): Promise<void> {
      await mixerCall(player, "/mixer/announce/cancel", {
        method: "POST",
        body: JSON.stringify({ guildId: String(player.guildId) }),
      });
    },

    async state(player: any): Promise<any> {
      const gid = encodeURIComponent(String(player.guildId));
      return mixerCall(player, `/mixer/state?guildId=${gid}`);
    },

    async preloadUpcoming(player: any): Promise<void> {
      await preloadUpcoming(player);
    },
  };
}
