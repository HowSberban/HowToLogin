package org.HowToLogin.plugin;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 国际化系统：根据客户端语言（Player.locale）匹配对应消息文件。
 * 找不到对应语言时回退到默认语言（由配置项 settings.default-language 指定）。
 */
public final class I18n {

    // 内置支持的语言资源（jar 内释放到插件 lang/ 目录）
    private static final String[] BUNDLED_RESOURCES = {
            "lang/zh_CN.properties",
            "lang/en_US.properties"
    };
    // 语言文件存放的子目录名
    private static final String LANG_DIR = "lang";
    // 解析文件名中的 locale 后缀：zh_CN.properties -> zh_CN
    private static final Pattern LOCALE_PATTERN = Pattern.compile("^(.+)\\.properties$");

    private static final Map<String, Properties> bundles = new HashMap<>();
    private static String defaultLocale = "zh_CN";
    private static boolean clientLanguageDetection = true;
    private static Plugin plugin;

    private I18n() {}

    public static void init(Plugin plugin) {
        I18n.plugin = plugin;
        // 释放所有内置语言文件（不覆盖已存在的，由版本检查机制负责覆盖）
        for (String resource : BUNDLED_RESOURCES) {
            File file = new File(plugin.getDataFolder(), resource);
            if (!file.exists()) {
                try {
                    plugin.saveResource(resource, false);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("无法从 jar 内释放 " + resource + "：" + e.getMessage());
                }
            }
        }
        loadAll();
    }

    /** 加载插件 lang/ 目录下所有 *.properties 语言文件 */
    public static void loadAll() {
        bundles.clear();
        File langDir = new File(plugin.getDataFolder(), LANG_DIR);
        File[] files = langDir.listFiles((dir, name) -> name.endsWith(".properties"));
        if (files != null) {
            for (File file : files) {
                Matcher matcher = LOCALE_PATTERN.matcher(file.getName());
                if (matcher.matches()) {
                    String locale = matcher.group(1);
                    Properties props = new Properties();
                    try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                        props.load(reader);
                        bundles.put(locale, props);
                    } catch (IOException e) {
                        plugin.getLogger().warning("无法加载语言文件 " + file.getName() + "：" + e.getMessage());
                    }
                }
            }
        }
        if (bundles.isEmpty()) {
            plugin.getLogger().warning("未找到任何语言文件，请检查插件目录");
        }
    }

    /** 重新加载所有语言文件（/htlogin reload 调用） */
    public static void reload() {
        loadAll();
    }

    /** 设置默认语言（控制台日志和 fallback 使用），由 ConfigManager 加载配置后调用 */
    public static void setDefaultLocale(String locale) {
        if (locale != null && !locale.isEmpty()) {
            defaultLocale = locale;
        }
    }

    /** 设置是否启用客户端语言检测（关闭后所有玩家消息使用默认语言） */
    public static void setClientLanguageDetection(boolean enabled) {
        clientLanguageDetection = enabled;
    }

    // ===== 默认语言（控制台日志用） =====

    public static String get(String key) {
        return get(key, defaultLocale);
    }

    public static String get(String key, Object... args) {
        return format(get(key), args);
    }

    // ===== 玩家语言 =====

    /** 根据玩家客户端 locale 获取消息，找不到时回退到默认语言；关闭检测时使用默认语言 */
    public static String get(String key, Player player) {
        if (!clientLanguageDetection) return get(key);
        // locale() 返回 Locale 对象，需 toString() 转为 "zh_CN" 格式字符串
        // 否则会误匹配 get(String, Object...) 重载，locale 被当作 format 参数忽略
        return get(key, player.locale().toString());
    }

    /** 根据玩家客户端 locale 获取消息并填充参数 */
    public static String get(String key, Player player, Object... args) {
        return format(get(key, player), args);
    }

    // ===== CommandSender（自动判断 Player/控制台） =====

    /** 根据 sender 类型自动选择语言：Player 用客户端语言（若启用检测），非 Player 用默认语言 */
    public static String get(String key, CommandSender sender) {
        return sender instanceof Player player ? get(key, player) : get(key);
    }

    /** 根据 sender 类型自动选择语言并填充参数 */
    public static String get(String key, CommandSender sender, Object... args) {
        return format(get(key, sender), args);
    }

    // ===== 指定 locale =====

    public static String get(String key, String locale) {
        Properties props = bundles.get(locale);
        if (props == null) {
            // 精确匹配失败：回退到默认语言
            props = bundles.get(defaultLocale);
        }
        if (props == null) {
            return key;
        }
        String value = props.getProperty(key);
        return value != null ? value : key;
    }

    public static String get(String key, String locale, Object... args) {
        return format(get(key, locale), args);
    }

    private static String format(String pattern, Object... args) {
        if (args == null || args.length == 0) return pattern;
        try {
            return MessageFormat.format(pattern, args);
        } catch (Exception e) {
            return pattern;
        }
    }
}
