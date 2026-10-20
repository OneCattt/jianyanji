// 微信公众平台的 request 合法域名需配置为 https://api.jianyanji.cn；此处不放 AppSecret。
module.exports = {
  apiBase: "https://api.jianyanji.cn/api",
  // 仅开发工具本地体验可手动开启，服务端也必须显式开启 DEV_AUTH。
  devAuth: false,
};
