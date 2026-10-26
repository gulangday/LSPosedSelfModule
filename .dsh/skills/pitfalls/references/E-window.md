# E Hook 窗口与视觉

## E3 · 自动拦截时弹窗闪一下

- **现象**：拦截生效，但用户能看到弹窗闪了一帧。
- **根因**：先 `show()` 再改窗口属性，中间有一帧可见。
- **正确做法**：`show()` 之前把窗口 `alpha = 0`（或尺寸置 0），show 完成后再设真实属性；更好的做法是在布局 inflate 前就拦掉，不 show。
- **自查项**：
  - [ ] 隐藏属性是否在 show 之前设置？
- **关联**：E5

## E4 · 悬浮球点不到

- **现象**：目标应用弹出 Dialog 后，悬浮球无法点击。
- **根因**：Dialog 是独立窗口，Activity 层的触摸被 Dialog 吞掉。
- **正确做法**：把球挂到当前 Dialog 的 `decorView` 上（遍历 `shownDialogs`），Dialog dismiss 时同步移除。
- **自查项**：
  - [ ] 球是否挂在 decorView 而非 Activity？
  - [ ] dismiss 时是否移除了？
- **关联**：E5

## E5 · 描边框住整个屏幕

- **现象**：拾取模式的描边是整屏半透明框，看不清要选的是哪个弹窗。
- **根因**：给 `decorView` 加了描边，而 decorView 是透明全屏容器。
- **正确做法**：从 decorView 往下找真正有背景的最大子 View（`findCardView`），按它在窗口中的坐标画描边（`MainHook` 第 228-248 行）。
- **自查项**：
  - [ ] 框的是内容 card 而不是 decor？
  - [ ] 坐标是否用 `getLocationInWindow` 计算？
- **关联**：E3
