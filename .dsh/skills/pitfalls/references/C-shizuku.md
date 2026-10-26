# C Shizuku 服务

## C1 · UserService 崩溃或绑定即死

- **现象**：绑定 Shizuku 服务抛 `ClassCastException`，或服务起不来。
- **根因**：把 UserService 写成了 `android.app.Service`；Shizuku 要求该类本身是 Binder，即继承 AIDL 的 Stub。
- **正确做法**：`class RemoteService : IRemoteService.Stub()`（当前实现），AIDL 与模块同包，方法的异常在内部吃掉。
- **自查项**：
  - [ ] 类是否继承 `IRemoteService.Stub()`？
  - [ ] AIDL 是否在 `src/main/aidl` 且包名一致？
- **关联**：C2

## C2 · remote 进程堆积导致别家应用闪退

- **现象**：反复打开模块后出现上百个 remote 进程，其他应用被杀、相册闪退。
- **根因**：`UserServiceArgs.daemon(false)`，每次绑定都新建进程，且不随模块退出回收。
- **正确做法**：`.daemon(true)` 复用同一常驻进程（`ShizukuBridge` 绑定处），配合 `processNameSuffix("remote")`。
- **自查项**：
  - [ ] daemon 参数是否为 true？
  - [ ] 是否设置了 processNameSuffix？
- **关联**：C1、C6

## C6 · 每个命令卡 2 秒

- **现象**：每次读配置或执行命令都要等 2 秒以上。
- **根因**：`ensureBound()` 每次都 sleep 轮询等绑定。
- **正确做法**：用 `waitedOnce` 标记，整个进程只真正等一次，之后立即返回（`ShizukuBridge` 的 ensureBound）；再配合 `TargetFiles` 的一声命令合并取数 + 30s 缓存。
- **自查项**：
  - [ ] ensureBound 是否有一次性标记？
  - [ ] 是否每个字段单独跑了一次 IPC？
- **关联**：C2、D7、F2
