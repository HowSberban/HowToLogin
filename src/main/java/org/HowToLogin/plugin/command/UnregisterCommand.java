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

        // 使用 getOfflinePlayerIfCached 避免阻塞主线程（不会发起 Mojang API 请求）
        // 返回 null 表示该玩家从未进服，必然未注册
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[0]);

        if (target == null || !authManager.unregister(target.getUniqueId())) {
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
