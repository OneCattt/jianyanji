import {
  createDecipheriv,
  randomBytes,
  sign,
  verify,
  createPrivateKey,
  createPublicKey,
} from "node:crypto";
import { readFileSync } from "node:fs";
import type { IncomingHttpHeaders } from "node:http";
import { HttpError } from "./http.ts";

/** 微信支付渠道结果；金额单位为分，只接受服务端已验签结果。 */
export interface ChannelTrade {
  out_trade_no: string;
  mchid: string;
  appid: string;
  trade_state: string;
  transaction_id?: string;
  amount?: { total: number; currency: string };
  payer?: { openid: string };
}
export interface MiniPayParameters {
  timeStamp: string;
  nonceStr: string;
  package: string;
  signType: "RSA";
  paySign: string;
}
/** 测试渠道由调用方显式注入，生产默认只读取服务端环境配置。 */
export interface PaymentAdapter {
  appId: string;
  mchId: string;
  create(input: {
    number: string;
    cents: number;
    openid: string;
    description: string;
    expiresAt: string;
  }): Promise<MiniPayParameters>;
  query(number: string): Promise<ChannelTrade>;
  close(number: string): Promise<void>;
  notification(raw: Buffer, headers: IncomingHttpHeaders): ChannelTrade;
}
/** 退款仅允许传入原渠道商户单号及其原始总金额，退款单号由售后侧持久化并幂等复用。 */
export interface RefundInput {
  outTradeNo: string;
  transactionId: string;
  outRefundNo: string;
  totalCents: number;
  refundCents: number;
  reason: string;
}
export interface RefundResult {
  status: "processing" | "success" | "failed" | "unknown";
  outRefundNo: string;
  outTradeNo: string;
  refundCents: number;
  totalCents: number;
  refundId?: string;
  reason?: string;
}
export interface PayChannel extends PaymentAdapter {
  refund(input: RefundInput): Promise<RefundResult>;
  queryRefund(outRefundNo: string): Promise<RefundResult>;
  verifyNotification?(raw: Buffer, headers: IncomingHttpHeaders): ChannelTrade;
}
export async function createRefund(channel: PayChannel, input: RefundInput) {
  return channel.refund(input);
}
export const queryRefund = (channel: PayChannel, number: string) =>
  channel.queryRefund(number);
export interface WechatPayConfig {
  appId: string;
  mchId: string;
  serial: string;
  privateKey: string;
  platformSerial: string;
  platformPublicKey: string;
  apiV3Key: string;
  notifyUrl: string;
}
export class ChannelError extends Error {
  constructor(
    public code: string,
    message: string,
  ) {
    super(message);
  }
}
const nonce = () => randomBytes(24).toString("hex");
const seconds = () => String(Math.floor(Date.now() / 1000));
const signed = (key: string, message: string) =>
  sign("RSA-SHA256", Buffer.from(message), key).toString("base64");
