export class WechatError extends Error {}

// 仅服务端向微信换取 openid，密钥、临时码及 session_key 不进入日志或响应。
export async function exchangeWechatCode(
  code: string,
  appId: string,
  appSecret: string,
): Promise<string> {
  if (!appId || !appSecret)
    throw new WechatError("微信登录尚未配置，请联系品牌方");
  const url = new URL("https://api.weixin.qq.com/sns/jscode2session");
  url.search = new URLSearchParams({
    appid: appId,
    secret: appSecret,
    js_code: code,
    grant_type: "authorization_code",
  }).toString();
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(8000) });
    if (!response.ok) throw new Error("渠道请求失败");
    const result = (await response.json()) as {
      openid?: string;
      errcode?: number;
    };
    if (result.errcode || typeof result.openid !== "string" || !result.openid)
      throw new Error("临时凭证无效");
    return result.openid;
  } catch {
    throw new WechatError("微信登录失败，请重新登录");
  }
}
