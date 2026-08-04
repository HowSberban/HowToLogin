package org.howtologin.plugin.premium;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单连接会话状态机（模块1 辅助）。
 * <p>
 * 绑定唯一网络连接，记录正版验证流程的当前阶段和临时数据。
 * 内部状态变更使用 AtomicReference 保证可见性（IO线程写入，异步线程可能读取）。
 * <p>
 * 阶段流转：START → WAITING_ENCRYPTION_RESPONSE → ENCRYPTED → DONE
 */
public final class SessionContext {

    /** 会话阶段 */
    public enum Stage {
        /** 初始状态：尚未发送 EncryptionRequest */
        START,
        /** 已发送 EncryptionRequest，等待客户端回传 EncryptionResponse */
        WAITING_ENCRYPTION_RESPONSE,
        /** 已启用 AES 加密，正在进行 Mojang 会话验证 */
        ENCRYPTED,
        /** LoginSuccess 已发送且状态机已推进，流程结束 */
        DONE
    }

    private final AtomicReference<Stage> stage = new AtomicReference<>(Stage.START);

    // LoginStart 时获取的玩家名
    private volatile String username;
    // 玩家 IP 地址
    private volatile String ip;
    // RSA 公钥（用于计算 serverHash）
    private volatile byte[] publicKey;
    // 一次性验证令牌（RSA 加密后发送给客户端）
    private volatile byte[] verifyToken;
    // AES 共享密钥（解密后获得）
    private volatile byte[] sharedSecret;
    // 正版验证后获得的 UUID（验证成功后设置）
    private volatile UUID premiumUuid;
    // 正版验证后获得的 properties JSON（验证成功后设置）
    private volatile String propertiesJson;

    public Stage stage() {
        return stage.get();
    }

    /** 原子推进到下一阶段，仅当当前阶段匹配预期时成功 */
    public boolean advance(Stage expected, Stage next) {
        return stage.compareAndSet(expected, next);
    }

    public void stage(Stage stage) {
        this.stage.set(stage);
    }

    public String username() { return username; }
    public void username(String username) { this.username = username; }

    public String ip() { return ip; }
    public void ip(String ip) { this.ip = ip; }

    public byte[] publicKey() { return publicKey; }
    public void publicKey(byte[] publicKey) { this.publicKey = publicKey; }

    public byte[] verifyToken() { return verifyToken; }
    public void verifyToken(byte[] verifyToken) { this.verifyToken = verifyToken; }

    public byte[] sharedSecret() { return sharedSecret; }
    public void sharedSecret(byte[] sharedSecret) { this.sharedSecret = sharedSecret; }

    public UUID premiumUuid() { return premiumUuid; }
    public void premiumUuid(UUID premiumUuid) { this.premiumUuid = premiumUuid; }

    public String propertiesJson() { return propertiesJson; }
    public void propertiesJson(String propertiesJson) { this.propertiesJson = propertiesJson; }
}
