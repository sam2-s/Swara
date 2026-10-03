# NOTICE — where Swara's code comes from

Swara is a fork of BitChord, with provider logic ported from ArchiveTune and
download-engine design ported from LastWave-native. All three are GPL-3.0
works, and Swara is distributed under GPL-3.0 (see LICENSE) on the same
terms. Every ported file keeps its original copyright header.

## BitChord — the base

- Repository: https://github.com/kushagrasinghx/BitChord
- License: GNU General Public License v3.0
- What was taken: the entire application — UI, playback pipeline, source
  registry and resolver, download manager, and tests — renamed from
  `com.music.bitchord` to `com.music.swara`. Upstream copyright headers are
  intact wherever the file was kept.

## ArchiveTune (4nx3b fork) — the source providers

- Repository: https://github.com/4nx3b/ArchiveTune
  (fork of https://github.com/rukamori/ArchiveTune)
- License: GNU General Public License v3.0
- What was ported, restructured onto Swara's `MusicSource` interface:
  - `qobuz/` — request signing (`track/getFileUrl` MD5 scheme), `format_id`
    ladder, search scoring, preview refusal, web-player bundle scraping for
    the `app_id`/`app_secret` pair, token health probing.
  - `tidal/` — DASH manifest parsing (SegmentTemplate/SegmentList/BaseURL),
    quality ladder with the hi-res downgrade guard, FLAC-in-MP4 segment
    assembly (`dfLa` metadata extraction, `mdat` payload concatenation),
    token health probing.
  - `apple/` — public iTunes Search API catalogue, browse-only by design;
    playback of Apple rows goes through YouTube Music.
  - The Jaro-Winkler composite matching thresholds informed the ported
    scorers; the scorers themselves are Swara code.

## LastWave-native — the lossless download engine

- Repository: https://github.com/Clash-Projects/LastWave-native
- License: GNU General Public License v3.0
- What was ported is the *design*, not the backend: LastWave's lossless
  streams come from its `clashflac` service, whose signing secret is injected
  at its own CI and gated to its own package — it cannot be reused here, and
  no attempt is made to. What Swara takes is the engine shape: parallel
  ranged FLAC fetching, DASH-segment-to-FLAC assembly, container sniffing
  before a download becomes a route, sidecar lyrics, and embedded cover art
  with release metadata. The implementation is Swara code written against
  Swara's `Downloader`/`DownloadStore`/`MediaTagger`.

## What is intentionally *not* carried over

- LastWave's `clashflac` backend credentials, signing secret, and native
  signing library.
- BitChord's `bitchord.kushagrasingh.in` invite domain and hosted party
  server defaults. Swara ships no web origin of its own; the invite origin
  and party server are build-time properties an operator supplies
  (`LISTEN_TOGETHER_ORIGIN`, `LISTEN_TOGETHER_SERVER`), and invites work in
  the `swara://party` form with neither.
