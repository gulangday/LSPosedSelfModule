# 项目约定

## 0 先清单后动手（违反视为任务失败）

任务第一段必须给「分析 + 清单」；用户没说「开始 / 可以 / 做吧 / 动手」前，禁止写代码、构建、装机、commit/push。
例外：只读检查（读代码、日志、adb）可先行。

## 0.1 安全红线

1. 删东西先列候选，确认后删，并校验绝对路径。
2. 复制大文件先空跑：`robocopy "源" "目标" /E /L`。
3. 删除 / 覆盖 / 格式化 / 改系统设置，四类先问一句。

## 1 技术栈（不要替换）

Kotlin 2.0.21 / AGP 8.7.3 / compileSdk 35 / targetSdk 28 / minSdk 26 / JDK 17（`JAVA_HOME=D:\JAVA\Java17`）。
依赖：xposed api 82（compileOnly）、shizuku api+provider 13.1.5；UI 纯代码自绘（无 XML）。
禁止：换依赖、换 UI 框架、改 XML 布局、动 `xposedminversion`。

## 2 目录与分层

`MainHook`（注入）· `MainActivity`（壳+路由）· `ConfigClient`+`ModuleConfigProvider`（Provider）· `WorkMode` · `RootShell` · `ShizukuBridge`+`RemoteService`（必须继承 AIDL Stub）· 功能存储（Keywords / HintStyle / DialogRules）· `data/ModuleRepository` · `ui/*Screen` + `ui/ScreenHost`。
禁止：UI 逻辑进 MainHook、hook 进 ui、跨层直调（UI 只经 ModuleRepository / ScreenHost）。

## 3 数据落点

模块配置/日志/目标列表 → Provider。
hook 要读的数据 → 目标应用目录：规则 `shared_prefs/selfxposed_block.xml`、关键字 `files/selfxposed_keywords.conf`、拦截方式 `files/selfxposed_pick_mode.conf`、悬浮球 `files/selfxposed_ball_off`、运行标记 `files/selfxposed_seen`、截图 `files/blocked/*`。
原因：targetSdk 30+ 看不到模块 Provider；新增数据沿用此模式并记入 HANDOFF。

## 4 质量要求

1. 改完 `gradlew assembleDebug` 通过，尽量装机验证。
2. 禁止 PowerShell / sed 批量替换源码，逐处编辑。
3. 静态 Handler / SharedPreferences 用 `by lazy` 或方法内创建。
4. hook 与文件操作全包 try/catch，异常只记日志。
5. 主线程不做 root / 文件 IO，状态走缓存 + 后台刷新。
6. 新页面四处注册：`PAGE_*`、`renderPage`、`handleBack`、`ScreenHost`。
7. root 功能用 `WorkMode.canUseRoot` 判断，无能力用 `ui.lockedCard` 置灰。

## 5 交付

先清单等确认；不主动 commit / push；完成后清单式汇报；不确定先问。

## 6 交接

多步骤改造后按 `docs/HANDOFF.md` 模板追加一节。

## 7 产品与交互规则

1. 功能默认关闭、按应用开启，状态存目标应用 `features.conf`。
2. 三层开关：主页 `master` → 功能 `block`/`popup`/`toast` → 功能内选项；上层未开，下层置灰。
3. 显示 = 真实执行；功能页计数 = 主页启用 且 该功能开启 的应用数。
4. 模块内提示统一 `host.toast()`（白底、2 秒 + 1 秒淡出、覆盖式、500ms 防抖），ui 层禁用系统 Toast。
5. 删除 / 清空类操作必须二次确认。
6. 文案统一：模块已启用/未启用、已开启/未开启、总开关未启用、需要 Root / Shizuku 模式。
7. 返回回上一级，顶部按钮与系统返回键一致。
