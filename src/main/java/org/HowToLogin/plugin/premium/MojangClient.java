package org.howtologin.plugin.premium;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // 从 hasJoined JSON 中提取 "id" 字段（无横线 UUID）
    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    // 从 hasJoined JSON 中提取 "properties" 数组（含皮肤数据）
    private static final Pattern PROPERTIES_PATTERN =
            Pattern.compile("\"properties\"\\s*:\\s*(\\[[^\\]]*\\])");

    private final int timeoutSeconds;

    public MojangClient(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 异步向 Mojang 会话服务器发起 hasJoined 验证。
     *
     * @param serverHash 服务器哈希（SHA-1(serverId + sharedSecret + publicKey) 的正十六进制）
     * @param username   玩家名称
     * @return 200 OK 时返回 PremiumProfile（含正版 UUID 和 properties），失败/超时返回 empty
     */
    public CompletableFuture<Optional<PremiumProfile>> hasJoined(String serverHash, String username) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = HAS_JOINED_API
                        + URLEncoder.encode(username, StandardCharsets.UTF_8)
                        + "&serverId=" + serverHash;
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .GET()
                        .build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) return Optional.empty();

                String body = response.body();
                String id = extractId(body);
                if (id == null) return Optional.empty();

                UUID uuid = parseUuid(id);
                String properties = extractProperties(body);
                return Optional.of(new PremiumProfile(uuid, properties));
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }

    /** 从 JSON 中提取 "id" 字段的字符串值 */
    private static String extractId(String json) {
        Matcher matcher = ID_PATTERN.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 从 JSON 中提取 "properties" 数组的原始 JSON 字符串（可能为 null） */
    private static String extractProperties(String json) {
        Matcher matcher = PROPERTIES_PATTERN.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 将 Mojang 返回的无横线 UUID 解析为 UUID 对象 */
    private static UUID parseUuid(String id) {
        if (id.contains("-")) return UUID.fromString(id);
        return UUID.fromString(id.replaceFirst(
                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
    }

    /** 正版验证结果（不可变） */
    public static final class PremiumProfile {
        private final UUID uuid;
        private final String propertiesJson;

        public PremiumProfile(UUID uuid, String propertiesJson) {
            this.uuid = uuid;
            this.propertiesJson = propertiesJson;
        }

        public UUID uuid() { return uuid; }
        public String propertiesJson() { return propertiesJson; }
    }
}
