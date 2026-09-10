# Changelog

All notable changes to Zales are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
this project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Project charter, architecture, art direction and phased roadmap (`docs/`).
- Architecture Decision Records 0001–0006.
- Gradle project: AGP 9.4.0, Kotlin 2.4.20, Gradle 9.7.1, Compose BOM 2026.09.00,
  compileSdk 37, minSdk 26.
- `build-logic` convention plugins: `zales.android.application`,
  `zales.android.library`, `zales.android.compose`.
- Modules `:app`, `:core:common`, `:core:design`.
- Design system foundation: the two-family `ZalesColors` palette, the
  grandfather-first `ZalesTypography` scale, `ZalesTheme` and `ZalesText`.
- Phase 0 shell screen proving palette, type scale and edge-to-edge layout on device.
- CI (`build.yml`): wrapper validation, detekt, Android Lint, tests, debug and
  release builds, APK artifact upload. Dependabot for Gradle and Actions.
- Backups and device transfer disabled at the manifest level.
- First run on the real target device (Galaxy A12, Android 13) over wireless
  debugging; `SETUP.md` documents that path and how to tell a charge-only cable
  from a missing driver.

### Changed

- Licence switched from GPL-3.0-or-later to MPL-2.0 before any code was written,
  to keep commercialization, iOS and a future GPL sing-box build all possible
  ([ADR-0005](docs/adr/0005-license-mpl2.md)).
- Art direction rebuilt around the cold-thicket / warm-hut dissonance, with a
  bounded eerie layer and a matching two-register voice.

[Unreleased]: https://github.com/nkVas1/zales/commits/main
