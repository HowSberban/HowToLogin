package org.howtologin.plugin.premium;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;
import org.howtologin.plugin.config.ConfigManager;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

    // hasJoined 接口路径，拼接在会话验证服务器基础 URL 之后
    private static final String HAS_JOINED_PATH = "/session/minecraft/hasJoined?username=";

    // Mojang sessionserver 要求 User-Agent，否则可能返回 403/429
    private static final String USER_AGENT = "HTLogin-Premium/1.0";

    /** 验证端点：唯一 key + HTTP 客户端（绑定出站代理或直连）+ 目标服务器基础 URL + 日志描述 */
    private record Endpoint(String key, HttpClient client, String baseUrl, String description) {}

    // 直连 HttpClient（无出站代理），所有直连端点共用
    private static final HttpClient DIRECT_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final HTLogin plugin;
    // 按代理地址缓存 HttpClient：出站代理在 HttpClient 构建时绑定且创建开销大，同地址代理复用
    private final Map<String, HttpClient> proxyClients = new ConcurrentHashMap<>();
    // 启动探测结果：端点 key → 是否可用（连接成功=true）。未探测/探测中无记录，使用时不跳过
    private final Map<String, Boolean> availability = new ConcurrentHashMap<>();
    // 独立线程池：hasJoined 内部含 sleep + 重试等待，最长可阻塞较久，
    // 用专用线程池避免占用公共 ForkJoinPool 拖累其它插件的异步任务。
    // 池大小由配置 premium.http-pool-size 控制（阻塞式任务，高并发正版验证时可按负载调大）
    private final ExecutorService httpExecutor;

    public MojangClient(HTLogin plugin) {
        this.plugin = plugin;
        int poolSize = plugin.getConfigManager().premiumHttpPoolSize();
        this.httpExecutor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "HTLogin-Mojang");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 按当前配置构建全部验证端点（不过滤，供启动探测使用）。
     * 端点顺序即尝试顺序：
     * 1. 每个 HTTP 出站代理 → 官方地址（借道代理访问真正的 Mojang，验证结果可信）
     * 2. 直连 → 镜像候选列表（官方地址硬编码首位 + 配置的镜像，代理均不可用时的备选）
     */
    private List<Endpoint> allEndpoints() {
        ConfigManager config = plugin.getConfigManager();
        List<Endpoint> list = new ArrayList<>();
        String official = config.premiumSessionServerCandidates().get(0);
        for (Proxy proxy : config.premiumHttpProxies()) {
            var addr = (InetSocketAddress) proxy.address();
            String proxyDesc = addr.getHostString() + ":" + addr.getPort();
            String proxyKey = "proxy:" + proxyDesc + "@" + official;
            list.add(new Endpoint(proxyKey, proxyClient(proxyDesc, proxy), official,
                    I18n.get("log.premium_endpoint_via_proxy", official, proxyDesc)));
        }
        for (String baseUrl : config.premiumSessionServerCandidates()) {
            list.add(new Endpoint("direct:" + baseUrl, DIRECT_CLIENT, baseUrl, baseUrl));
        }
        return list;
    }

    /** 构建验证端点列表：跳过启动探测确认为不可用的端点；未探测/探测中的端点不跳过（保留兜底） */
    private List<Endpoint> buildEndpoints() {
        List<Endpoint> list = new ArrayList<>();
        for (Endpoint endpoint : allEndpoints()) {
            // null=未探测/探测中不跳过（保持既有的运行时兜底）；true=可用；false=启动探测失败，跳过
            if (Boolean.FALSE.equals(availability.get(endpoint.key()))) {
                plugin.getLogger().warning(I18n.get("log.premium_endpoint_skipped", endpoint.description()));
                continue;
            }
            list.add(endpoint);
        }
        return list;
    }

    /** 获取/创建绑定指定出站代理的 HttpClient（Java HttpClient 不支持按请求切换代理，须按代理构建） */
    private HttpClient proxyClient(String key, Proxy proxy) {
        return proxyClients.computeIfAbsent(key, k -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .proxy(ProxySelector.of((InetSocketAddress) proxy.address()))
                .build());
    }

    /** 关闭线程池（插件禁用时调用） */
    public void close() {
        httpExecutor.shutdown();
    }

    /**
     * 启动时异步探测所有验证端点的可用性（不阻塞服务器启动）：
     * 向每个端点发一个轻量请求，连接成功（未抛 IOException）即视为可用并记录结果，
     * 失败记录为不可用，供 buildEndpoints 在后续验证时选择性跳过。
     * 连接池在此过程中自然预热，首个验证的玩家可省去握手耗时。
     * 探测无结果（仍为 null）时使用时不跳过，保留既有的运行时兜底。
     */
    public void probeAll() {
        for (Endpoint endpoint : allEndpoints()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            endpoint.baseUrl() + HAS_JOINED_PATH + "probe&serverId=probe"))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            endpoint.client().sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    // 收到任意 HTTP 响应：服务可达；抛异常（连接失败/超时）：不可达
                    .thenApply(r -> true)
                    .exceptionally(e -> false)
                    .thenAccept(ok -> availability.put(endpoint.key(), ok));
        }
    }

    /**
     * 异步向 Mojang 会话服务器发起 hasJoined 验证。
     * 依次尝试验证端点（HTTP 代理→官方地址优先，直连→镜像备选），直到某个端点给出确定答复；
     * 某端点不可达或持续过载时自动切换到下一个。
     * 整个验证（含端点切换）受总时限（premium.verify-deadline-ms）约束：
     * 客户端在"通讯加密中"界面约 30 秒未收到 LoginSuccess 会主动断开，
     * 服务端验证须在此之前结束，否则验证白做且玩家需重连。
     *
     * @param serverHash 服务器哈希（SHA-1(serverId + sharedSecret + publicKey) 的正十六进制）
     * @param username   玩家名称
     * @return 200 OK 时返回 PremiumProfile（含正版 UUID 和 properties），未加入/全部端点不可用/超时返回 empty
     */
    public CompletableFuture<Optional<PremiumProfile>> hasJoined(String serverHash, String username) {
        return CompletableFuture.supplyAsync(() -> {
            ConfigManager config = plugin.getConfigManager();
            long deadline = System.currentTimeMillis() + config.premiumVerifyDeadlineMs();
            String encodedName = URLEncoder.encode(username, StandardCharsets.UTF_8);
            // 依次尝试端点，直到某个端点给出确定答复或总时限耗尽
            for (Endpoint endpoint : buildEndpoints()) {
                if (System.currentTimeMillis() >= deadline) break;
                Optional<PremiumProfile> result = queryServer(endpoint, serverHash, encodedName, username, config, deadline);
                if (result != null) {
                    return result; // 该端点可达且给出确定答复（验证成功或未加入）
                }
                // result == null：该端点不可用（不可达/持续过载/时限耗尽），尝试下一个
                plugin.getLogger().warning(I18n.get("log.premium_session_server_unavailable", endpoint.description()));
            }
            // 所有端点均不可用或总时限耗尽
            plugin.getLogger().warning(I18n.get("log.premium_hasjoined_failed",
                    username, "all session servers unavailable"));
            return Optional.empty();
        }, httpExecutor);
    }

    /**
     * 向单个验证端点发起 hasJoined 查询（含重试）。
     * 无需等待：客户端在发送 EncryptionResponse 之前已向 sessionserver 发送 /join，
     * 收到 EncryptionResponse 即可立即查询（与原版服务端行为一致）。
     * 单次请求超时与重试等待均受总时限约束，避免超出客户端等待上限。
     *
     * @return 确定答复：Optional.of(档案)=验证成功，Optional.empty()=未加入/拒绝；
     *         null 表示该端点不可用（不可达/持续过载/时限耗尽），调用方应尝试下一个端点
     */
    private Optional<PremiumProfile> queryServer(Endpoint endpoint, String serverHash, String encodedName,
                                                 String username, ConfigManager config, long deadline) {
        int maxRetries = config.premiumMaxRetries();
        long retryIntervalMs = config.premiumRetryIntervalMs();
        long requestTimeoutMs = config.premiumTimeoutSeconds() * 1000L;
        String url = endpoint.baseUrl() + HAS_JOINED_PATH + encodedName + "&serverId=" + serverHash;
        // 记录最后一次可重试结果的性质：重试耗尽后据此判断是"玩家未加入"（确定）还是"服务器过载"（换端点）
        boolean lastWasOverload = false;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) return null; // 总时限耗尽，停止尝试
            HttpResponse<String> response;
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        // 请求超时不超过剩余时限，保证整体不超总时限
                        .timeout(Duration.ofMillis(Math.min(requestTimeoutMs, remaining)))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                response = endpoint.client().send(request, HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException e) {
                // 连接失败（含连接/读取超时）：端点不可达，立即切换下一个（不在本端点重试，快速回退）
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }

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
                return Optional.of(new PremiumProfile(parseUuid(id), extractProperties(obj)));
            }
            if (code == 204) {
                // 玩家未加入会话：可能是客户端 /join 尚未生效，等待后重试；重试耗尽视为确定的"未加入"
                lastWasOverload = false;
            } else if (isRetryable(code)) {
                // 429/5xx：服务器过载，等待后重试；重试耗尽视为不可用，切换下一个端点
                lastWasOverload = true;
            } else {
                // 其他状态码：确定失败
                plugin.getLogger().warning(I18n.get("log.premium_hasjoined_failed",
                        username, "HTTP " + code));
                return Optional.empty();
            }

            if (attempt < maxRetries) {
                long remainingAfter = deadline - System.currentTimeMillis();
                if (remainingAfter <= 0) return null; // 总时限耗尽，不再等待重试
                plugin.getLogger().warning(I18n.get("log.premium_hasjoined_retry",
                        username, attempt + 1, maxRetries));
                sleep(Math.min(retryIntervalMs, remainingAfter));
            }
        }
        // 重试耗尽：过载 → 不可用（换端点）；未加入 → 确定答复
        return lastWasOverload ? null : Optional.empty();
    }

    /** 判断 HTTP 状态码是否可重试 */
    private static boolean isRetryable(int code) {
        return code == 429 || code == 502 || code == 503 || code == 504;
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
