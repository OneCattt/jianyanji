package com.jianyanji.identity;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 验证加盐密码校验、随机盐及失败路径，禁止以明文持久化密码。 */
class PasswordsTest {
    @Test
    void verifiesAndRejectsWrongPassword() {
        String stored = Passwords.hash("测试密码-2026!");
        assertTrue(Passwords.check("测试密码-2026!", stored));
        assertFalse(Passwords.check("其他密码", stored));
        assertFalse(stored.contains("测试密码"));
    }

    @Test
    void createsDifferentSaltAndRejectsMalformedHash() {
        assertNotEquals(Passwords.hash("same-password"), Passwords.hash("same-password"));
        assertFalse(Passwords.check("password", "not-a-digest"));
    }
}
