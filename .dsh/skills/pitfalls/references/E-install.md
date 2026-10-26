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
