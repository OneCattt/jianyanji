import { fixture } from "./test-fixture.ts";
import type { TestContext } from "node:test";
import assert from "node:assert/strict";
export async function commerceFixture(
  t: TestContext,
  options: Parameters<typeof fixture>[1] = {},
) {
  const f = await fixture(t, options),
    cookie = await f.admin(),
    u = await f.user();
  const cat = await f.call("/admin/categories", {
    method: "POST",
    cookie,
    body: { name: "测试商品", sort: 0, active: true, reason: "自动化测试" },
  });
  assert.equal(cat.status, 200);
  const product = await f.call("/admin/products", {
    method: "POST",
    cookie,
    body: {
      categoryId: cat.data.id,
      name: "测试精华",
      subtitle: "仅供测试",
      description: "测试商品",
      image: "/assets/figma/productSerum.png",
      detailImage: "/assets/figma/productHero.png",
      active: true,
      featured: true,
      skus: [
        {
          name: "30ml",
          unit: "瓶",
          retailCents: 10000,
          storeCents: 10000,
          principalCents: 8000,
          stock: 10,
          active: true,
        },
      ],
      reason: "自动化测试",
    },
  });
  assert.equal(product.status, 200);
  const p = (await f.call("/admin/products", { cookie })).data[0];
  const shipping = await f.call("/admin/shipping-settings", {
    method: "PUT",
    cookie,
    body: {
      provinces: ["浙江省"],
      feeCents: 1000,
      instructions: "品牌仓配送",
      pendingMinutes: 30,
      reason: "自动化测试",
    },
  });
  assert.equal(shipping.status, 200);
  await f.call("/admin/aftersales-settings", {
    method: "PUT",
    cookie,
    body: { days: 7, policy: "测试售后政策", reason: "自动化测试" },
  });
  const address = {
    recipient: "测试购买者",
    phone: "13800000000",
    province: "浙江省",
    city: "杭州市",
    district: "西湖区",
    detail: "测试地址1号",
    isDefault: true,
  };
  const a = await f.call("/addresses", {
    method: "POST",
    token: u.token,
    body: address,
  });
  assert.equal(a.status, 200);
  async function quote(quantity = 3) {
    const r = await f.call("/checkout/quote", {
      method: "POST",
      token: u.token,
      body: {
        addressId: a.data.id,
        items: [{ skuId: p.skus[0].id, quantity }],
      },
    });
    assert.equal(r.status, 200);
    return r.data;
  }
  async function order(
    quantity = 3,
    key = "order-request-" + Math.random().toString(36).slice(2),
  ) {
    const q = await quote(quantity);
    const r = await f.call("/orders", {
      method: "POST",
      token: u.token,
      body: { quoteId: q.id, requestKey: key },
    });
    assert.equal(r.status, 200);
    return r.data;
  }
  return { ...f, cookie, u, p, address, addressId: a.data.id, quote, order };
}
