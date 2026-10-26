# 项目约定（AGENTS.md）

本文件是**项目级约束**，任何改动都必须遵守。与用户当次要求冲突时，以用户要求为准，但要在交付时说明冲突点。

## 0. 最高优先级：先清单、后动手（违反视为任务失败）

**任何任务，第一段回复必须是「分析 + 改动清单」；用户没有明确说「开始 / 可以 / 做吧 / 动手」之前，禁止：**

- 写代码、改文件
- 构建（gradlew）
- 装机、截图验证
- commit / push

例外：**只读检查**（读代码、读日志、grep、查 adb 状态）可以先行，因为那是为了把清单写清楚。

清单必须包含：改哪个文件/页面、改什么、影响范围、怎么验证。
用户确认后才开始执行；完成后按清单逐项汇报结果。

## 1. 技术栈（不要替换）

| 项 | 值 |
|---|---|
| 语言 | Kotlin 2.0.21 |
| AGP | 8.7.3 |
| compileSdk / targetSdk / minSdk | 35 / 28 / 26 |
| JDK | 17（`JAVA_HOME=D:\JAVA\Java17`） |
| 依赖 | `de.robv.android.xposed:api:82`（compileOnly）、`dev.rikka.shizuku:api:13.1.5`、`dev.rikka.shizuku:provider:13.1.5` |
| UI | 纯代码自绘（无 XML 布局），`ui/UiKit.kt` 提供 card/label/actionCard/dialog/listDialog/lockedCard |
| 构建 | `gradlew assembleDebug` |

**禁止**：擅自升级/更换依赖、引入新的 UI 框架、改用 XML 布局、改动 `xposedminversion` 等元数据。

## 2. 目录约定

```
app/src/main/java/com/example/selfxposed/
  MainHook.kt            Xposed 入口（注入、弹窗守卫、悬浮球、截图、规则）
  MainActivity.kt        壳 + 路由（PAGE_*）+ 工作模式选择
  ConfigClient.kt        模块↔目标进程的 Provider 协议常量与调用
  ModuleConfigProvider.kt Provider 实现（配置/日志/目标应用/提示样式）
  WorkMode.kt            Root / Shizuku / 非Root（文件存储，不用 SharedPreferences）
  RootShell.kt           统一命令通道（Shizuku → 直接 su → 直读文件）
  ShizukuBridge.kt       Shizuku UserService 绑定与调用
  RemoteService.kt       Shizuku 远程进程（必须继承 AIDL Stub，不能写成 Service）
  Keywords.kt / HintStyle*.kt / DialogRules.kt   按功能拆分的本地存储
  data/ModuleRepository.kt   UI 侧统一数据入口
  ui/*Screen.kt          各页面，实现 ScreenHost 回调
  ui/ScreenHost.kt       页面跳转与 toast 接口（新页面必须加方法）
app/src/main/aidl/com/example/selfxposed/IRemoteService.aidl
docs/                    说明与交接文档
```

**禁止**：把 UI 逻辑塞进 `MainHook`；把 hook 逻辑塞进 `ui/*`；跨层直接调用（UI 只能经 `ModuleRepository` / `ScreenHost`）。

## 3. 数据与通道约定

- 模块自己的配置、日志、目标应用列表：`ModuleConfigProvider`（`${applicationId}.config`）。
- **需要 hook 在目标进程读取的数据**，必须落在目标应用自己的目录：
  - 规则：`shared_prefs/selfxposed_block.xml`
  - 关键字：`files/selfxposed_keywords.conf`
  - 拦截方式：`files/selfxposed_pick_mode.conf`（`ball` / `auto`）
  - 悬浮球关闭标记：`files/selfxposed_ball_off`
  - 运行标记：`files/selfxposed_seen`
  - 拦截截图：`files/blocked/<时间戳>.png` + 同名 `.txt`（标题）
- 原因：targetSdk 30+ 的应用受包可见性限制，调不到模块 Provider，只能靠本地文件 + root/Shizuku 读取。
- 新增需要 hook 读取的数据时，必须沿用这个模式，并同步在 `docs/HANDOFF.md` 记录。

## 4. 硬性质量要求

1. 改完必须 `gradlew assembleDebug` 通过；有条件就装机验证。
2. **禁止用 PowerShell / sed 批量替换源码**（编码会写坏文件，已踩过坑）；改动一律用编辑工具逐处修改。
3. 静态 `Handler`、`SharedPreferences` 等要用 `by lazy` 或在方法内创建，不能在 `MainHook.<clinit>` 里初始化。
4. 所有 hook 与文件操作必须包 `try/catch`，异常只记日志，不能影响目标应用。
5. 涉及 UI 线程的阻塞操作（root 命令、文件读取）必须放后台线程；主页状态走缓存 + 后台刷新。
6. 页面跳转：新页面必须在 `MainActivity` 注册 `PAGE_*`、在 `handleBack` 加返回目标、在 `ScreenHost` 加方法、在 `renderPage` 加分支，**四处齐全**。
7. 需要 root 的功能，用 `WorkMode.canUseRoot(context)` 判断；非 Root 模式用 `ui.lockedCard(...)` 置灰。

## 5. 交付流程（用户习惯）

1. 收到需求先**列清单**，等用户明确"开始"再动手。
2. **不主动 git commit / push**，用户说推送再推。
3. 改动完成后给**清单式汇报**：改了什么、验证结果、遗留问题。
4. 不确定的技术点先问，不要猜。

## 6. 交接文档

每次**多步骤改造**结束，按 `docs/HANDOFF.md` 的模板追加一节，内容包括：改动清单、验证方式与结果、遗留问题、下一步建议。小改动（单文件、文案）可省略。
