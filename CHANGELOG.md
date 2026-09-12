# Changelog

All notable changes to Zales are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
this project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

Nothing yet.

## [0.2.1] — 2026-09-12

### Fixed

- **The app crashed on startup once a key was stored.** A file lock keeps the
  two processes from writing over each other, but within one process it is not
  a mutex and not reentrant: ask for a region the same JVM already holds and it
  throws rather than waits. The home screen and the key screen read the store at
  the same moment every time they wake up together, and the second read took the
  whole app down with `OverlappingFileLockException`. Affected 0.1.0 and 0.2.0.

## [0.2.0] — 2026-09-12

### Added

- A launcher icon that is the wood itself: three ranks of spruce and a fire's
  worth of light between the trunks, holding down to 24dp, with a themed layer
  and the same mark on the notification, the tile and the widget.
- English throughout. All 161 strings exist in both languages, the app declares
  both so Android 13 and later offer the per-app language picker, and CI fails
  a string that exists in one language and not the other. The sayings stay
  Russian: they are folk idiom, and translated they would be twee.
- A key sent as a link opens the app with the key already in it — no copying,
  which is the step that goes wrong.
- The privacy page every claim on which names the file it can be checked in,
  linked from the settings screen.
- Issue forms, a pull request checklist, a security policy and a code of
  conduct, so the repository can receive strangers.

### Changed

- The README no longer says the project is at phase 0 with one screen.
- R8 rules are spelled out rather than inherited from an AAR that keeps the
  whole application package.
- Nothing the app is made of is built until it is first asked for, and the
  corpus of sayings is read off the main thread: the first frame no longer
  waits on three files and the Keystore.

### Fixed

- Walking away from the path check while connected left the core stopped under
  a live interface: every connection blackholed and the screen still said the
  path was open. The same shape was waiting in recovery, which ran inside the
  job that polls the traffic counters — a job cancelled for reasons that have
  nothing to do with recovery.
- `onResume` had been deleted by accident, so the key list went stale after a
  trip to the system's VPN settings — the exact trip the always-on walkthrough
  sends people on.
- The latency on the nameplate said «мс» in every language.

## [0.1.0] — 2026-09-12

### Added

- **The tunnel.** Xray-core through libXray in its own `:tunnel` process, with
  the TUN descriptor handed straight to Xray's own `tun` inbound — no tun2socks
  and no local SOCKS port for another app to find
  ([ADR-0007](docs/adr/0007-native-tun-inbound.md)).
- **Autopilot.** Every rung of the strategy ladder is raced at once, so finding
  a way in costs one round trip rather than one per rung, and the winner is
  remembered per network so a known place needs a single probe next time.
- **Watchdog.** Catches the failure that does not announce itself: bytes stop
  coming back while the phone keeps asking — the TSPU freeze — and the core is
  swapped over underneath a live interface, so open connections survive.
- **Network resilience.** Losing Wi-Fi is a pause, not a failure; the interface
  stays up, and a returning network starts a fresh race with a fresh address.
  Aeroplane mode, no network and a captive portal are each named in their own
  words before anything is blamed on the tunnel. Waits between attempts grow
  with jitter, because a client that retries on a metronome is itself a
  signature.
- **«Проверка тропы».** The probe ladder, climbed in front of the person one
  rung at a time, stopping at the first failure — one answer instead of a list
  of hypotheses. Under it, one sentence and one action; folded below that, the
  English report for whoever gave the key, scrubbed of every secret and of the
  server's own label.
- **Keys by QR.** Point one phone at another's screen, or read the screenshot
  somebody was sent. ZXing rather than ML Kit, which needs Google Play services
  a phone in Russia may not have.
- **Quick settings tile and home-screen widget.** The switch without opening
  anything, in the same words the big screen uses.
- **Survives a restart.** What the person asked for is remembered and honoured
  after a reboot or an overnight update.
- **Settings.** Four switches and three doors, each a sentence someone might say
  out loud: «Только рубильник», sayings, Russian sites around the tunnel, update
  checks, the four taps that turn on always-on VPN, the path check, and three
  steps for when nothing works.
- **Accessibility.** Every decorative movement stops when the system asks for
  reduced motion; the home screen becomes a scrolling column above 150% text so
  nothing is crushed or cut off.
- **Screens dissolve** through the same ordered Bayer matrix the forest is drawn
  with. Never a fade.
- Release builds split by architecture (40 MB instead of 76) and a tag-driven
  release workflow that signs, names and publishes them with checksums.
- A launcher icon that is the wood itself: three ranks of spruce and a fire's
  worth of light between the trunks, holding down to 24dp, with a themed layer
  and the same mark on the notification, the tile and the widget.

### Changed

- Licence switched from GPL-3.0-or-later to MPL-2.0 before any code was written,
  to keep commercialization, iOS and a future GPL sing-box build all possible
  ([ADR-0005](docs/adr/0005-license-mpl2.md)).
- Art direction rebuilt around the cold-thicket / warm-hut dissonance, with a
  bounded eerie layer and a matching two-register voice.
- The switch closes by being lifted, the way a real knife switch does, and the
  documents and the spoken state now agree with the model.
- The switch sequence is held at two resolutions: full size for the two resting
  positions, which is what a person actually looks at, and working size for the
  third of a second of movement, which costs a third of the graphics memory.
- tun2socks removed from the living documents; it had not been in the build for
  some time ([ADR-0002](docs/adr/0002-tun-via-hev.md) superseded).

[Unreleased]: https://github.com/nkVas1/zales/compare/v0.2.1...HEAD
[0.2.1]: https://github.com/nkVas1/zales/releases/tag/v0.2.1
[0.2.0]: https://github.com/nkVas1/zales/releases/tag/v0.2.0
[0.1.0]: https://github.com/nkVas1/zales/releases/tag/v0.1.0
