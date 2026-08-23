package com.p1nero.preloading_dlc.service;

import net.neoforged.fml.ModLoadingException;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import settingdust.preloading_tricks.api.PreloadingEntrypoint;
import settingdust.preloading_tricks.api.PreloadingTricksCallbacks;

import java.nio.file.Path;

public class MyPreloadingEntrypoint implements PreloadingEntrypoint {
    private static final Logger LOGGER = LoggerFactory.getLogger("Force DLC Loader");

    public MyPreloadingEntrypoint() {
        PreloadingTricksCallbacks.SETUP_MODS.register(modManager -> {
            int removed = StartupModBlocker.removeThirdPartyMods(modManager);
            if (removed > 0) {
                LOGGER.error("Removed {} third-party mod file(s) after required DLC installation failed.", removed);
            }
        });
        PreloadingTricksCallbacks.COLLECT_MOD_CANDIDATES.register(modCandidates -> {
            StartupModBlocker.beginStartupCheck();
            try {
                ForceDlcPreloader.preload(gameDirectory(), modCandidates::add);
            } catch (ForceDlcPreloader.InstallException exception) {
                StartupModBlocker.blockStartup();
                LOGGER.error("{}", exception.getMessage());
                throw new ModLoadingException(ModLoadingIssue.error(
                                "fml.modloadingissue.technical_error", exception.getMessage())
                        .withAffectedPath(exception.primaryConfigPath())
                        .withCause(exception));
            } catch (RuntimeException exception) {
                StartupModBlocker.blockStartup();
                String message = "Required DLC startup check failed unexpectedly: " + exception;
                LOGGER.error(message, exception);
                throw new ModLoadingException(ModLoadingIssue.error(
                                "fml.modloadingissue.technical_error", message)
                        .withCause(exception));
            }
        });
    }

    private static Path gameDirectory() {
        try {
            return FMLPaths.GAMEDIR.get();
        } catch (RuntimeException exception) {
            return Path.of(System.getProperty("user.dir"));
        }
    }

}
