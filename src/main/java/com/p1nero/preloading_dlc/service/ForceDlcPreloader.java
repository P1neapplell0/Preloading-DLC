package com.p1nero.preloading_dlc.service;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.FileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/** Early-start compatibility bridge for DLC Manager's required DLC file format. */
final class ForceDlcPreloader {
    private static final Logger LOGGER = LoggerFactory.getLogger("Force DLC Loader");
    private static final String DEFAULT_DLC_ROOT = "config/dlc_manager";
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_TASK_TIMEOUT = Duration.ofSeconds(60);
    private static final int DEFAULT_MAX_RETRY_COUNT = 2;

    private ForceDlcPreloader() {
    }

    static void preload(Path gameDirectory, Consumer<Path> candidateConsumer) {
        Path gameDir = gameDirectory.toAbsolutePath().normalize();
        Path managerConfig = gameDir.resolve(DEFAULT_DLC_ROOT).resolve("config.dc");
        ManagerSettings settings = readManagerSettings(gameDir, managerConfig);
        Path requiredDir = settings.dlcRoot().resolve("required");
        if (Files.notExists(requiredDir.resolve("FORCE"))) {
            LOGGER.debug("DLC Manager FORCE marker is absent; skipping forced DLC preloading.");
            return;
        }

        List<RequiredDlc> entries = expandDependencies(readRequiredEntries(requiredDir), requiredDir);
        List<Path> modCandidates = new ArrayList<>();
        List<InstallFailure> failures = new ArrayList<>();
        for (RequiredDlc entry : entries) {
            if (!entry.inList()) {
                continue;
            }
            try {
                preloadEntry(gameDir, requiredDir, entry, settings, modCandidates);
            } catch (Exception exception) {
                failures.add(new InstallFailure(entry, exception));
            }
        }

        if (!failures.isEmpty()) {
            throw new InstallException(buildFailureMessage(gameDir, requiredDir, failures, settings), failures);
        }

        modCandidates.stream().distinct().forEach(candidateConsumer);
        if (!modCandidates.isEmpty()) {
            LOGGER.info("Added {} forced DLC mod(s) to this launch.", modCandidates.size());
        }
    }

    private static void preloadEntry(Path gameDir, Path requiredDir, RequiredDlc entry,
                                     ManagerSettings settings, List<Path> modCandidates) throws Exception {
        Path cachedFile = safeResolve(requiredDir, entry.fileName());
        boolean installsMod = entry.appliedTargets().stream()
                .map(target -> resolveAppliedTarget(gameDir, target))
                .anyMatch(target -> isModsTarget(gameDir, target));
        if (Files.exists(cachedFile) && installsMod && inspectModJar(cachedFile) == ModJarType.INVALID) {
            LOGGER.warn("Discarding invalid cached mod DLC '{}': {}",
                    entry.identifier(), cachedFile);
            Files.delete(cachedFile);
        }
        if (Files.notExists(cachedFile)) {
            download(entry, cachedFile, settings);
        }

        for (String appliedTarget : entry.appliedTargets()) {
            Path targetDir = resolveAppliedTarget(gameDir, appliedTarget);
            Path targetFile = safeResolve(targetDir, entry.fileName());
            if (!sameFileContent(cachedFile, targetFile)) {
                Files.createDirectories(targetDir);
                atomicCopy(cachedFile, targetFile);
                LOGGER.info("Applied forced DLC '{}' to {}.", entry.identifier(), targetFile);
            }
            if (isModsTarget(gameDir, targetDir)) {
                ModJarType jarType = inspectModJar(targetFile);
                if (jarType == ModJarType.INVALID) {
                    throw new IOException("Downloaded file is not a supported mod jar: " + targetFile);
                }
                if (jarType == ModJarType.NEOFORGE) {
                    modCandidates.add(targetFile.toAbsolutePath().normalize());
                } else {
                    LOGGER.info(
                            "Applied Fabric DLC '{}' for the installed compatibility loader to discover.",
                            entry.identifier());
                }
            }
        }
    }

