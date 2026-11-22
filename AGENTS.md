# 项目约定

## 0 先清单后动手（违反视为任务失败）

任务第一段必须给「分析 + 清单」；用户没说「开始 / 可以 / 做吧 / 动手」前，禁止写代码、构建、装机、commit/push。
例外：只读检查（读代码、日志、adb）可先行。

## 0.1 安全红线

1. 删东西先列候选，用户确认后删，并校验绝对路径。
2. 复制大文件先空跑：`robocopy "源" "目标" /E /L`。
3. 删除 / 覆盖 / 格式化 / 改系统设置，四类先问一句。

## 0.2 先查踩坑索引

收到「新建功能 / 改 UI / 修 bug / 改 hook / 写数据 / 编译装机」类需求时，**动手前必须先调用 `pitfalls` 技能**：

1. 用症状查索引，命中 key 就 grep 定位、只读那一节；
2. 把命中的条目写进给用户的「分析 + 清单」，注明「本次要避开 xxx」；
3. 索引未命中时按协议停下问用户，禁止猜。

### 0.2.1 新踩坑回填

问题最终解决后，如果它开始时索引里查不到（`pitfalls` 没命中，或命中了但没覆盖真正的根因），**停下来问用户一句**：要不要把本质原因和解决办法补进 `pitfalls-skill`。用户同意后再写，同时更新 `docs/pitfalls-skill/` 与 `.dsh/skills/pitfalls/` 两处；用户不同意就不写。

## 0.3 授权边界（硬约束）

1. **授权按当轮清单生效，不跨问题继承。** 用户之前说过「开始」，不代表后续新反馈也获批。
2. **描述现象不等于授权。** 「X 有问题」「怎么没有 Y」、截图、提问、抱怨都只是输入；先给分析与清单。
3. **要改文件、构建、装机、升版本、commit/push，必须先回复「分析 + 清单 + 涉及文件」，然后停下等明确指令。** 即使是文档或一行修复也一样。
4. **已定位根因也要等确认。** 只读检查（读代码、日志、adb）可先行，结论不能直接变成修改。
5. **版本号、依赖、构建配置的变更单独确认**，不随功能修复顺带执行。
6. **单轮只做清单内的事。** 不得以「顺手修了」夹带未批准的改动。
7. **违反本条视为任务失败。** 立即说明未经同意的改动，给出保留/撤销选项，等用户决定后再动。

### 授权前回复模板

```
现象判断：…
涉及文件：…
改动清单：1. … 2. …
风险：…
等待：回复「开始」后执行。
```

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

### 5.1 临时文件清理

任务中新建的临时文件（临时脚本、init 脚本、生成的桩代码、为构建补的 `local.properties`、临时克隆的仓库、诊断输出等）在任务结束时列出来，问用户需不需要清理；用户同意后再删，删前校验绝对路径。已纳入 `.gitignore` 的构建产物不算临时文件，不需要问。

### 5.2 长任务进度通报

任务耗时较长（编译、下载依赖、装机、长链路排查）或连续一段时间没有可见进展时，隔一段时间主动查看实际状态并回复用户：现在在做什么、卡在哪、已完成的到哪一步。不要静默等待工具返回，也不要只回「在跑」。

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
