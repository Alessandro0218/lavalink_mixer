# mixer-plugin × lavalink-client (Tomato6966) — bot wiring guide

From zero to a working bot with **gapless queue + crossfade + silence
skipping + TTS overlay**. `mixerPlugin.ts` is the drop-in wiring used in
step 4/5; tested against lavalink-client v2.9+ API.

## What you need

| Piece | Version | Why |
|---|---|---|
| Lavalink server | v4 | the audio node |
| `mixer-*.jar` | latest build | the plugin (gapless/crossfade/silence/overlay) |
| lavalink-client | v2.9+ | bot-side manager (`filterManager.data` passthrough, manager events, `player.skip()`) |
| discord.js | v14 (or any lib that can forward raw gateway events) | shard transport |

## Step 1 — Server: install the plugin

1. Copy `mixer-*.jar` into the Lavalink server's `plugins/` directory and
   restart. Verify with `GET /version` — the plugin must appear in
   `plugins`.
2. Optional `application.yml` defaults under `mixer:` (all overridable per
   guild via the wiring in step 4):

   ```yaml
   mixer:
     crossfadeEnabled: true
     crossfadeMs: 5000
     fadeInMs: 300
     duckLevel: 0.2
     duckFadeMs: 300
     maskMs: 80
     silenceSkipEnabled: false
     silenceThresholdDb: -60
     silenceMinSoundMs: 120
     silenceHeadScanMs: 15000
     silenceTailScanMs: 15000
     tailConfirmMs: 1000
     analysisThreads: 2
   ```

No other server config needed. The mixer filter is enabled per guild from
the bot (step 4 does it automatically).

## Step 2 — Bot: install lavalink-client

```bash
npm install lavalink-client
```

## Step 3 — Wire the LavalinkManager

`autoSkip: false` is **REQUIRED**. The server starts the preloaded track
itself; with `autoSkip: true` the client races the server's auto-advance on
every trackEnd (double-skips). `mixerPlugin.ts` forces it too.

```ts
import { Client, GatewayIntentBits } from "discord.js";
import { LavalinkManager } from "lavalink-client";
import { attachMixer } from "./mixerPlugin";

const client = new Client({
  intents: [
    GatewayIntentBits.Guilds,
    GatewayIntentBits.GuildVoiceStates,
  ],
});

const lavalink = new LavalinkManager({
  nodes: [
    { authorization: "youshallnotpass", host: "127.0.0.1", port: 2333, id: "main" },
  ],
  // Forward gateway payloads (VOICE_STATE_UPDATE) to the right shard:
  sendToShard: (guildId, payload) =>
    client.guilds.cache.get(guildId)?.shard?.send(payload),
  autoSkip: false, // REQUIRED: the server advances tracks itself
  client: { id: process.env.CLIENT_ID!, username: "MyBot" },
});

// raw gateway events -> lavalink-client
client.on("raw", (d) => lavalink.sendRawData(d));

client.once("ready", () => lavalink.init(client.user));

// Step 4 — attach the mixer integration:
const mixer = attachMixer(lavalink, {
  crossfade: { enabled: true, durationMs: 5000 },
  fadeInMs: 300,
  // silence: { enabled: true, thresholdDb: -60 }, // opt-in: one extra decode per track
});

client.login(process.env.TOKEN);
```

## Step 5 — Play something

First track: play normally — `queue.add()` + `player.play()`. The
`trackStart` handler (inside `attachMixer`) enables the mixer filter for
the guild and preloads the successor automatically. Every track after that
is started **by the server** — your code never sends `play` on trackEnd.

```ts
client.on("interactionCreate", async (i) => {
  if (!i.isChatInputCommand()) return;

  if (i.commandName === "play") {
    if (!i.member.voice.channelId) return i.reply("Join a voice channel first");

    const player = lavalink.createPlayer({
      guildId: i.guildId,
      voiceChannelId: i.member.voice.channelId,
    });
    await player.connect(); // first time only; safe to call again

    const res = await lavalink.search(
      { query: i.options.getString("query", true), source: "youtube" },
      i.user,
    );
    if (!res.tracks.length) return i.reply("No results");

    await player.queue.add(res.tracks[0]);
    if (!player.queue.current) await player.play(); // starts the FIRST track
    return i.reply(`Playing **${res.tracks[0].info.title}**`);
  }

  if (i.commandName === "skip") {
    const player = lavalink.players.get(i.guildId);
    if (!player) return i.reply("Nothing playing");
    await mixer.skip(player); // NOT player.skip() — see "User skip" below
    return i.reply("Skipped");
  }

  if (i.commandName === "announce") {
    const player = lavalink.players.get(i.guildId);
    if (!player) return i.reply("Nothing playing");
    try {
      // TTS clip hosted somewhere the Lavalink server can reach:
      await mixer.announce(player, {
        identifier: "https://tts.example.com/clip.mp3",
      });
      return i.reply("Announcing");
    } catch (e: any) {
      if (e?.status === 409) return i.reply("Mixer busy — try again in a second");
      throw e;
    }
  }
});
```

That's the whole integration. From here the server owns transitions:
current track ends → the preloaded next track starts instantly
(lead-trimmed, crossfaded) → `trackStart` fires on the bot → the client
queue is synced and the next successor is preloaded. No `play` sent by you.

## Event flow (what happens under the hood)

