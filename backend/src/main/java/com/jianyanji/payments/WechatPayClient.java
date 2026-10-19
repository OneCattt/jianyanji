package com.jianyanji.payments;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.jianyanji.common.BusinessException;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 微信支付 V3 真实渠道；验签后才解析响应，通知使用原始字节验签及 AES-GCM 解密。 */
@Component
public class WechatPayClient {
    /** 渠道请求总时限执行器；守护线程不阻止应用退出，取消任务立即移出队列。 */
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
    /** 微信应用编号，沿用 WECHAT_APP_ID 环境配置。 */
    private final String applicationId;
    /** 微信商户编号。 */
    private final String merchantId;
    /** 商户证书序列号。 */
    private final String serial;
    /** 可信微信平台公钥或证书标识，必须精确匹配签名头。 */
    private final String platformSerial;
    /** API V3 密钥，UTF-8 编码必须恰为 32 字节。 */
    private final byte[] apiKey;
    /** HTTPS 支付通知地址。 */
    private final String notifyUrl;
    /** 商户 RSA 私钥。 */
    private PrivateKey privateKey;
    /** 可信微信平台 RSA 公钥。 */
    private PublicKey publicKey;
    /** 密钥解析错误；能力查询显示不可用，实际请求返回明确配置错误。 */
    private RuntimeException configurationError;
    /** 所有必需配置是否齐全。 */
    private final boolean complete;
    /** 安全随机数生成器，用于请求和小程序支付签名。 */
    private final SecureRandom random = new SecureRandom();

    public WechatPayClient(Environment env) {
        applicationId = value(env, "WECHAT_APP_ID");
        merchantId = value(env, "WECHAT_PAY_MCH_ID");
        serial = value(env, "WECHAT_PAY_SERIAL_NO");
        platformSerial = value(env, "WECHAT_PAY_PLATFORM_SERIAL");
        apiKey = value(env, "WECHAT_PAY_API_V3_KEY").getBytes(StandardCharsets.UTF_8);
        notifyUrl = value(env, "WECHAT_PAY_NOTIFY_URL");
        String privatePath = value(env, "WECHAT_PAY_PRIVATE_KEY_PATH");
        String publicPath = value(env, "WECHAT_PAY_PLATFORM_PUBLIC_KEY_PATH");
        complete =
                !applicationId.isEmpty()
                        && !merchantId.isEmpty()
                        && !serial.isEmpty()
                        && !platformSerial.isEmpty()
                        && apiKey.length > 0
                        && !notifyUrl.isEmpty()
                        && !privatePath.isEmpty()
                        && !publicPath.isEmpty();
        if (complete) {
            try {
                if (apiKey.length != 32 || !notifyUrl.startsWith("https://"))
                    throw new IllegalArgumentException("微信支付配置不完整");
                privateKey =
                        readPrivate(
                                new String(
                                        Files.readAllBytes(Paths.get(privatePath)),
                                        StandardCharsets.UTF_8));
                publicKey =
                        readPublic(
                                new String(
                                        Files.readAllBytes(Paths.get(publicPath)),
                                        StandardCharsets.UTF_8));
                if (!"RSA".equals(privateKey.getAlgorithm())
                        || !"RSA".equals(publicKey.getAlgorithm()))
                    throw new IllegalArgumentException("微信支付必须配置 RSA 密钥");
            } catch (Exception e) {
                configurationError = error(503, "PAYMENT_CONFIG_INVALID", "微信支付密钥或配置无效，请联系管理员");
            }
        }
    }

    private static String value(Environment env, String key) {
        return env.getProperty(key, "");
    }

    public boolean configured() {
        return complete && configurationError == null;
    }

    public String appId() {
        return applicationId;
    }

    public String mchId() {
        return merchantId;
    }

    /** 缺失配置和无效配置采用与原接口一致的不同错误码。 */
    public void requireConfigured() {
        if (configurationError != null) throw configurationError;
        if (!complete) throw error(503, "PAYMENT_NOT_CONFIGURED", "微信支付尚未配置，请联系品牌方");
    }

