# B 导航与刷新

## B4 · 返回键回错页面

- **现象**：顶部返回按钮正常，但按系统返回键直接退出 Activity 或回到主页。
- **根因**：只改了返回按钮的 onClick，没改 `MainActivity.handleBack()`。
- **正确做法**：`handleBack()` 按当前 `PAGE_*` 逐级返回，按钮直接复用 `handleBack()`；Android 13+ 还要在 `OnBackInvokedDispatcher` 注册（`MainActivity` 第 123-125 行）。
- **自查项**：
  - [ ] 新页面是否齐了四处注册：`PAGE_*`、`renderPage`、`handleBack`、`ScreenHost`？
  - [ ] 返回按钮是否复用 `handleBack()`？
- **关联**：无

## B8 · 开关切换后整页重建、滚动跳顶

- **现象**：切换一个开关，页面闪一下并回到顶部。
- **根因**：`refresh()` 直接重绘整页，ScrollView 位置丢失。
- **正确做法**：优先局部更新（`statusChip` 自己 render）；必须 refresh 时先记 `scrollY`，渲染完 `post { scrollTo(0, y) }`。
- **自查项**：
  - [ ] refresh 前是否保存了 scrollY？
  - [ ] 能否改成局部更新？
- **关联**：F5
