package org.HowToLogin.plugin;

import org.bukkit.plugin.Plugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.MessageFormat;
import java.util.Properties;

public final class I18n {

    private static final Properties props = new Properties();
    private static Plugin plugin;

    private I18n() {}

    public static void init(Plugin plugin) {
        I18n.plugin = plugin;
        File file = new File(plugin.getDataFolder(), "translations.properties");
        if (!file.exists()) {
            try (InputStream in = I18n.class.getResourceAsStream("/translations.properties")) {
                if (in != null) Files.copy(in, file.toPath());
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to release translations.properties");
            }
        }

        load(file);
    }

    private static void load(File file) {
        props.clear();
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to load translations.properties");
        }
    }

    public static void reload() {
        load(new File(plugin.getDataFolder(), "translations.properties"));
    }

    public static String get(String key) {
        String value = props.getProperty(key);
        return value != null ? value : key;
    }

    public static String get(String key, Object... args) {
        try {
            return MessageFormat.format(get(key), args);
        } catch (Exception e) {
            return get(key);
        }
    }
}