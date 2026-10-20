const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

// 执行真实共用模块，只替换宿主及网络，确保分页和缓存逻辑也进入回归。
function createRequire(entry, context, request) {
  const modules = new Map();
  function load(filename) {
    if (filename.endsWith("/utils/api.js")) return { request };
    if (modules.has(filename)) return modules.get(filename).exports;
    const module = { exports: {} };
    modules.set(filename, module);
    vm.runInNewContext(
      fs.readFileSync(filename, "utf8"),
      {
        ...context,
        module,
        exports: module.exports,
        require: (name) =>
          load(path.resolve(path.dirname(filename), name + ".js")),
      },
      { filename },
    );
    return module.exports;
  }
  return (name) => load(path.resolve(path.dirname(entry), name + ".js"));
}

function setData(values) {
  for (const [key, value] of Object.entries(values)) {
    const parts = key.replace(/\[(\d+)\]/g, ".$1").split(".");
    let target = this.data;
    for (const part of parts.slice(0, -1)) target = target[part];
    target[parts.at(-1)] = value;
  }
}

module.exports = { createRequire, setData };
