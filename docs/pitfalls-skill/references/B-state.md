# B 开关与状态显示

## B2 · 开关字体与其他文字不一致

- **现象**：系统 Switch 的字体、尺寸、颜色和自绘 UI 对不上。
- **根因**：用了 `android.widget.Switch`，字体被系统主题接管。
- **正确做法**：统一用 `ui.statusChip(initial, onChange)`，文案固定"已开启 / 未开启"，绿 / 灰配色；`onChange` 返回 true 才更新显示。
- **自查项**：
  - [ ] 页面里是否还残留 Switch / CheckBox？
  - [ ] 状态文案是否统一为"已开启 / 未开启"？
- **关联**：B3

## B3 · 总开关关了子项还能点

- **现象**：主页模块总开关关闭，功能页的项仍可点击、仍能写入。
- **根因**：只在 UI 上置灰，没有在渲染入口和写入入口拦截。
- **正确做法**：未启用时整页 `ui.lockedCard(...)` 后 return（`UpdateBlockScreen`、`AppSettingsScreen` 顶部 `masterOn` 判断）；写入函数里再判一次。
- **自查项**：
  - [ ] 页面顶部是否有 `if (!masterOn) { lockedCard; return }`？
  - [ ] 写入函数是否二次判断？
- **关联**：B9、H3

## B9 · 功能页计数与主页不一致

- **现象**：主页显示 2 个应用启用，功能页显示 3 个。
- **根因**：两处统计口径不同，一处只判 feature，另一处判 master && feature。
- **正确做法**：统一表达式 `state.isOn(Features.MASTER) && state.isOn(feature)`（见 `FeaturesScreen` 第 78 行）。
- **自查项**：
  - [ ] 两个页面是否用同一表达式或同一 helper？
- **关联**：B3、H3
