package org.howtologin.plugin;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final Map<String, Properties> bundles = new ConcurrentHashMap<>();
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

    /**
     * 加载插件 lang/ 目录下所有 *.properties 语言文件。
     * 先构建新 map 再整体替换，避免 reload 期间其它线程读到空 map 导致回退到 key 名显示。
     */
    public static void loadAll() {
        Map<String, Properties> newBundles = new HashMap<>();
        File langDir = new File(plugin.getDataFolder(), LANG_DIR);
        File[] files = langDir.listFiles((dir, name) -> name.endsWith(".properties"));
        if (files != null) {
            for (File file : files) {
                Matcher matcher = LOCALE_PATTERN.matcher(file.getName());
                if (matcher.matches()) {
                    String locale = matcher.group(1);
                    Properties props = new Properties();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                        loadPropertiesWithNewlines(props, reader);
                        newBundles.put(locale, props);
                    } catch (IOException e) {
                        plugin.getLogger().warning("无法加载语言文件 " + file.getName() + "：" + e.getMessage());
                    }
                }
            }
        }
        // 原子替换：clear + putAll，ConcurrentHashMap 保证读线程不会看到中间状态
        bundles.clear();
        bundles.putAll(newBundles);
        if (bundles.isEmpty()) {
            plugin.getLogger().warning("未找到任何语言文件，请检查插件目录");
        }
    }

    /**
     * 自定义 properties 加载：支持三引号 {@code """} 包裹的多行值。
     * 语法：
     *   - 单行值：key=value（标准 properties，支持反斜杠续行和 \n \t 等转义）
     *   - 多行值：key=""" 开始，换行书写内容，以单独的 """ 行结束
     *     多行值内可包含 # = : 等特殊字符，无需转义；物理换行保留为换行符
     *     仍支持 \n \t 等转义序列（会被解析为对应字符）
     */
    private static void loadPropertiesWithNewlines(Properties props, BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.stripLeading();
            // 空行或注释跳过
            if (trimmed.isEmpty() || trimmed.charAt(0) == '#' || trimmed.charAt(0) == '!') continue;

            // 处理续行（行尾奇数个反斜杠）
            StringBuilder logical = new StringBuilder(trimmed);
            while (countTrailingBackslashes(logical.toString()) % 2 == 1) {
                logical.setLength(logical.length() - 1);
                String next = reader.readLine();
                if (next == null) break;
                logical.append(next.stripLeading());
            }
            String full = logical.toString();

            // 分割 key 和 value
            int eq = full.indexOf('=');
            int colon = full.indexOf(':');
            int sep = (eq < 0) ? colon : (colon < 0 ? eq : Math.min(eq, colon));
            if (sep <= 0) continue;

            String key = full.substring(0, sep).trim();
            String value = full.substring(sep + 1).trim();

            // 三引号多行值
            if (value.startsWith("\"\"\"")) {
                String rest = value.substring(3);
                // 同行结束："""..."""
                if (rest.endsWith("\"\"\"")) {
                    value = rest.substring(0, rest.length() - 3);
                } else {
                    // 多行模式：读取直到 """
                    String[] result = readMultilineValue(reader, rest);
                    value = result[0];
                    if ("false".equals(result[1])) {
                        plugin.getLogger().warning("语言文件中 key \"" + key + "\" 的三引号值未闭合");
                    }
                }
            }

            props.setProperty(key, unescape(value));
        }
    }

    /**
     * 读取三引号多行值，直到遇到单独的 """ 行或行尾 """。
     * @return [0] = 拼接后的值；[1] = "true" 表示正常闭合，"false" 表示未闭合（读到 EOF）
     */
    private static String[] readMultilineValue(BufferedReader reader, String firstLine) throws IOException {
        List<String> lines = new ArrayList<>();
        if (!firstLine.isEmpty()) {
            lines.add(firstLine);
        }
        boolean closed = false;
        String nextLine;
        while ((nextLine = reader.readLine()) != null) {
            // 整行是 """，结束
            if (nextLine.equals("\"\"\"")) {
                closed = true;
                break;
            }
            // 行尾是 """，结束（取前面的内容）
            if (nextLine.endsWith("\"\"\"")) {
                lines.add(nextLine.substring(0, nextLine.length() - 3));
                closed = true;
                break;
            }
            lines.add(nextLine);
        }
        return new String[]{String.join("\n", lines), Boolean.toString(closed)};
    }

    /** 统计字符串末尾连续反斜杠的数量 */
    private static int countTrailingBackslashes(String s) {
        int count = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            if (s.charAt(i) == '\\') count++;
            else break;
        }
        return count;
    }

    /** properties 标准反转义：换行、制表、回车、反斜杠、Unicode 转义序列 */
    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '\\' -> sb.append('\\');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                sb.append('\\').append(next);
                            }
                        } else {
                            sb.append('\\').append(next);
                        }
                    }
                    default -> sb.append('\\').append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 重新加载所有语言文件（/htlogin reload 调用） */
    public static void reload() {
        loadAll();
    }

    /** 清理静态状态（onDisable 调用，避免热卸载时类加载器无法回收） */
    public static void shutdown() {
        bundles.clear();
        plugin = null;
        defaultLocale = "zh_CN";
        clientLanguageDetection = true;
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
        return getByLocale(key, defaultLocale);
    }

    public static String get(String key, Object... args) {
        return format(get(key), args);
    }

    // ===== 玩家语言 =====

    /** 根据玩家客户端 locale 获取消息，找不到时回退到默认语言；关闭检测时使用默认语言 */
    public static String get(String key, Player player) {
        if (!clientLanguageDetection) return get(key);
        // locale() 返回 Locale 对象，需 toString() 转为 "zh_CN" 格式字符串
        return getByLocale(key, player.locale().toString());
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

    // ===== 内部方法 =====

    /** 按 locale 查找消息（精确匹配失败回退到默认语言） */
    private static String getByLocale(String key, String locale) {
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

    private static String format(String pattern, Object... args) {
        if (args == null || args.length == 0) return pattern;
        try {
            return MessageFormat.format(pattern, args);
        } catch (Exception e) {
            return pattern;
        }
    }
}
