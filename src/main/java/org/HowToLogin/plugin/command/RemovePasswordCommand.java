package org.howtologin.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.howtologin.plugin.I18n;
import org.howtologin.plugin.auth.AuthManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 移除密码，转为无密码账户：
 * 离线账户须已绑定双因素认证（验证码成为唯一登录因素）；
 * 正版账户以正版验证为身份凭证，未绑定 2FA 也可移除（验证失败时无回退登录，直接踢出）
 */
public final class RemovePasswordCommand implements BasicCommand {

    private final AuthManager authManager;

    public RemovePasswordCommand(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public void execute(CommandSourceStack stack, String @NotNull [] args) {
        CommandSender sender = stack.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(I18n.msg("command.player_only"));
            return;
        }

        if (!authManager.isLoggedIn(player)) {
            player.sendMessage(I18n.msg("removepassword.must_login", player));
            return;
        }

        if (authManager.isPasswordless(player.getUniqueId())) {
            player.sendMessage(I18n.msg("removepassword.already", player));
            return;
        }

        // 离线账户必须已绑定 2FA，否则移除密码后账号无任何验证因素
        boolean bound2fa = authManager.hasTotpSecret(player.getUniqueId());
        if (!bound2fa && !authManager.isPremium(player)) {
            player.sendMessage(I18n.msg("removepassword.need_2fa", player));
            return;
        }

        String code = args.length > 0 ? args[0] : null;
        // 已绑定 2FA 时需输入验证码确认（移除后即为唯一登录因素）
        if (bound2fa && (code == null || code.isEmpty())) {
            player.sendMessage(I18n.msg("removepassword.usage", player));
            return;
        }

        if (authManager.removePassword(player, code)) {
            player.sendMessage(I18n.msg("removepassword.success", player));
        } else {
            player.sendMessage(I18n.msg("2fa.confirm_incorrect", player));
        }
    }
}
