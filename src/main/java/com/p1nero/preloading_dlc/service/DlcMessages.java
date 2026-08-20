package com.p1nero.preloading_dlc.service;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

final class DlcMessages {
    private static final String RESOURCE_ROOT = "/assets/preloading_dlc/lang/";

    private final Locale locale;
    private final Properties translations;

    private DlcMessages(Locale locale) {
        this.locale = locale;
        this.translations = loadTranslations(locale);
    }

    static DlcMessages forGameDirectory(Path gameDir) {
        return new DlcMessages(resolveLocale(gameDir));
    }

    static DlcMessages forLanguage(String language) {
        return new DlcMessages(supportedLocale(language));
    }

    String text(String key) {
        return translations.getProperty(key, key);
    }

    String format(String key, Object... arguments) {
        return String.format(locale, text(key), arguments);
    }

    private static Locale resolveLocale(Path gameDir) {
        Path options = gameDir.resolve("options.txt");
        if (Files.isRegularFile(options)) {
            try {
                for (String line : Files.readAllLines(options)) {
                    if (line.startsWith("lang:")) {
                        return supportedLocale(line.substring("lang:".length()));
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return supportedLocale(Locale.getDefault().toString());
    }

    private static Locale supportedLocale(String configured) {
        String value = configured.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (value.startsWith("zh_tw") || value.startsWith("zh_hk")) {
            return Locale.forLanguageTag("zh-TW");
        }
        if (value.startsWith("zh")) {
            return Locale.forLanguageTag("zh-CN");
        }
        if (value.startsWith("ja")) {
            return Locale.JAPAN;
        }
        if (value.startsWith("ko")) {
            return Locale.KOREA;
        }
        if (value.startsWith("de")) {
            return Locale.GERMANY;
        }
        if (value.startsWith("fr")) {
            return Locale.FRANCE;
        }
        if (value.startsWith("es")) {
            return Locale.forLanguageTag("es-ES");
        }
        if (value.startsWith("pt")) {
            return Locale.forLanguageTag("pt-BR");
        }
        if (value.startsWith("ru")) {
            return Locale.forLanguageTag("ru-RU");
        }
        return Locale.US;
    }

    private static Properties loadTranslations(Locale locale) {
        Properties translations = englishFallback();
        loadResource(translations, "messages.properties");
        if (!Locale.US.equals(locale)) {
            loadResource(translations, "messages_" + locale + ".properties");
        }
        return translations;
    }

    private static void loadResource(Properties translations, String fileName) {
        try (InputStream input = DlcMessages.class.getResourceAsStream(RESOURCE_ROOT + fileName)) {
            if (input != null) {
                translations.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static Properties englishFallback() {
        Properties translations = new Properties();
        translations.setProperty("failure.title", "Required DLC installation failed; startup has been blocked.");
        translations.setProperty("failure.missing_count", "Missing component(s): %d");
        translations.setProperty("failure.expected_file", "Expected file");
        translations.setProperty("failure.manual_target", "Download it manually and place it at");
        translations.setProperty("failure.applied_targets", "It will be applied to");
        translations.setProperty("failure.configuration", "Configuration");
        translations.setProperty("failure.last_error", "Last error");
        translations.setProperty("failure.retry_and_restart",
                "Each download source was attempted %d time(s). Restart the game after placing every listed file.");
        return translations;
    }
}
