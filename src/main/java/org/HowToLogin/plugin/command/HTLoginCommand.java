package org.HowToLogin.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.HowToLogin.plugin.HTLogin;
import org.HowToLogin.plugin.I18n;
import org.HowToLogin.plugin.auth.AuthManager;
import org.HowToLogin.plugin.data.PlayerDataManager.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class HTLoginCommand implements BasicCommand {

    private final HTLogin plugin;

    public HTLoginCommand(HTLogin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();
        if (args.length < 1) {
            sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.usage", sender)));
            return;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.getConfigManager().reload();
                I18n.reload();
                plugin.getAuthManager().cleanupExpiredStates();
                sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.reload_success", sender)));
                plugin.getLogger().info(I18n.get("plugin.config_reload_log"));
            }
            case "accounts" -> handleAccounts(sender, args);
            case "forcelogout" -> handleForceLogout(sender, args);
            case "forcechangepw" -> handleForceChangePw(sender, args);
            case "forcelogin" -> handleForceLogin(sender, args);
            default -> sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.unknown_subcommand", sender)));
        }
    }

    // 查找指定玩家 IP 下的其它账号（异步执行，避免 getOfflinePlayer 阻塞区域线程）
    private void handleAccounts(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.accounts_usage", sender)));
            return;
        }
        String targetName = args[1];

        // 异步执行：getOfflinePlayer 可能阻塞网络查询（Folia 兼容）
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
            PlayerData data = plugin.getPlayerDataManager().getPlayer(target.getUniqueId());
            if (data == null) {
                sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.accounts_not_found", sender)));
                return;
            }
            String ip = data.ip();
            if (ip == null || ip.isEmpty()) {
                sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.accounts_no_ip", sender)));
                return;
            }

            // 查找同 IP 的所有账号
            List<PlayerData> sameIpAccounts = plugin.getPlayerDataManager().findByIp(ip);
            // 排除目标玩家自身，输出其他账号名
            List<String> otherNames = sameIpAccounts.stream()
                    .filter(d -> !d.uuid().equals(target.getUniqueId()))
                    .map(d -> {
                        OfflinePlayer op = Bukkit.getOfflinePlayer(d.uuid());
                        return op.getName() != null ? op.getName() : d.uuid().toString();
                    })
                    .toList();

            sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.accounts_result", sender, targetName, ip, otherNames.size())));
            for (String name : otherNames) {
                sender.sendMessage(Component.text(" - " + name));
            }
        });
    }

    // 强制登出：玩家在线或离线均可（清除登录状态）。在线玩家会被踢出以重新登录
    private void handleForceLogout(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.forcelogout_usage", sender)));
            return;
        }
        String targetName = args[1];
        // 异步解析 UUID（Folia 兼容）
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
            boolean success = plugin.getAuthManager().forceLogout(target.getUniqueId());
            if (success) {
                sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.forcelogout_success", sender, targetName)));
                // 在线玩家踢出以重新登录
                Player online = Bukkit.getPlayerExact(targetName);
                if (online != null) {
                    online.kick(HTLogin.legacy(I18n.get("htlogin.forcelogout_kick", online)));
                }
            } else {
                sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.not_logged_in", sender, targetName)));
            }
        });
    }

    // 强制修改密码：玩家在线或离线均可，无需旧密码。在线玩家会被踢出以重新登录
    private void handleForceChangePw(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.forcechangepw_usage", sender)));
            return;
        }
        String targetName = args[1];
        String newPassword = args[2];
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
            AuthManager auth = plugin.getAuthManager();
            if (!auth.forceChangePassword(target.getUniqueId(), newPassword)) {
                sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.accounts_not_found", sender)));
                return;
            }
            sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.forcechangepw_success", sender, targetName)));
            // 在线玩家踢出以重新登录
            Player online = Bukkit.getPlayerExact(targetName);
            if (online != null) {
                online.kick(HTLogin.legacy(I18n.get("htlogin.forcechangepw_kick", online)));
            }
        });
    }

    // 强制登录：仅对在线玩家生效
    private void handleForceLogin(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.forcelogin_usage", sender)));
            return;
        }
        String targetName = args[1];
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.player_not_online", sender, targetName)));
            return;
        }
        plugin.getAuthManager().forceLogin(target);
        // 强制登录后传送回上次退出位置（坐标保护模式下生效）
        plugin.getAuthManager().returnToLogoutLocation(target);
        sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.forcelogin_success", sender, targetName)));
    }

    @Override
    public String permission() {
        return "htlogin.admin";
    }

    @Override
    public List<String> suggest(CommandSourceStack stack, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            // 补全子命令名
            String prefix = args[0].toLowerCase();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(prefix)) result.add(sub);
            }
        } else if (args.length == 2) {
            // accounts/forcelogout/forcechangepw/forcelogin 第二个参数补全在线玩家名
            String sub = args[0].toLowerCase();
            if (sub.equals("accounts") || sub.equals("forcelogout")
                    || sub.equals("forcechangepw") || sub.equals("forcelogin")) {
                String prefix = args[1].toLowerCase();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    String name = player.getName();
                    if (name.toLowerCase().startsWith(prefix)) result.add(name);
                }
            }
        }
        return result;
    }

    private static final String[] SUBCOMMANDS = {
            "reload", "accounts", "forcelogout", "forcechangepw", "forcelogin"
    };
}
