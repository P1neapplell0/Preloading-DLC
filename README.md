# Preloading DLC

Preloading DLC is a player-focused companion mod for [DLC Manager](https://www.curseforge.com/minecraft/mc-mods/dlc-manager) on NeoForge. It allows required DLC mods to be downloaded and included during the same game launch, so players normally do not need to restart Minecraft after installing missing required content.

## What It Does

During startup, Preloading DLC works with [Preloading Tricks](https://www.curseforge.com/minecraft/mc-mods/preloading-tricks) at the `COLLECT_MOD_CANDIDATES` stage. It checks the required DLC list provided by the modpack and handles any missing entries before normal mod loading begins.

When a required DLC is missing, the mod will:

- Download the missing file using the sources configured by DLC Manager.
- Retry failed downloads according to the DLC Manager settings.
- Install required mods and resources into their configured locations.
- Add newly downloaded NeoForge mods to the current launch.
- Allow compatible Fabric mods to be discovered by the installed compatibility loader.
- Download required DLC dependencies as part of the same startup check.

If every required file downloads successfully, Minecraft continues loading immediately with the new mods included. No extra restart is needed.

## When a Download Fails

Required DLC cannot be skipped when the modpack enables forced installation. If all configured download sources fail, startup is stopped before the remaining mods are loaded.

The error dialog and log will show:

- Every missing component.
- The expected file name.
- The exact folder where the file must be placed.
- The location where DLC Manager will apply the file.
- The last download error.

Download each listed file manually, place it in the displayed location, and then start Minecraft again. Preloading DLC will detect the files and continue the installation automatically.

Error instructions are available in English, Simplified Chinese, Traditional Chinese, Japanese, Korean, German, French, Spanish, Brazilian Portuguese, and Russian. The client uses the language selected in Minecraft. Dedicated servers use the Java or operating-system language and fall back to English when necessary.

## Requirements

- Minecraft
- NeoForge
- [Preloading Tricks](https://www.curseforge.com/minecraft/mc-mods/preloading-tricks)
- A modpack configured to use [DLC Manager](https://www.curseforge.com/minecraft/mc-mods/dlc-manager) required DLC entries

Players normally do not need to configure this mod. The required content, download sources, destinations, and forced-installation rules are supplied by the modpack.

## Important Notes

- An internet connection may be required when a required DLC is not already installed.
- Download time depends on the configured source and the player's connection. Required DLC checks have a 300-second default setup/wait limit, while an active download is allowed to continue at any speed as long as new data keeps arriving.
- You can change this limit in `config/preloading_dlc.properties`:

```properties
download.maxWaitSeconds=300
```

If a connection or download makes no progress for the configured DLC Manager task timeout, it is treated as a network failure and the standard NeoForge error screen lists the files to download manually. A slow but continuously progressing download is not terminated by the total wait limit.
While a required DLC is downloading, NeoForge's early Minecraft loading window shows its progress, percentage, and current transfer speed. The same information is written to the log approximately once per second.
- Closing the forced-DLC error does not bypass the requirement. All listed files must be installed before the game can continue.
- When manual installation is required, follow the exact paths shown in the dialog or log.

## Offline Test Mode

For troubleshooting, Preloading DLC creates `config/preloading_dlc.properties` with offline simulation disabled:

```properties
download.maxWaitSeconds=300
debug.simulateOffline=false
```

Set the value to `true` to make required DLC network requests fail immediately as if the computer were offline. Existing cached DLC files are still used, so remove the cached file for the DLC you want to test. Restore the value to `false` after testing.
