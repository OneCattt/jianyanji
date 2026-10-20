#!/usr/bin/env bash
# 在服务器上人工止损；只操作 Nginx 开关文件，不改变订单和支付状态。
set -euo pipefail

guard_dir="${JYJ_GUARD_DIR:-/etc/nginx/jianyanji}"
action="${1:-status}"
case "$action" in
  pause-api|pause-assets)
    mkdir -p "$guard_dir"
    : > "$guard_dir/$action"
    echo "已暂停 ${action#pause-} 入口，新请求返回 503；微信支付回调仍正常处理。"
    ;;
  resume-api|resume-assets)
    rm -f "$guard_dir/pause-${action#resume-}"
    echo "已恢复 ${action#resume-} 入口。"
    ;;
  status)
    for entry in api assets; do
      if [[ -f "$guard_dir/pause-$entry" ]]; then
        echo "$entry：已暂停"
      else
        echo "$entry：未暂停"
      fi
    done
    ;;
  *)
    echo "用法：$0 {status|pause-api|resume-api|pause-assets|resume-assets}" >&2
    exit 2
    ;;
esac
echo "仅影响已安装配套配置的 Nginx；OSS 直连访问须在 OSS 控制台单独处理。"
