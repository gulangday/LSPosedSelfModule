# B 布局与裁切

## B1 · 状态标签被右边缘裁切

- **现象**：横向行右侧的"已开启 / 未开启"标签显示不全，右边被切掉。
- **根因**：`UiKit.chip()` 默认带 `LinearLayout.LayoutParams(-2,-2)` 且 `topMargin = dp(6)`；直接放进横向行时 margin 参与测量，挤压宽度。
- **正确做法**：放进横向行后重置 `view.layoutParams = LinearLayout.LayoutParams(-2, -2)`（见 `UiKit.statusChip` 第 103 行注释）；chip 自身 padding 为左右 `dp(8)`、上下 `dp(3)`。
- **自查项**：
  - [ ] chip 进横向行后是否重新赋了 layoutParams？
  - [ ] 宽度是否为 `-2` 而非固定值？
- **关联**：B5

## B5 · 长标题被裁或布局错位

- **现象**：长应用名、长提示文字超出一行，右侧内容被挤出屏幕。
- **根因**：固定宽度容器，或文本没有权重，wrap 后把兄弟 View 挤出去。
- **正确做法**：横向行里给文本 `LinearLayout.LayoutParams(0, -2, 1f)`；文本设 `maxLines` 与 ellipsize。
- **自查项**：
  - [ ] 文本是否为 0 宽 + weight 1？
  - [ ] 是否设置了 maxLines / ellipsize？
- **关联**：B1
