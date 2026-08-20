package com.p1nero.preloading_dlc.service;

import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.ImmediateWindowHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import settingdust.preloading_tricks.api.PreloadingEntrypoint;
import settingdust.preloading_tricks.api.PreloadingTricksCallbacks;

import java.nio.file.Path;

public class MyPreloadingEntrypoint implements PreloadingEntrypoint {
    private static final Logger LOGGER = LoggerFactory.getLogger("Force DLC Loader");

    public MyPreloadingEntrypoint() {
        PreloadingTricksCallbacks.COLLECT_MOD_CANDIDATES.register(modCandidates -> {
            try {
                ForceDlcPreloader.preload(gameDirectory(), modCandidates::add);
            } catch (ForceDlcPreloader.InstallException exception) {
                LOGGER.error("{}", exception.getMessage());
                ImmediateWindowHandler.crash(exception.getMessage());
                throw new BootstrapAbortError(exception.getMessage(), exception);
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

    private static final class BootstrapAbortError extends Error {
        private BootstrapAbortError(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
