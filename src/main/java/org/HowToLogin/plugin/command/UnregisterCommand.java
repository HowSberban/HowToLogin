package org.howtologin.plugin.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;
import org.howtologin.plugin.auth.AuthManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

/**
 * unregister 管理命令，使用 brigadier 原生注册以支持玩家名补全。
 */
@SuppressWarnings("SameReturnValue")
public final class UnregisterCommand {

    private final HTLogin plugin;
    private final AuthManager authManager;

    public UnregisterCommand(HTLogin plugin, AuthManager authManager) {
        this.plugin = plugin;
        this.authManager = authManager;
    }

    /** 构建命令树节点（由 HTLogin 注册时调用） */
    public LiteralCommandNode<io.papermc.paper.command.brigadier.CommandSourceStack> buildNode() {
        return literal("unregister")
                .requires(stack -> stack.getSender().hasPermission("htlogin.admin"))
                .then(argument("player", StringArgumentType.word())
                        .suggests(SUGGEST_OFFLINE_PLAYERS)
                        .executes(this::execute))
                .build();
    }

    /** 补全所有已注册玩家（包括离线玩家），与 htlogin 命令的补全逻辑一致 */
    private final SuggestionProvider<io.papermc.paper.command.brigadier.CommandSourceStack> SUGGEST_OFFLINE_PLAYERS =
            (context, builder) -> {
                // 先添加在线玩家
                for (Player player : Bukkit.getOnlinePlayers()) {
                    builder.suggest(player.getName());
                }
                // 再添加已注册的离线玩家
                for (UUID uuid : plugin.getPlayerDataManager().getAllUuids()) {
                    if (Bukkit.getPlayer(uuid) != null) continue;
                    OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
                    String name = offline.getName();
                    if (name != null) {
                        builder.suggest(name);
                    }
                }
                return builder.buildFuture();
            };

    private int execute(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");

        // 异步解析玩家：getOfflinePlayer 可能发起 Mojang API 请求（阻塞），不能在主线程调用
        // 之前用 getOfflinePlayerIfCached 导致服务器重启后找不到未进服的玩家
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            // 优先检查在线玩家（getOfflinePlayer 内部也会检查，但这里单独检查以便后续踢出）
            Player onlinePlayer = Bukkit.getPlayerExact(targetName);
            // getOfflinePlayer 会依次检查：在线玩家 → 缓存 → playerdata 目录 → Mojang API
            OfflinePlayer target = onlinePlayer != null ? onlinePlayer : Bukkit.getOfflinePlayer(targetName);
            UUID targetUuid = target.getUniqueId();

            if (!authManager.unregister(targetUuid)) {
                sender.sendMessage(HTLogin.legacy(I18n.get("unregister.not_found", sender)));
                return;
            }

            // 若目标在线则踢出，下次进服需重新注册
            if (onlinePlayer != null) {
                onlinePlayer.kick(HTLogin.legacy(I18n.get("unregister.kick", onlinePlayer)));
            }

            sender.sendMessage(HTLogin.legacy(I18n.get("unregister.success", sender, targetName)));
        });
        return Command.SINGLE_SUCCESS;
    }
}
