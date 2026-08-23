# Changelog

All notable changes to Preloading DLC are documented here.

## 1.0.4

### Fixed

- Required DLC downloads now run off the mod-discovery thread while the loading thread continues to process NeoForge's early-window events, preventing the Minecraft window from becoming unresponsive during long downloads.
- Downloaded mod candidates are added back on the original discovery thread after background work completes.

## 1.0.3

### Added

- Required DLC download progress, percentage, and current transfer speed in NeoForge's early Minecraft loading window.
- A response-body watchdog so stalled downloads reliably honor the configured wait limit.

### Fixed

- Download timeouts are consistently converted into NeoForge mod-loading issues instead of escaping as an early startup crash.
- Unexpected required-DLC startup failures are also routed to NeoForge's standard error screen.

## 1.0.2

### Added

- A configurable maximum total wait time for required DLC checks and downloads (`download.maxWaitSeconds`, default `300`).
- Required DLC download progress logs approximately once per second, including downloaded and total size when available.
- Clear timeout reporting when the configured wait limit is exceeded; the failure is shown in NeoForge's standard Minecraft error screen for manual installation.

### Changed

- Required DLC network requests and retries now honor the total wait limit in addition to DLC Manager's per-request timeout.

### Added

- A default-off `config/preloading_dlc.properties` debug option for simulating offline required-DLC downloads.

### Changed

- Required DLC failures are now shown only through NeoForge's Minecraft mod-loading error screen.
- After a required DLC failure, all third-party mods are removed from the pending mod list before initialization so they cannot obscure the original error.

## 1.0.1

### Added

- Localized required-DLC failure messages for English, Simplified Chinese, Traditional Chinese, Japanese, Korean, German, French, Spanish, Brazilian Portuguese, and Russian.
- A detailed missing-component report showing the expected file name, manual download location, configured destination, DLC configuration file, and last download error.
- An early-window error message for required DLC failures.
- A NeoForge `ModLoadingException` fallback when the early error window is unavailable or cannot be displayed.

### Changed

- Required DLC downloads now retry each configured source according to the DLC Manager retry setting.
- All failed required DLC entries are collected and reported together before startup is blocked.
- Failed required-DLC handling stops further mod loading to prevent unrelated mods from obscuring the actual cause.
- Localization resources are loaded directly as UTF-8 properties, including a built-in English fallback for early startup environments.

## 1.0.0

- Initial release of Preloading DLC.
