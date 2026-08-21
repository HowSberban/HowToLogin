package org.howtologin.plugin.packet;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.handshaking.client.WrapperHandshakingClientHandshake;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 握手协议版本追踪：按远程地址记录客户端协议版本。
 * Pre-join Dialog 据此判断客户端是否支持 Dialog（1.21.6+）：旧客户端收到配置阶段
 * Show Dialog 包会无法解析而断连，必须回退聊天栏提示流程。
 * 注意：ViaVersion 等协议翻译层可能改写握手版本号，此类环境依赖原始协议号比较（见 supportsDialogs）。
 */
public final class HandshakeTracker extends PacketListenerAbstract {

    // 远程地址 → [协议版本, 记录时间]
    private final Map<InetSocketAddress, long[]> versions = new ConcurrentHashMap<>();
    // 容量守卫 + 过期时间：status ping 等未进入配置阶段的连接会残留条目
    private static final int CAP = 512;
    private static final long TTL_MS = 60_000L;

    public HandshakeTracker() {
        super(PacketListenerPriority.LOW);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Handshaking.Client.HANDSHAKE) return;
        try {
            var wrapper = new WrapperHandshakingClientHandshake(event);
            // 仅记录登录意图（status ping 不进入配置阶段，无需记录）
            if (wrapper.getIntention() != WrapperHandshakingClientHandshake.ConnectionIntention.LOGIN) return;
            InetSocketAddress address = event.getSocketAddress();
            // 容量守卫：超限时清理过期条目（连接通常几秒内完成握手→配置，60 秒足够宽裕）
            if (versions.size() > CAP) {
                long now = System.currentTimeMillis();
                versions.values().removeIf(v -> now - v[1] >= TTL_MS);
            }
            versions.put(address, new long[]{wrapper.getProtocolVersion(), System.currentTimeMillis()});
        } catch (Exception ignored) {
            // 解析失败不拦截连接：版本未知时按不支持 Dialog 处理（回退聊天栏提示）
        }
    }

    /**
     * 客户端是否支持 Dialog（1.21.6+）。
     * 读取后移除记录（每个连接仅在配置阶段查询一次）；未记录（异常情况）保守返回 false。
     */
    public boolean supportsDialogs(InetSocketAddress address) {
        long[] entry = versions.remove(address);
        if (entry == null) return false;
        // 用原始协议号直接比较，避免 ClientVersion.getById 的 UNKNOWN 陷阱：
        // getById 仅做精确枚举匹配，协议号落在枚举空隙（或经 Via 改写）时返回 UNKNOWN(-1)，
        // 使 isNewerThanOrEquals(V_1_21_6) 恒为 false，导致 1.21.6+（含 26.x）客户端被误判为旧版回退聊天栏提示。
        // 协议号单调递增，>= 1.21.6 的协议号即表示客户端支持 Dialog。
        return entry[0] >= ClientVersion.V_1_21_6.getProtocolVersion();
    }
}