    public JsonNode create(JsonNode input) {
        JsonNode result =
                request(
                        "POST",
                        "/v3/pay/transactions/jsapi",
                        obj(
                                "appid",
                                applicationId,
                                "mchid",
                                merchantId,
                                "description",
                                s(input, "description"),
                                "out_trade_no",
                                s(input, "number"),
                                "notify_url",
                                notifyUrl,
                                "time_expire",
                                s(input, "expiresAt"),
                                "amount",
                                obj("total", l(input, "cents"), "currency", "CNY"),
                                "payer",
                                obj("openid", s(input, "openid"))));
        if (!result.path("prepay_id").isTextual() || s(result, "prepay_id").isEmpty())
            throw error(502, "INVALID_RESPONSE", "微信未返回预支付编号");
        String timestamp = seconds(), salt = nonce(), pkg = "prepay_id=" + s(result, "prepay_id");
        return obj(
                "timeStamp",
                timestamp,
                "nonceStr",
                salt,
                "package",
                pkg,
                "signType",
                "RSA",
                "paySign",
                signed(applicationId + "\n" + timestamp + "\n" + salt + "\n" + pkg + "\n"));
    }

    public JsonNode query(String number) {
        return request(
                "GET",
                "/v3/pay/transactions/out-trade-no/"
                        + encoded(number)
                        + "?mchid="
                        + encoded(merchantId),
                null);
    }

    public void close(String number) {
        request(
                "POST",
                "/v3/pay/transactions/out-trade-no/" + encoded(number) + "/close",
                obj("mchid", merchantId));
    }

    public JsonNode refund(JsonNode input) {
        long refund = l(input, "refundCents"), total = l(input, "totalCents");
        if (!safeInteger(input.path("refundCents"))
                || !safeInteger(input.path("totalCents"))
                || refund <= 0
                || refund > total
                || !s(input, "outRefundNo").matches("[a-zA-Z0-9_\\-|*@]{1,64}")
                || s(input, "outTradeNo").isEmpty()
                || s(input, "transactionId").isEmpty())
            throw error(400, "INVALID_REFUND", "退款金额或原支付单号不合法");
        JsonNode result =
                refundResult(
                        request(
                                "POST",
                                "/v3/refund/domestic/refunds",
                                obj(
                                        "transaction_id",
                                        s(input, "transactionId"),
                                        "out_refund_no",
                                        s(input, "outRefundNo"),
                                        "reason",
                                        s(input, "reason"),
                                        "amount",
                                        obj("refund", refund, "total", total, "currency", "CNY"))));
        if (!s(result, "outTradeNo").equals(s(input, "outTradeNo"))
                || !s(result, "outRefundNo").equals(s(input, "outRefundNo"))
                || l(result, "refundCents") != refund
                || l(result, "totalCents") != total)
            throw error(502, "REFUND_MISMATCH", "微信退款响应与原订单不匹配");
        return result;
    }

    public JsonNode queryRefund(String number) {
        JsonNode result =
                refundResult(
                        request("GET", "/v3/refund/domestic/refunds/" + encoded(number), null));
        if (!number.equals(s(result, "outRefundNo")))
            throw error(502, "REFUND_MISMATCH", "退款查询单号不匹配");
        return result;
    }

    private JsonNode refundResult(JsonNode value) {
        JsonNode amount = value.path("amount");
        if (s(value, "out_trade_no").isEmpty()
                || s(value, "out_refund_no").isEmpty()
                || s(value, "refund_id").isEmpty()
                || !safeInteger(amount.path("total"))
                || !safeInteger(amount.path("refund"))
                || !"CNY".equals(s(amount, "currency")))
            throw error(502, "REFUND_MISMATCH", "微信退款响应不完整");
        String status;
        switch (s(value, "status")) {
            case "SUCCESS":
                status = "success";
                break;
            case "PROCESSING":
                status = "processing";
                break;
            case "CLOSED":
            case "ABNORMAL":
                status = "failed";
                break;
            default:
                status = "unknown";
        }
        return obj(
                "status",
                status,
                "outTradeNo",
                s(value, "out_trade_no"),
                "outRefundNo",
                s(value, "out_refund_no"),
                "refundCents",
                l(amount, "refund"),
                "totalCents",
                l(amount, "total"),
                "refundId",
                s(value, "refund_id"));
    }

