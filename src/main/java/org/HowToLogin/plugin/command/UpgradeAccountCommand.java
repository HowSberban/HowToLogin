package org.howtologin.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;
import org.howtologin.plugin.auth.AuthManager;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 升级指令：将离线账号升级为正版账号。
 * 玩家输入指令后添加升级标记，下一次进入服务器时尝试正版验证，
 * 验证成功则迁移账号为正版（保留退出位置等数据），失败则回退为离线账号。
 */
public final class UpgradeAccountCommand implements BasicCommand {

    private final HTLogin plugin;
    private final AuthManager authManager;

    public UpgradeAccountCommand(HTLogin plugin, AuthManager authManager) {
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
            player.sendMessage(HTLogin.legacy(I18n.get("upgrade.must_login")));
            return;
        }
        if (authManager.isPremium(player)) {
            player.sendMessage(HTLogin.legacy(I18n.get("upgrade.already_premium")));
            return;
        }
        // 正版验证总开关或升级开关未开启时升级不可用
        if (!plugin.getConfigManager().premiumEnabled() || !plugin.getConfigManager().premiumUpgradeEnabled()) {
            player.sendMessage(HTLogin.legacy(I18n.get("upgrade.disabled")));
            return;
        }
        // 缺少 PacketEvents 时正版验证无法运行，升级无效
        if (Bukkit.getPluginManager().getPlugin("packetevents") == null) {
            player.sendMessage(HTLogin.legacy(I18n.get("upgrade.unavailable")));
            return;
        }

        UUID offlineUuid = player.getUniqueId();
        if (!authManager.hasAccount(offlineUuid)) {
            player.sendMessage(HTLogin.legacy(I18n.get("upgrade.no_account")));
            return;
        }

        if (authManager.markUpgradePending(offlineUuid)) {
            player.sendMessage(HTLogin.legacy(I18n.get("upgrade.marked_success")));
        } else {
            player.sendMessage(HTLogin.legacy(I18n.get("upgrade.already_marked")));
        }
    }
}