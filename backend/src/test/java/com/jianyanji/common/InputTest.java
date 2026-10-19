package com.jianyanji.common;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 输入与历史 JSON 合同边界，防止字符串金额、自行提权和过期身份误用。 */
class InputTest {
    @Test
    void rejectsMoneyCoercion() {
        assertThrows(
                BusinessException.class,
                () -> Input.integer(obj("amountCents", "100"), "amountCents", 1, 100000000));
        assertThrows(
                BusinessException.class,
                () -> Input.integer(obj("amountCents", 1.5), "amountCents", 1, 100000000));
        assertEquals(100, Input.integer(obj("amountCents", 100), "amountCents", 1, 100000000));
    }

    @Test
    void rejectsUnexpectedIdentityFields() {
        assertThrows(
                BusinessException.class,
                () -> Input.strict(obj("nickname", "访客", "identity", "B"), "nickname", "phone"));
    }

    @Test
    void requiresActualBoolean() {
        assertThrows(BusinessException.class, () -> Input.bool(obj("verified", 1), "verified"));
    }

    @Test
    void capsSafeIds() {
        assertThrows(BusinessException.class, () -> Input.id(9007199254740992L));
        assertThrows(BusinessException.class, () -> Input.id(0));
    }

    @Test
    void expiredAgentReturnsVisitor() {
        assertEquals(
                "D",
                effectiveIdentity(
                        obj(
                                "identity",
                                "B",
                                "identity_valid_until",
                                Instant.now().minusSeconds(1).toString())));
        assertEquals("B", effectiveIdentity(obj("identity", "B", "identity_valid_until", null)));
    }

    @Test
    void serializesNullAndIntegersWithoutCoercion() {
        assertEquals(
                "{\"money\":1234567890123,\"missing\":null}",
                stringify(obj("money", 1234567890123L, "missing", null)));
        assertTrue(now().matches(".*\\.\\d{3}Z"));
    }
}
