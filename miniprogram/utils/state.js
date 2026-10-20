let revision = 0;
const freshFor = 60000;

function session() {
  return wx.getStorageSync("jyj_token") || "";
}
function invalidate() {
  revision += 1;
}
function isFresh(page) {
  return (
    page._loadedSession === session() &&
    page._loadedRevision === revision &&
    Date.now() - page._loadedAt < freshFor
  );
}
function remember(page) {
  page._loadedSession = session();
  page._loadedRevision = revision;
  page._loadedAt = Date.now();
}

module.exports = { session, invalidate, isFresh, remember };
