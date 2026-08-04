package org.howtologin.plugin.premium;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientEncryptionResponse;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerDisconnect;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerEncryptionRequest;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;

import javax.crypto.Cipher;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连接处理器（模块1）—— 调度 + 加密握手合并。
 * <p>
 * 监听 LoginStart / EncryptionResponse 包事件，为每个连接维护会话状态机，
 * 编排 DataService / MojangClient / PlayerInjector 完成正版验证流程。
 * <p>
 * 线程模型：
 * - onPacketReceive 运行在 PacketEvents IO/事件线程
 * - Mojang HTTP 在异步线程执行
 * - 状态推进和 LoginSuccess 发送切回 IO 线程（channel.eventLoop）
 * <p>
 * 仅拦截需要正版验证的连接（premium=1 或新玩家），离线玩家（premium=0 或离线确认命中）
 * 不取消 LoginStart，由服务端原生处理。
 */
public final class ConnectionHandler extends PacketListenerAbstract {

    private final HTLogin plugin;
    private final DataService dataService;
    private final MojangClient mojangClient;
    private final PlayerInjector playerInjector;
    private final KeyPair rsaKeyPair;
    private final byte[] publicKeyEncoded;

    // 每连接会话状态：channel → SessionContext
    private final Map<Channel, SessionContext> sessions = new ConcurrentHashMap<>();

    // Netty pipeline 中断开检测器名称
    private static final String DETECTOR_NAME = "htlogin_disconnect_detector";