```
trackStart(A) → enable mixer filter (once) → POST /mixer/queue/next (B)
A content ends → server starts B instantly (lead-trimmed, crossfaded)
trackStart(B) → client queue synced (no play sent!) → POST /mixer/queue/next (C)
```

"Content ends" means `duration - trailingSilence`, not file end: detected
trailing silence is never played or crossfaded. With crossfade enabled the
overlap covers audible content only, and the handoff lands exactly at
content end. Without crossfade (or for streams) the server cuts to B at the
last audible sample.

- `trackEnd` → the wiring does nothing if we preloaded (server took over);
  a watchdog resumes via `player.skip()` if no `trackStart` follows in ~5s
  (`confirmTimeoutMs`).
- `trackError`/`trackStuck` → same treatment (the server advances on
  `LOAD_FAILED` too, and its own stuck failover hands off / advances before
  the event even reaches you).
- queue empty at `trackEnd` → normal `queueEnd` flow, untouched.

## Trim timing: pending → ready

`POST /mixer/queue/next` returns as soon as the track loads; silence
analysis finishes async. Check `GET /mixer/state` via `mixer.state(player)`:

- `hasNext: true`, `nextTrimMs: null` → queued but still analyzing
  ("pending"). The server still advances gaplessly, but without trim.
- `nextTrimMs: [leadMs, trailMs]` → analysis done ("ready"). The upcoming
  transition uses both ends.

Queue early (right after `trackStart`, which is what `attachMixer` does) so
analysis usually finishes before the transition. If analysis is still
running at content end, the server advances anyway (degraded, no trim)
rather than playing silence. Crossfade overlap itself waits for analysis to
release the sub player; a slow analysis degrades that one transition to a
cut.

First-track caveat: the track you start with plain `player.play()` never
went through the preload slot, so its own leading silence is only skipped
if its trim was already cached from an earlier preload. Every
server-advanced track after that gets lead-trimmed automatically.

## User skip — use the handle, not `player.skip()`

```ts
await mixer.skip(player);
```

Direct `player.skip()` leaves a stale preloaded track that the server would
replay at the next track end. `mixer.skip()` clears the slot first
(`DELETE /mixer/queue/next`), then skips; the coming `trackStart`
re-preloads the new successor.

## API of the returned handle

| Call | Effect |
|---|---|
| `mixer.skip(player)` | Clear the server slot, then `player.skip()` |
| `mixer.announce(player, { identifier? , encodedTrack? }, duckLevel?)` | Overlay audio (TTS/jingle) over ducked music; throws with `status: 409` when busy |
| `mixer.cancelAnnounce(player)` | Stop the overlay, restore music |
| `mixer.state(player)` | Mixer state + config + `nextTrimMs` |
| `mixer.preloadUpcoming(player)` | Force (re)preload of the upcoming client-queue track |

`identifier` is anything the **server** can load (URL, track identifier,
search prefix). Prefer `encodedTrack` (the exact base64 you already have) —
no re-resolution.

## Rules you must not break

1. **`autoSkip: false`** — the server advances tracks itself.
2. **Never send `play` on trackEnd** for server-advanced tracks — the
   wiring's `trackStart`/watchdog handles the rare failure cases.
3. **`resetFilters()` wipes the mixer key** — call
   `enableMixerFilter(player)` again afterwards (it re-puts
   `pluginFilters.mixer` and applies).
4. **Every filters op replaces the whole server-side chain** — never send a
   hand-built filters object without the mixer block; prefer the
   filterManager (`setEQ()` is a partial update, safe anytime).
5. **Repeat modes** (`player.repeatMode !== "off"`): preloading is skipped —
   the repeater owns transitions and the plugin would fight it.
   Gapless/crossfade/silence trim apply to linear queues.
6. **guildIds are always strings** — snowflakes exceed 2^53.

## Troubleshooting

- **TrackStuck events**: with the current plugin build the server fails
  over on its own (handoff during crossfade, gapless advance when a track
  is queued) before the stuck event reaches you. A stuck event therefore
  means: no queued track and no crossfade — the wiring's watchdog resumes
  via `player.skip()` within ~5s. If it keeps happening, check the server
  logs for the real stall (slow source / rate limit).
- **409 on announce** — secondary player busy (crossfade overlap / overlay
  / silence analysis running) → retry later, or
  `mixer.cancelAnnounce(player)` + retry. `nextTrimMs` appearing in
  `mixer.state()` means the sub player is free again.
- **Preload failed silently** — the watchdog covers it, but check the
  server logs; unreferenceable upcoming tracks are logged and skipped.
- **No audible effect at all** — the mixer filter must be in the chain:
  check `player.filterManager.data.pluginFilters.mixer` exists (the wiring
  enables it on `playerCreate`/`trackStart`).

## Debugging

```ts
console.log(await mixer.state(player));
// { hasNext, nextTrimMs: [leadMs, trailMs] | null, overlayActive,
//   crossfadeActive, crossfadeEnabled, crossfadeMs, fadeInMs, duckLevel, ... }
```

`nextTrimMs` appears once silence analysis of the queued track finishes
(see "Trim timing" above). `hasNext` is true from queue time, even while
`nextTrimMs` is still null.

Server-side REST reference: [`api/openapi.yaml`](../api/openapi.yaml) ·
plugin internals: [`../README.md`](../README.md)
