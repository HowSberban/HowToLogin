package org.howtologin.plugin.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;

import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;
import org.howtologin.plugin.auth.AuthManager;
import org.howtologin.plugin.data.PlayerDataManager.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

/**
 * htlogin 管理命令，使用 brigadier 原生注册。
 * 子命令作为 literal 节点，客户端输入空格后能自动显示子命令列表。
 */
@SuppressWarnings("SameReturnValue")
public final class HTLoginCommand {

    private final HTLogin plugin;
    /** 在线玩家名补全（不依赖实例状态，static） */
    private static final SuggestionProvider<io.papermc.paper.command.brigadier.CommandSourceStack> SUGGEST_PLAYERS =
            (context, builder) -> {
                String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (Player player : Bukkit.getOnlinePlayers()) {
                    String name = player.getName();
                    if (remaining.isEmpty() || name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        builder.suggest(name);
                    }
                }
                return builder.buildFuture();
            };
    private final SuggestionProvider<io.papermc.paper.command.brigadier.CommandSourceStack> SUGGEST_ALL_PLAYERS;

    public HTLoginCommand(HTLogin plugin) {
        this.plugin = plugin;
        this.SUGGEST_ALL_PLAYERS = (context, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            // 先添加在线玩家
            for (Player player : Bukkit.getOnlinePlayers()) {
                String name = player.getName();
                if (remaining.isEmpty() || name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(name);
                }
            }
            // 再添加已注册的离线玩家
            for (UUID uuid : this.plugin.getPlayerDataManager().getAllUuids()) {
                if (Bukkit.getPlayer(uuid) != null) continue;
                OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
                String name = offline.getName();
                if (name != null && (remaining.isEmpty() || name.toLowerCase(Locale.ROOT).startsWith(remaining))) {
                    builder.suggest(name);
                }
            }
            return builder.buildFuture();
        };
    }

    /** 构建命令树节点（由 HTLogin 注册时调用） */
    public LiteralCommandNode<io.papermc.paper.command.brigadier.CommandSourceStack> buildNode() {
        return literal("htlogin")
                .requires(stack -> stack.getSender().hasPermission("htlogin.admin"))
                // /htlogin — 显示用法
                .executes(this::showUsage)
                // /htlogin reload
                .then(literal("reload")
                        .executes(this::handleReload))
                // /htlogin accounts <player>
                .then(literal("accounts")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(SUGGEST_ALL_PLAYERS)
                                .executes(this::handleAccounts)))
                // /htlogin forcelogout <player>
                .then(literal("forcelogout")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(SUGGEST_ALL_PLAYERS)
                                .executes(this::handleForceLogout)))
                // /htlogin forcechangepw <player> <newpassword>
                .then(literal("forcechangepw")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(SUGGEST_ALL_PLAYERS)
                                .then(argument("newpassword", StringArgumentType.word())
                                        .executes(this::handleForceChangePw))))
                // /htlogin forcelogin <player>
                .then(literal("forcelogin")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(SUGGEST_PLAYERS)
                                .executes(this::handleForceLogin)))
                // /htlogin forceregister <player> <password>
                .then(literal("forceregister")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(SUGGEST_PLAYERS)
                                .then(argument("password", StringArgumentType.word())
                                        .executes(this::handleForceRegister))))
                .build();
    }

    private int showUsage(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.usage", sender)));
        return Command.SINGLE_SUCCESS;
    }

    private int handleReload(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        boolean dbChanged = plugin.getConfigManager().reload();
        I18n.reload();
        plugin.getAuthManager().cleanupExpiredStates();
        // 背包保护开启但无 PacketEvents 时提醒
        if (plugin.getConfigManager().protectionInventoryEnabled()
                && org.bukkit.Bukkit.getPluginManager().getPlugin("packetevents") == null) {
            plugin.getLogger().warning(I18n.get("log.packetevents_missing"));
        }
        if (dbChanged) {
            sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.reload_db_changed", sender)));
            plugin.getLogger().warning(I18n.get("htlogin.reload_db_changed"));
        } else {
            sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.reload_success", sender)));
        }
        plugin.getLogger().info(I18n.get("plugin.config_reload_log"));
        return Command.SINGLE_SUCCESS;
    }

    // 查找指定玩家 IP 下的其它账号（异步执行，避免 getOfflinePlayer 阻塞区域线程）
    private int handleAccounts(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");

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
                sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.accounts_item", sender, name)));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    // 强制登出：玩家在线或离线均可（清除登录状态）。在线玩家会被踢出以重新登录
    private int handleForceLogout(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
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
        return Command.SINGLE_SUCCESS;
    }

    // 强制修改密码：玩家在线或离线均可，无需旧密码。在线玩家会被踢出以重新登录
    private int handleForceChangePw(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        String newPassword = StringArgumentType.getString(ctx, "newpassword");
        if (PasswordValidator.invalidPattern(plugin, sender, newPassword)) return Command.SINGLE_SUCCESS;
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
        return Command.SINGLE_SUCCESS;
    }

    // 强制登录：仅对在线玩家生效
    private int handleForceLogin(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.player_not_online", sender, targetName)));
            return 0;
        }
        // 已登录则无需重复操作
        if (plugin.getAuthManager().isLoggedIn(target)) {
            sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.already_logged_in", sender, targetName)));
            return 0;
        }
        plugin.getAuthManager().forceLogin(target);
        // 强制登录后传送回上次退出位置
        plugin.getAuthManager().returnToLogoutLocation(target);
        sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.forcelogin_success", sender, targetName)));
        return Command.SINGLE_SUCCESS;
    }

    // 强制注册：绕过 IP 限制为玩家创建账号。玩家在线或离线均可，注册后需自行 /login
    private int handleForceRegister(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        String password = StringArgumentType.getString(ctx, "password");
        if (PasswordValidator.invalidPattern(plugin, sender, password)) return Command.SINGLE_SUCCESS;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
            AuthManager auth = plugin.getAuthManager();
            if (!auth.forceRegister(target.getUniqueId(), password)) {
                sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.forceregister_already_exists", sender, targetName)));
                return;
            }
            sender.sendMessage(HTLogin.legacy(I18n.get("htlogin.forceregister_success", sender, targetName)));
            // 在线玩家：切换为待登录状态，重启登录提醒和超时任务（注册提醒会因 hasAccount=true 自动取消）
            Player online = Bukkit.getPlayerExact(targetName);
            if (online != null) {
                auth.addPendingLogin(online);
                online.sendMessage(HTLogin.legacy(I18n.get("listener.please_login", online)));
                plugin.getPlayerListener().scheduleReminder(online, true);
                plugin.getPlayerListener().scheduleLoginTimeout(online);
            }
        });
        return Command.SINGLE_SUCCESS;
    }
}
