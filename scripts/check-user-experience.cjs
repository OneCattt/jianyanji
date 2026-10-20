const fs = require("node:fs");
const path = require("node:path");
const cp = require("node:child_process");
const vm = require("node:vm");
const root = path.resolve(__dirname, "../miniprogram");
const compiler =
  process.env.WECHAT_COMPILER_DIR ||
  "/Applications/wechatwebdevtools.app/Contents/Resources/app.asar.unpacked/node_modules/wcc-exec";
const pages = [
  "pages/refunds/index",
  "pages/seeds/index",
  "pages/post/index",
  "pages/courses/index",
  "components/content-recommendations/index",
];
const output = fs.mkdtempSync(
  path.join(require("node:os").tmpdir(), "jyj-v2-experience-"),
);
for (const p of [...pages, "custom-tab-bar/index", "pages/profile/index"])
  new vm.Script(fs.readFileSync(path.join(root, p + ".js"), "utf8"), {
    filename: p,
  });
cp.execFileSync(
  path.join(compiler, "wcc"),
  [
    "-o",
    path.join(output, "wxml.js"),
    ...pages.map((p) => p + ".wxml"),
    "pages/home/index.wxml",
  ],
  { cwd: root, stdio: "pipe" },
);
cp.execFileSync(
  path.join(compiler, "wcsc"),
  ["-lc", "-js", "-pc", "1", ...pages.map((p) => p + ".wxss")],
  { cwd: root, stdio: "pipe" },
);
const app = JSON.parse(fs.readFileSync(path.join(root, "app.json"), "utf8"));
if (app.tabBar.list.map((t) => t.text).join(",") !== "首页,商城,种草空间,我的")
  throw Error("四栏导航不符");
for (const p of app.pages)
  for (const ext of ["js", "json", "wxml"])
    if (!fs.existsSync(path.join(root, p + "." + ext)))
      throw Error("页面文件缺失：" + p + "." + ext);
console.log(
  JSON.stringify(
    {
      passed: true,
      wxml: 6,
      wxss: 5,
      js: 7,
      tabs: app.tabBar.list.map((t) => t.text),
      output,
    },
    null,
    2,
  ),
);
