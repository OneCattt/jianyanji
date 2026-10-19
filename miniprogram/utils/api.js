const config = require("../config");

function request(path, method = "GET", data) {
  return new Promise((resolve, reject) => {
    const token = wx.getStorageSync("jyj_token");
    wx.request({
      url: config.apiBase + path,
      method,
      data,
      header: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: "Bearer " + token } : {}),
      },
      success(response) {
        if (response.statusCode >= 200 && response.statusCode < 300)
          return resolve(response.data);
        if (response.statusCode === 401) wx.removeStorageSync("jyj_token");
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
async function login(account = "visitor") {
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
    result = await request("/auth/wechat", "POST", { code });
  }
  wx.setStorageSync("jyj_token", result.token);
  return result.user;
}
module.exports = { request, login };
