package org.howtologin.plugin.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;
import org.howtologin.plugin.auth.AuthManager;
import org.bukkit.Bukkit;
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
                        .suggests(HTLoginCommand.suggestAllPlayers(plugin))
                        .executes(this::execute))
                .build();
    }

    private int execute(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");

        // 异步执行：注销涉及数据库写操作与玩家数据文件删除，不该阻塞主线程
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            // 优先使用在线玩家 UUID（在线玩家的 UUID 必与数据库账号一致，天然不受 usercache 污染）
            Player onlinePlayer = Bukkit.getPlayerExact(targetName);
            UUID targetUuid = onlinePlayer != null
                    ? onlinePlayer.getUniqueId()
                    // 离线玩家按数据库记录解析：不用 getOfflinePlayer（usercache 可能同名缓存不同 UUID）
                    : plugin.getPlayerDataManager().findUuidByName(targetName);

            if (targetUuid == null || !authManager.unregister(targetUuid)) {
                sender.sendMessage(I18n.msg("htlogin.accounts_not_found", sender));
                return;
            }

            // 若目标在线则踢出，下次进服需重新注册
            if (onlinePlayer != null) {
                onlinePlayer.kick(I18n.msg("unregister.kick", onlinePlayer));
            }

            sender.sendMessage(I18n.msg("unregister.success", sender, targetName));
        });
        return Command.SINGLE_SUCCESS;
    }
}
