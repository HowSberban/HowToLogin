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

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

/**
 * unregister 管理命令，使用 brigadier 原生注册以支持玩家名补全。
 */
public final class UnregisterCommand {

    private final AuthManager authManager;

    public UnregisterCommand(AuthManager authManager) {
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

    /** 补全已进过服的玩家名 */
    private final SuggestionProvider<io.papermc.paper.command.brigadier.CommandSourceStack> SUGGEST_OFFLINE_PLAYERS =
            (context, builder) -> {
                for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                    String name = op.getName();
                    if (name != null) {
                        builder.suggest(name);
                    }
                }
                return builder.buildFuture();
            };

    private int execute(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");

        // 使用 getOfflinePlayerIfCached 避免阻塞主线程（不会发起 Mojang API 请求）
        // 返回 null 表示该玩家从未进服，必然未注册
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(targetName);

        if (target == null || !authManager.unregister(target.getUniqueId())) {
            sender.sendMessage(HTLogin.legacy(I18n.get("unregister.not_found", sender)));
            return 0;
        }

        // 若目标在线则踢出，下次进服需重新注册
        Player online = Bukkit.getPlayer(target.getUniqueId());
        if (online != null) {
            online.kick(HTLogin.legacy(I18n.get("unregister.kick", online)));
        }

        sender.sendMessage(HTLogin.legacy(I18n.get("unregister.success", sender, targetName)));
        return Command.SINGLE_SUCCESS;
    }
}
