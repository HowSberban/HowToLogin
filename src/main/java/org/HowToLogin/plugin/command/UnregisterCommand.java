package org.HowToLogin.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.HowToLogin.plugin.HTLogin;
import org.HowToLogin.plugin.I18n;
import org.HowToLogin.plugin.auth.AuthManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

public final class UnregisterCommand implements BasicCommand {

    private final HTLogin plugin;
    private final AuthManager authManager;

    public UnregisterCommand(HTLogin plugin, AuthManager authManager) {
        this.plugin = plugin;
        this.authManager = authManager;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        if (args.length < 1) {
            stack.getSender().sendMessage(HTLogin.legacy(I18n.get("unregister.usage")));
            return;
        }

        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);

        if (target.getUniqueId() == null || !authManager.unregister(target.getUniqueId())) {
            stack.getSender().sendMessage(HTLogin.legacy(I18n.get("unregister.not_found")));
            return;
        }

        stack.getSender().sendMessage(HTLogin.legacy(I18n.get("unregister.success", args[0])));
    }

    @Override
    public @NotNull String permission() {
        return "htlogin.admin";
    }
}