function header(headers: IncomingHttpHeaders, name: string) {
  const value = headers[name];
  if (typeof value !== "string" || !value)
    throw new ChannelError("INVALID_SIGNATURE", "微信响应缺少验签头");
  return value;
}
/** 同时校验签名时间和平台证书标识；部署时须维护可信平台公钥与标识。 */
export function verifyWechatSignature(
  raw: Buffer,
  headers: IncomingHttpHeaders,
  config: Pick<WechatPayConfig, "platformSerial" | "platformPublicKey">,
) {
  const timestamp = header(headers, "wechatpay-timestamp");
  const salt = header(headers, "wechatpay-nonce");
  const signature = header(headers, "wechatpay-signature");
  if (
    !/^\d+$/.test(timestamp) ||
    Math.abs(Date.now() / 1000 - Number(timestamp)) > 300 ||
    header(headers, "wechatpay-serial") !== config.platformSerial ||
    signature.startsWith("WECHATPAY/SIGNTEST/")
  )
    throw new ChannelError("INVALID_SIGNATURE", "微信签名时间或平台证书不匹配");
  if (
    !verify(
      "RSA-SHA256",
      Buffer.concat([
        Buffer.from(`${timestamp}\n${salt}\n`),
        raw,
        Buffer.from("\n"),
      ]),
      config.platformPublicKey,
      Buffer.from(signature, "base64"),
    )
  )
    throw new ChannelError("INVALID_SIGNATURE", "微信支付验签失败");
}
export class WechatPayV3 implements PayChannel {
  appId: string;
  mchId: string;
  constructor(
    private config: WechatPayConfig,
    private transport: typeof fetch = fetch,
  ) {
    this.appId = config.appId;
    this.mchId = config.mchId;
    if (
      !config.appId ||
      !config.mchId ||
      !config.serial ||
      !config.platformSerial ||
      Buffer.byteLength(config.apiV3Key) !== 32 ||
      !/^https:\/\//.test(config.notifyUrl)
    )
      throw new Error("微信支付配置不完整");
    if (
      createPrivateKey(config.privateKey).asymmetricKeyType !== "rsa" ||
      createPublicKey(config.platformPublicKey).asymmetricKeyType !== "rsa"
    )
      throw new Error("微信支付必须配置 RSA 密钥");
  }
  private async request(method: string, path: string, body?: object) {
    const raw = body ? JSON.stringify(body) : "";
    const timestamp = seconds(),
      salt = nonce();
    const signature = signed(
      this.config.privateKey,
      `${method}\n${path}\n${timestamp}\n${salt}\n${raw}\n`,
    );
    const authorization = `WECHATPAY2-SHA256-RSA2048 mchid="${this.mchId}",nonce_str="${salt}",timestamp="${timestamp}",serial_no="${this.config.serial}",signature="${signature}"`;
    const response = await this.transport(
      "https://api.mch.weixin.qq.com" + path,
      {
        method,
        headers: {
          Authorization: authorization,
          "Content-Type": "application/json",
          Accept: "application/json",
          "Wechatpay-Serial": this.config.platformSerial,
        },
        ...(raw ? { body: raw } : {}),
        signal: AbortSignal.timeout(10000),
        redirect: "error",
      },
    );
    const bytes = Buffer.from(await response.arrayBuffer());
    verifyWechatSignature(
      bytes,
      Object.fromEntries(response.headers.entries()),
      this.config,
    );
    const value = bytes.length ? JSON.parse(bytes.toString("utf8")) : {};
    if (!response.ok)
      throw new ChannelError(
        String(value.code || "CHANNEL_ERROR"),
        String(value.message || "微信支付接口失败"),
      );
    return value;
  }
  async create(input: {
    number: string;
    cents: number;
    openid: string;
    description: string;
    expiresAt: string;
  }) {
    const result = await this.request("POST", "/v3/pay/transactions/jsapi", {
      appid: this.appId,
      mchid: this.mchId,
      description: input.description,
      out_trade_no: input.number,
      notify_url: this.config.notifyUrl,
      time_expire: input.expiresAt,
      amount: { total: input.cents, currency: "CNY" },
      payer: { openid: input.openid },
    });
    if (typeof result.prepay_id !== "string" || !result.prepay_id)
      throw new ChannelError("INVALID_RESPONSE", "微信未返回预支付编号");
    const timeStamp = seconds(),
      nonceStr = nonce(),
      pkg = "prepay_id=" + result.prepay_id;
    return {
      timeStamp,
      nonceStr,
      package: pkg,
      signType: "RSA" as const,
      paySign: signed(
        this.config.privateKey,
        `${this.appId}\n${timeStamp}\n${nonceStr}\n${pkg}\n`,
      ),
    };
  }
  query(number: string): Promise<ChannelTrade> {
    return this.request(
      "GET",
      `/v3/pay/transactions/out-trade-no/${encodeURIComponent(number)}?mchid=${encodeURIComponent(this.mchId)}`,
    );
  }
  async close(number: string) {
    await this.request(
      "POST",
      `/v3/pay/transactions/out-trade-no/${encodeURIComponent(number)}/close`,
      { mchid: this.mchId },
    );
  }
  async refund(input: RefundInput): Promise<RefundResult> {
    if (
      !Number.isSafeInteger(input.refundCents) ||
      input.refundCents <= 0 ||
      !Number.isSafeInteger(input.totalCents) ||
      input.refundCents > input.totalCents ||
      !/^[a-zA-Z0-9_\-|*@]{1,64}$/.test(input.outRefundNo) ||
      !input.outTradeNo ||
      !input.transactionId
    )
      throw new HttpError(400, "INVALID_REFUND", "退款金额或原支付单号不合法");
    const result = await this.request("POST", "/v3/refund/domestic/refunds", {
      transaction_id: input.transactionId,
      out_refund_no: input.outRefundNo,
      reason: input.reason,
      amount: {
        refund: input.refundCents,
        total: input.totalCents,
        currency: "CNY",
      },
    });
    const mapped = this.refundResult(result);
    if (
      mapped.outTradeNo !== input.outTradeNo ||
      mapped.outRefundNo !== input.outRefundNo ||
      mapped.refundCents !== input.refundCents ||
      mapped.totalCents !== input.totalCents
    )
      throw new ChannelError("REFUND_MISMATCH", "微信退款响应与原订单不匹配");
    return mapped;
  }
  async queryRefund(number: string): Promise<RefundResult> {
    const result = this.refundResult(
      await this.request(
        "GET",
        "/v3/refund/domestic/refunds/" + encodeURIComponent(number),
      ),
    );
    if (result.outRefundNo !== number)
      throw new ChannelError("REFUND_MISMATCH", "退款查询单号不匹配");
    return result;
  }
  private refundResult(value: {
    out_trade_no: string;
    out_refund_no: string;
    refund_id: string;
    status: string;
    amount: { total: number; refund: number; currency: string };
  }): RefundResult {
    if (
      !value.out_trade_no ||
      !value.out_refund_no ||
      !value.refund_id ||
      !Number.isSafeInteger(value.amount?.total) ||
      !Number.isSafeInteger(value.amount?.refund) ||
      value.amount?.currency !== "CNY"
    )
      throw new ChannelError("REFUND_MISMATCH", "微信退款响应不完整");
    const status =
      (
        {
          SUCCESS: "success",
          PROCESSING: "processing",
          CLOSED: "failed",
          ABNORMAL: "failed",
        } as const
      )[value.status as "SUCCESS"] ?? "unknown";
    return {
      status,
      outTradeNo: value.out_trade_no,
      outRefundNo: value.out_refund_no,
      refundCents: value.amount.refund,
      totalCents: value.amount.total,
      refundId: value.refund_id,
    };
  }
  verifyNotification(raw: Buffer, headers: IncomingHttpHeaders) {
    return this.notification(raw, headers);
  }
  notification(raw: Buffer, headers: IncomingHttpHeaders): ChannelTrade {
    verifyWechatSignature(raw, headers, this.config);
    const event = JSON.parse(raw.toString("utf8"));
    if (
      event.event_type !== "TRANSACTION.SUCCESS" ||
      event.resource_type !== "encrypt-resource" ||
      event.resource?.algorithm !== "AEAD_AES_256_GCM"
    )
      throw new ChannelError("INVALID_NOTIFICATION", "不支持的微信支付通知");
    const resource = event.resource;
    const encrypted = Buffer.from(resource.ciphertext, "base64");
    if (encrypted.length < 17)
      throw new ChannelError("INVALID_NOTIFICATION", "微信通知密文长度错误");
    const decipher = createDecipheriv(
      "aes-256-gcm",
      Buffer.from(this.config.apiV3Key),
      Buffer.from(resource.nonce),
    );
    decipher.setAuthTag(encrypted.subarray(-16));
    decipher.setAAD(Buffer.from(resource.associated_data || ""));
    const trade = JSON.parse(
      Buffer.concat([
        decipher.update(encrypted.subarray(0, -16)),
        decipher.final(),
      ]).toString("utf8"),
    ) as ChannelTrade;
    if (trade.trade_state !== "SUCCESS")
      throw new ChannelError(
        "INVALID_NOTIFICATION",
        "支付成功通知的交易状态错误",
      );
    return trade;
  }
}
export function paymentAdapterFromEnv(
  env: NodeJS.ProcessEnv = process.env,
): PayChannel | undefined {
  const keys = [
    "WECHAT_APP_ID",
    "WECHAT_PAY_MCH_ID",
    "WECHAT_PAY_SERIAL_NO",
    "WECHAT_PAY_PRIVATE_KEY_PATH",
    "WECHAT_PAY_PLATFORM_SERIAL",
    "WECHAT_PAY_PLATFORM_PUBLIC_KEY_PATH",
    "WECHAT_PAY_API_V3_KEY",
    "WECHAT_PAY_NOTIFY_URL",
  ];
  if (keys.some((key) => !env[key])) return undefined;
  try {
    return new WechatPayV3({
      appId: env.WECHAT_APP_ID!,
      mchId: env.WECHAT_PAY_MCH_ID!,
      serial: env.WECHAT_PAY_SERIAL_NO!,
      privateKey: readFileSync(env.WECHAT_PAY_PRIVATE_KEY_PATH!, "utf8"),
      platformSerial: env.WECHAT_PAY_PLATFORM_SERIAL!,
      platformPublicKey: readFileSync(
        env.WECHAT_PAY_PLATFORM_PUBLIC_KEY_PATH!,
        "utf8",
      ),
      apiV3Key: env.WECHAT_PAY_API_V3_KEY!,
      notifyUrl: env.WECHAT_PAY_NOTIFY_URL!,
    });
  } catch {
    throw new HttpError(
      503,
      "PAYMENT_CONFIG_INVALID",
      "微信支付密钥或配置无效，请联系管理员",
    );
  }
}
