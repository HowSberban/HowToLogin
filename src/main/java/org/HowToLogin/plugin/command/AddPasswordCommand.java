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

/** 为无密码账户设置密码（/addpassword <新密码> <确认新密码>），已有密码的账户请使用 /changepassword */
public final class AddPasswordCommand implements BasicCommand {

    private final HTLogin plugin;
    private final AuthManager authManager;

    public AddPasswordCommand(HTLogin plugin, AuthManager authManager) {
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

        if (!authManager.isLoggedIn(player)) {
            player.sendMessage(HTLogin.legacy(I18n.get("addpassword.must_login", player)));
            return;
        }

        if (!authManager.isPasswordless(player.getUniqueId())) {
            player.sendMessage(HTLogin.legacy(I18n.get("addpassword.has_password", player)));
            return;
        }

        if (args.length < 2) {
            player.sendMessage(HTLogin.legacy(I18n.get("addpassword.usage", player)));
            return;
        }

        String password = args[0];
        String confirm = args[1];

        if (!password.equals(confirm)) {
            player.sendMessage(HTLogin.legacy(I18n.get("register.password_mismatch", player)));
            return;
        }

        if (PasswordValidator.invalid(plugin, player, password)) return;

        if (authManager.addPassword(player, password)) {
            player.sendMessage(HTLogin.legacy(I18n.get("addpassword.success", player)));
        } else {
            player.sendMessage(HTLogin.legacy(I18n.get("addpassword.failed", player)));
        }
    }
}