    private static void download(RequiredDlc entry, Path destination, ManagerSettings settings) throws Exception {
        List<String> urls = resolveDownloadUrls(entry, settings);
        if (urls.isEmpty()) {
            throw new IOException("No usable download source is configured");
        }

        IOException failure = null;
        for (String url : urls) {
            for (int attempt = 1; attempt <= settings.maxRetryCount(); attempt++) {
                try {
                    LOGGER.info(
                            "Downloading forced DLC '{}' from {} (attempt {}/{}).",
                            entry.identifier(), url, attempt, settings.maxRetryCount());
                    downloadUrl(url, destination, settings);
                    return;
                } catch (IOException exception) {
                    failure = exception;
                    LOGGER.warn(
                            "Download source failed for forced DLC '{}' on attempt {}/{}: {} ({})",
                            entry.identifier(), attempt, settings.maxRetryCount(), url, rootCauseSummary(exception));
                }
            }
        }
        throw new IOException("All download sources failed", failure);
    }

    private static String buildFailureMessage(Path gameDir, Path requiredDir, List<InstallFailure> failures,
                                              ManagerSettings settings) {
        DlcMessages messages = DlcMessages.forGameDirectory(gameDir);
        StringBuilder message = new StringBuilder(messages.text("failure.title")).append('\n')
                .append(messages.format("failure.missing_count", failures.size())).append('\n');
        for (int index = 0; index < failures.size(); index++) {
            InstallFailure failure = failures.get(index);
            RequiredDlc entry = failure.entry();
            Path manualTarget = requiredDir.resolve(entry.fileName()).toAbsolutePath().normalize();
            message.append('\n').append(index + 1).append(". ").append(entry.identifier()).append('\n')
                    .append("   ").append(messages.text("failure.expected_file")).append(": ")
                    .append(entry.fileName()).append('\n')
                    .append("   ").append(messages.text("failure.manual_target")).append(": ")
                    .append(manualTarget).append('\n')
                    .append("   ").append(messages.text("failure.applied_targets")).append(":");
            for (String target : entry.appliedTargets()) {
                message.append("\n   - ").append(resolveAppliedTarget(gameDir, target).resolve(entry.fileName())
                        .toAbsolutePath().normalize());
            }
            message.append("\n   ").append(messages.text("failure.configuration")).append(": ")
                    .append(entry.configPath().toAbsolutePath().normalize())
                    .append("\n   ").append(messages.text("failure.last_error")).append(": ")
                    .append(rootCauseSummary(failure.cause())).append('\n');
        }
        return message.append('\n').append(messages.format("failure.retry_and_restart", settings.maxRetryCount()))
                .toString();
    }

