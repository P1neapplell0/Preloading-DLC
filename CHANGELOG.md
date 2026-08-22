# Changelog

All notable changes to Preloading DLC are documented here.

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
