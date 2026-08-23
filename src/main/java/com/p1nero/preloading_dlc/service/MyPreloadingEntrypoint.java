package com.p1nero.preloading_dlc.service;

import net.neoforged.fml.ModLoadingException;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.FMLLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import settingdust.preloading_tricks.api.PreloadingEntrypoint;
import settingdust.preloading_tricks.api.PreloadingTricksCallbacks;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.locks.LockSupport;

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
            List<Path> downloadedCandidates;
            FutureTask<List<Path>> preloadTask = new FutureTask<>(() -> {
                List<Path> candidates = new ArrayList<>();
                ForceDlcPreloader.preload(gameDirectory(), candidates::add);
                return candidates;
            });
            Thread downloadThread = new Thread(preloadTask, "required-dlc-preloader");
            downloadThread.setDaemon(true);
            downloadThread.start();
            while (!preloadTask.isDone()) {
                tickEarlyWindow();
                LockSupport.parkNanos(50_000_000L);
            }
            try {
                downloadedCandidates = preloadTask.get();
            } catch (ForceDlcPreloader.InstallException exception) {
                throw loadingFailure(exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw loadingFailure(exception);
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                if (cause instanceof ForceDlcPreloader.InstallException installException) {
                    throw loadingFailure(installException);
                }
                throw loadingFailure(cause);
            } catch (RuntimeException exception) {
                throw loadingFailure(exception);
            }
            downloadedCandidates.forEach(modCandidates::add);
        });
    }

    private static void tickEarlyWindow() {
        Runnable tick = FMLLoader.progressWindowTick;
        if (tick == null) {
            return;
        }
        try {
            tick.run();
        } catch (RuntimeException | LinkageError exception) {
            LOGGER.debug("Unable to refresh the NeoForge early loading window.", exception);
        }
    }

    private static ModLoadingException loadingFailure(Throwable cause) {
        StartupModBlocker.blockStartup();
        if (cause instanceof ForceDlcPreloader.InstallException exception) {
            LOGGER.error("{}", exception.getMessage());
            return new ModLoadingException(ModLoadingIssue.error(
                            "fml.modloadingissue.technical_error", exception.getMessage())
                    .withAffectedPath(exception.primaryConfigPath())
                    .withCause(exception));
        }
        String message = "Required DLC startup check failed unexpectedly: " + cause;
        LOGGER.error(message, cause);
        return new ModLoadingException(ModLoadingIssue.error(
                        "fml.modloadingissue.technical_error", message)
                .withCause(cause));
    }

    private static Path gameDirectory() {
        try {
            return FMLPaths.GAMEDIR.get();
        } catch (RuntimeException exception) {
            return Path.of(System.getProperty("user.dir"));
        }
    }

}
