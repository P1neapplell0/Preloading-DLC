package com.p1nero.preloading_dlc.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForceDlcPreloaderTest {
    @TempDir
    Path gameDir;

    @Test
    void forceMarkerBlocksForDownloadAndAddsModCandidate() throws Exception {
        byte[] modJar = modJar();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/test.jar", exchange -> {
            exchange.sendResponseHeaders(200, modJar.length);
            exchange.getResponseBody().write(modJar);
            exchange.close();
        });
        server.start();
        try {
            Path required = gameDir.resolve("config/dlc_manager/required");
            Files.createDirectories(required);
            Files.createFile(required.resolve("FORCE"));
            Files.writeString(required.resolve("test.dc"), """
                    [download]
                    directUrl = ["http://127.0.0.1:%d/test.jar"]
                    priority = ["directUrl"]

                    [basic]
                    identifier = "test_mod"
                    file_name = "test-mod.jar"
                    appliedTarget = "mods"
                    isInList = true
                    """.formatted(server.getAddress().getPort()));

            List<Path> candidates = new ArrayList<>();
            ForceDlcPreloader.preload(gameDir, candidates::add);

            Path installed = gameDir.resolve("mods/test-mod.jar").toAbsolutePath();
            assertEquals(List.of(installed), candidates);
            assertArrayEquals(modJar, Files.readAllBytes(installed));
            assertArrayEquals(modJar, Files.readAllBytes(required.resolve("test-mod.jar")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNothingWithoutForceMarker() throws Exception {
        Path required = gameDir.resolve("config/dlc_manager/required");
        Files.createDirectories(required);
        Files.writeString(required.resolve("test.dc"), "not valid dc");

        List<Path> candidates = new ArrayList<>();
        ForceDlcPreloader.preload(gameDir, candidates::add);

        assertFalse(Files.exists(gameDir.resolve("mods")));
        assertEquals(List.of(), candidates);
        assertTrue(Files.readString(gameDir.resolve("config/preloading_dlc.properties"))
                .contains("debug.simulateOffline=false"));
    }

    @Test
    void installsFabricModForCompatibilityLoaderWithoutNativeCandidate() throws Exception {
        byte[] fabricJar = fabricModJar();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fabric.jar", exchange -> {
            exchange.sendResponseHeaders(200, fabricJar.length);
            exchange.getResponseBody().write(fabricJar);
            exchange.close();
        });
        server.start();
        try {
            Path required = gameDir.resolve("config/dlc_manager/required");
            Files.createDirectories(required);
            Files.createFile(required.resolve("FORCE"));
            Files.writeString(required.resolve("notice.dc"), "[download_desc]\nen_us = \"Notice\"\n");
            Files.writeString(required.resolve("fabric.dc"), """
                    [download]
                    directUrl = ["http://127.0.0.1:%d/fabric.jar"]
                    priority = "modrinth,directUrl,curseforge"

                    [basic]
                    identifier = "fabric_test"
                    file_name = "fabric-test.jar"
                    appliedTarget = "mods"
                    isEnabled = true
                    """.formatted(server.getAddress().getPort()));

            List<Path> candidates = new ArrayList<>();
            ForceDlcPreloader.preload(gameDir, candidates::add);

            assertEquals(List.of(), candidates);
            assertArrayEquals(fabricJar, Files.readAllBytes(gameDir.resolve("mods/fabric-test.jar")));
            assertFalse(Files.exists(required.resolve("notice.jar")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downloadsAndAppliesInlineNonModDependencies() throws Exception {
        byte[] modJar = modJar();
        byte[] resourcePack = "resource-pack".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mod.jar", exchange -> {
            exchange.sendResponseHeaders(200, modJar.length);
            exchange.getResponseBody().write(modJar);
            exchange.close();
        });
        server.createContext("/pack.zip", exchange -> {
            exchange.sendResponseHeaders(200, resourcePack.length);
            exchange.getResponseBody().write(resourcePack);
            exchange.close();
        });
        server.start();
        try {
            Path required = gameDir.resolve("config/dlc_manager/required");
            Files.createDirectories(required);
            Files.createFile(required.resolve("FORCE"));
            Files.writeString(required.resolve("main.dc"), """
                    [download]
                    directUrl = ["http://127.0.0.1:%1$d/mod.jar"]
                    priority = ["directUrl"]

                    [basic]
                    identifier = "main_mod"
                    file_name = "main-mod.jar"
                    appliedTarget = "mods"
                    isEnabled = true

                    [dependencies.assets.download]
                    directUrl = ["http://127.0.0.1:%1$d/pack.zip"]
                    priority = ["directUrl"]

                    [dependencies.assets.basic]
                    identifier = "assets"
                    file_name = "assets.zip"
                    appliedTarget = "resourcepacks"
                    isEnabled = true
                    """.formatted(server.getAddress().getPort()));

            List<Path> candidates = new ArrayList<>();
            ForceDlcPreloader.preload(gameDir, candidates::add);

            assertEquals(List.of(gameDir.resolve("mods/main-mod.jar").toAbsolutePath()), candidates);
            assertArrayEquals(resourcePack, Files.readAllBytes(required.resolve("assets.zip")));
            assertArrayEquals(resourcePack, Files.readAllBytes(gameDir.resolve("resourcepacks/assets.zip")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesDownloadUsingDlcManagerRetrySetting() throws Exception {
        byte[] modJar = modJar();
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/retry.jar", exchange -> {
            if (requests.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(503, -1);
            } else {
                exchange.sendResponseHeaders(200, modJar.length);
                exchange.getResponseBody().write(modJar);
            }
            exchange.close();
        });
        server.start();
        try {
            Path required = gameDir.resolve("config/dlc_manager/required");
            Files.createDirectories(required);
            Files.createFile(required.resolve("FORCE"));
            Files.writeString(gameDir.resolve("config/dlc_manager/config.dc"), """
                    [downloads]
                    maxRetryCount = 2
                    """);
            Files.writeString(required.resolve("retry.dc"), """
                    [download]
                    directUrl = ["http://127.0.0.1:%d/retry.jar"]
                    priority = ["directUrl"]

                    [basic]
                    identifier = "retry_mod"
                    file_name = "retry-mod.jar"
                    appliedTarget = "mods"
                    """.formatted(server.getAddress().getPort()));

            List<Path> candidates = new ArrayList<>();
            ForceDlcPreloader.preload(gameDir, candidates::add);

            assertEquals(2, requests.get());
            assertEquals(List.of(gameDir.resolve("mods/retry-mod.jar").toAbsolutePath()), candidates);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsPermanentFailureForNeoForgeLoadingIssue() throws Exception {
        Files.writeString(gameDir.resolve("options.txt"), "lang:en_us\n");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/missing.jar", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            Path required = gameDir.resolve("config/dlc_manager/required");
            Files.createDirectories(required);
            Files.createFile(required.resolve("FORCE"));
            Files.writeString(required.resolve("missing.dc"), """
                    [download]
                    directUrl = ["http://127.0.0.1:%d/missing.jar"]
                    priority = ["directUrl"]

                    [basic]
                    identifier = "missing_mod"
                    file_name = "missing-mod.jar"
                    appliedTarget = "mods"
                    """.formatted(server.getAddress().getPort()));

            ForceDlcPreloader.InstallException exception = assertThrows(ForceDlcPreloader.InstallException.class,
                    () -> ForceDlcPreloader.preload(gameDir, ignored -> { }));

            assertEquals(2, requests.get());
            assertTrue(exception.getMessage().contains("Missing component(s): 1"));
            assertTrue(exception.getMessage().contains("1. missing_mod"));
            assertTrue(exception.getMessage().contains(
                    "Download it manually and place it at: " + required.resolve("missing-mod.jar").toAbsolutePath()));
            assertTrue(exception.getMessage().contains(
                    gameDir.resolve("mods/missing-mod.jar").toAbsolutePath().toString()));
            assertEquals(1, exception.failureCount());
            assertEquals(required.resolve("missing.dc"), exception.primaryConfigPath());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void debugConfigCanSimulateOfflineWithoutMakingARequest() throws Exception {
        byte[] modJar = modJar();
        Files.createDirectories(gameDir.resolve("config"));
        Files.writeString(gameDir.resolve("config/preloading_dlc.properties"),
                "debug.simulateOffline=true\n");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/online.jar", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, modJar.length);
            exchange.getResponseBody().write(modJar);
            exchange.close();
        });
        server.start();
        try {
            Path required = gameDir.resolve("config/dlc_manager/required");
            Files.createDirectories(required);
            Files.createFile(required.resolve("FORCE"));
            Files.writeString(required.resolve("offline.dc"), """
                    [download]
                    directUrl = ["http://127.0.0.1:%d/online.jar"]
                    priority = ["directUrl"]

                    [basic]
                    identifier = "offline_test"
                    file_name = "offline-test.jar"
                    appliedTarget = "mods"
                    """.formatted(server.getAddress().getPort()));

            ForceDlcPreloader.InstallException exception = assertThrows(ForceDlcPreloader.InstallException.class,
                    () -> ForceDlcPreloader.preload(gameDir, ignored -> { }));

            assertEquals(0, requests.get());
            assertTrue(exception.getMessage().contains("Simulated offline mode is enabled"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsEveryMissingComponentAndManualDestination() throws Exception {
        Files.writeString(gameDir.resolve("options.txt"), "lang:en_us\n");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/missing", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            Path required = gameDir.resolve("config/dlc_manager/required");
            Files.createDirectories(required);
            Files.createFile(required.resolve("FORCE"));
            Files.writeString(required.resolve("first.dc"), missingDlcConfig(
                    server.getAddress().getPort(), "first", "first.jar", "mods"));
            Files.writeString(required.resolve("second.dc"), missingDlcConfig(
                    server.getAddress().getPort(), "second", "second.zip", "resourcepacks"));

            ForceDlcPreloader.InstallException exception = assertThrows(ForceDlcPreloader.InstallException.class,
                    () -> ForceDlcPreloader.preload(gameDir, ignored -> { }));

            assertEquals(4, requests.get());
            assertEquals(2, exception.failureCount());
            assertTrue(exception.getMessage().contains("Missing component(s): 2"));
            assertTrue(exception.getMessage().contains("1. first"));
            assertTrue(exception.getMessage().contains("2. second"));
            assertTrue(exception.getMessage().contains(required.resolve("first.jar").toAbsolutePath().toString()));
            assertTrue(exception.getMessage().contains(required.resolve("second.zip").toAbsolutePath().toString()));
            assertTrue(exception.getMessage().contains(
                    gameDir.resolve("resourcepacks/second.zip").toAbsolutePath().toString()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesMinecraftLanguageForEarlyFailureMessages() throws Exception {
        Files.writeString(gameDir.resolve("options.txt"), "lang:zh_cn\n");

        DlcMessages messages = DlcMessages.forGameDirectory(gameDir);

        assertEquals("必要 DLC 安装失败，启动已被阻止。", messages.text("failure.title"));
        assertEquals("缺少组件数量：2", messages.format("failure.missing_count", 2));
    }

    @Test
    void everySupportedLanguageContainsTheFailureMessageKeys() {
        List<String> keys = List.of("failure.title", "failure.missing_count", "failure.expected_file",
                "failure.manual_target", "failure.applied_targets", "failure.configuration",
                "failure.last_error", "failure.retry_and_restart");
        for (String language : List.of("en_us", "zh_cn", "zh_tw", "ja_jp", "ko_kr",
                "de_de", "fr_fr", "es_es", "pt_br", "ru_ru")) {
            DlcMessages messages = DlcMessages.forLanguage(language);
            assertTrue(keys.stream().map(messages::text).noneMatch(keys::contains), language);
            assertFalse(messages.format("failure.missing_count", 2).contains("%d"), language);
            assertFalse(messages.format("failure.retry_and_restart", 2).contains("%d"), language);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "forceDlcIntegration", matches = "true")
    void installsProvidedRunConfiguration() {
        Path runDirectory = Path.of("run").toAbsolutePath().normalize();
        List<Path> candidates = new ArrayList<>();

        ForceDlcPreloader.preload(runDirectory, candidates::add);

        Path entityCulling = runDirectory.resolve("mods/entityculling.jar");
        assertTrue(Files.exists(entityCulling));
        assertTrue(candidates.contains(entityCulling));
    }

    private static byte[] modJar() throws Exception {
        return jarWithEntry("META-INF/neoforge.mods.toml", "modLoader=\"javafml\"");
    }

    private static String missingDlcConfig(int port, String identifier, String fileName, String target) {
        return """
                [download]
                directUrl = ["http://127.0.0.1:%d/missing"]
                priority = ["directUrl"]

                [basic]
                identifier = "%s"
                file_name = "%s"
                appliedTarget = "%s"
                """.formatted(port, identifier, fileName, target);
    }

    private static byte[] fabricModJar() throws Exception {
        return jarWithEntry("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"fabric_test\",\"version\":\"1\"}");
    }

    private static byte[] jarWithEntry(String name, String contents) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry(name));
            jar.write(contents.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }
}
