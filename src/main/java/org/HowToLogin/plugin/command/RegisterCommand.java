package org.howtologin.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;
import org.howtologin.plugin.auth.AuthManager;
import org.howtologin.plugin.auth.PasswordValidator;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class RegisterCommand implements BasicCommand {

    private final HTLogin plugin;
    private final AuthManager authManager;

    public RegisterCommand(HTLogin plugin, AuthManager authManager) {
        this.plugin = plugin;
        this.authManager = authManager;
    }

    @Override
    public void execute(CommandSourceStack stack, String @NotNull [] args) {
        CommandSender sender = stack.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(HTLogin.legacy(I18n.get("command.player_only")));
            return;
        }

        if (args.length < 2) {
            player.sendMessage(HTLogin.legacy(I18n.get("register.usage", player)));
            return;
        }

        if (authManager.hasAccount(player)) {
            player.sendMessage(HTLogin.legacy(I18n.get("register.already_registered", player)));
            return;
        }

        String password = args[0];
        String confirm = args[1];

        if (!password.equals(confirm)) {
            player.sendMessage(HTLogin.legacy(I18n.get("register.password_mismatch", player)));
            return;
        }

        if (PasswordValidator.invalid(plugin, player, password)) return;

        if (authManager.register(player, password)) {
            player.sendMessage(HTLogin.legacy(I18n.get("register.success", player)));
            // 注册成功后传送到默认世界 spawn（启用坐标保护时生效）
            authManager.returnToLogoutLocation(player);
        } else {
            player.sendMessage(HTLogin.legacy(I18n.get("register.failed", player)));
        }
    }
}
