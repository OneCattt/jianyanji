const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const ts = require("typescript");
const moduleValue = { exports: {} };
vm.runInNewContext(
  ts.transpileModule(
    fs.readFileSync(path.join(__dirname, "../web/image-crop.ts"), "utf8"),
    { compilerOptions: { module: ts.ModuleKind.CommonJS } },
  ).outputText,
  { exports: moduleValue.exports },
);
const { squareCrop, coverSize, dragPosition } = moduleValue.exports;

test("横图、竖图默认裁剪居中，放大及边缘位置不会越界", () => {
  for (const [width, height] of [
    [1600, 1000],
    [1000, 1600],
    [900, 900],
  ]) {
    const centered = squareCrop(width, height, 1, 50, 50);
    assert.equal(centered.size, Math.min(width, height));
    assert.equal(centered.x, (width - centered.size) / 2);
    assert.equal(centered.y, (height - centered.size) / 2);
    for (const zoom of [0.5, 1, 2, 4, 10]) {
      for (const pos of [-30, 0, 50, 100, 130]) {
        const crop = squareCrop(width, height, zoom, pos, pos);
        assert.ok(crop.x >= 0 && crop.y >= 0);
        assert.ok(crop.x + crop.size <= width);
        assert.ok(crop.y + crop.size <= height);
      }
    }
  }
});

test("输出封面限制像素且不放大小图；放大主体会降低可用像素", () => {
  assert.equal(coverSize(squareCrop(4000, 3000, 1, 50, 50)), 1200);
  assert.equal(coverSize(squareCrop(400, 600, 1, 50, 50)), 400);
  assert.equal(coverSize(squareCrop(1000, 1600, 2, 50, 50)), 500);
});

test("拖动方向、边缘限制及无移动空间的轴正确", () => {
  assert.equal(dragPosition(50, 60, 300, 1000, 1600), 50 - 100 / 3);
  assert.equal(dragPosition(50, -600, 300, 1000, 1600), 100);
  assert.equal(dragPosition(50, 600, 300, 1000, 1600), 0);
  assert.equal(dragPosition(50, 30, 300, 1000, 1000), 50);
});
