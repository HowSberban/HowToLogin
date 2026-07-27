package org.HowToLogin.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.HowToLogin.plugin.HTLogin;
import org.HowToLogin.plugin.I18n;
import org.HowToLogin.plugin.auth.AuthManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

public final class UnregisterCommand implements BasicCommand {

    private final AuthManager authManager;

    public UnregisterCommand(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();
        if (args.length < 1) {
            sender.sendMessage(HTLogin.legacy(I18n.get("unregister.usage", sender)));
            return;
        }

        // 使用 getOfflinePlayerIfCached 避免阻塞主线程（不会发起 Mojang API 请求）
        // 返回 null 表示该玩家从未进服，必然未注册
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[0]);

        if (target == null || !authManager.unregister(target.getUniqueId())) {
            sender.sendMessage(HTLogin.legacy(I18n.get("unregister.not_found", sender)));
            return;
        }

        sender.sendMessage(HTLogin.legacy(I18n.get("unregister.success", sender, args[0])));
    }

    @Override
    public String permission() {
        return "htlogin.admin";
    }
}
