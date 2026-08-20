package org.howtologin.plugin.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

/**
 * TOTP 双因素认证工具（RFC 6238）。
 * <p>
 * 算法参数：HMAC-SHA1、30 秒周期、6 位验证码，
 * 与 Google Authenticator / Authy 等主流认证器兼容。
 * 密钥使用 RFC 4648 Base32 编码存储。
 */
public final class Totp {

    private static final int PERIOD_SECONDS = 30;
    private static final int CODE_DIGITS = 6;
    private static final int CODE_MODULUS = 1_000_000;
    // RFC 4648 Base32 字母表
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    // 验证时允许的时间窗口偏移（±1 个周期，容忍客户端/服务端时钟偏差）
    private static final int WINDOW = 1;

    private Totp() {}

    /** 生成随机 TOTP 密钥（20 字节，Base32 编码） */
    public static String generateSecret() {
        byte[] bytes = new byte[20];
        new SecureRandom().nextBytes(bytes);
        return encodeBase32(bytes);
    }

    /**
     * 验证 6 位验证码是否匹配密钥（允许 ±1 个时间窗口的时钟偏差）。
     * @param base32Secret Base32 编码的密钥
     * @param code         用户输入的 6 位数字验证码
     */
    // 调用方均以 ! 守卫子句形式使用（if (!verifyCode(...)) return;），IDE 误报"始终反转"，
    // 但反转方法逻辑（如改名为 isCodeInvalid）会与方法名语义相反，破坏直觉命名
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean verifyCode(String base32Secret, String code) {
        if (base32Secret == null || code == null || !code.matches("\\d{" + CODE_DIGITS + "}")) {
            return false;
        }
        byte[] key = decodeBase32(base32Secret);
        if (key.length == 0) return false;
        long now = System.currentTimeMillis() / 1000;
        for (int offset = -WINDOW; offset <= WINDOW; offset++) {
            if (generateCode(key, now + (long) offset * PERIOD_SECONDS).equals(code)) {
                return true;
            }
        }
        return false;
    }

    /** 按 RFC 6238 生成指定时刻的 6 位验证码 */
    private static String generateCode(byte[] key, long timeSeconds) {
        long counter = timeSeconds / PERIOD_SECONDS;
        // 计数器转 8 字节大端序
        byte[] msg = new byte[8];
        for (int i = 7; i >= 0; i--) {
            msg[i] = (byte) (counter & 0xFF);
            counter >>= 8;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(msg);
            // 动态截断（RFC 4226 §5.4）
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            return String.format("%0" + CODE_DIGITS + "d", binary % CODE_MODULUS);
        } catch (Exception e) {
            return "";
        }
    }

    /** RFC 4648 Base32 编码（不含填充） */
    private static String encodeBase32(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                sb.append(BASE32_ALPHABET.charAt((buffer >> bits) & 0x1F));
            }
        }
        if (bits > 0) {
            sb.append(BASE32_ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }

    /** RFC 4648 Base32 解码（忽略非法字符与填充） */
    private static byte[] decodeBase32(String encoded) {
        String cleaned = encoded.toUpperCase().replaceAll("[^A-Z2-7]", "");
        if (cleaned.isEmpty()) return new byte[0];
        byte[] result = new byte[cleaned.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int idx = 0;
        for (char c : cleaned.toCharArray()) {
            int val = BASE32_ALPHABET.indexOf(c);
            buffer = (buffer << 5) | val;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                if (idx < result.length) {
                    result[idx++] = (byte) ((buffer >> bits) & 0xFF);
                }
            }
        }
        return result;
    }
}
