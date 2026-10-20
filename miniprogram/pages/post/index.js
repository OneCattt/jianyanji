const { request } = require("../../utils/api");
const { imageUrl } = require("../../utils/image");
Page({
  data: { post: null, loading: true, error: "", videoError: "" },
  onLoad(options) {
    this.postId = Number(options.id);
    this.load();
  },
  async load() {
    this.setData({ loading: true, error: "", post: null, videoError: "" });
    if (!Number.isSafeInteger(this.postId) || this.postId < 1)
      return this.setData({ loading: false, error: "内容编号不合法" });
    try {
      const post = await request("/public/posts/" + this.postId);
      this.setData({
        post: {
          ...post,
          coverDisplay: imageUrl(post.cover, "detail"),
          displayImages: post.imageUrls.map((url) => ({
            url,
            displayUrl: imageUrl(url, "detail"),
          })),
          products: post.products.map((product) => ({
            ...product,
            imageThumb: imageUrl(product.image, "thumb"),
          })),
        },
      });
    } catch (e) {
      this.setData({ error: e.message });
    } finally {
      this.setData({ loading: false });
    }
  },
  preview(e) {
    wx.previewImage({
      current: e.currentTarget.dataset.url,
      urls: this.data.post.imageUrls,
    });
  },
  product(e) {
    wx.navigateTo({
      url: "/pages/product/index?id=" + e.currentTarget.dataset.id,
    });
  },
  videoError() {
    this.setData({ videoError: "视频暂时无法播放，请稍后重试或联系品牌客服" });
  },
  retryVideo() {
    this.setData({ videoError: "" });
  },
  onShareAppMessage() {
    return this.data.post
      ? {
          title: this.data.post.title,
          imageUrl: imageUrl(this.data.post.cover, "card"),
          path: "/pages/post/index?id=" + this.postId,
        }
      : { title: "清欣简妍肌种草空间", path: "/pages/seeds/index" };
  },
  onShareTimeline() {
    return this.data.post
      ? {
          title: this.data.post.title,
          imageUrl: imageUrl(this.data.post.cover, "card"),
          query: "id=" + this.postId,
        }
      : {};
  },
});
