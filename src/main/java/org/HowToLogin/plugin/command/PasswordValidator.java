package org.howtologin.plugin.command;

import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;
import org.bukkit.entity.Player;

/** 密码校验工具：统一处理密码长度等规则，避免在多个命令中重复代码 */
final class PasswordValidator {

    private PasswordValidator() {}

    /**
     * 校验密码长度是否违反配置规则。
     * 长度非法时自动向玩家发送错误消息。
     * @return true 表示长度非法（已发送消息，调用方应直接 return）；false 表示合法
     */
    static boolean invalidLength(HTLogin plugin, Player player, String password) {
        int minLen = plugin.getConfigManager().minPasswordLength();
        int maxLen = plugin.getConfigManager().maxPasswordLength();
        if (password.length() < minLen || password.length() > maxLen) {
            player.sendMessage(HTLogin.legacy(I18n.get("command.password_length", player, minLen, maxLen)));
            return true;
        }
        return false;
    }
}
