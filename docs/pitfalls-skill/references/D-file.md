# D 文件权限与可见性

## D5 · 写完文件 hook 读不到

- **现象**：模块里改了功能开关，目标应用仍按旧值执行。
- **根因**：文件 owner 是 root / shell，目标应用 uid 没有读权限。
- **正确做法**：写完执行 `chown $(stat -c %u:%g /data/data/<pkg>)` + `chmod 660`（`Features.setRemote`），并 `TargetFiles.invalidate(pkg)`。
- **自查项**：
  - [ ] 是否 chown 到目标应用的 uid:gid？
  - [ ] 权限是否为 660？
- **关联**：D6

## D6 · hook 读的配置读不到

- **现象**：版本伪装等配置填了，目标应用没反应。
- **根因**：配置写进了模块 Provider，targetSdk 30+ 的目标应用看不到。
- **正确做法**：hook 要读的一律写目标应用本地（如 `files/selfxposed_update_block.conf`，`TargetFiles` 的 `@@U` 段）。
- **自查项**：
  - [ ] 这份配置由谁读？读方能否访问该路径？
- **关联**：D1、D5

## D7 · 多状态各跑一次 IPC，页面慢

- **现象**：主页要 4~5 秒才出状态。
- **根因**：每个状态单独发一次 root / Shizuku 命令，每次约 1 秒。
- **正确做法**：一条命令取全部（`TargetFiles.load` 用 `@@F/@@M/@@B/@@U/@@R` 分段）+ 30s TTL 缓存 + `peek` 先渲染。
- **自查项**：
  - [ ] 是否合并成一条命令？
  - [ ] 首帧是否用了缓存？
- **关联**：C6、F2
