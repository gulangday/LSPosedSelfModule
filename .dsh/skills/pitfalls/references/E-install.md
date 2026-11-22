# E Hook 安装与开关

## E1 · 版本伪装无效

- **现象**：伪装版本号不生效，应用照样提示更新。
- **根因**：hook 等到 Activity resume 才装，而应用在 `Application.onCreate` 里已经检查过更新。
- **正确做法**：在 `handleLoadPackage` 阶段就装（`MainHook` 里的 `installVersionSpoofEarly`）。
- **自查项**：
  - [ ] 安装点是否早于 Activity 创建？
  - [ ] 是否覆盖了 `getPackageInfo` / `versionName` 读取路径？
- **关联**：E2

## E2 · 显示已开启但 hook 不执行

- **现象**：功能页开关是开，实际没有拦截。
- **根因**：hook 里只判断了功能开关，没判断 master。
- **正确做法**：`Features.isOn(context, MASTER) && Features.isOn(context, FEATURE)` 双判（`MainHook` 中所有 hook 入口）。
- **自查项**：
  - [ ] 每个 hook 入口是否先判 master？
- **关联**：H6、H3

## E7 · hook 重复安装

- **现象**：拦截逻辑执行两次，弹两次窗、日志重复。
- **根因**：同一进程内多次调用安装函数，或没判断进程名导致多进程重复。
- **正确做法**：安装函数用包名 + 进程名做一次性标记（`mutableSetOf` + synchronized）；当前 `handleLoadPackage` 每进程只调用一次，新增安装点仍须显式判重，避免多进程应用重复注入。
- **自查项**：
  - [ ] 新增 hook 点是否有判重？
  - [ ] 多进程应用是否需要限制到主进程？
- **关联**：E8

## E10 · 想自动勾选 LSPosed 作用域

- **现象**：希望在模块内添加应用后，LSPosed 作用域自动勾选。
- **根因**：没有普通应用可用的公开 API。libxposed 的 `XposedService.requestScope()` 需要现代模块；本机实测 legacy API 82 加 `libxposed:service:101` 不会绑定；只加 `module.prop` 会让 LSPosed 报 `failed to load module`；service 101 还要求 compileSdk 36，并把 Kotlin stdlib 拉到 2.2.10 与项目 Kotlin 2.0.21 冲突。
- **正确做法**：不要为自动勾选改 LSPosed 数据库或引入 libxposed service；添加应用后检查 `files/selfxposed_seen` + 作用域数据库，弹「未勾选 / 已勾选待注入 / 无法确认」，并提供「打开 LSPosed」。若将来完整迁移到现代 API 101，再单独评估。
- **自查项**：
  - [ ] 是否引入了 `libxposed:service` / `module.prop` / `java_init.list`？
  - [ ] 是否直接写 LSPosed 数据库？
  - [ ] 失败时是否回退到手动引导？
- **关联**：D8、H9、G5
