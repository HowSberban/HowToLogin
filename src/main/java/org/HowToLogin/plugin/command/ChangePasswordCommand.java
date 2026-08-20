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

public final class ChangePasswordCommand implements BasicCommand {

    private final HTLogin plugin;
    private final AuthManager authManager;

    public ChangePasswordCommand(HTLogin plugin, AuthManager authManager) {
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
            player.sendMessage(HTLogin.legacy(I18n.get("changepw.must_login", player)));
            return;
        }

        if (args.length < 2) {
            player.sendMessage(HTLogin.legacy(I18n.get("changepw.usage", player)));
            return;
        }

        String oldPassword = args[0];
        String newPassword = args[1];

        if (PasswordValidator.invalid(plugin, player, newPassword)) return;

        if (authManager.changePassword(player, oldPassword, newPassword)) {
            player.sendMessage(HTLogin.legacy(I18n.get("changepw.success", player)));
        } else {
            player.sendMessage(HTLogin.legacy(I18n.get("changepw.incorrect_old", player)));
        }
    }
}
