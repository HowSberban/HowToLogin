package org.howtologin.plugin.premium;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 玩家注入器（模块4）—— authenticatedProfile 设置 + state 推进。
 * <p>
 * 方案：取消 LoginStart 后，服务端 state 保持 HELLO。
 * 验证完成后设置 authenticatedProfile + state=VERIFYING，
 * 让服务端 tick() 自然调用 verifyLoginAndFinishConnectionSetup：
 *   → canPlayerLogin（触发 PlayerLoginEvent）→ state=WAITING_FOR_DUPE_DISCONNECT
 *   → finishLoginAndWaitForClient → 发送 LoginSuccess（含正版 UUID + 皮肤）→ state=PROTOCOL_SWITCHING
 * 客户端收到 LoginSuccess 后发送 LoginAcknowledged，服务端自然接手协议切换。
 * <p>
 * 线程模型：
 * - fireAsyncPreLogin: 异步线程
 * - setProfileAndAdvanceState: IO线程（持有 channel 的 eventLoop）
 * <p>
 * NMS 字段（packetListener/authenticatedProfile/requestedUsername/state）需反射，
 * 因为 NMS 类不在编译期依赖中。GameProfile/Property 可直接使用（authlib compileOnly）。
 */
public final class PlayerInjector {

    private final HTLogin plugin;

    public PlayerInjector(HTLogin plugin) {
        this.plugin = plugin;
    }

    /**
     * 异步触发 AsyncPlayerPreLoginEvent，让其他插件（权限组/领地等）准备玩家数据。
     * 由于取消了 LoginStart，服务端不会自动触发此事件，需手动 callEvent。
     *
     * @param name 玩家名
     * @param uuid 正版 UUID
     * @param ip   玩家 IP
     * @return true 表示允许继续，false 表示被 KICK
     */
    @SuppressWarnings({"removal", "UnstableApiUsage"})
    public CompletableFuture<Boolean> fireAsyncPreLogin(String name, UUID uuid, String ip) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                InetAddress address = InetAddress.getByName(ip);
                AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent(name, address, uuid);
                event.callEvent();
                return event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED;
            } catch (Exception e) {
                plugin.getLogger().warning(I18n.get("log.premium_prelogin_failed", name, e.getMessage()));
                return false;
            }
        });
    }

    /**
     * 设置 authenticatedProfile + state=VERIFYING，让服务端 tick() 接管。
     * <p>
     * 服务端 tick() 在 VERIFYING 状态下调用 verifyLoginAndFinishConnectionSetup：
     *   → canPlayerLogin（触发 PlayerLoginEvent）→ state=WAITING_FOR_DUPE_DISCONNECT
     *   → finishLoginAndWaitForClient → 发送 LoginSuccess（含正版 UUID + 皮肤）→ state=PROTOCOL_SWITCHING
     * 客户端收到 LoginSuccess 后发送 LoginAcknowledged，服务端自然接手协议切换。
     *
     * @param channel        底层 Netty 通道
     * @param uuid           正版 UUID
     * @param name           玩家名
     * @param propertiesJson Mojang 返回的 properties JSON（可能为 null）
     * @throws Exception 反射操作失败
     */
    public void setProfileAndAdvanceState(Channel channel, UUID uuid, String name, String propertiesJson) throws Exception {
        // 1. 取 NMS Connection（原版固定处理器名 packet_handler）
        ChannelPipeline pipeline = channel.pipeline();
        Object connection = pipeline.get("packet_handler");
        if (connection == null) {
            throw new IllegalStateException(I18n.get("log.premium_channel_cast_failed"));
        }

        // 2. 从 Connection 获取 packetListener（ServerLoginPacketListenerImpl）
        Field packetListenerField = findField(connection.getClass(), "packetListener");
        packetListenerField.setAccessible(true);
        Object loginListener = packetListenerField.get(connection);
        if (loginListener == null) {
            throw new IllegalStateException("Packet listener is null");
        }

        // 3. 构造 GameProfile 并设置到 authenticatedProfile
        GameProfile profile = buildGameProfile(uuid, name, propertiesJson);
        setFieldValue(loginListener, "authenticatedProfile", profile);
        // 取消了 LoginStart，服务端未设置此字段
        setFieldValue(loginListener, "requestedUsername", name);

        // 4. 设置 state = VERIFYING
        // 服务端 tick() 会调用 verifyLoginAndFinishConnectionSetup：
        //   → canPlayerLogin（触发 PlayerLoginEvent）→ state=WAITING_FOR_DUPE_DISCONNECT
        //   → finishLoginAndWaitForClient → 发送 LoginSuccess → state=PROTOCOL_SWITCHING
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object verifying = Enum.valueOf(
                (Class<Enum>) Class.forName("net.minecraft.server.network.ServerLoginPacketListenerImpl$State"),
                "VERIFYING");
        setFieldValue(loginListener, "state", verifying);
    }

    /**
     * 构造 authlib GameProfile（含皮肤 properties）。
     * 用于反射设置 ServerLoginPacketListenerImpl.authenticatedProfile。
     * 服务端在创建玩家实体时使用此 profile 的 UUID。
     * <p>
     * authlib 9.0+ GameProfile 为 record，PropertyMap 内部 Multimap 不可变，
     * 故用可变 ArrayListMultimap 收集 properties 后通过 PropertyMap(Multimap) 构造器传入，
     * 再用 GameProfile 三参构造器重建 profile。
     */
    private static GameProfile buildGameProfile(UUID uuid, String name, String propertiesJson) {
        if (propertiesJson == null || propertiesJson.isEmpty()) {
            return new GameProfile(uuid, name);
        }
        try {
            JsonArray array = JsonParser.parseString(propertiesJson).getAsJsonArray();
            if (array.isEmpty()) {
                return new GameProfile(uuid, name);
            }
            com.google.common.collect.Multimap<String, Property> multimap =
                    com.google.common.collect.ArrayListMultimap.create();
            for (JsonElement element : array) {
                JsonObject obj = element.getAsJsonObject();
                String propName = obj.get("name").getAsString();
                String value = obj.get("value").getAsString();
                String signature = obj.has("signature") && !obj.get("signature").isJsonNull()
                        ? obj.get("signature").getAsString() : null;
                multimap.put(propName, signature != null
                        ? new Property(propName, value, signature)
                        : new Property(propName, value));
            }
            return new GameProfile(uuid, name, new PropertyMap(multimap));
        } catch (Exception e) {
            // JSON 解析失败不影响登录（仅缺少皮肤）
            return new GameProfile(uuid, name);
        }
    }

    /** 反射设置 private 字段（递归查找父类） */
    private static void setFieldValue(Object obj, String fieldName, Object value) throws Exception {
        Field field = findField(obj.getClass(), fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }

    // 递归查找字段（含父类）
    private static Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
