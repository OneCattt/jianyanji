// 临时 IP 地址仅用于开启调试并跳过域名校验的开发联调；正式使用前恢复 HTTPS 合法域名。
module.exports = {
  apiBase: "https://api.jianyanji.cn/api",
  // 仅开发工具本地体验可手动开启，服务端也必须显式开启 DEV_AUTH。
  devAuth: false,
};
