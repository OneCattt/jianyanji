import { test } from "node:test";
import assert from "node:assert/strict";
import { fixture } from "./test-fixture.ts";
const content = {
  brandName: "清欣简妍肌",
  introduction: "品牌真实介绍",
  heroTitle: "让肌肤，回到\n它本来的光",
  heroSubtitle: "东方肌肤管理美学",
  heroImage: "/assets/figma/Home-imgImage.png",
  customerService: "",
  purchaseNotes: "",
  companyName: "",
  bankName: "",
  bankAccount: "",
  transferNotes: "",
  reason: "录入品牌资料",
};
const product = (categoryId: number) => ({
  categoryId,
  name: "测试精华",
  subtitle: "测试环境",
  description: "测试说明",
  image: "/assets/figma/productSerum.png",
  detailImage: "/assets/figma/productHero.png",
  active: true,
  featured: true,
  skus: [
    {
      name: "30ml",
      unit: "瓶",
      retailCents: 46800,
      storeCents: 32800,
      principalCents: 26000,
      stock: 20,
      active: true,
    },
  ],
  reason: "测试商品录入",
});
test("后台内容发布后匿名可读，未配置不伪造收款，越权写入被拦截", async (t) => {
  const f = await fixture(t);
  assert.equal((await f.call("/public/content")).data.configured, false);
  const admin = await f.admin();
  const saved = await f.call("/admin/content", {
    method: "PUT",
    cookie: admin,
    body: content,
  });
  assert.equal(saved.status, 200);
  assert.equal(
    (await f.call("/public/content")).data.content.introduction,
    content.introduction,
  );
  const operator = await f.admin("operator");
  assert.equal(
    (
      await f.call("/admin/content", {
        method: "PUT",
        cookie: operator,
        body: content,
      })
    ).status,
    403,
  );
  assert.equal(
    (
      await f.call("/admin/content", {
        method: "PUT",
        cookie: admin,
        body: { ...content, companyName: "不能只有户名" },
      })
    ).status,
    400,
  );
  assert.ok(
    f.db
      .prepare(
        "SELECT id FROM audit_events WHERE action='content.update' AND is_deleted=0",
      )
      .get(),
  );
});
test("同商品三身份使用真实档价，身份过期恢复游客价，缺价及停售不可购买", async (t) => {
  const f = await fixture(t),
    cookie = await f.admin();
  const category = (
    await f.call("/admin/categories", {
      method: "POST",
      cookie,
      body: { name: "精华", sort: 1, active: true, reason: "测试分类" },
    })
  ).data.id;
  const body = product(category);
  const r = await f.call("/admin/products", { method: "POST", cookie, body });
  assert.equal(r.status, 200);
  const id = r.data.id;
  assert.equal(
    (await f.call("/products/" + id)).data.skus[0].priceCents,
    46800,
  );
  for (const [identity, price] of [
    ["B", 26000],
    ["C", 32800],
    ["D", 46800],
  ] as const) {
    const u = await f.user("test" + identity.toLowerCase());
    f.db
      .prepare("UPDATE users SET identity=? WHERE id=?")
      .run(identity, u.user.id);
    const p = (await f.call("/products/" + id, { token: u.token })).data;
    assert.equal(p.skus[0].priceCents, price);
    assert.equal(p.skus[0].principalCents, undefined);
    f.db
      .prepare("UPDATE users SET identity_valid_until='2020-01-01' WHERE id=?")
      .run(u.user.id);
    assert.equal(
      (await f.call("/products/" + id, { token: u.token })).data.skus[0]
        .priceCents,
      46800,
    );
  }
  const adminP = (await f.call("/admin/products", { cookie })).data[0];
  const skuId = adminP.skus[0].id;
  assert.equal(
    (
      await f.call("/admin/products/" + id, {
        method: "PUT",
        cookie,
        body: {
          ...body,
          skus: [{ ...body.skus[0], id: skuId, principalCents: 40000 }],
        },
      })
    ).status,
    400,
  );
  assert.equal(
    (
      await f.call("/admin/products/" + id, {
        method: "PUT",
        cookie,
        body: {
          ...body,
          skus: [{ ...body.skus[0], id: skuId, retailCents: null }],
        },
      })
    ).status,
    200,
  );
  assert.equal((await f.call("/products/" + id)).data.skus[0].available, false);
  await f.call("/admin/products/" + id, {
    method: "PUT",
    cookie,
    body: { ...body, active: false, skus: [{ ...body.skus[0], id: skuId }] },
  });
  assert.equal((await f.call("/products/" + id)).status, 404);
  assert.equal((await f.call("/products")).data.items.length, 0);
});
test("分类停用隐藏商品，移除保留原记录；无权限账号不能维护商品", async (t) => {
  const f = await fixture(t),
    cookie = await f.admin();
  const cat = (
    await f.call("/admin/categories", {
      method: "POST",
      cookie,
      body: { name: "洁面", sort: 0, active: true, reason: "测试" },
    })
  ).data.id;
  const body = product(cat),
    id = (await f.call("/admin/products", { method: "POST", cookie, body }))
      .data.id;
  const operator = await f.admin("operator");
  assert.equal(
    (
      await f.call("/admin/products", {
        method: "POST",
        cookie: operator,
        body,
      })
    ).status,
    403,
  );
  await f.call("/admin/categories/" + cat, {
    method: "PUT",
    cookie,
    body: { name: "洁面", sort: 0, active: false, reason: "停用" },
  });
  assert.equal((await f.call("/products/" + id)).status, 404);
  await f.call("/admin/products/" + id + "/remove", {
    method: "POST",
    cookie,
    body: { reason: "移除商品" },
  });
  assert.equal(
    f.db.prepare("SELECT is_deleted FROM products WHERE id=?").get(id)
      ?.is_deleted,
    1,
  );
});