    public ConnectionHandler(HTLogin plugin, DataService dataService, MojangClient mojangClient,
                             PlayerInjector playerInjector) {
        super(PacketListenerPriority.LOWEST);
        this.plugin = plugin;
        this.dataService = dataService;
        this.mojangClient = mojangClient;
        this.playerInjector = playerInjector;
        this.rsaKeyPair = generateKeyPair();
        this.publicKeyEncoded = rsaKeyPair.getPublic().getEncoded();
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Login.Client.LOGIN_START) {
            handleLoginStart(event);
        } else if (event.getPacketType() == PacketType.Login.Client.ENCRYPTION_RESPONSE) {
            handleEncryptionResponse(event);
        }
    }

    // ===== 阶段1：LoginStart 拦截与查档 =====

    private void handleLoginStart(PacketReceiveEvent event) {
        Channel channel = (Channel) event.getChannel();
        User user = event.getUser();

        WrapperLoginClientLoginStart wrapper = new WrapperLoginClientLoginStart(event);
        String username = wrapper.getUsername();

        // 获取玩家 IP
        String ip = extractIp(channel);
        if (ip == null) return; // 无法获取 IP，放行让服务端处理

        // 1. 检查离线确认标记：命中则不拦截，让服务端原生处理
        if (dataService.isOfflineConfirmed(ip, username)) {
            return;
        }

        // 2. 查询档案（纯内存操作）
        DataService.ProfileResult profile = dataService.getProfile(username);

        // 存在且 premium=0 → 离线玩家，不拦截
        if (profile.exists() && !profile.premium()) {
            return;
        }

        // 3. premium=1 或新玩家 → 取消包，走正版验证流程
        event.setCancelled(true);

        // 清理旧会话（同一 channel 不应有多条 LoginStart，但防御性处理）
        sessions.remove(channel);

        // 创建会话
        SessionContext session = new SessionContext();
        session.username(username);
        session.ip(ip);
        session.publicKey(publicKeyEncoded);
        sessions.put(channel, session);

        // 4. 生成验证令牌并发送 EncryptionRequest
        byte[] verifyToken = new byte[4];
        new SecureRandom().nextBytes(verifyToken);
        session.verifyToken(verifyToken);

        WrapperLoginServerEncryptionRequest request =
                new WrapperLoginServerEncryptionRequest("", rsaKeyPair.getPublic(), verifyToken);
        user.sendPacket(request);

        // 5. 注册断开检测器：若在收到 EncryptionResponse 之前断开，确认为离线客户端
        channel.pipeline().addFirst(DETECTOR_NAME, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                SessionContext s = sessions.get(channel);
                if (s != null && s.stage() == SessionContext.Stage.WAITING_ENCRYPTION_RESPONSE) {
                    // 发送 EncryptionRequest 后未收到响应即断开 → 离线客户端
                    dataService.markOfflineConfirmed(s.ip(), s.username());
                }
                sessions.remove(channel);
                ctx.fireChannelInactive();
            }
        });

        session.advance(SessionContext.Stage.START, SessionContext.Stage.WAITING_ENCRYPTION_RESPONSE);
    }

    // ===== 阶段2-3：加密握手与启用 =====

    private void handleEncryptionResponse(PacketReceiveEvent event) {
        Channel channel = (Channel) event.getChannel();
        User user = event.getUser();

        SessionContext session = sessions.get(channel);
        // 无会话（非正版验证流程的 EncryptionResponse）→ 放行
        if (session == null) return;
        // 阶段不匹配 → 放行
        if (session.stage() != SessionContext.Stage.WAITING_ENCRYPTION_RESPONSE) return;

        // 取消包：阻止服务端处理（服务端在 online-mode=false 时不会收到此包，但防御性取消）
        event.setCancelled(true);

        try {
            WrapperLoginClientEncryptionResponse response = new WrapperLoginClientEncryptionResponse(event);

            // 7. RSA 解密共享密钥
            byte[] sharedSecret = response.getSecretKey(rsaKeyPair.getPrivate()).getEncoded();

            // 8. RSA 解密验证令牌并校验
            byte[] encryptedToken = response.getEncryptedVerifyToken().orElse(null);
            if (encryptedToken == null) {
                // 缺少验证令牌，直接断连
                cleanupSession(channel);
                channel.close();
                return;
            }
            Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsaCipher.init(Cipher.DECRYPT_MODE, rsaKeyPair.getPrivate());
            byte[] decryptedToken = rsaCipher.doFinal(encryptedToken);

            if (!Arrays.equals(decryptedToken, session.verifyToken())) {
                // 验证令牌不匹配，直接断连（不发送 Disconnect，因为加密尚未启用）
                cleanupSession(channel);
                channel.close();
                return;
            }

            // 9. 启用 AES-CFB8 双向加密
            CryptoHandler.enableEncryption(channel, sharedSecret);
            session.sharedSecret(sharedSecret);
            session.advance(SessionContext.Stage.WAITING_ENCRYPTION_RESPONSE, SessionContext.Stage.ENCRYPTED);

            // 10. 移除断开检测器（已收到响应，确认为正版客户端）
            removeDetector(channel);

            // 11. 计算服务器哈希并异步调用 hasJoined
            String serverHash = computeServerHash(sharedSecret, publicKeyEncoded);
            String username = session.username();

            mojangClient.hasJoined(serverHash, username).thenAccept(premiumProfile -> {
                if (premiumProfile.isEmpty()) {
                    // 验证失败：经加密通道发送 Disconnect
                    channel.eventLoop().execute(() -> {
                        sendDisconnect(user, I18n.get("listener.premium_unavailable"));
                        cleanupSession(channel);
                    });
                    return;
                }

                UUID uuid = premiumProfile.get().uuid();
                String properties = premiumProfile.get().propertiesJson();

                // 13. 保存正版数据（异步落盘）
                dataService.savePremium(uuid, username, session.ip(), properties);

                // 17. 异步触发 AsyncPlayerPreLoginEvent
                playerInjector.fireAsyncPreLogin(username, uuid, session.ip()).thenAccept(allowed -> {
                    channel.eventLoop().execute(() -> {
                        if (!channel.isActive()) {
                            cleanupSession(channel);
                            return;
                        }
                        if (!allowed) {
                            // 被 KICK：经加密通道发送 Disconnect
                            sendDisconnect(user, I18n.get("listener.premium_invalid_session"));
                            cleanupSession(channel);
                            return;
                        }
                        // 18-19. 推进状态机 + 发送 LoginSuccess
                        try {
                            playerInjector.advanceState(channel, uuid, username, properties);
                            playerInjector.sendLoginSuccess(user, uuid, username, properties);
                            session.advance(SessionContext.Stage.ENCRYPTED, SessionContext.Stage.DONE);
                        } catch (Exception e) {
                            plugin.getLogger().severe(I18n.get("log.premium_state_advance_failed", e.getMessage()));
                            sendDisconnect(user, I18n.get("listener.premium_unavailable"));
                            cleanupSession(channel);
                        }
                    });
                });
            });
        } catch (Exception e) {
            plugin.getLogger().severe(I18n.get("log.premium_cipher_init_failed", e.getMessage()));
            cleanupSession(channel);
            channel.close();
        }
    }

    // ===== 辅助方法 =====

    /** 从 channel 提取玩家 IP */
    private static String extractIp(Channel channel) {
        if (channel.remoteAddress() instanceof InetSocketAddress addr) {
            return addr.getAddress().getHostAddress();
        }
        return null;
    }

    /**
     * 计算 Mojang 服务器哈希：正十六进制( SHA-1( "" + sharedSecret + publicKey ) )
     * BigInteger(1, digest) 保证无前导零的正数表示。
     */
    private static String computeServerHash(byte[] sharedSecret, byte[] publicKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update("".getBytes(StandardCharsets.UTF_8));
            sha1.update(sharedSecret);
            sha1.update(publicKey);
            byte[] digest = sha1.digest();
            return new java.math.BigInteger(1, digest).toString(16);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute server hash", e);
        }
    }

    /** 经已加密通道发送 Disconnect 包 */
    private void sendDisconnect(User user, String message) {
        WrapperLoginServerDisconnect disconnect = new WrapperLoginServerDisconnect(
                HTLogin.legacy(message));
        user.writePacket(disconnect);
    }

    /** 移除 pipeline 中的断开检测器 */
    private void removeDetector(Channel channel) {
        try {
            channel.pipeline().remove(DETECTOR_NAME);
        } catch (Exception ignored) {
            // 已移除或 pipeline 已关闭
        }
    }

    /** 清理会话和检测器 */
    private void cleanupSession(Channel channel) {
        sessions.remove(channel);
        removeDetector(channel);
    }

    /** 生成 RSA 2048 密钥对（启动时一次，所有连接复用） */
    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException(I18n.get("log.premium_keypair_failed"), e);
        }
    }
}
