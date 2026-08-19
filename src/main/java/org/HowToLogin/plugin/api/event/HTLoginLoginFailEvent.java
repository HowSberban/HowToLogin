package org.howtologin.plugin.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * 玩家登录失败事件。
 * 在密码错误等登录失败后触发，供第三方插件联动（如惩罚、统计登录失败次数）。
 */
@SuppressWarnings("unused")
public class HTLoginLoginFailEvent extends Event {

    /** 登录失败原因 */
    public enum Reason {
        /** 密码错误 */
        WRONG_PASSWORD
    }

    private static final HandlerList handlers = new HandlerList();
    private final Player player;
    private final Reason reason;

    public HTLoginLoginFailEvent(@NotNull Player player, @NotNull Reason reason) {
        this.player = player;
        this.reason = reason;
    }

    @NotNull
    public Player getPlayer() {
        return player;
    }

    @NotNull
    public Reason getReason() {
        return reason;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}