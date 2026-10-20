const widths = { thumb: 160, card: 400, detail: 1200 };

// 与后台使用相同尺寸档位；只转换本项目生成的公开对象，保留原地址供预览和分享。
function imageUrl(url, scene = "card") {
  if (
    !/^https:\/\/[^?#]+\/products\/(catalog|content|membership)\/[0-9]{8}\/[0-9a-f-]{36}\.(jpg|png|webp)$/.test(
      url,
    )
  )
    return url;
  return `${url}?x-oss-process=image/resize,w_${widths[scene]},m_lfit,limit_1/quality,q_80/format,webp`;
}
module.exports = { imageUrl };
