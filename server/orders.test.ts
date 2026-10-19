import { test } from "node:test";
import assert from "node:assert/strict";
import { commerceFixture } from "./commerce-fixture.ts";
test("订单按服务端核价、运费和地址快照成交，待付款占库存不扣款，重试不重复建单", async (t) => {
  const f = await commerceFixture(t);
  const q = await f.quote(3);
  assert.equal(q.totalCents, 31000);
  const input = { quoteId: q.id, requestKey: "same-request-2026" };
  const [a, b] = await Promise.all([
    f.call("/orders", { method: "POST", token: f.u.token, body: input }),
    f.call("/orders", { method: "POST", token: f.u.token, body: input }),
  ]);
  assert.equal(a.status, 200);
  assert.equal(a.data.id, b.data.id);
  assert.equal(a.data.payment_state, "unpaid");
  assert.equal(
    f.db.prepare("SELECT reserved FROM skus WHERE id=?").get(f.p.skus[0].id)
      ?.reserved,
    3,
  );
  await f.call("/addresses/" + f.addressId, {
    method: "PUT",
    token: f.u.token,
    body: { ...f.address, detail: "新地址" },
  });
  const old = (await f.call("/orders/" + a.data.id, { token: f.u.token })).data;
  assert.equal(old.snapshot.address.detail, "测试地址1号");
  assert.equal(old.snapshot.lines[0].unitCents, 10000);
  const stranger = await f.user("stranger");
  assert.equal(
    (await f.call("/orders/" + a.data.id, { token: stranger.token })).status,
    404,
  );
});
test("报价后改价须重新确认，库存并发不超占，取消释放且不可重扣", async (t) => {
  const f = await commerceFixture(t);
  const q = await f.quote(6);
  f.db
    .prepare("UPDATE skus SET retail_cents=9000 WHERE id=?")
    .run(f.p.skus[0].id);
  assert.equal(
    (
      await f.call("/orders", {
        method: "POST",
        token: f.u.token,
        body: { quoteId: q.id, requestKey: "changed-price-2026" },
      })
    ).data.code,
    "QUOTE_CHANGED",
  );
  const q1 = await f.quote(6),
    q2 = await f.quote(6);
  const results = await Promise.all([
    f.call("/orders", {
      method: "POST",
      token: f.u.token,
      body: { quoteId: q1.id, requestKey: "concurrent-one-2026" },
    }),
    f.call("/orders", {
      method: "POST",
      token: f.u.token,
      body: { quoteId: q2.id, requestKey: "concurrent-two-2026" },
    }),
  ]);
  assert.equal(results.filter((r) => r.status === 200).length, 1);
  const success = results.find((r) => r.status === 200)!;
  await f.call("/orders/" + success.data.id + "/cancel", {
    method: "POST",
    token: f.u.token,
  });
  await f.call("/orders/" + success.data.id + "/cancel", {
    method: "POST",
    token: f.u.token,
  });
  assert.equal(
    f.db.prepare("SELECT reserved FROM skus WHERE id=?").get(f.p.skus[0].id)
      ?.reserved,
    0,
  );
});
test("配送范围及地址归属由服务端校验，默认地址互斥，购物袋移除后可再添加", async (t) => {
  const f = await commerceFixture(t);
  const other = await f.user("other");
  assert.equal(
    (
      await f.call("/addresses/" + f.addressId, {
        method: "PUT",
        token: other.token,
        body: f.address,
      })
    ).status,
    404,
  );
  const a = await f.call("/addresses", {
    method: "POST",
    token: f.u.token,
    body: { ...f.address, province: "广东省" },
  });
  assert.equal(
    (
      await f.call("/checkout/quote", {
        method: "POST",
        token: f.u.token,
        body: {
          addressId: a.data.id,
          items: [{ skuId: f.p.skus[0].id, quantity: 1 }],
        },
      })
    ).status,
    409,
  );
  assert.equal(
    f.db
      .prepare(
        "SELECT count(*) AS n FROM addresses WHERE user_id=? AND is_default=1 AND is_deleted=0",
      )
      .get(f.u.user.id)?.n,
    1,
  );
  for (let i = 0; i < 2; i++) {
    assert.equal(
      (
        await f.call("/cart/" + f.p.skus[0].id, {
          method: "PUT",
          token: f.u.token,
          body: { quantity: 2, selected: true },
        })
      ).status,
      200,
    );
    assert.equal(
      (await f.call("/cart", { token: f.u.token })).data.items.length,
      1,
    );
    await f.call("/cart/" + f.p.skus[0].id + "/remove", {
      method: "POST",
      token: f.u.token,
    });
  }
  assert.equal(
    (await f.call("/cart", { token: f.u.token })).data.items.length,
    0,
  );
});
test("店主订单保存当时主理人单价和归属，角色后来变化不回写历史", async (t) => {
  const f = await commerceFixture(t);
  const b = await f.user("owner");
  f.db.prepare("UPDATE users SET identity='B' WHERE id=?").run(b.user.id);
  f.db
    .prepare("UPDATE users SET identity='C',principal_id=? WHERE id=?")
    .run(b.user.id, f.u.user.id);
  const o = await f.order();
  assert.equal(o.snapshot.identity, "C");
  assert.equal(o.snapshot.principalId, b.user.id);
  assert.equal(o.snapshot.lines[0].principalCents, 8000);
  f.db
    .prepare("UPDATE skus SET principal_cents=7000 WHERE id=?")
    .run(f.p.skus[0].id);
  assert.equal(
    (await f.call("/orders/" + o.id, { token: f.u.token })).data.snapshot
      .lines[0].principalCents,
    8000,
  );
});
