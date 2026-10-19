const { request } = require("../../utils/api");
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
    items: [],
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
    await this.load();
  },
  async load() {
    try {
      this.setData({ items: await request("/addresses"), error: "" });
    } catch (e) {
      this.setData({ error: e.message });
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
