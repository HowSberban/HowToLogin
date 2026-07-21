package org.HowToLogin.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.HowToLogin.plugin.HTLogin;
import org.HowToLogin.plugin.I18n;
import org.HowToLogin.plugin.auth.AuthManager;
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
    public void execute(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        CommandSender sender = stack.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(HTLogin.legacy(I18n.get("command.player_only")));
            return;
        }

        if (args.length < 2) {
            player.sendMessage(HTLogin.legacy(I18n.get("register.usage")));
            return;
        }

        if (authManager.hasAccount(player)) {
            player.sendMessage(HTLogin.legacy(I18n.get("register.already_registered")));
            return;
        }

        String password = args[0];
        String confirm = args[1];

        if (!password.equals(confirm)) {
            player.sendMessage(HTLogin.legacy(I18n.get("register.password_mismatch")));
            return;
        }

        int minLen = plugin.getConfigManager().minPasswordLength();
        int maxLen = plugin.getConfigManager().maxPasswordLength();

        if (password.length() < minLen || password.length() > maxLen) {
            player.sendMessage(HTLogin.legacy(I18n.get("command.password_length", minLen, maxLen)));
            return;
        }

        if (authManager.register(player, password)) {
            player.sendMessage(HTLogin.legacy(I18n.get("register.success")));
        } else {
            player.sendMessage(HTLogin.legacy(I18n.get("register.failed")));
        }
    }
}
