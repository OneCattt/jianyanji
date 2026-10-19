import { test } from "node:test";
import assert from "node:assert/strict";
import { commerceFixture } from "./commerce-fixture.ts";
import {
  finishRefund,
  getRefund,
  type RefundChannel,
  type RefundResult,
} from "./aftersales.ts";
import { transaction } from "./db.ts";
async function paid(t: Parameters<typeof commerceFixture>[0]) {
  const f = await commerceFixture(t),
    order = await f.order(3);
  f.db
    .prepare(
      "UPDATE orders SET payment_state='paid',payment_method='wechat',status='paid' WHERE id=?",
    )
    .run(order.id);
  f.db
    .prepare("UPDATE skus SET stock=stock-3,reserved=reserved-3 WHERE id=?")
    .run(f.p.skus[0].id);
  return { ...f, order };
}
async function refund(
  f: Awaited<ReturnType<typeof paid>>,
  quantity: number,
  key = "refund-request-" + Math.random().toString(36).slice(2),
) {
  return f.call("/admin/refunds", {
    method: "POST",
    cookie: f.cookie,
    body: {
      orderId: f.order.id,
      items: [{ lineId: f.order.lines[0].id, quantity }],
      shippingCents: 0,
      reason: "客服受理退货",
      requestKey: key,
    },
  });
}
test("部分退款受理即占用可退数量，重复请求幂等，并发不能超退，驳回释放", async (t) => {
  const f = await paid(t),
    key = "same-refund-request-2026";
  const [a, b] = await Promise.all([refund(f, 2, key), refund(f, 2, key)]);
  assert.equal(a.status, 200);
  assert.equal(a.data.id, b.data.id);
  assert.equal(a.data.goods_cents, 20000);
  assert.equal((await refund(f, 2)).status, 409);
  const line = f.db
    .prepare("SELECT * FROM order_lines WHERE id=?")
    .get(f.order.lines[0].id)!;
  assert.equal(line.refund_held, 2);
  assert.equal(line.held_unshipped, 2);
  assert.equal(
    Number(line.quantity) -
      Number(line.shipped_qty) -
      Number(line.refunded_unshipped) -
      Number(line.held_unshipped),
    1,
  );
  await f.call("/admin/refunds/" + a.data.id + "/review", {
    method: "POST",
    cookie: f.cookie,
    body: { approve: false, identityDecision: null, reason: "用户撤回申请" },
  });
  assert.equal(
    f.db
      .prepare("SELECT refund_held FROM order_lines WHERE id=?")
      .get(f.order.lines[0].id)?.refund_held,
    0,
  );
  assert.equal((await refund(f, 3)).status, 200);
});
test("退款按历史价格且不自动回库存，渠道完成才增加已退数量，重复成功不重复退款", async (t) => {
  const f = await paid(t);
  f.db
    .prepare("UPDATE skus SET retail_cents=12000 WHERE id=?")
    .run(f.p.skus[0].id);
  const r = await refund(f, 1);
  assert.equal(r.data.total_cents, 10000);
  await f.call("/admin/refunds/" + r.data.id + "/review", {
    method: "POST",
    cookie: f.cookie,
    body: { approve: true, identityDecision: null, reason: "同意退款" },
  });
  let reverseCalls = 0;
  const hooks = {
    reverseRewards: () => {
      reverseCalls++;
    },
  };
  transaction(f.db, () => {
    finishRefund(
      f.db,
      getRefund(f.db, r.data.id),
      hooks,
      f.manager,
      "test-refund",
    );
    finishRefund(
      f.db,
      getRefund(f.db, r.data.id),
      hooks,
      f.manager,
      "test-refund",
    );
  });
  assert.equal(reverseCalls, 1);
  assert.equal(
    f.db
      .prepare("SELECT refunded_qty FROM order_lines WHERE id=?")
      .get(f.order.lines[0].id)?.refunded_qty,
    1,
  );
  assert.equal(
    f.db.prepare("SELECT stock FROM skus WHERE id=?").get(f.p.skus[0].id)
      ?.stock,
    7,
  );
  assert.equal(
    (await f.call("/refunds", { token: f.u.token })).data[0].status,
    "success",
  );
  const other = await f.user("other-refund");
  assert.equal(
    (await f.call("/refunds", { token: other.token })).data.length,
    0,
  );
});
test("已发货退款先验实际退货，验货入库仅一次且剩余发货量不重复扣减", async (t) => {
  const f = await paid(t);
  f.db
    .prepare("UPDATE order_lines SET shipped_qty=2,received_qty=2 WHERE id=?")
    .run(f.order.lines[0].id);
  const r = await refund(f, 2);
  assert.equal(r.data.lines[0].unshippedQuantity, 1);
  assert.equal(r.data.lines[0].returnQuantity, 1);
  await f.call("/admin/refunds/" + r.data.id + "/review", {
    method: "POST",
    cookie: f.cookie,
    body: { approve: true, identityDecision: null, reason: "同意退货" },
  });
  assert.throws(
    () =>
      transaction(f.db, () =>
        finishRefund(f.db, getRefund(f.db, r.data.id), {}, f.manager, "test"),
      ),
    /实际退货/,
  );
  const inspect = {
    returnedAt: new Date(Date.now() - 1000).toISOString(),
    inspection: "实际收到1瓶，外包装完好可售",
    restock: true,
    reason: "验货入库",
  };
  assert.equal(
    (
      await f.call("/admin/refunds/" + r.data.id + "/inspect", {
        method: "POST",
        cookie: f.cookie,
        body: inspect,
      })
    ).status,
    200,
  );
  assert.equal(
    (
      await f.call("/admin/refunds/" + r.data.id + "/inspect", {
        method: "POST",
        cookie: f.cookie,
        body: inspect,
      })
    ).status,
    409,
  );
  assert.equal(
    f.db.prepare("SELECT stock FROM skus WHERE id=?").get(f.p.skus[0].id)
      ?.stock,
    8,
  );
  transaction(f.db, () =>
    finishRefund(f.db, getRefund(f.db, r.data.id), {}, f.manager, "test"),
  );
  const line = f.db
    .prepare("SELECT * FROM order_lines WHERE id=?")
    .get(f.order.lines[0].id)!;
  assert.equal(line.refunded_qty, 2);
  assert.equal(line.refunded_unshipped, 1);
  assert.equal(
    Number(line.quantity) -
      Number(line.shipped_qty) -
      Number(line.refunded_unshipped) -
      Number(line.held_unshipped),
    0,
  );
});
test("售后使用原政策，超过原期限、金额及无权限受理被拦截", async (t) => {
  const f = await paid(t);
  f.db
    .prepare("UPDATE orders SET received_at=? WHERE id=?")
    .run(new Date(Date.now() - 8 * 86400000).toISOString(), f.order.id);
  assert.equal((await refund(f, 1)).status, 409);
  f.db.prepare("UPDATE orders SET received_at=NULL WHERE id=?").run(f.order.id);
  assert.equal(
    (
      await f.call("/admin/refunds", {
        method: "POST",
        cookie: f.cookie,
        body: {
          orderId: f.order.id,
          items: [{ lineId: f.order.lines[0].id, quantity: 1 }],
          shippingCents: 2000,
          reason: "超退运费",
          requestKey: "shipping-too-much-2026",
        },
      })
    ).status,
    409,
  );
  const operator = await f.admin("operator");
  assert.equal(
    (
      await f.call("/admin/refunds", {
        method: "POST",
        cookie: operator,
        body: {},
      })
    ).status,
    403,
  );
});

