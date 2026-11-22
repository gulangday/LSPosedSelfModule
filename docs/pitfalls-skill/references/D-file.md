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

## D8 · 读取 LSPosed 作用域总为空

- **现象**：作用域查询始终返回 null，界面全部显示「作用域未确认」。
- **根因**：`/data/adb/lspd/config/modules_config.db` 是 WAL 数据库；root 复制出的文件归 root 所有、权限 600，模块 App 读不到；只读打开 WAL 副本可能失败；SQL 列名已带别名时又拼一次 `m.` / `s.`，实际变成 `m.m.module_pkg_name`。
- **正确做法**：复制 `modules_config.db`、`-wal`、`-shm` 三件套；用 `stat -c %u:%g` 取模块缓存目录的 owner，把三个文件 `chown` 过去后 `chmod 600`；用 `SQLiteDatabase.OPEN_READWRITE` 打开缓存副本；表结构用 `scope(module_pkg_name, app_pkg_name, user_id)` + `modules(module_pkg_name, apk_path)`；SQL 中列名变量不要再带表别名前缀。失败返回 null，不能当成「未勾选」。
- **自查项**：
  - [ ] 是否复制了 wal / shm？
  - [ ] 复制后是否 chown 给模块 App？
  - [ ] 是否用读写模式打开？
  - [ ] SQL 是否出现 `m.m.` / `s.s.`？
- **关联**：D5、E10、H8