    public JsonNode notification(byte[] raw, HttpServletRequest servletRequest) {
        requireConfigured();
        verifySignature(raw, servletRequest::getHeader);
        try {
            JsonNode event = parse(new String(raw, StandardCharsets.UTF_8));
            JsonNode resource = event.path("resource");
            if (!"TRANSACTION.SUCCESS".equals(s(event, "event_type"))
                    || !"encrypt-resource".equals(s(event, "resource_type"))
                    || !"AEAD_AES_256_GCM".equals(s(resource, "algorithm")))
                throw error(400, "INVALID_NOTIFICATION", "不支持的微信支付通知");
            byte[] encrypted = Base64.getDecoder().decode(s(resource, "ciphertext"));
            if (encrypted.length < 17) throw error(400, "INVALID_NOTIFICATION", "微信通知密文长度错误");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(apiKey, "AES"),
                    new GCMParameterSpec(
                            128, s(resource, "nonce").getBytes(StandardCharsets.UTF_8)));
            cipher.updateAAD(s(resource, "associated_data").getBytes(StandardCharsets.UTF_8));
            JsonNode trade = parse(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));
            if (!"SUCCESS".equals(s(trade, "trade_state")))
                throw error(400, "INVALID_NOTIFICATION", "支付成功通知的交易状态错误");
            return trade;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw error(400, "INVALID_NOTIFICATION", "微信通知格式或解密认证失败");
        }
    }

    /** 验签严格绑定五分钟时窗、可信平台标识、原始响应字节；禁止签名测试探针。 */
    void verifySignature(byte[] raw, Function<String, String> headers) {
        try {
            String timestamp = requiredHeader(headers, "Wechatpay-Timestamp");
            String salt = requiredHeader(headers, "Wechatpay-Nonce");
            String signature = requiredHeader(headers, "Wechatpay-Signature");
            if (!timestamp.matches("[0-9]+")
                    || Math.abs(Instant.now().getEpochSecond() - Double.parseDouble(timestamp))
                            > 300
                    || !platformSerial.equals(requiredHeader(headers, "Wechatpay-Serial"))
                    || signature.startsWith("WECHATPAY/SIGNTEST/"))
                throw error(400, "INVALID_SIGNATURE", "微信签名时间或平台证书不匹配");
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update((timestamp + "\n" + salt + "\n").getBytes(StandardCharsets.UTF_8));
            verifier.update(raw);
            verifier.update((byte) '\n');
            if (!verifier.verify(Base64.getDecoder().decode(signature)))
                throw error(400, "INVALID_SIGNATURE", "微信支付验签失败");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw error(400, "INVALID_SIGNATURE", "微信支付验签失败");
        }
    }

    private static String requiredHeader(Function<String, String> headers, String key) {
        String value = headers.apply(key);
        if (value == null || value.isEmpty()) throw error(400, "INVALID_SIGNATURE", "微信响应缺少验签头");
        return value;
    }

    /** 真实请求使用固定微信域名、十秒超时并拒绝重定向，禁止事务内等待渠道。 */
    protected JsonNode request(String method, String path, JsonNode body) {
        requireConfigured();
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw error(500, "CHANNEL_IN_TRANSACTION", "微信渠道请求必须在数据库事务外执行");
        HttpURLConnection connection = null;
        ScheduledFuture<?> timeout = null;
        try {
            String raw = body == null ? "" : stringify(body), timestamp = seconds(), salt = nonce();
            String signature =
                    signed(
                            method + "\n" + path + "\n" + timestamp + "\n" + salt + "\n" + raw
                                    + "\n");
            connection =
                    (HttpURLConnection)
                            new URL("https://api.mch.weixin.qq.com" + path).openConnection();
            final HttpURLConnection timedConnection = connection;
            timeout = DEADLINES.schedule(timedConnection::disconnect, 10, TimeUnit.SECONDS);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestMethod(method);
            connection.setRequestProperty(
                    "Authorization",
                    "WECHATPAY2-SHA256-RSA2048 mchid=\""
                            + merchantId
                            + "\",nonce_str=\""
                            + salt
                            + "\",timestamp=\""
                            + timestamp
                            + "\",serial_no=\""
                            + serial
                            + "\",signature=\""
                            + signature
                            + "\"");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Wechatpay-Serial", platformSerial);
            if (body != null) {
                connection.setDoOutput(true);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(raw.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            byte[] bytes = read(connection, status);
            final HttpURLConnection response = connection;
            verifySignature(bytes, response::getHeaderField);
            JsonNode value =
                    bytes.length == 0 ? obj() : parse(new String(bytes, StandardCharsets.UTF_8));
            if (status < 200 || status >= 300)
                throw error(
                        502,
                        s(value, "code").isEmpty() ? "CHANNEL_ERROR" : s(value, "code"),
                        "微信支付接口失败："
                                + (s(value, "message").isEmpty()
                                        ? "渠道未提供原因"
                                        : s(value, "message")));
            return value;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw error(502, "CHANNEL_ERROR", "微信支付网络或响应处理失败，请核查原业务单");
        } finally {
            if (timeout != null) timeout.cancel(false);
            if (connection != null) connection.disconnect();
        }
    }

    private static ScheduledThreadPoolExecutor deadlines() {
        ScheduledThreadPoolExecutor executor =
                new ScheduledThreadPoolExecutor(
                        1,
                        runnable -> {
                            Thread thread = new Thread(runnable, "wechat-request-timeout");
                            thread.setDaemon(true);
                            return thread;
                        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static byte[] read(HttpURLConnection connection, int status) throws IOException {
        InputStream stream =
                status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) return new byte[0];
        try (InputStream input = stream;
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int size;
            while ((size = input.read(buffer)) != -1) {
                output.write(buffer, 0, size);
            }
            return output.toByteArray();
        }
    }

    private String signed(String message) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw error(503, "PAYMENT_CONFIG_INVALID", "微信支付签名失败，请检查商户私钥");
        }
    }

    private String nonce() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static String seconds() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    private static String encoded(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean safeInteger(JsonNode value) {
        return value.isIntegralNumber()
                && value.canConvertToLong()
                && Math.abs((double) value.longValue()) <= 9007199254740991L;
    }

    private static BusinessException error(int status, String code, String message) {
        return new BusinessException(status, code, message);
    }

    private static byte[] pem(String value) {
        return Base64.getMimeDecoder().decode(value.replaceAll("-----[^-]+-----", ""));
    }

    private static PrivateKey readPrivate(String value) throws GeneralSecurityException {
        byte[] der = pem(value);
        if (value.contains("BEGIN RSA PRIVATE KEY"))
            der = sequence(new byte[] {2, 1, 0}, algorithm(), tagged(4, der));
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static PublicKey readPublic(String value) throws GeneralSecurityException {
        if (value.contains("BEGIN CERTIFICATE"))
            return CertificateFactory.getInstance("X.509")
                    .generateCertificate(
                            new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII)))
                    .getPublicKey();
        byte[] der = pem(value);
        if (value.contains("BEGIN RSA PUBLIC KEY"))
            der = sequence(algorithm(), tagged(3, concat(new byte[] {0}, der)));
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    private static byte[] algorithm() {
        return new byte[] {
            48, 13, 6, 9, 42, (byte) 134, 72, (byte) 134, (byte) 247, 13, 1, 1, 1, 5, 0
        };
    }

    private static byte[] sequence(byte[]... parts) {
        return tagged(48, concat(parts));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) output.write(part, 0, part.length);
        return output.toByteArray();
    }

    private static byte[] tagged(int tag, byte[] bytes) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(tag);
        int length = bytes.length;
        if (length < 128) output.write(length);
        else {
            int count = length <= 255 ? 1 : length <= 65535 ? 2 : 3;
            output.write(128 | count);
            for (int i = count - 1; i >= 0; i--) output.write(length >> (i * 8) & 255);
        }
        output.write(bytes, 0, bytes.length);
        return output.toByteArray();
    }
}
