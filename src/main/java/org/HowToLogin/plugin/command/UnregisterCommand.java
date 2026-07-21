package org.HowToLogin.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.HowToLogin.plugin.HTLogin;
import org.HowToLogin.plugin.I18n;
import org.HowToLogin.plugin.auth.AuthManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

public final class UnregisterCommand implements BasicCommand {

    private final AuthManager authManager;

    public UnregisterCommand(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        if (args.length < 1) {
            stack.getSender().sendMessage(HTLogin.legacy(I18n.get("unregister.usage")));
            return;
        }

        // 注意：getOfflinePlayer(name) 即使玩家不存在也会返回离线 UUID，
        // 不会返回 null；玩家未注册时 unregister() 会返回 false
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);

        if (!authManager.unregister(target.getUniqueId())) {
            stack.getSender().sendMessage(HTLogin.legacy(I18n.get("unregister.not_found")));
            return;
        }

        stack.getSender().sendMessage(HTLogin.legacy(I18n.get("unregister.success", args[0])));
    }

    @Override
    public String permission() {
        return "htlogin.admin";
    }
}
