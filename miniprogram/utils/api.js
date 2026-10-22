const config = require("../config");
const { invalidate } = require("./state");
const privacy = require("./privacy");

function isPublic(path, method) {
  const route = path.split("?")[0];
  return (
    method === "GET" &&
    (route.startsWith("/public/") ||
      /^\/products(?:\/[1-9]\d*)?$/.test(route) ||
      route === "/mini/products" ||
      route === "/membership" ||
      route === "/sales/candidate" ||
      route === "/invitations/candidate")
  );
}

async function request(path, method = "GET", data) {
  const originalGeneration = privacy.sessionGeneration();
  const originalToken = privacy.hasConsent()
    ? wx.getStorageSync("jyj_token")
    : "";
  let anonymousFallback = false;
  if (originalToken) {
    try {
      await privacy.checkSessionPrivacy();
    } catch (error) {
      // 状态未确认时公开浏览退回匿名，个人业务保持拦截。
      if (!isPublic(path, method)) throw error;
      if (privacy.hasConsent() || wx.getStorageSync("jyj_token")) throw error;
      anonymousFallback = true;
    }
  }
  if (
    !anonymousFallback &&
    (originalGeneration !== privacy.sessionGeneration() ||
      (originalToken && originalToken !== wx.getStorageSync("jyj_token")))
  )
    throw privacy.sessionChangedError();
  return new Promise((resolve, reject) => {
    if (!isPublic(path, method) && !privacy.hasConsent()) {
      reject(privacy.privacyError());
      return;
    }
    const token = privacy.hasConsent() ? wx.getStorageSync("jyj_token") : "";
    const generation = privacy.sessionGeneration();
    wx.request({
      url: config.apiBase + path,
      method,
      data,
      header: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: "Bearer " + token } : {}),
      },
      success(response) {
        if (
          generation !== privacy.sessionGeneration() ||
          (token && token !== wx.getStorageSync("jyj_token"))
        ) {
          reject(privacy.sessionChangedError());
          return;
        }
        if (response.statusCode >= 200 && response.statusCode < 300) {
          if (method !== "GET") invalidate();
          return resolve(response.data);
        }
        if (response.statusCode === 401) {
          privacy.clearLocalSession();
          invalidate();
        }
        const error = new Error(
          response.data.message || "请求失败，请稍后重试",
        );
        error.status = response.statusCode;
        reject(error);
      },
      fail() {
        reject(new Error("无法连接服务，请检查网络后重试"));
      },
    });
  });
}
let loginPending = null;
function login(account = "visitor") {
  if (loginPending) return loginPending;
  loginPending = loginOnce(account).finally(() => {
    loginPending = null;
  });
  return loginPending;
}

async function loginOnce(account) {
  await privacy.ensureConsent();
  await privacy.requireWechatPrivacy();
  if (!privacy.hasConsent()) throw privacy.privacyError();
  const generation = privacy.sessionGeneration();
  let result;
  if (config.devAuth) result = await request("/auth/dev", "POST", { account });
  else {
    const code = await new Promise((resolve, reject) =>
      wx.login({
        success(value) {
          value.code
            ? resolve(value.code)
            : reject(new Error("微信登录未完成，请重试"));
        },
        fail() {
          reject(new Error("微信登录失败，请重试"));
        },
      }),
    );
    if (generation !== privacy.sessionGeneration() || !privacy.hasConsent())
      throw privacy.privacyError();
    result = await request("/auth/wechat", "POST", { code });
  }
  if (generation !== privacy.sessionGeneration() || !privacy.hasConsent())
    throw privacy.privacyError();
  // 主动切换已有账号时清掉前一账号的业务选择，首次登录保留本人刚确认的分享候选。
  if (wx.getStorageSync("jyj_token")) privacy.clearLocalSession();
  wx.setStorageSync("jyj_token", result.token);
  privacy.markPlatformReady();
  invalidate();
  return result.user;
}
module.exports = { request, login };
