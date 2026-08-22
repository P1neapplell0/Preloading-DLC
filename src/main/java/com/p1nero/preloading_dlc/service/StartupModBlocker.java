package com.p1nero.preloading_dlc.service;

import settingdust.preloading_tricks.api.ModManager;

import java.util.HashSet;
import java.util.Set;

final class StartupModBlocker {
    private static final Set<String> SYSTEM_MOD_IDS = Set.of("minecraft", "neoforge");
    private static volatile boolean blocked;

    private StartupModBlocker() {
    }

    static void beginStartupCheck() {
        blocked = false;
    }

    static void blockStartup() {
        blocked = true;
    }

    static boolean isBlocked() {
        return blocked;
    }

    static int removeThirdPartyMods(ModManager<?> manager) {
        if (!blocked) {
            return 0;
        }
        return retainSystemMods(manager);
    }

    private static <M> int retainSystemMods(ModManager<M> manager) {
        Set<M> retained = new HashSet<>();
        for (String id : SYSTEM_MOD_IDS) {
            M mod = manager.getById(id);
            if (mod != null) {
                retained.add(mod);
            }
        }

        int before = manager.all().size();
        manager.removeIf(mod -> !retained.contains(mod));
        return before - manager.all().size();
    }
}
