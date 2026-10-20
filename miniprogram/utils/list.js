const { request } = require("./api");
const { session } = require("./state");

function listData(key = "items") {
  return {
    [key]: [],
    [key + "Loading"]: false,
    [key + "Loaded"]: false,
    [key + "NextPage"]: 1,
    [key + "HasMore"]: true,
  };
}

function listValues(page, key, items, reset) {
  if (reset) return { [key]: items };
  const itemKey = (item) =>
    item.eventKey || item.application_id || item.id || item.skuId;
  const seen = new Set(page.data[key].map(itemKey));
  const values = {};
  items
    .filter((item) => !seen.has(itemKey(item)))
    .forEach((item, index) => {
      values[key + "[" + (page.data[key].length + index) + "]"] = item;
    });
  return values;
}

// 新筛选可以替换正在读取的旧筛选；旧响应不能覆盖新列表，失败不推进页码。
async function loadPage(page, options) {
  const {
    url,
    key = "items",
    reset = true,
    map = (item) => item,
    extra,
  } = options;
  const requests = page._listRequests || (page._listRequests = {});
  if (!reset && (page.data[key + "Loading"] || !page.data[key + "NextPage"]))
    return false;
  const generation = (requests[key] || 0) + 1;
  requests[key] = generation;
  const identity = session();
  const nextPage = reset ? 1 : page.data[key + "NextPage"];
  const sessions = page._listSessions || (page._listSessions = {});
  const changedUser = sessions[key] !== undefined && sessions[key] !== identity;
  sessions[key] = identity;
  page.setData({
    [key + "Loading"]: true,
    error: "",
    ...(changedUser ? { [key]: [] } : {}),
  });
  try {
    const result = await request(
      url + (url.includes("?") ? "&" : "?") + "limit=20&page=" + nextPage,
    );
    if (requests[key] !== generation || session() !== identity) return false;
    // 只把新增行发往视图层，避免每次滚动重新传输已经显示的全部列表。
    const values = listValues(page, key, result.items.map(map), reset);
    page.setData({
      ...values,
      [key + "NextPage"]: result.nextPage,
      [key + "HasMore"]: result.hasMore,
      [key + "Loaded"]: true,
      ...(extra ? extra(result) : {}),
    });
    return true;
  } catch (error) {
    if (requests[key] === generation) {
      page.setData({
        error: error.message,
        ...(error.status === 401 ? { [key]: [], [key + "Loaded"]: false } : {}),
      });
    }
    return false;
  } finally {
    if (requests[key] === generation)
      page.setData({ [key + "Loading"]: false });
  }
}

module.exports = { listData, loadPage, listValues };
