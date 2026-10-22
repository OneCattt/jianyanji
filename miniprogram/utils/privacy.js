// 政策实质变化时更新版本；只保存用户主动同意的结果，不用浏览行为代替同意。
const POLICY_VERSION = "2026-10-22";
const CONSENT_KEY = "jyj_privacy_consent";
const SESSION_KEYS = [
  "jyj_token",
  "jyj_address",
  "jyj_checkout",
  "jyj_sales_candidate",
  "jyj_invitation_candidate",
];
let pending = null;
let generation = 0;
let platformCheck = null;
const nativeResolvers = new Set();

function hasConsent() {
  const record = wx.getStorageSync(CONSENT_KEY);
  return !!(
    record &&
    record.version === POLICY_VERSION &&
    record.agreedAt &&
    record.operator &&
    record.contact
  );
}

function privacyError(message = "请先阅读并同意隐私政策后登录") {
  const error = new Error(message);
  error.code = "PRIVACY_REQUIRED";
  error.status = 401;
  return error;
}

function sessionChangedError() {
  const error = new Error("登录状态已变化，请重新进入页面");
  error.code = "SESSION_CHANGED";
  error.status = 409;
  return error;
}

function clearLocalSession() {
  generation += 1;
  platformCheck = null;
  SESSION_KEYS.forEach((key) => wx.removeStorageSync(key));
}

function rejectConsent() {
  for (const resolve of nativeResolvers) resolve({ event: "disagree" });
  nativeResolvers.clear();
  const current = pending;
  pending = null;
  if (current)
    current.reject(privacyError("你暂未同意隐私政策，仍可浏览商品和品牌内容"));
}

function ensureConsent(force = false) {
  if (pending) return pending.promise;
  if (force && hasConsent()) {
    // 微信出现新的待同意指引时，旧本机同意不能继续放行个人业务。
    wx.removeStorageSync(CONSENT_KEY);
    clearLocalSession();
  }
  if (!force && hasConsent()) return Promise.resolve();
  let resolve, reject;
  const promise = new Promise((yes, no) => {
    resolve = yes;
    reject = no;
  });
  pending = { promise, resolve, reject };
  wx.navigateTo({
    url: "/pages/privacy/index?consent=1",
    fail() {
      rejectConsent();
    },
  });
  return promise;
}

function recordConsent(operator, contact) {
  if (!operator || !contact)
    throw privacyError("隐私主体与联系方式尚未配置，请联系品牌方");
  wx.setStorageSync(CONSENT_KEY, {
    version: POLICY_VERSION,
    agreedAt: new Date().toISOString(),
    operator,
    contact,
  });
  // 微信要求在同意按钮仍挂载时传回按钮 ID，不能用本地记录代替平台授权。
  for (const resolve of nativeResolvers)
    resolve({ event: "agree", buttonId: "privacy-agree" });
  nativeResolvers.clear();
}

function finishConsent() {
  const current = pending;
  pending = null;
  if (current) current.resolve();
}

function revokeConsent() {
  rejectConsent();
  wx.removeStorageSync(CONSENT_KEY);
  clearLocalSession();
}

function installPrivacy() {
  if (!hasConsent()) clearLocalSession();
  else if (wx.getStorageSync("jyj_token"))
    checkSessionPrivacy().catch(() => {});
  if (typeof wx.onNeedPrivacyAuthorization === "function") {
    wx.onNeedPrivacyAuthorization((resolve) => {
      nativeResolvers.add(resolve);
      ensureConsent(true).catch(() => {});
    });
  }
}

// 恢复已有令牌时只检查一次；所有带身份的请求共享结果，不能仅依赖隐私接口触发的监听。
function checkSessionPrivacy() {
  if (platformCheck) return platformCheck;
  const current = generation;
  platformCheck = Promise.resolve().then(
    () =>
      new Promise((resolve, reject) => {
        const fail = (message) => {
          if (current === generation) {
            wx.removeStorageSync(CONSENT_KEY);
            clearLocalSession();
          }
          reject(privacyError(message));
        };
        if (typeof wx.getPrivacySetting !== "function") {
          fail("当前微信版本暂不支持隐私授权，请升级微信后重试");
          return;
        }
        wx.getPrivacySetting({
          success(result) {
            if (current !== generation) {
              reject(sessionChangedError());
            } else if (result.needAuthorization) {
              fail("微信隐私指引已更新，请重新阅读并同意后登录");
            } else resolve();
          },
          fail: () => fail("微信隐私授权状态暂不可读取，请重新确认后登录"),
        });
      }),
  );
  return platformCheck;
}

function requireWechatPrivacy() {
  return new Promise((resolve, reject) => {
    if (typeof wx.requirePrivacyAuthorize !== "function") {
      reject(privacyError("当前微信版本暂不支持隐私授权，请升级微信后重试"));
      return;
    }
    wx.requirePrivacyAuthorize({
      success() {
        // 原生回调可能早于授权页退栈；必须等待用户最终完成或取消本次页面流程。
        if (pending) pending.promise.then(resolve, reject);
        else resolve();
      },
      fail: () => reject(privacyError("微信隐私授权未完成，请重试")),
    });
  });
}

module.exports = {
  POLICY_VERSION,
  CONSENT_KEY,
  hasConsent,
  privacyError,
  sessionChangedError,
  clearLocalSession,
  ensureConsent,
  recordConsent,
  finishConsent,
  rejectConsent,
  revokeConsent,
  installPrivacy,
  requireWechatPrivacy,
  checkSessionPrivacy,
  markPlatformReady: () => {
    platformCheck = Promise.resolve();
  },
  sessionGeneration: () => generation,
};
