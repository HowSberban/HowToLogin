package org.HowToLogin.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.HowToLogin.plugin.HTLogin;
import org.HowToLogin.plugin.I18n;
import org.jetbrains.annotations.NotNull;

public final class HTLoginCommand implements BasicCommand {

    private final HTLogin plugin;

    public HTLoginCommand(HTLogin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        if (args.length < 1) {
            stack.getSender().sendMessage(HTLogin.legacy(I18n.get("htlogin.usage")));
            return;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            plugin.getConfigManager().reload();
            I18n.reload();
            stack.getSender().sendMessage(HTLogin.legacy(I18n.get("htlogin.reload_success")));
            plugin.getLogger().info(I18n.get("plugin.config_reload_log"));
            return;
        }

        stack.getSender().sendMessage(HTLogin.legacy(I18n.get("htlogin.unknown_subcommand")));
    }

    @Override
    public @NotNull String permission() {
        return "htlogin.admin";
    }
}
