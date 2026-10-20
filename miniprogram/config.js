// 真机使用已配置 request 合法域名的 HTTPS 地址；此处不放 AppSecret。
module.exports = {
  apiBase: "http://127.0.0.1:3100/api",
  // 仅开发工具本地体验可手动开启，服务端也必须显式开启 DEV_AUTH。
  devAuth: true,
};
