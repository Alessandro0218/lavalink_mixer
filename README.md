# lavalink_mixer

A [Lavalink v4](https://github.com/lavalink-devs/Lavalink) plugin that plays a clip
(TTS, an announcement) **over the music with the music ducked**, on the same player
and the same voice connection. Fork of
[mochilabs/lavalink_mixer](https://github.com/mochilabs/lavalink_mixer), cut down to
that one job (gapless queue, crossfade, silence skipping and fade-in were removed)
and hardened for it.

Built against `dev.arbjerg.lavalink:plugin-api:4.2.1` + `dev.arbjerg:lavaplayer:2.2.6` (Java 17).

## How it works

Discord voice carries a single Opus stream per connection, so a second audible
source has to be mixed before encoding:

1. A hidden secondary Lavaplayer `AudioPlayer` per guild decodes the clip. It never
   touches voice.
2. Its decoded PCM is siphoned into a bounded queue and a shared 50Hz loop keeps
   that decoder flowing.
3. The `mixer` filter sits in the main player's chain and computes
   `out = main * mainGain + clip * clipGain`, clamped. The music ramps down to
   `duckLevel` over `duckFadeMs`, the clip starts after that ramp, and when the clip
   has been heard to the last sample the music ramps back up.

Consequences worth knowing:

- The mix happens where the main track is *decoded*, so the clip is heard about one
  frame buffer (`frameBufferDurationMs`) after the call, together with the duck.
- The chain only runs while the main player decodes. With no music flowing there is
  nothing to mix into: `announce` answers 409 and the caller should play the clip the
  normal way (pause path).
- The mixer filter must be in the chain **before the track starts** (Lavaplayer does
  not rebuild the chain of a track that is already playing). `announce` answers 409
  `filter_inactive` when music flows without it.

## Setup

1. `./gradlew build` produces `build/libs/mixer-1.0.0.jar`; drop it in the server's
   `plugins/` directory (or reference a release asset / JitPack in `application.yml`).
2. Enable the filter for the guild in the `filters` op, **before** the track plays:
   ```json
   { "pluginFilters": { "mixer": { "guildId": "123" } } }
   ```
   `guildId` is a **string** (snowflakes exceed 2^53) and binds the filter to the
   guild's mixer, because the extension API passes no player identity.

Optional `application.yml`:

```yaml
mixer:
  duckLevel: 0.2        # music gain while the clip plays, 0..1
  duckFadeMs: 300       # ramp down before the clip, ramp up after it
  maxOverlayMs: 120000  # hard cap per overlay
```

## REST (`/mixer/*`, same auth as the server)

Machine-readable spec: [`api/openapi.yaml`](api/openapi.yaml).

| Method | Path | Body / query | Effect |
|---|---|---|---|
| POST | `/mixer/announce` | `{guildId, encodedTrack? \| identifier?, duckLevel?}` | Overlay the clip with the music ducked. Returns `{overlayId, track}` as soon as it starts |
| POST | `/mixer/announce/cancel` | `{guildId}` | Stop the clip, music comes back. 404 if none is running |
| GET | `/mixer/state?guildId=` | | `{overlayActive, overlayId, lastOverlay:{id,reason}, filterLive, duckLevel, duckFadeMs}` |

`encodedTrack` (exact Lavalink base64, no re-resolution) is preferred over `identifier`.

Errors: `409 overlay_busy`, `409 mixer_unavailable:main_idle`,
`409 mixer_unavailable:filter_inactive`, `400` bad request or unloadable clip,
`504` clip did not load in 20s (nothing was started).

**Knowing the clip is over:** poll `/mixer/state` until `lastOverlay.id >= overlayId`.
`lastOverlay.reason` is one of `finished`, `cancelled`, `failed`, `stuck`, `timeout`,
`main_stopped`, `play_failed`.

## Differences from upstream

- Overlay ends only after the clip has been *mixed out*, not when its decoder
  finishes; upstream cleared the queue at decoder end and cut off the last seconds.
- Music waits `duckFadeMs` to get out of the way before the clip is audible
  (upstream played them together for the first ~300ms).
- A hard cap, a "main stopped" check and a "filter not in chain" check, so the music
  can never stay ducked and the caller learns when a clip would be inaudible.
- An announce that times out loading no longer starts the overlay later.
- Builds against `plugin-api` 4.2.1 (4.2.2 is not resolvable).

## Developing

```bash
./gradlew build   # tests + jar
```

## License

Public domain, [UNLICENSE](UNLICENSE).
