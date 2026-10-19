const fs = require("node:fs");
const path = require("node:path");
const cp = require("node:child_process");
const vm = require("node:vm");
// 使用微信开发者工具自带编译器，仅检查源码，不修改开发者工具配置。
const root = path.resolve(__dirname, "../miniprogram");
const compiler =
  process.env.WECHAT_COMPILER_DIR ||
  "/Applications/wechatwebdevtools.app/Contents/Resources/app.asar.unpacked/node_modules/wcc-exec";
const output = fs.mkdtempSync(
  path.join(require("node:os").tmpdir(), "jianyanji-native-"),
);
fs.mkdirSync(output, { recursive: true });
function walk(dir) {
  return fs
    .readdirSync(dir, { withFileTypes: true })
    .flatMap((e) =>
      e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)],
    );
}
const files = walk(root).map((f) => path.relative(root, f));
const templates = files.filter((f) => f.endsWith(".wxml"));
const styles = files.filter((f) => f.endsWith(".wxss"));
const scripts = files.filter((f) => f.endsWith(".js"));
const configs = files.filter((f) => f.endsWith(".json"));
const commands = [];
const failures = [];
const warnings = [];
function run(exe, args) {
  commands.push([exe, ...args]);
  return cp.execFileSync(exe, args, {
    cwd: root,
    encoding: "utf8",
    maxBuffer: 32 * 1024 * 1024,
  });
}
for (const file of configs)
  JSON.parse(fs.readFileSync(path.join(root, file), "utf8"));
for (const file of scripts)
  new vm.Script(fs.readFileSync(path.join(root, file), "utf8"), {
    filename: file,
  });
try {
  run(path.join(compiler, "wcc"), [
    "-o",
    path.join(output, "all-wxml.js"),
    ...templates,
  ]);
} catch (error) {
  for (const file of templates) {
    try {
      run(path.join(compiler, "wcc"), [
        "-o",
        path.join(output, file.replaceAll("/", "_") + ".js"),
        file,
      ]);
    } catch (e) {
      failures.push({
        kind: "wxml",
        file,
        error: e.stderr?.trim() || e.message,
      });
    }
  }
}
for (const file of styles) {
  try {
    const deps = styles.filter((f) => f !== file);
    const css = run(path.join(compiler, "wcsc"), [
      "-lc",
      "-js",
      "-pc",
      "1",
      file,
      ...deps,
    ]);
    fs.writeFileSync(path.join(output, file.replaceAll("/", "_") + ".js"), css);
  } catch (error) {
    failures.push({
      kind: "wxss",
      file,
      error: error.stderr?.trim() || error.message,
    });
  }
}
const app = JSON.parse(fs.readFileSync(path.join(root, "app.json"), "utf8"));
const registered = new Set(app.pages);
const missingFiles = [];
for (const page of app.pages)
  for (const ext of ["js", "wxml"])
    if (!fs.existsSync(path.join(root, page + "." + ext)))
      missingFiles.push(page + "." + ext);
for (const tab of app.tabBar.list)
  if (!registered.has(tab.pagePath))
    throw Error("tab 页面未注册：" + tab.pagePath);
const pageTemplates = templates.filter((f) => f.startsWith("pages/"));
const unregisteredPages = pageTemplates
  .map((f) => f.slice(0, -5))
  .filter((f) => !registered.has(f));
if (missingFiles.length)
  throw Error("已注册页面缺少文件：" + missingFiles.join(", "));
const report = {
  passed: failures.length === 0,
  failures,
  warnings,
  wxml: templates.length,
  wxss: styles.length,
  js: scripts.length,
  json: configs.length,
  registeredPages: app.pages.length,
  unregisteredPages,
  missingFiles,
  commands,
  output,
};
fs.writeFileSync(
  path.join(output, "report.json"),
  JSON.stringify(report, null, 2),
);
fs.writeFileSync(
  path.join(output, "commands.txt"),
  commands
    .map((args) => args.map((a) => JSON.stringify(a)).join(" "))
    .join("\n") + "\n",
);
console.log(
  JSON.stringify(
    { ...report, commands: path.join(output, "commands.txt") },
    null,
    2,
  ),
);

if (failures.length) process.exitCode = 1;
