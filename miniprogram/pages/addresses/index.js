const { request } = require("../../utils/api");
const { listData, loadPage } = require("../../utils/list");
const { isFresh, remember } = require("../../utils/state");
const empty = () => ({
  recipient: "",
  phone: "",
  province: "",
  city: "",
  district: "",
  detail: "",
  isDefault: false,
});
Page({
  data: {
    ...listData(),
    form: empty(),
    editing: false,
    editingId: 0,
    busy: false,
    error: "",
  },
  onLoad(q) {
    this.selecting = q.select === "1";
  },
  async onShow() {
    if (!isFresh(this) && !this.data.itemsLoading) await this.load();
  },
  async load(reset = true) {
    if (await loadPage(this, { reset, url: "/mini/addresses" })) remember(this);
  },
  onReachBottom() {
    return this.load(false);
  },
  async onPullDownRefresh() {
    try {
      await this.load();
    } finally {
      wx.stopPullDownRefresh();
    }
  },
  input(e) {
    this.setData({ ["form." + e.currentTarget.dataset.key]: e.detail.value });
  },
  region(e) {
    const [province, city, district] = e.detail.value;
    this.setData({
      "form.province": province,
      "form.city": city,
      "form.district": district,
    });
  },
  toggle(e) {
    this.setData({ "form.isDefault": e.detail.value });
  },
  openPrivacy() {
    wx.navigateTo({ url: "/pages/privacy/index" });
  },
  add() {
    this.setData({ form: empty(), editing: true, editingId: 0 });
  },
  edit(e) {
    const a = this.data.items.find(
      (a) => a.id === Number(e.currentTarget.dataset.id),
    );
    this.setData({
      form: {
        recipient: a.recipient,
        phone: a.phone,
        province: a.province,
        city: a.city,
        district: a.district,
        detail: a.detail,
        isDefault: Boolean(a.is_default),
      },
      editing: true,
      editingId: a.id,
    });
  },
  choose(e) {
    if (this.selecting) {
      wx.setStorageSync("jyj_address", Number(e.currentTarget.dataset.id));
      wx.navigateBack();
    }
  },
  async save() {
    if (this.data.busy) return;
    this.setData({ busy: true, error: "" });
    try {
      await request(
        "/addresses" + (this.data.editingId ? "/" + this.data.editingId : ""),
        this.data.editingId ? "PUT" : "POST",
        this.data.form,
      );
      this.setData({ editing: false });
      await this.load();
    } catch (e) {
      this.setData({ error: e.message });
    } finally {
      this.setData({ busy: false });
    }
  },
  async remove(e) {
    if (this.data.busy) return;
    this.setData({ busy: true });
    try {
      await request(
        "/addresses/" + e.currentTarget.dataset.id + "/remove",
        "POST",
      );
      await this.load();
    } catch (e) {
      this.setData({ error: e.message });
    } finally {
      this.setData({ busy: false });
    }
  },
});
