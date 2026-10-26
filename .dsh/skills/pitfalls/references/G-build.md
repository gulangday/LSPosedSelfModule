# G 构建与装机

## G1 · 编译报 JDK 错误

- **现象**：`Unsupported class file major version`，或 `JAVA_HOME is not set`。
- **根因**：默认 JDK 不是 17。
- **正确做法**：构建前设 `$env:JAVA_HOME='D:\JAVA\Java17'`，再执行 `.\gradlew.bat assembleDebug`。
- **自查项**：
  - [ ] JAVA_HOME 是否指向 JDK 17？
- **关联**：G2

## G2 · 找不到安装包

- **现象**：构建成功，但不知道 apk 在哪里。
- **根因**：不熟悉 AGP 输出路径。
- **正确做法**：固定路径 `app\build\outputs\apk\debug\app-debug.apk`。
- **自查项**：
  - [ ] APK 时间戳是否新于源码改动？
- **关联**：G3

## G3 · 装机失败

- **现象**：`adb: device offline` 或安装超时。
- **根因**：无线 adb 掉线。
- **正确做法**：`adb reconnect` / `adb devices` 确认 online 后再 `adb install -r`。
- **自查项**：
  - [ ] `adb devices` 是否显示 device 而非 offline？
- **关联**：G2

## G4 · 静态 Handler NPE

- **现象**：偶发 `NullPointerException` 指向 Handler 字段。
- **根因**：类初始化时机早于 Looper 就绪。
- **正确做法**：`by lazy` 或方法内创建（AGENTS.md 质量要求第 3 条）。
- **自查项**：
  - [ ] 新增静态字段是否用了 by lazy？
- **关联**：无

## G5 · 改了代码没生效

- **现象**：重装模块后行为没变。
- **根因**：只重装了模块，目标应用进程还活着（Xposed 只在进程启动时注入）。
- **正确做法**：改 hook 后重启目标应用；改配置类数据（features / 关键字）不需要重启。
- **自查项**：
  - [ ] 这次改的是 hook 还是配置？
  - [ ] 目标进程是否已重启？
- **关联**：E1

## G6 · LSPosed 模块图标不更新

- **现象**：改了图标，LSPosed 里还显示旧的。
- **根因**：LSPosed 按 versionCode 缓存模块信息。
- **正确做法**：提升 versionCode 后再安装。
- **自查项**：
  - [ ] versionCode 是否 +1？
- **关联**：无
