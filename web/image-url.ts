const widths = { thumb: 160, card: 400, detail: 1200 } as const;

/** 仅处理本项目生成的公开对象地址；本地、外部及带查询参数的图片沿用原地址。 */
export function imageUrl(url: string, scene: keyof typeof widths = "card") {
  if (
    !/^https:\/\/[^?#]+\/products\/(catalog|content|membership)\/[0-9]{8}\/[0-9a-f-]{36}\.(jpg|png|webp)$/.test(
      url,
    )
  )
    return url;
  return `${url}?x-oss-process=image/resize,w_${widths[scene]},m_lfit,limit_1/quality,q_80/format,webp`;
}
