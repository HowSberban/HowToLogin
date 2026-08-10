package org.howtologin.plugin.premium;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;
import org.howtologin.plugin.config.ConfigManager;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mojang 会话验证客户端（模块3）。
 * <p>
 * 仅负责与 Mojang 会话服务器交互，执行 hasJoined 验证。
 * 所有 HTTP 调用严格异步执行，超时后返回空结果。
 * <p>
 * 线程模型：所有方法在异步线程执行，返回 CompletableFuture。
 */
public final class MojangClient {

    private static final String HAS_JOINED_API =
            "https://sessionserver.mojang.com/session/minecraft/hasJoined?username=";

    // Mojang sessionserver 要求 User-Agent，否则可能返回 403/429
    private static final String USER_AGENT = "HTLogin-Premium/1.0";

    // 连接超时较短（5s），读取超时由 timeoutSeconds 控制
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final HTLogin plugin;
    // 独立线程池：hasJoined 内部含 sleep + 指数退避重试，最长可阻塞较久，
    // 用专用线程池避免占用公共 ForkJoinPool 拖累其它插件的异步任务。
    // 池大小由配置 premium.http-pool-size 控制（阻塞式任务，高并发正版验证时可按负载调大）
    private final ExecutorService httpExecutor;

    public MojangClient(HTLogin plugin) {
        this.plugin = plugin;
        int poolSize = Math.max(2, plugin.getConfigManager().premiumHttpPoolSize());
        this.httpExecutor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "HTLogin-Mojang");
            t.setDaemon(true);
            return t;
        });
    }

    /** 关闭线程池（插件禁用时调用） */
    public void close() {
        httpExecutor.shutdown();
    }

    /**
     * 异步向 Mojang 会话服务器发起 hasJoined 验证。
     * 遇 204/429/502/503/504 等可重试结果时按配置（premium.max-retries / retry-backoff-base-ms）
     * 指数退避自动重试。
     *
     * @param serverHash 服务器哈希（SHA-1(serverId + sharedSecret + publicKey) 的正十六进制）
     * @param username   玩家名称
     * @return 200 OK 时返回 PremiumProfile（含正版 UUID 和 properties），失败/重试耗尽返回 empty
     */
    public CompletableFuture<Optional<PremiumProfile>> hasJoined(String serverHash, String username) {
        return CompletableFuture.supplyAsync(() -> {
            ConfigManager config = plugin.getConfigManager();
            int maxRetries = config.premiumMaxRetries();
            long backoffBase = config.premiumRetryBackoffBaseMs();

            // 首次调用前等待，确保客户端已 join Mojang sessionserver
            // 客户端发 EncryptionResponse 后才 join Mojang，服务端可能更快到达 hasJoined
            sleep(config.premiumInitialDelayMs());

            String url = HAS_JOINED_API
                    + URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + "&serverId=" + serverHash;

            // 首次尝试 + 最多 maxRetries 次重试；重试耗尽（限流/超时持续）时由循环条件退出
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(config.premiumTimeoutSeconds()))
                            .header("User-Agent", USER_AGENT)
                            .header("Accept", "application/json")
                            .GET()
                            .build();
                    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                    int code = response.statusCode();

                    if (code == 200) {
                        // 只解析一次 JSON，复用 JsonObject 提取 id 与 properties
                        JsonObject obj = parseJson(response.body());
                        String id = obj == null ? null : extractId(obj);
                        if (id == null) {
                            plugin.getLogger().warning(I18n.get("log.premium_hasjoined_failed",
                                    username, "no id field"));
                            return Optional.empty();
                        }
                        UUID uuid = parseUuid(id);
                        String properties = extractProperties(obj);
                        return Optional.of(new PremiumProfile(uuid, properties));
                    }

                    // 非可重试状态码：直接失败
                    if (code != 204 && !isRetryable(code)) {
                        plugin.getLogger().warning(I18n.get("log.premium_hasjoined_failed",
                                username, "HTTP " + code));
                        return Optional.empty();
                    }

                    // 走到这里说明本次结果为可重试（204 未 join / 429,502,503,504 限流或临时不可用）
                } catch (java.net.http.HttpTimeoutException timeout) {
                    // 请求/连接超时均抛 HttpTimeoutException（含其子类 HttpConnectTimeoutException），按可重试处理
                } catch (Exception e) {
                    plugin.getLogger().warning(I18n.get("log.premium_hasjoined_failed",
                            username, e.getClass().getSimpleName() + ": " + e.getMessage()));
                    return Optional.empty();
                }

                // 可重试结果统一在此退避；若仍剩尝试次数则重试，否则由循环条件终止
                // 频繁重试会加重限流，故采用指数退避（间隔较长）
                if (attempt < maxRetries) {
                    plugin.getLogger().warning(I18n.get("log.premium_hasjoined_retry",
                            username, attempt + 1, maxRetries));
                    sleep(backoffDelay(backoffBase, attempt));
                }
            }
            // 重试耗尽：所有尝试均限流或超时
            plugin.getLogger().warning(I18n.get("log.premium_hasjoined_failed",
                    username, "max retries"));
            return Optional.empty();
        }, httpExecutor);
    }

    /** 判断 HTTP 状态码是否可重试 */
    private static boolean isRetryable(int code) {
        return code == 429 || code == 502 || code == 503 || code == 504;
    }

    /** 指数退避延迟（毫秒）：attempt=0 → base，attempt=1 → 2*base */
    private static long backoffDelay(long base, int attempt) {
        return base * (1L << attempt);
    }

    /** 线程睡眠（不抛异常） */
    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    /** 解析 JSON 为 JsonObject，解析失败返回 null */
    private static JsonObject parseJson(String json) {
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    /** 从已解析的 JsonObject 中提取 "id" 字段的无横线 UUID 字符串 */
    private static String extractId(JsonObject obj) {
        return obj.has("id") && !obj.get("id").isJsonNull() ? obj.get("id").getAsString() : null;
    }

    /** 从已解析的 JsonObject 中提取 "properties" 数组的原始 JSON 字符串（可能为 null） */
    private static String extractProperties(JsonObject obj) {
        JsonElement props = obj.get("properties");
        return props == null || props.isJsonNull() ? null : props.toString();
    }

    /** 将 Mojang 返回的无横线 UUID 解析为 UUID 对象 */
    private static UUID parseUuid(String id) {
        if (id.contains("-")) return UUID.fromString(id);
        return UUID.fromString(id.replaceFirst(
                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
    }

    /**
     * 正版验证结果（不可变）
     */
    public record PremiumProfile(UUID uuid, String propertiesJson) {
    }
}