test("微信退款网络未知后只查询原单，查实后完成且重复核对不重复记账", async (t) => {
  let submitted = 0,
    queried = 0,
    number = "";
  const channel = {
    refund: async (v: any) => {
      submitted++;
      number = v.outRefundNo;
      throw new Error("测试渠道超时");
    },
    queryRefund: async (n: string) => {
      queried++;
      return {
        status: "success" as const,
        outRefundNo: n,
        outTradeNo: "original-wechat-trade",
        totalCents: 31000,
        refundCents: 10000,
        refundId: "wechat-refund-id",
      };
    },
  };
  const f = await commerceFixture(t, { refunds: { channel } }),
    order = await f.order(3);
  f.db
    .prepare(
      "UPDATE orders SET payment_state='paid',payment_method='wechat',status='paid' WHERE id=?",
    )
    .run(order.id);
  f.db
    .prepare("UPDATE skus SET stock=stock-3,reserved=reserved-3 WHERE id=?")
    .run(f.p.skus[0].id);
  f.db
    .prepare(
      "INSERT INTO payments(order_id,method,transaction_id,out_trade_no,cents,paid_at) VALUES(?,'wechat','original-transaction','original-wechat-trade',31000,?)",
    )
    .run(order.id, new Date().toISOString());
  const r = await f.call("/admin/refunds", {
    method: "POST",
    cookie: f.cookie,
    body: {
      orderId: order.id,
      items: [{ lineId: order.lines[0].id, quantity: 1 }],
      shippingCents: 0,
      reason: "微信部分退款",
      requestKey: "wechat-refund-request-2026",
    },
  });
  assert.equal(r.status, 200);
  await f.call("/admin/refunds/" + r.data.id + "/review", {
    method: "POST",
    cookie: f.cookie,
    body: { approve: true, identityDecision: null, reason: "审核通过" },
  });
  const url = "/admin/refunds/" + r.data.id;
  assert.equal(
    (
      await f.call(url + "/wechat", {
        method: "POST",
        cookie: f.cookie,
        body: { reason: "提交原路退款" },
      })
    ).data.status,
    "unknown",
  );
  assert.equal(
    (
      await f.call(url + "/wechat", {
        method: "POST",
        cookie: f.cookie,
        body: { reason: "重复提交" },
      })
    ).status,
    409,
  );
  assert.equal(
    f.db
      .prepare("SELECT refund_held FROM order_lines WHERE id=?")
      .get(order.lines[0].id)?.refund_held,
    1,
  );
  assert.equal(
    (
      await f.call(url + "/query", {
        method: "POST",
        cookie: f.cookie,
        body: { reason: "核实原退款单" },
      })
    ).data.status,
    "success",
  );
  assert.equal(
    (
      await f.call(url + "/query", {
        method: "POST",
        cookie: f.cookie,
        body: { reason: "重复核实" },
      })
    ).data.status,
    "success",
  );
  assert.equal(submitted, 1);
  assert.equal(queried, 1);
  assert.equal(number, r.data.number);
  assert.equal(
    f.db
      .prepare("SELECT refunded_qty FROM order_lines WHERE id=?")
      .get(order.lines[0].id)?.refunded_qty,
    1,
  );
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
async function wechatRefund(
  t: Parameters<typeof commerceFixture>[0],
  channel: RefundChannel,
) {
  const f = await commerceFixture(t, { refunds: { channel } }),
    order = await f.order(3);
  f.db
    .prepare(
      "UPDATE orders SET payment_state='paid',payment_method='wechat',status='paid' WHERE id=?",
    )
    .run(order.id);
  f.db
    .prepare("UPDATE skus SET stock=stock-3,reserved=reserved-3 WHERE id=?")
    .run(f.p.skus[0].id);
  f.db
    .prepare(
      "INSERT INTO payments(order_id,method,transaction_id,out_trade_no,cents,paid_at) VALUES(?,'wechat','original-transaction','original-wechat-trade',31000,?)",
    )
    .run(order.id, new Date().toISOString());
  const r = await refund({ ...f, order }, 1);
  assert.equal(r.status, 200);
  assert.equal(
    (
      await f.call("/admin/refunds/" + r.data.id + "/review", {
        method: "POST",
        cookie: f.cookie,
        body: { approve: true, identityDecision: null, reason: "核实原路退款" },
      })
    ).status,
    200,
  );
  const send = (action: string) =>
    f.call("/admin/refunds/" + r.data.id + "/" + action, {
      method: "POST",
      cookie: f.cookie,
      body: { reason: "核对原渠道退款" },
    });
  const result = (status: RefundResult["status"]): RefundResult => ({
    status,
    outRefundNo: r.data.number,
    outTradeNo: "original-wechat-trade",
    totalCents: 31000,
    refundCents: 10000,
    refundId: "verified-refund",
  });
  return { ...f, order, r: r.data, send, result };
}

test("微信退款发起和查询互斥，渠道调用期间不能重复提交或查询", async (t) => {
  const submitted = deferred<void>(),
    queried = deferred<void>(),
    submitResult = deferred<RefundResult>(),
    queryResult = deferred<RefundResult>();
  let submits = 0,
    queries = 0,
    submitDone = false;
  const f = await wechatRefund(t, {
    refund: async (input) => {
      submits++;
      assert.equal(input.transactionId, "original-transaction");
      assert.equal(input.outTradeNo, "original-wechat-trade");
      assert.equal(input.totalCents, 31000);
      submitted.resolve();
      return submitResult.promise;
    },
    queryRefund: async () => {
      queries++;
      if (!submitDone) return f.result("failed");
      queried.resolve();
      return queryResult.promise;
    },
  });
  const pending = f.send("wechat");
  await submitted.promise;
  try {
    assert.equal((await f.send("wechat")).status, 409);
    assert.equal((await f.send("query")).status, 409);
  } finally {
    submitResult.resolve(f.result("processing"));
    await pending;
    submitDone = true;
  }
  const query = f.send("query");
  await queried.promise;
  try {
    assert.equal((await f.send("query")).status, 409);
    assert.equal((await f.send("wechat")).status, 409);
  } finally {
    queryResult.resolve(f.result("success"));
    await query;
  }
  assert.equal(submits, 1);
  assert.equal(queries, 1);
  assert.equal(getRefund(f.db, f.r.id).status, "success");
  const line = f.db
    .prepare("SELECT * FROM order_lines WHERE id=?")
    .get(f.order.lines[0].id)!;
  assert.equal(line.refunded_qty, 1);
  assert.equal(line.refund_held, 0);
  assert.equal(
    f.db.prepare("SELECT stock FROM skus WHERE id=?").get(f.p.skus[0].id)
      ?.stock,
    7,
  );
});

test("原微信支付金额或流水缺失时禁止请求渠道并保留退款占用", async (t) => {
  let calls = 0;
  const f = await wechatRefund(t, {
    refund: async () => {
      calls++;
      return f.result("success");
    },
    queryRefund: async () => {
      calls++;
      return f.result("success");
    },
  });
  for (const payment of [
    {
      cents: 20000,
      trade: "original-wechat-trade",
      transaction: "original-transaction",
    },
    { cents: 31000, trade: null, transaction: "original-transaction" },
    { cents: 31000, trade: "original-wechat-trade", transaction: "" },
  ]) {
    f.db
      .prepare(
        "UPDATE payments SET cents=?,out_trade_no=?,transaction_id=? WHERE order_id=?",
      )
      .run(payment.cents, payment.trade, payment.transaction, f.order.id);
    assert.equal((await f.send("wechat")).status, 409);
    assert.equal((await f.send("query")).status, 409);
  }
  assert.equal(calls, 0);
  assert.equal(getRefund(f.db, f.r.id).status, "approved");
  assert.equal(
    f.db
      .prepare("SELECT refund_held FROM order_lines WHERE id=?")
      .get(f.order.lines[0].id)?.refund_held,
    1,
  );
});

test("退款租约到期可查询原单，旧响应不能覆盖新结果或释放新租约", async (t) => {
  const submitted = deferred<void>(),
    queried = deferred<void>(),
    submitResult = deferred<RefundResult>(),
    queryResult = deferred<RefundResult>();
  const f = await wechatRefund(t, {
    refund: async () => {
      submitted.resolve();
      return submitResult.promise;
    },
    queryRefund: async () => {
      queried.resolve();
      return queryResult.promise;
    },
  });
  const pending = f.send("wechat");
  await submitted.promise;
  const originalToken = getRefund(f.db, f.r.id).channel_token;
  f.db
    .prepare("UPDATE refunds SET channel_until=? WHERE id=?")
    .run(new Date(Date.now() - 1000).toISOString(), f.r.id);
  assert.equal((await f.send("wechat")).status, 409);
  const query = f.send("query");
  await queried.promise;
  try {
    submitResult.resolve(f.result("failed"));
    await pending;
    const current = getRefund(f.db, f.r.id);
    assert.equal(current.status, "processing");
    assert.ok(current.channel_token);
    assert.notEqual(current.channel_token, originalToken);
    assert.equal((await f.send("query")).status, 409);
  } finally {
    submitResult.resolve(f.result("failed"));
    queryResult.resolve(f.result("success"));
    await Promise.all([pending, query]);
  }
  assert.equal(getRefund(f.db, f.r.id).status, "success");
  assert.equal(getRefund(f.db, f.r.id).channel_token, null);
  assert.equal(
    f.db
      .prepare("SELECT refunded_qty FROM order_lines WHERE id=?")
      .get(f.order.lines[0].id)?.refunded_qty,
    1,
  );
});

test("渠道对象或金额不匹配只能保留未知，核对原单成功后才记一次退款", async (t) => {
  const f = await wechatRefund(t, {
    refund: async () => ({
      ...f.result("success"),
      outTradeNo: "other-payment",
    }),
    queryRefund: async () => f.result("success"),
  });
  assert.equal((await f.send("wechat")).data.status, "unknown");
  assert.equal((await f.send("wechat")).status, 409);
  assert.equal(
    f.db
      .prepare("SELECT refunded_qty FROM order_lines WHERE id=?")
      .get(f.order.lines[0].id)?.refunded_qty,
    0,
  );
  assert.equal(
    f.db
      .prepare("SELECT refund_held FROM order_lines WHERE id=?")
      .get(f.order.lines[0].id)?.refund_held,
    1,
  );
  assert.equal((await f.send("query")).data.status, "success");
  assert.equal((await f.send("query")).data.status, "success");
  assert.equal(
    f.db
      .prepare("SELECT refunded_qty FROM order_lines WHERE id=?")
      .get(f.order.lines[0].id)?.refunded_qty,
    1,
  );
});

test("商品与运费累计退款不超过原支付金额，超额失败回滚数量占用", async (t) => {
  const f = await paid(t),
    first = await refund(f, 2);
  assert.equal(first.status, 200);
  const body = {
    orderId: f.order.id,
    items: [{ lineId: f.order.lines[0].id, quantity: 1 }],
    shippingCents: 1001,
    reason: "剩余商品与运费退款",
    requestKey: "refund-goods-shipping-cap",
  };
  assert.equal(
    (await f.call("/admin/refunds", { method: "POST", cookie: f.cookie, body }))
      .status,
    409,
  );
  assert.equal(
    f.db
      .prepare("SELECT refund_held FROM order_lines WHERE id=?")
      .get(f.order.lines[0].id)?.refund_held,
    2,
  );
  const last = await f.call("/admin/refunds", {
    method: "POST",
    cookie: f.cookie,
    body: { ...body, shippingCents: 1000 },
  });
  assert.equal(last.status, 200);
  assert.equal(first.data.total_cents + last.data.total_cents, 31000);
  assert.equal(
    (
      await f.call("/admin/refunds", {
        method: "POST",
        cookie: f.cookie,
        body: {
          ...body,
          items: [],
          shippingCents: 1,
          requestKey: "refund-excess-shipping",
        },
      })
    ).status,
    409,
  );
});

test("超期售后普通受理拒绝，品牌方须财务权限及特批依据并保留原政策", async (t) => {
  const f = await paid(t);
  f.db
    .prepare("UPDATE orders SET received_at=? WHERE id=?")
    .run(new Date(Date.now() - 9 * 86400000).toISOString(), f.order.id);
  const body = {
    orderId: f.order.id,
    items: [{ lineId: f.order.lines[0].id, quantity: 1 }],
    shippingCents: 0,
    reason: "奖励已结算后品牌方同意售后",
    requestKey: "exception-refund-after-settlement",
  };
  assert.equal(
    (await f.call("/admin/refunds", { method: "POST", cookie: f.cookie, body }))
      .status,
    409,
  );
  f.db
    .prepare("UPDATE staff SET permissions=? WHERE id=?")
    .run(JSON.stringify(["orders:manage"]), f.operator);
  const operator = await f.admin("operator");
  const approved = {
    ...body,
    exceptionReason: "品牌方核实特殊质量原因，同意超期退货",
  };
  assert.equal(
    (
      await f.call("/admin/refunds", {
        method: "POST",
        cookie: operator,
        body: approved,
      })
    ).status,
    403,
  );
  assert.equal(
    (
      await f.call("/admin/refunds", {
        method: "POST",
        cookie: f.cookie,
        body: { ...approved, exceptionReason: "" },
      })
    ).status,
    400,
  );
  assert.equal(
    f.db
      .prepare("SELECT refund_held FROM order_lines WHERE id=?")
      .get(f.order.lines[0].id)?.refund_held,
    0,
  );
  const r = await f.call("/admin/refunds", {
    method: "POST",
    cookie: f.cookie,
    body: approved,
  });
  assert.equal(r.status, 200);
  assert.equal(r.data.policy.days, 7);
  assert.equal(r.data.policy.policy, "测试售后政策");
  assert.equal(r.data.policy.exceptionReason, approved.exceptionReason);
  assert.equal(
    (
      await f.call("/admin/refunds", {
        method: "POST",
        cookie: f.cookie,
        body: { ...approved, exceptionReason: "不同特批依据" },
      })
    ).status,
    409,
  );
  assert.equal(
    f.db
      .prepare("SELECT refund_held FROM order_lines WHERE id=?")
      .get(f.order.lines[0].id)?.refund_held,
    1,
  );
  const audit = f.db
    .prepare(
      "SELECT reason FROM audit_events WHERE action='refund.request' AND object_id=? ORDER BY id DESC",
    )
    .get(String(r.data.id));
  assert.match(String(audit?.reason), /品牌方特批.*特殊质量/);
});
