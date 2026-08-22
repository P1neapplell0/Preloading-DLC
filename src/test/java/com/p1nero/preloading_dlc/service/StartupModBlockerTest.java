package com.p1nero.preloading_dlc.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import settingdust.preloading_tricks.api.ModManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StartupModBlockerTest {
    @AfterEach
    void resetState() {
        StartupModBlocker.beginStartupCheck();
    }

    @Test
    void leavesModListUntouchedWhenStartupIsNotBlocked() {
        TestModManager manager = manager("minecraft", "neoforge", "cobblemon");

        int removed = StartupModBlocker.removeThirdPartyMods(manager);

        assertEquals(0, removed);
        assertEquals(List.of("minecraft", "neoforge", "cobblemon"), manager.ids());
    }

    @Test
    void retainsOnlyMinecraftAndNeoForgeAfterRequiredDlcFailure() {
        TestModManager manager = manager("minecraft", "neoforge", "cobblemon", "connector", "example");
        StartupModBlocker.blockStartup();

        int removed = StartupModBlocker.removeThirdPartyMods(manager);

        assertEquals(3, removed);
        assertEquals(List.of("minecraft", "neoforge"), manager.ids());
    }

    private static TestModManager manager(String... ids) {
        return new TestModManager(new ArrayList<>(List.of(ids)));
    }

    private static final class TestModManager implements ModManager<String> {
        private final List<String> mods;

        private TestModManager(List<String> mods) {
            this.mods = mods;
        }

        List<String> ids() {
            return List.copyOf(mods);
        }

        @Override
        public Collection<String> all() {
            return mods;
        }

        @Override
        public void add(String mod) {
            mods.add(mod);
        }

        @Override
        public void addAll(Collection<String> mod) {
            mods.addAll(mod);
        }

        @Override
        public String getById(String id) {
            return mods.stream().filter(id::equals).findFirst().orElse(null);
        }

        @Override
        public boolean remove(String mod) {
            return mods.remove(mod);
        }

        @Override
        public boolean removeIf(Predicate<String> predicate) {
            return mods.removeIf(predicate);
        }

        @Override
        public boolean removeAll(Collection<String> mods) {
            return this.mods.removeAll(mods);
        }

        @Override
        public boolean removeById(String id) {
            return mods.remove(id);
        }

        @Override
        public boolean removeByIds(Set<String> ids) {
            return mods.removeIf(ids::contains);
        }

        @Override
        public String createVirtualMod(String id, Path referencePath) {
            return id;
        }
    }
}