    private static String rootCauseSummary(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static List<String> resolveDownloadUrls(RequiredDlc entry, ManagerSettings settings)
            throws IOException, InterruptedException {
        Set<String> resolved = new LinkedHashSet<>();
        for (String source : entry.priority()) {
            try {
                switch (source.toLowerCase(Locale.ROOT)) {
                    case "directurl", "direct" -> resolved.addAll(entry.directUrls());
                    case "modrinth" -> resolved.addAll(resolveModrinth(entry, settings));
                    case "curseforge" -> resolved.addAll(resolveCurseForge(entry));
                    default -> LOGGER.warn(
                            "Ignoring unknown DLC download source '{}' for '{}'.", source, entry.identifier());
                }
            } catch (IOException exception) {
                LOGGER.warn("Unable to resolve {} source for forced DLC '{}'; trying the next source. ({})",
                        source, entry.identifier(), rootCauseSummary(exception));
            }
        }
        resolved.addAll(entry.directUrls());
        return resolved.stream().filter(value -> value != null && !value.isBlank()).toList();
    }

    private static List<String> resolveModrinth(RequiredDlc entry, ManagerSettings settings)
            throws IOException, InterruptedException {
        if (entry.modrinthProjectId().isBlank() || entry.modrinthVersionId().isBlank()) {
            return List.of();
        }
        HttpClient client = httpClient(settings);
        String version = entry.modrinthVersionId();
        JsonElement exact;
        try {
            exact = getJson(client, "https://api.modrinth.com/v2/version/" + encodePath(version), settings);
        } catch (IOException exception) {
            // A configured value may be a version_number instead of a version id. Modrinth returns
            // either 400 or 404 for that first probe, so continue with the project version listing.
            exact = null;
        }
        if (exact != null && exact.isJsonObject()) {
            return modrinthFileUrls(exact.getAsJsonObject());
        }

        String minecraftVersion = minecraftVersion();
        if (minecraftVersion == null) {
            return List.of();
        }
        String project = encodePath(entry.modrinthProjectId());
        String filters = "?loaders=" + encodeQuery("[\"neoforge\"]")
                + "&game_versions=" + encodeQuery("[\"" + minecraftVersion + "\"]");
        JsonElement versions = getJson(client,
                "https://api.modrinth.com/v2/project/" + project + "/version" + filters, settings);
        if (versions == null || !versions.isJsonArray()) {
            return List.of();
        }
        for (JsonElement value : versions.getAsJsonArray()) {
            JsonObject object = value.getAsJsonObject();
            if (version.equals(jsonString(object, "id")) || version.equals(jsonString(object, "version_number"))) {
                return modrinthFileUrls(object);
            }
        }
        return List.of();
    }

    private static List<String> resolveCurseForge(RequiredDlc entry) {
        if (entry.curseForgeFileId().isBlank()) {
            return List.of();
        }
        try {
            long fileId = Long.parseLong(entry.curseForgeFileId());
            String path = (fileId / 1000) + "/" + String.format(Locale.ROOT, "%03d", fileId % 1000)
                    + "/" + encodePath(entry.fileName());
            return List.of("https://edge.forgecdn.net/files/" + path,
                    "https://mediafilez.forgecdn.net/files/" + path);
        } catch (NumberFormatException exception) {
            LOGGER.warn("Invalid CurseForge file id '{}' for forced DLC '{}'.",
                    entry.curseForgeFileId(), entry.identifier());
            return List.of();
        }
    }

    private static List<String> modrinthFileUrls(JsonObject version) {
        JsonArray files = version.getAsJsonArray("files");
        if (files == null) {
            return List.of();
        }
        List<String> primary = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (JsonElement value : files) {
            JsonObject file = value.getAsJsonObject();
            String url = jsonString(file, "url");
            if (url == null || url.isBlank()) {
                continue;
            }
            if (file.has("primary") && file.get("primary").getAsBoolean()) {
                primary.add(url);
            } else {
                others.add(url);
            }
        }
        primary.addAll(others);
        return primary;
    }

    private static JsonElement getJson(HttpClient client, String url, ManagerSettings settings)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(settings.taskTimeout())
                .header("User-Agent", "Force-DLC-Loader/1.1 (DLC-Manager compatibility)")
                .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        return JsonParser.parseString(response.body());
    }

