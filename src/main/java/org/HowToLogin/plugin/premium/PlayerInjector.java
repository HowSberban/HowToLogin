package org.howtologin.plugin.premium;

import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerLoginSuccess;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 玩家注入器（模块4）—— LoginSuccess 发送 + 状态机反射推进。
 * <p>
 * 不手动创建实体和触发 PlayerLoginEvent/PlayerJoinEvent：
 * 推进状态机到 PROTOCOL_SWITCHING 后，服务端原生逻辑接手 LoginAcknowledged → Configuration → Play → 实体创建 → 事件触发。
 * <p>
 * 线程模型：
 * - fireAsyncPreLogin: 异步线程
 * - advanceState / sendLoginSuccess: IO线程（持有 channel 的 eventLoop）
 * <p>
 * 注意：authlib（com.mojang.authlib.GameProfile/Property）运行时由 Paper 服务端提供，
 * 编译期不在依赖中，因此通过反射创建 GameProfile 避免 compileOnly 依赖。
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
    @SuppressWarnings("removal")
    public CompletableFuture<Boolean> fireAsyncPreLogin(String name, UUID uuid, String ip) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                InetAddress address = InetAddress.getByName(ip);
                AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent(name, address, uuid);
                event.callEvent();
                if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                    return false;
                }
                return true;
            } catch (Exception e) {
                plugin.getLogger().warning(I18n.get("log.premium_state_advance_failed", e.getMessage()));
                return false;
            }
        });
    }

    /**
     * 反射推进登录状态机：设置 authenticatedProfile 和 state=PROTOCOL_SWITCHING。
     * 必须在 sendLoginSuccess 之前调用，使服务端准备好接收客户端的 LoginAcknowledged。
     *
     * @param channel        底层 Netty 通道
     * @param uuid           正版 UUID
     * @param name           玩家名
     * @param propertiesJson Mojang 返回的 properties JSON（可能为 null）
     * @throws Exception 反射操作失败
     */
    public void advanceState(Channel channel, UUID uuid, String name, String propertiesJson) throws Exception {
        // 1. 在 pipeline 中查找 NMS Connection（net.minecraft.network.Connection）
        Object connection = findConnection(channel.pipeline());
        if (connection == null) {
            throw new IllegalStateException(I18n.get("log.premium_channel_cast_failed"));
        }

        // 2. 从 Connection 获取 packetListener（ServerLoginPacketListenerImpl）
        Field listenerField = connection.getClass().getDeclaredField("packetListener");
        listenerField.setAccessible(true);
        Object loginListener = listenerField.get(connection);
        if (loginListener == null) {
            throw new IllegalStateException("Packet listener is null");
        }

        // 3. 通过反射构造 GameProfile（authlib 运行时由 Paper 提供，编译期不可见）
        Object profile = buildGameProfileReflective(uuid, name, propertiesJson);

        Field profileField = loginListener.getClass().getDeclaredField("authenticatedProfile");
        profileField.setAccessible(true);
        profileField.set(loginListener, profile);

        // 4. 设置 requestedUsername（取消了 LoginStart，服务端未设置此字段）
        Field usernameField = loginListener.getClass().getDeclaredField("requestedUsername");
        usernameField.setAccessible(true);
        usernameField.set(loginListener, name);

        // 5. 设置 state = PROTOCOL_SWITCHING
        Class<?> stateEnumClass = Class.forName("net.minecraft.server.network.ServerLoginPacketListenerImpl$State");
        Field stateField = loginListener.getClass().getDeclaredField("state");
        stateField.setAccessible(true);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object protocolSwitching = Enum.valueOf((Class<Enum>) stateEnumClass, "PROTOCOL_SWITCHING");
        stateField.set(loginListener, protocolSwitching);
    }

    /**
     * 通过 PacketEvents 发送 LoginSuccess 包（经已加密通道）。
     * 包含正版 UUID 和皮肤 properties，客户端据此加载皮肤。
     *
     * @param user           PacketEvents User 对象
     * @param uuid           正版 UUID
     * @param name           玩家名
     * @param propertiesJson Mojang 返回的 properties JSON（可能为 null）
     */
    public void sendLoginSuccess(com.github.retrooper.packetevents.protocol.player.User user,
                                  UUID uuid, String name, String propertiesJson) {
        UserProfile profile = buildUserProfile(uuid, name, propertiesJson);
        WrapperLoginServerLoginSuccess wrapper = new WrapperLoginServerLoginSuccess(profile);
        user.writePacket(wrapper);
    }

    /**
     * 在 pipeline 中查找 NMS Connection 对象。
     * 原版 Minecraft 将 Connection 作为 "packet_handler" 添加到 pipeline 末尾。
     */
    private static Object findConnection(ChannelPipeline pipeline) {
        // 优先按类名匹配（最可靠）
        for (var entry : pipeline) {
            ChannelHandler handler = entry.getValue();
            if (handler.getClass().getName().equals("net.minecraft.network.Connection")) {
                return handler;
            }
        }
        // 兜底：按处理器名查找
        Object handler = pipeline.get("packet_handler");
        if (handler != null) {
            return handler;
        }
        return null;
    }

    /**
     * 通过反射构造 NMS GameProfile（含 properties）。
     * 用于反射设置 ServerLoginPacketListenerImpl.authenticatedProfile。
     * <p>
     * authlib 9.0+ 的 GameProfile 是 record：
     *   GameProfile(UUID, String) — 两参构造，内部创建空 PropertyMap
     *   properties() — 访问器（非 getProperties()），返回 PropertyMap
     * PropertyMap 继承 ForwardingMultimap，可调用 put(String, Property) 添加皮肤属性。
     */
    private static Object buildGameProfileReflective(UUID uuid, String name, String propertiesJson) throws Exception {
        Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");
        Constructor<?> ctor = gameProfileClass.getConstructor(UUID.class, String.class);
        Object profile = ctor.newInstance(uuid, name);

        if (propertiesJson == null || propertiesJson.isEmpty()) {
            return profile;
        }

        // 获取 PropertyMap 并通过反射添加 properties
        Method propertiesMethod = gameProfileClass.getMethod("properties");
        Object propertyMap = propertiesMethod.invoke(profile);

        Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
        // 优先用三参构造（name, value, signature），无签名时用两参构造
        Constructor<?> ctor3 = propertyClass.getConstructor(String.class, String.class, String.class);
        Constructor<?> ctor2 = propertyClass.getConstructor(String.class, String.class);

        // PropertyMap 继承 ForwardingMultimap<String, Property>，put 方法签名为 (Object, Object)
        Method putMethod = propertyMap.getClass().getMethod("put", Object.class, Object.class);

        JsonArray array = JsonParser.parseString(propertiesJson).getAsJsonArray();
        for (JsonElement element : array) {
            JsonObject obj = element.getAsJsonObject();
            String propName = obj.get("name").getAsString();
            String value = obj.get("value").getAsString();
            boolean hasSignature = obj.has("signature") && !obj.get("signature").isJsonNull();
            String signature = hasSignature ? obj.get("signature").getAsString() : null;

            Object property = hasSignature
                    ? ctor3.newInstance(propName, value, signature)
                    : ctor2.newInstance(propName, value);
            putMethod.invoke(propertyMap, propName, property);
        }
        return profile;
    }

    /**
     * 构造 PacketEvents UserProfile（含 TextureProperties）。
     * 用于构造 WrapperLoginServerLoginSuccess 发送给客户端。
     */
    private static UserProfile buildUserProfile(UUID uuid, String name, String propertiesJson) {
        UserProfile profile = new UserProfile(uuid, name);
        if (propertiesJson != null && !propertiesJson.isEmpty()) {
            try {
                JsonArray array = JsonParser.parseString(propertiesJson).getAsJsonArray();
                List<TextureProperty> textures = new ArrayList<>();
                for (JsonElement element : array) {
                    JsonObject obj = element.getAsJsonObject();
                    String propName = obj.get("name").getAsString();
                    String value = obj.get("value").getAsString();
                    String signature = obj.has("signature") && !obj.get("signature").isJsonNull()
                            ? obj.get("signature").getAsString() : null;
                    textures.add(new TextureProperty(propName, value, signature));
                }
                profile.setTextureProperties(textures);
            } catch (Exception ignored) {
                // properties 解析失败不影响登录，仅缺少皮肤
            }
        }
        return profile;
    }
}
