package org.howtologin.plugin.hook;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.howtologin.plugin.HTLogin;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PlaceholderAPI 变量扩展
 * 变量列表：
 *   %htlogin_is_logged_in%    - 是否已登录（yes/no）
 *   %htlogin_is_registered%   - 是否已注册（yes/no）
 */
public final class HTLoginExpansion extends PlaceholderExpansion {

    private final HTLogin plugin;

    public HTLoginExpansion(HTLogin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "htlogin";
    }

    @Override
    public @NotNull String getAuthor() {
        return "HowSberban";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return "";
        return switch (params.toLowerCase()) {
            case "is_logged_in" -> plugin.getAuthManager().isLoggedIn(player) ? "yes" : "no";
            case "is_registered", "has_account" -> plugin.getAuthManager().hasAccount(player) ? "yes" : "no";
            default -> null;
        };
    }
}
