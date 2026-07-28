package org.HowToLogin.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.HowToLogin.plugin.HTLogin;
import org.HowToLogin.plugin.I18n;
import org.HowToLogin.plugin.auth.AuthManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class LogoutCommand implements BasicCommand {

    private final AuthManager authManager;

    public LogoutCommand(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public void execute(CommandSourceStack stack, String @NotNull [] args) {
        CommandSender sender = stack.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(HTLogin.legacy(I18n.get("command.player_only")));
            return;
        }

        if (!authManager.isLoggedIn(player)) {
            player.sendMessage(HTLogin.legacy(I18n.get("logout.not_logged_in", player)));
            return;
        }

        // 登出流程：先保存当前位置（下次登录回到这里）→ 进入待登录状态 → 踢出服务器
        authManager.saveLogoutLocation(player);
        authManager.logout(player);
        player.kick(HTLogin.legacy(I18n.get("logout.success", player)));
    }
}
