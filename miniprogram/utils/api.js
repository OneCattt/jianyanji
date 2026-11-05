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
      route === "/offers" ||
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
function login(account = "visitor", options = {}) {
  if (loginPending) return loginPending;
  const pages = getCurrentPages();
  const page = pages[pages.length - 1];
  const sheet = page && page.selectComponent("#login-sheet");
  if (!sheet)
    return Promise.reject(new Error("登录面板尚未就绪，请重新进入页面"));
  loginPending = sheet.open({ account, ...options }).finally(() => {
    loginPending = null;
  });
  return loginPending;
}

let authenticatePending = null;
// 仅由用户操作后的登录面板调用；公开浏览和后台读取不发起登录。
function authenticate(
  account = "visitor",
  phoneCode = "",
  active = () => true,
) {
  if (authenticatePending) return authenticatePending;
  authenticatePending = authenticateOnce(account, phoneCode, active).finally(
    () => {
      authenticatePending = null;
    },
  );
  return authenticatePending;
}
async function authenticateOnce(account, phoneCode, active) {
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
            : reject(new Error("登录未完成，请重试"));
        },
        fail() {
          reject(new Error("登录失败，请重试"));
        },
      }),
    );
    if (!active()) throw cancelledLogin();
    if (generation !== privacy.sessionGeneration() || !privacy.hasConsent())
      throw privacy.privacyError();
    result = await request("/auth/wechat", "POST", {
      code,
      ...(phoneCode ? { phoneCode } : {}),
    });
  }
  if (!active()) throw cancelledLogin();
  if (generation !== privacy.sessionGeneration() || !privacy.hasConsent())
    throw privacy.privacyError();
  // 主动切换已有账号时清掉前一账号的业务选择，首次登录保留本人刚确认的分享候选。
  if (wx.getStorageSync("jyj_token")) privacy.clearLocalSession();
  wx.setStorageSync("jyj_token", result.token);
  privacy.markPlatformReady();
  invalidate();
  return result.user;
}
function cancelledLogin() {
  const error = new Error("已取消登录，你仍可继续浏览");
  error.code = "LOGIN_CANCELLED";
  return error;
}
module.exports = { request, login, authenticate, cancelledLogin };
