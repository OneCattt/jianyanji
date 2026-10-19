package com.jianyanji.payments;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

/** 使用真实 RSA 和 AES-GCM 算法验证通知认证、篡改拒绝及渠道金额合同。 */
class WechatPayClientTest {
    /** 测试专用平台 RSA 密钥，绝不读取生产密钥。 */
    private static KeyPair keys;
    /** 临时商户私钥路径。 */
    private static Path privatePath;
    /** 临时可信平台公钥路径。 */
    private static Path publicPath;
    /** 测试专用 32 字节 API V3 密钥。 */
    private static final String API_KEY = "12345678901234567890123456789012";

    @BeforeAll
    static void prepare() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        privatePath = Files.createTempFile("jianyanji-private-", ".pem");
        publicPath = Files.createTempFile("jianyanji-public-", ".pem");
        Files.write(
                privatePath,
                pem("PRIVATE KEY", keys.getPrivate().getEncoded())
                        .getBytes(StandardCharsets.US_ASCII));
        Files.write(
                publicPath,
                pem("PUBLIC KEY", keys.getPublic().getEncoded())
                        .getBytes(StandardCharsets.US_ASCII));
        privatePath.toFile().deleteOnExit();
        publicPath.toFile().deleteOnExit();
    }

    private static MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("WECHAT_APP_ID", "app")
                .withProperty("WECHAT_PAY_MCH_ID", "mch")
                .withProperty("WECHAT_PAY_SERIAL_NO", "merchant-serial")
                .withProperty("WECHAT_PAY_PLATFORM_SERIAL", "platform-serial")
                .withProperty("WECHAT_PAY_PRIVATE_KEY_PATH", privatePath.toString())
                .withProperty("WECHAT_PAY_PLATFORM_PUBLIC_KEY_PATH", publicPath.toString())
                .withProperty("WECHAT_PAY_API_V3_KEY", API_KEY)
                .withProperty(
                        "WECHAT_PAY_NOTIFY_URL", "https://example.com/api/payments/wechat/notify");
    }

    @Test
    void missingAndInvalidConfigurationNeverPretendAvailable() {
        WechatPayClient missing = new WechatPayClient(new MockEnvironment());
        assertFalse(missing.configured());
        assertEquals(
                "PAYMENT_NOT_CONFIGURED",
                assertThrows(BusinessException.class, missing::requireConfigured).getCode());
        WechatPayClient invalid =
                new WechatPayClient(environment().withProperty("WECHAT_PAY_API_V3_KEY", "short"));
        assertFalse(invalid.configured());
        assertEquals(
                "PAYMENT_CONFIG_INVALID",
                assertThrows(BusinessException.class, invalid::requireConfigured).getCode());
    }

    @Test
    void verifiesRawBytesAndRejectsReplayWrongSerialProbeAndTampering() throws Exception {
        WechatPayClient client = new WechatPayClient(environment());
        assertTrue(client.configured());
        byte[] raw = "{\"金额\":100}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = signedRequest(raw, Instant.now().getEpochSecond());
        assertDoesNotThrow(() -> client.verifySignature(raw, request::getHeader));
        assertThrows(
                BusinessException.class,
                () ->
                        client.verifySignature(
                                "{}".getBytes(StandardCharsets.UTF_8), request::getHeader));
        MockHttpServletRequest expired =
                signedRequest(raw, Instant.now().minusSeconds(360).getEpochSecond());
        assertThrows(
                BusinessException.class, () -> client.verifySignature(raw, expired::getHeader));
        request.removeHeader("Wechatpay-Serial");
        request.addHeader("Wechatpay-Serial", "other-platform");
        assertThrows(
                BusinessException.class, () -> client.verifySignature(raw, request::getHeader));
        MockHttpServletRequest probe = signedRequest(raw, Instant.now().getEpochSecond());
        probe.removeHeader("Wechatpay-Signature");
        probe.addHeader("Wechatpay-Signature", "WECHATPAY/SIGNTEST/probe");
        assertThrows(BusinessException.class, () -> client.verifySignature(raw, probe::getHeader));
        assertThrows(
                BusinessException.class,
                () -> client.verifySignature(raw, new MockHttpServletRequest()::getHeader));
    }

    @Test
    void decryptsAuthenticatedNotificationAndRejectsBadAadAndWrongEvent() throws Exception {
        WechatPayClient client = new WechatPayClient(environment());
        ObjectNode trade =
                obj(
                        "out_trade_no",
                        "P1",
                        "mchid",
                        "mch",
                        "appid",
                        "app",
                        "trade_state",
                        "SUCCESS",
                        "transaction_id",
                        "wx1",
                        "amount",
                        obj("total", 100, "currency", "CNY"),
                        "payer",
                        obj("openid", "payer"));
        ObjectNode event = encryptedEvent(trade);
        byte[] raw = stringify(event).getBytes(StandardCharsets.UTF_8);
        assertEquals(
                trade,
                client.notification(raw, signedRequest(raw, Instant.now().getEpochSecond())));
        ((ObjectNode) event.get("resource")).put("associated_data", "tampered");
        byte[] bad = stringify(event).getBytes(StandardCharsets.UTF_8);
        assertEquals(
                "INVALID_NOTIFICATION",
                assertThrows(
                                BusinessException.class,
                                () ->
                                        client.notification(
                                                bad,
                                                signedRequest(bad, Instant.now().getEpochSecond())))
                        .getCode());
        event.put("event_type", "REFUND.SUCCESS");
        byte[] wrongEvent = stringify(event).getBytes(StandardCharsets.UTF_8);
        assertThrows(
                BusinessException.class,
                () ->
                        client.notification(
                                wrongEvent,
                                signedRequest(wrongEvent, Instant.now().getEpochSecond())));
    }

    @Test
    void jsapiSignatureAndRefundUsePersistedOriginalReferences() throws Exception {
        RecordingClient client = new RecordingClient();
        client.response = obj("prepay_id", "wx-prepay");
        JsonNode parameters =
                client.create(
                        obj(
                                "number",
                                "P-original",
                                "cents",
                                1000,
                                "openid",
                                "payer",
                                "description",
                                "商品订单",
                                "expiresAt",
                                "2026-10-19T12:00:00Z"));
        assertEquals("/v3/pay/transactions/jsapi", client.path);
        assertEquals("P-original", s(client.body, "out_trade_no"));
        assertEquals(1000, l(client.body.path("amount"), "total"));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keys.getPublic());
        verifier.update(
                ("app\n"
                                + s(parameters, "timeStamp")
                                + "\n"
                                + s(parameters, "nonceStr")
                                + "\n"
                                + s(parameters, "package")
                                + "\n")
                        .getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(s(parameters, "paySign"))));
        client.response =
                obj(
                        "out_trade_no",
                        "P-original",
                        "out_refund_no",
                        "RF-original",
                        "refund_id",
                        "wx-refund",
                        "status",
                        "SUCCESS",
                        "amount",
                        obj("total", 1000, "refund", 300, "currency", "CNY"));
        ObjectNode input =
                obj(
                        "outTradeNo",
                        "P-original",
                        "transactionId",
                        "wx-original",
                        "outRefundNo",
                        "RF-original",
                        "totalCents",
                        1000,
                        "refundCents",
                        300,
                        "reason",
                        "退货");
        JsonNode refund = client.refund(input);
        assertEquals("success", s(refund, "status"));
        assertEquals("wx-original", s(client.body, "transaction_id"));
        assertEquals("RF-original", s(client.body, "out_refund_no"));
        input.put("refundCents", 1001);
        assertEquals(
                "INVALID_REFUND",
                assertThrows(BusinessException.class, () -> client.refund(input)).getCode());
        input.put("refundCents", 300);
        client.response.put("out_trade_no", "other-payment");
        assertEquals(
                "REFUND_MISMATCH",
                assertThrows(BusinessException.class, () -> client.refund(input)).getCode());
        client.response.put("out_trade_no", "P-original");
        client.response.put("out_refund_no", "other-refund");
        assertThrows(BusinessException.class, () -> client.queryRefund("RF-original"));
    }

    @Test
    void productionChannelRejectsNetworkInsideTransaction() {
        WechatPayClient client = new WechatPayClient(environment());
        org.springframework.transaction.support.TransactionSynchronizationManager
                .setActualTransactionActive(true);
        try {
            assertEquals(
                    "CHANNEL_IN_TRANSACTION",
                    assertThrows(BusinessException.class, () -> client.query("P1")).getCode());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .setActualTransactionActive(false);
        }
    }

    @Test
    void validatesAmountMerchantAppPayerAndSuccessfulTransaction() {
        ObjectNode attempt =
                obj(
                        "number", "P1", "mch_id", "mch", "app_id", "app", "cents", 100, "openid",
                        "payer");
        ObjectNode trade =
                obj(
                        "out_trade_no",
                        "P1",
                        "mchid",
                        "mch",
                        "appid",
                        "app",
                        "trade_state",
                        "SUCCESS",
                        "transaction_id",
                        "wx1",
                        "amount",
                        obj("total", 100, "currency", "CNY"),
                        "payer",
                        obj("openid", "payer"));
        assertDoesNotThrow(() -> PaymentService.match(attempt, trade));
        for (String field : Arrays.asList("out_trade_no", "mchid", "appid", "transaction_id")) {
            ObjectNode invalid = trade.deepCopy();
            invalid.put(field, "transaction_id".equals(field) ? "" : "other");
            assertThrows(BusinessException.class, () -> PaymentService.match(attempt, invalid));
        }
        ObjectNode wrongAmount = trade.deepCopy();
        ((ObjectNode) wrongAmount.get("amount")).put("total", 101);
        assertThrows(BusinessException.class, () -> PaymentService.match(attempt, wrongAmount));
        ObjectNode missingPayer = trade.deepCopy();
        missingPayer.remove("payer");
        assertThrows(BusinessException.class, () -> PaymentService.match(attempt, missingPayer));
        ObjectNode wrongPayer = trade.deepCopy();
        ((ObjectNode) wrongPayer.get("payer")).put("openid", "other");
        assertThrows(BusinessException.class, () -> PaymentService.match(attempt, wrongPayer));
    }

    private static ObjectNode encryptedEvent(JsonNode trade) throws Exception {
        String nonce = "123456789012", aad = "transaction";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(API_KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return obj(
                "event_type",
                "TRANSACTION.SUCCESS",
                "resource_type",
                "encrypt-resource",
                "resource",
                obj(
                        "algorithm",
                        "AEAD_AES_256_GCM",
                        "nonce",
                        nonce,
                        "associated_data",
                        aad,
                        "ciphertext",
                        Base64.getEncoder()
                                .encodeToString(
                                        cipher.doFinal(
                                                stringify(trade)
                                                        .getBytes(StandardCharsets.UTF_8)))));
    }

    private static MockHttpServletRequest signedRequest(byte[] raw, long timestamp)
            throws Exception {
        String nonce = "test-nonce";
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.getPrivate());
        signer.update((timestamp + "\n" + nonce + "\n").getBytes(StandardCharsets.UTF_8));
        signer.update(raw);
        signer.update((byte) '\n');
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Wechatpay-Timestamp", String.valueOf(timestamp));
        request.addHeader("Wechatpay-Nonce", nonce);
        request.addHeader("Wechatpay-Serial", "platform-serial");
        request.addHeader("Wechatpay-Signature", Base64.getEncoder().encodeToString(signer.sign()));
        return request;
    }

    private static String pem(String type, byte[] bytes) {
        return "-----BEGIN "
                + type
                + "-----\n"
                + Base64.getMimeEncoder().encodeToString(bytes)
                + "\n-----END "
                + type
                + "-----";
    }

    /** 显式测试替身，只替换网络传输，保留签名和金额规则；生产组件从不使用此类。 */
    private static class RecordingClient extends WechatPayClient {
        /** 预设已验签渠道响应。 */
        private ObjectNode response;
        /** 最近请求正文。 */
        private JsonNode body;
        /** 最近请求路径。 */
        private String path;

        RecordingClient() {
            super(environment());
        }

        @Override
        protected JsonNode request(String method, String path, JsonNode body) {
            this.path = path;
            this.body = body;
            return response;
        }
    }
}