    private static void downloadUrl(String url, Path destination, ManagerSettings settings)
            throws IOException, InterruptedException {
        Files.createDirectories(destination.getParent());
        Path part = destination.resolveSibling(destination.getFileName() + ".part");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(settings.taskTimeout())
                .header("User-Agent", "DLC-Manager")
                .GET().build();
        HttpResponse<InputStream> response = httpClient(settings).send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode());
        }
        try (InputStream input = response.body()) {
            Files.copy(input, part, StandardCopyOption.REPLACE_EXISTING);
            moveAtomically(part, destination);
        } catch (IOException exception) {
            Files.deleteIfExists(part);
            throw exception;
        }
    }

    private static HttpClient httpClient(ManagerSettings settings) {
        return HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private static List<RequiredDlc> readRequiredEntries(Path requiredDir) {
        if (!Files.isDirectory(requiredDir)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(requiredDir)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dc"))
                    .filter(path -> !path.getFileName().toString().equalsIgnoreCase("notice.dc"))
                    .sorted()
                    .map(ForceDlcPreloader::readRequiredEntry)
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to scan DLC Manager required directory " + requiredDir, exception);
        }
    }

    private static RequiredDlc readRequiredEntry(Path path) {
        try (FileConfig config = FileConfig.of(path, TomlFormat.instance())) {
            config.load();
            return readRequiredConfig(config, path, stripExtension(path.getFileName().toString()));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Unable to read DLC Manager required config " + path, exception);
        }
    }

    private static RequiredDlc readRequiredConfig(Config config, Path path, String identifierFallback) {
        String identifier = string(config, "basic.identifier", identifierFallback);
        String fileName = string(config, "basic.file_name", identifier + ".jar");
        List<String> targets = appliedTargets(config.get("basic.appliedTarget"));
        if (targets.isEmpty()) {
            targets = List.of("mods");
        }
        List<String> directUrls = stringList(config.get("download.directUrl"));
        List<String> priority = splitValues(config.get("download.priority"));
        if (priority.isEmpty()) {
            priority = List.of("modrinth", "directUrl", "curseforge");
        }
        return new RequiredDlc(path, identifier, fileName, targets,
                booleanValue(config, "basic.isInList",
                        booleanValue(config, "basic.isEnabled", true)), directUrls, priority,
                string(config, "download.modrinth_projectid", ""),
                string(config, "download.modrinth_versionid", ""),
                string(config, "download.curseforge_fileid", ""),
                readDependencies(config.get("dependencies"), path));
    }

    private static List<Dependency> readDependencies(Object value, Path ownerPath) {
        List<Dependency> dependencies = new ArrayList<>();
        collectDependencies(value, null, ownerPath, dependencies);
        return dependencies;
    }

    private static void collectDependencies(Object value, String key, Path ownerPath,
                                            List<Dependency> dependencies) {
        if (value instanceof String text) {
            splitValues(text).forEach(identifier -> dependencies.add(new Dependency(identifier, null)));
            return;
        }
        if (value instanceof List<?> values) {
            values.forEach(item -> collectDependencies(item, key, ownerPath, dependencies));
            return;
        }
        if (!(value instanceof Config config)) {
            return;
        }

        if (config.get("basic") instanceof Config || config.get("download") instanceof Config) {
            String fallback = key == null || key.isBlank() ? "dependency" : key;
            RequiredDlc entry = readRequiredConfig(config, ownerPath, fallback);
            dependencies.add(new Dependency(entry.identifier(), entry));
            return;
        }

        config.valueMap().forEach((name, child) -> {
            if (child instanceof String text && text.isBlank()) {
                dependencies.add(new Dependency(name, null));
            } else {
                collectDependencies(child, name, ownerPath, dependencies);
            }
        });
    }

    private static List<RequiredDlc> expandDependencies(List<RequiredDlc> roots, Path requiredDir) {
        LinkedHashMap<String, RequiredDlc> entries = new LinkedHashMap<>();
        roots.forEach(entry -> entries.putIfAbsent(entry.identifier(), entry));
        ArrayDeque<RequiredDlc> queue = new ArrayDeque<>(roots);

        while (!queue.isEmpty()) {
            RequiredDlc owner = queue.removeFirst();
            for (Dependency dependency : owner.dependencies()) {
                if (entries.containsKey(dependency.identifier())) {
                    continue;
                }
                RequiredDlc entry = dependency.entry();
                if (entry == null) {
                    Path configPath = requiredDir.resolve(dependency.identifier() + ".dc");
                    if (Files.exists(configPath)) {
                        entry = readRequiredEntry(configPath);
                    }
                }
                if (entry != null && entry.inList()) {
                    entries.put(dependency.identifier(), entry);
                    queue.addLast(entry);
                }
            }
        }
        return List.copyOf(entries.values());
    }

    private static ManagerSettings readManagerSettings(Path gameDir, Path configPath) {
        String dlcRoot = DEFAULT_DLC_ROOT;
        int connectTimeout = (int) DEFAULT_CONNECT_TIMEOUT.toSeconds();
        int taskTimeout = (int) DEFAULT_TASK_TIMEOUT.toSeconds();
        int maxRetryCount = DEFAULT_MAX_RETRY_COUNT;
        if (Files.exists(configPath)) {
            try (FileConfig config = FileConfig.of(configPath, TomlFormat.instance())) {
                config.load();
                dlcRoot = string(config, "paths.dlcRoot", dlcRoot);
                connectTimeout = integer(config, "downloads.connectTimeoutSeconds", connectTimeout);
                taskTimeout = integer(config, "downloads.taskTimeoutSeconds", taskTimeout);
                maxRetryCount = integer(config, "downloads.maxRetryCount", maxRetryCount);
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Unable to read DLC Manager config " + configPath, exception);
            }
        }
        Path root = Path.of(dlcRoot);
        if (!root.isAbsolute()) {
            root = gameDir.resolve(root);
        }
        return new ManagerSettings(root.normalize(), Duration.ofSeconds(Math.max(1, connectTimeout)),
                Duration.ofSeconds(Math.max(1, taskTimeout)), Math.max(1, maxRetryCount));
    }

    private static Path resolveAppliedTarget(Path gameDir, String target) {
        Path path = Path.of(target == null || target.isBlank() ? "mods" : target);
        return (path.isAbsolute() ? path : gameDir.resolve(path)).normalize();
    }

    private static boolean isModsTarget(Path gameDir, Path targetDir) {
        return targetDir.toAbsolutePath().normalize().equals(gameDir.resolve("mods").toAbsolutePath().normalize());
    }

    private static Path safeResolve(Path directory, String fileName) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        Path result = root.resolve(fileName).normalize();
        if (!result.getParent().equals(root)) {
            throw new IOException("DLC file_name must not escape its target directory: " + fileName);
        }
        return result;
    }

    private static boolean sameFileContent(Path source, Path target) throws IOException {
        return Files.exists(target) && Files.size(source) == Files.size(target)
                && Files.mismatch(source, target) == -1;
    }

    private static void atomicCopy(Path source, Path target) throws IOException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Files.copy(source, part, StandardCopyOption.REPLACE_EXISTING);
        moveAtomically(part, target);
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static ModJarType inspectModJar(Path path) {
        try (JarFile jar = new JarFile(path.toFile())) {
            if (jar.getEntry("META-INF/neoforge.mods.toml") != null || jar.getEntry("META-INF/mods.toml") != null) {
                return ModJarType.NEOFORGE;
            }
            if (jar.getEntry("fabric.mod.json") != null) {
                return ModJarType.FABRIC;
            }
        } catch (IOException exception) {
            LOGGER.debug("Unable to inspect mod DLC jar {}.", path, exception);
        }
        return ModJarType.INVALID;
    }

    private static String string(Config config, String key, String fallback) {
        Object value = config.get(key);
        return value instanceof String text && !text.isBlank() ? text.trim() : fallback;
    }

    private static boolean booleanValue(Config config, String key, boolean fallback) {
        Object value = config.get(key);
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static int integer(Config config, String key, int fallback) {
        Object value = config.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static List<String> stringList(Object value) {
        if (value instanceof String text) {
            return text.isBlank() ? List.of() : List.of(text.trim());
        }
        if (value instanceof List<?> values) {
            return values.stream().filter(String.class::isInstance).map(String.class::cast)
                    .map(String::trim).filter(text -> !text.isBlank()).toList();
        }
        return List.of();
    }

    private static List<String> appliedTargets(Object value) {
        return splitValues(value);
    }

    private static List<String> splitValues(Object value) {
        return stringList(value).stream()
                .flatMap(target -> Stream.of(target.split("[,;]")))
                .map(String::trim)
                .filter(target -> !target.isBlank())
                .toList();
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String minecraftVersion() {
        try {
            Class<?> loader = Class.forName("net.neoforged.fml.loading.FMLLoader");
            Object versionInfo = loader.getMethod("versionInfo").invoke(null);
            Object version = versionInfo.getClass().getMethod("mcVersion").invoke(versionInfo);
            return version instanceof String text && !text.isBlank() ? text : null;
        } catch (ReflectiveOperationException | LinkageError exception) {
            return null;
        }
    }

    private static String jsonString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private record ManagerSettings(Path dlcRoot, Duration connectTimeout, Duration taskTimeout, int maxRetryCount) {
    }

    private record RequiredDlc(Path configPath, String identifier, String fileName, List<String> appliedTargets,
                               boolean inList, List<String> directUrls, List<String> priority,
                               String modrinthProjectId, String modrinthVersionId, String curseForgeFileId,
                               List<Dependency> dependencies) {
    }

    private record Dependency(String identifier, RequiredDlc entry) {
    }

    private enum ModJarType {
        NEOFORGE,
        FABRIC,
        INVALID
    }

    private record InstallFailure(RequiredDlc entry, Throwable cause) {
    }

    static final class InstallException extends RuntimeException {
        private final List<InstallFailure> failures;

        InstallException(String message, List<InstallFailure> failures) {
            super(message, failures.getFirst().cause());
            this.failures = List.copyOf(failures);
        }

        int failureCount() {
            return failures.size();
        }

        Path primaryConfigPath() {
            return failures.getFirst().entry().configPath();
        }
    }
}
