# mixer-plugin × lavalink-client (Tomato6966) — integration guide

`mixerPlugin.ts` is a drop-in wiring for gapless + crossfade + silence
skipping + TTS overlay. Tested against lavalink-client v2.9+ API
(`filterManager.data` passthrough, manager events, `player.skip()`).

## Setup checklist

1. **Server**: put `mixer-1.0.0.jar` in the Lavalink `plugins/` dir, restart.
   Optional `application.yml` defaults under `mixer:` (see main README).
2. **Manager**: `autoSkip: false` is REQUIRED (the file forces it too —
   with `true` the client races the server's auto-advance on every trackEnd).
3. **Wire**: `attachMixer(lavalink, { crossfade: {...}, silence: {...} })`.
4. **First play**: play normally (`queue.add()` + `player.play()`). The
   `trackStart` handler enables the mixer filter and preloads the successor
   automatically.

## Event flow (what happens under the hood)

```
trackStart(A) → enable mixer filter (once) → POST /mixer/queue/next (B)
A ends       → server starts B instantly (lead-trimmed, crossfaded)
trackStart(B) → client queue synced (no play sent!) → POST /mixer/queue/next (C)
```

- `trackEnd` → do nothing if we preloaded (server took over); a watchdog
  resumes via `player.skip()` if no `trackStart` follows in ~5s.
- `trackError`/`trackStuck` → same treatment (server advances on
  `LOAD_FAILED` too).
- queue empty at `trackEnd` → normal `queueEnd` flow, untouched.

## User skip — use the handle, not `player.skip()`

```ts
const mixer = attachMixer(lavalink, {...});
// in your /skip command:
await mixer.skip(player);
```

Direct `player.skip()` leaves a stale preloaded track that the server would
replay at the next track end. `mixer.skip()` clears the slot first.

## Repeat modes

When `player.repeatMode !== "off"`, preloading is skipped — the repeater
owns transitions and the plugin would fight it. Gapless/crossfade/silence
trim apply to linear queues.

## TTS / announcements

Music keeps playing underneath (ducked). The audio must be loadable **by
the server** (its `identifier` is resolved server-side):

```ts
// TTS clip hosted somewhere the Lavalink server can reach:
await mixer.announce(player, { identifier: "https://tts.example.com/abc.mp3" });
// or an exact track Rosolved client-side:
await mixer.announce(player, { encodedTrack: track.encoded });
```

409 = secondary player busy (crossfade overlap / analysis running) → retry
later or `mixer.cancelAnnounce(player)` + retry. `mixer.cancelAnnounce`
also stops a running overlay and restores music.

## Filters interplay

- The mixer key is injected into `filterManager.data.pluginFilters` and sent
  via `applyPlayerFilters()`, which passes unknown plugin keys through.
- `setEQ()` is a partial update — safe anytime.
- `resetFilters()` wipes the mixer key — call `enableMixerFilter(player)`
  again afterwards.
- Every filters op replaces the whole server-side chain, so never send a
  hand-built filters object without the mixer block; prefer the filterManager.

## Debugging

```ts
console.log(await mixer.state(player));
// { hasNext, nextTrimMs: [leadMs, trailMs] | null, overlayActive, crossfadeActive, ... }
```

`nextTrimMs` appears once silence analysis of the queued track finishes.
