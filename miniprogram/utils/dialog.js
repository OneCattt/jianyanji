// 页面统一使用品牌弹层，确认结果由用户操作或页面离开时返回。
function showDialog(page, options) {
  const dialog = page.selectComponent("#brand-dialog");
  if (!dialog) throw new Error("页面弹层未加载，请重新进入页面");
  return dialog.open(options);
}

module.exports = { showDialog };
