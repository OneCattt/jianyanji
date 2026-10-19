package com.jianyanji.identity;

import java.nio.charset.StandardCharsets;
import java.security.*;
import org.bouncycastle.crypto.generators.SCrypt;

/** 使用与旧实现一致的加盐 scrypt 摘要，支持校验既有测试账号密码。 */
public final class Passwords {
    private Passwords() {}

    public static String hash(String password) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String s = hex(salt);
        return s + ":" + hex(derive(password, s));
    }

    public static boolean check(String password, String stored) {
        try {
            String[] parts = stored.split(":");
            if (parts.length != 2 || parts[0].length() != 32 || parts[1].length() != 128)
                return false;
            return MessageDigest.isEqual(derive(password, parts[0]), decode(parts[1]));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(String password, String salt) {
        return SCrypt.generate(
                password.getBytes(StandardCharsets.UTF_8),
                salt.getBytes(StandardCharsets.UTF_8),
                16384,
                8,
                1,
                64);
    }

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder();
        for (byte v : data) out.append(String.format("%02x", v));
        return out.toString();
    }

    private static byte[] decode(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
