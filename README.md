# 自用模块（LSPosed）2.1.0

一个按应用配置的自定义弹窗模块：在目标应用打开时，在底部显示一条白底黑字的提示，
显示时长与淡出时长都可以单独调整。

## 协作规则（每次任务都必须遵守）

1. **收到任何需求，先输出改动清单**：改哪个页面/文件、改什么、影响范围、怎么验证。
2. **等用户明确说「开始」/「可以」/「做吧」/「动手」之后再动代码**，不要边想边改。
3. 用户没确认前：不写代码、不构建、不装 APK、不提交。
4. **只读检查可以例外**：读代码、读日志、查 adb 状态用于把清单写清楚，这些不算违规。
5. 完成后按清单式汇报：改了什么、验证结果、遗留问题。
6. 不确定的技术点先问，不要猜。
7. 不主动 commit / push，用户说推送才推。

## 功能

- **自绘弹窗，不用系统 Toast**：白底、圆角、黑字、底部居中，显示与淡出时长精确可控。
- **多目标应用**：首页「添加目标应用」从已安装应用里挑选，可添加多个，长按卡片可移除。
- **每个应用独立配置**：总开关、提示开关、弹窗文字、显示时长（0.5–10 秒）、淡出时长（0–3 秒）。
- **切换界面不重置计时**：同一应用内切换 Activity 时，弹窗跟着新界面继续显示剩余时间。
- **跳过启动页**：启动页（Splash/Welcome 等）会被跳过，只在实际主界面上弹一次，避免"闪一下又出现"。
- **后台常驻与自启动**：前台服务保持模块进程存活，并引导开启系统自启动，模块被划掉后仍能弹窗。
- **运行日志**：每个应用最多保留 300 条记录，可在模块里查看与清空。
- **Hook 状态**：首页卡片显示「Hook 已连接 / 待检测」，用来区分"作用域没勾"和"开关没开"。

## 项目结构

```
app/src/main/java/com/example/selfxposed/
├── MainActivity.kt          # 外壳：标题、内容容器、底部导航、返回键
├── MainHook.kt              # 注入入口：读配置、判断开关、调度弹窗
├── PopupController.kt       # 弹窗视图与计时（自绘 DecorView 子视图）
├── ConfigClient.kt          # 目标进程 ↔ 模块 的字段与 Provider 接口
├── ModuleConfigProvider.kt  # SharedPreferences 读写与参数校验
├── KeepAliveService.kt      # 后台常驻前台服务
├── KeepAlive.kt             # 常驻开关的存储
├── BootReceiver.kt          # 开机后自动拉起常驻服务
├── data/ModuleRepository.kt # 界面侧数据层（配置、日志、目标列表、应用信息）
└── ui/                      # Palette / UiKit / ScreenHost / 各页面
    ├── HomeScreen.kt        # 目标应用列表（开关在右侧，左侧进设置）
    ├── AppSettingsScreen.kt # 单个应用的文字、时长、预览、保存
    ├── PickerScreen.kt      # 搜索 + 分页添加应用
    ├── SettingsScreen.kt    # 日志入口、说明、模块信息
    ├── LogsScreen.kt        # 日志查看/刷新/清空
    └── HelpScreen.kt        # 使用说明
```

## 构建

```bash
# 需要 JDK 17+、Android SDK（platform 35）与可访问 Maven 仓库的网络
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

本仓库默认 SDK 路径在 `local.properties`（该文件不入库）。

## 安装与配置

1. 安装 APK，在 LSPosed 管理器里启用「自用模块」。
2. 打开模块 →「添加目标应用」，选择需要弹窗的应用。
3. 在 LSPosed 的**作用域**里勾选同一个应用（添加应用不会自动勾选作用域）。
4. 启动一次该应用，模块记录一次运行时握手，首页状态变为「Hook 已连接」。
5. 回到模块，打开该应用的开关，设置文字与时长，可先点「预览弹窗」看效果。
6. 再次切到该应用，弹窗会在主界面底部出现。

## 两个必须知道的限制

**1. 作用域与开关是两件事。**
LSPosed 没有公开接口让模块查询"当前应用是否已勾选作用域"，所以主页只能通过运行时握手
显示「已连接 / 待检测」。状态是「待检测」时，先检查作用域是否勾选、以及添加应用后有没有启动过它。

**2. 国产 ROM 的"自启动"拦截（已解决，需要一次性授权）。**
目标应用读配置时会跨进程唤起本模块，MIUI 等系统默认禁止这种唤醒
（日志里表现为 `WakePathChecker ... Reject` / `Unable to launch app`）。
一旦模块进程被划掉或清理，弹窗就会失效。

解决办法是两步（在模块「设置 → 后台与自启动」里都有入口）：

1. 在系统的「自启动管理」里允许本模块自启动：这样模块被划掉后，目标应用仍能把它唤醒（**必须**）；
2. 「后台常驻」为可选项（默认关闭）：提高唤醒成功率，代价是常驻一条通知。

另外，hook 会把最近一次成功读到的配置缓存在目标应用自己的 SharedPreferences 里，
所以即使模块进程完全不可用，也能继续用上次的配置弹窗（改配置需模块被唤起一次才生效）。

## 调试入口

不依赖 LSPosed 注入，直接验证弹窗渲染与计时：

```bash
adb shell am start -n com.example.selfxposed/.MainActivity \
  --ez preview_popup true \
  --es preview_text "测试文字" \
  --ei preview_show_ms 5000 --ei preview_fade_ms 1000
```

打开/关闭后台常驻（等价于设置页里的开关）：

```bash
adb shell am start -n com.example.selfxposed/.MainActivity --ez keep_alive true
adb shell am start -n com.example.selfxposed/.MainActivity --ez keep_alive false
```

## 安全提示

Provider 按 LSPosed 的注入模型必须是 `exported=true`，因此对已安装应用可读可写。
它只存本模块的界面配置与日志，没有敏感数据；但仍建议只在自己的设备上使用，
不要把它当作安全边界。

## 版本

- 2.1.1（versionCode 10）：补齐传统密度 PNG 图标，修复第三方工具（LSPosed 模块列表）显示默认图标。
- 2.1.0（versionCode 9）：自绘弹窗、多应用、页面拆分、后台常驻与自启动。
- 2.0.x（versionCode 8）：单目标、系统 Toast、单文件 Activity。

> 图标相关：启动器用 `mipmap-anydpi-v26` 的自适应图标，同时保留 `mipmap-*dpi` 的 PNG
> 供按密度取图的第三方工具使用。LSPosed 管理器会按 versionCode 缓存模块图标，
> 更新图标后需要提升 versionCode（或重启管理器）才会刷新。

## 新增功能必读（踩坑清单）

以下是这个项目一路踩出来的问题，**新增功能前先过一遍**，能省很多返工。

### 1. 三层开关：模块总开关 → 功能开关 → 功能内选项

所有开关都是**按目标应用**、**默认关闭**，状态写在目标应用本地的
`files/selfxposed_features.conf`（`Features` 类，形如 `master=1`、`block=1`、`toast=0`）。

| 层 | 位置 | 字段 | 规则 |
|---|---|---|---|
| 1 | 主页应用卡片「模块已启用 / 未启用」 | `master` | **总开关**。关闭时模块对该应用什么都不做（hook 直接返回），所有功能页整页置灰 |
| 2 | 功能页「启用拦截」「启用提示通知」 | `block` / `popup` / `toast` | 只有 `master=1` 时才能打开；关闭时该功能页其余选项置灰 |
| 3 | 拦截方式 / 关键字 / 提示样式等 | 各自文件 | 只有对应功能开关打开后才可用 |

新增功能时：

1. `Features` 加常量（如 `const val XXX = "xxx"`）；
2. hook 侧先判断 `Features.isOn(context, Features.MASTER)`，再判断功能开关，**默认不做事**；
3. 功能页放「启用」开关，用 `Features.setRemote(pkg, XXX, checked)` 写入；
4. 功能页开头先读 `MASTER`：未启用时只显示引导卡 + 全部 `ui.lockedCard(..., tag = "总开关未启用")`，然后 `return`。

其他约定：

- 切换任一总开关后要 `host.refresh()` 重建页面（滚动位置会自动恢复），让整页可用性立即变化；
- 标签统一写「总开关未启用」或「需要 Root / Shizuku 模式」（看具体原因）；
- **不要**再往模块配置（Provider）里加"总开关"——targetSdk 30+ 的应用读不到模块 Provider。

### 2. 需要 hook 读取的数据，必须落在目标应用自己的目录

`/data/data/<目标包>/files/*` 或 `shared_prefs/*`，因为：

- 模块 Provider 对 targetSdk 30+ 的应用不可见（实测 `Unknown authority`）；
- LSPosed 的文件服务读模块目录会返回空流；
- 只有"写自己目录、hook 在自己进程读"这条路是通的。

新增数据文件时同步更新 `AGENTS.md` 第 3 节的文件清单。

### 3. 读取状态要"合并 + 缓存 + 失败不缓存"

- 一个页面要读多个状态时，用 `TargetFiles.load(pkg)` 的**一条命令**全部取回，
  不要每个状态各跑一次 root/Shizuku（每次 IPC 50–150ms，Root 模式更慢）。
- **失败结果绝不能缓存**：Shizuku 未绑定完成时返回空串，如果缓存了空结果，
  接下来 30 秒内所有页面都会显示"未开启"（这个 bug 真的发生过）。
  现在的写法是：输出里带 `@@F` 标记才缓存。

### 4. 新页面要"四处注册"

新增一个页面时，`MainActivity` 里必须同时改：

1. `PAGE_*` 常量；
2. `renderPage()` 分支；
3. `handleBack()` 的返回目标；
4. `ScreenHost` 接口 + `MainActivity` 实现。

少一处就会出现"能进不能退"或"返回跑到别处"。

### 5. 子页面要记住"从哪来的"

- 日志页：`logsBackPage`
- 弹窗规则页：`rulesBackPage`

**不要**用"有没有包名"来猜返回目标——从「拦截弹窗」进来和从「应用设置」进来，
包名都有，猜不对就会退错页面。

### 6. 窗口层级：Dialog 是独立窗口，会吞掉背后的触摸

- 弹窗出现时，挂在 Activity 上的悬浮球点不到（触摸被 Dialog 窗口吃掉）。
- 正确做法：弹窗出现时把球挂到**弹窗的 decorView** 上，同时隐藏 Activity 上的那个，
  弹窗 dismiss 再恢复。
- 描边要框**内容卡片**（`decorView` 第一个子 View 里面积最大、有背景的那个），
  不能框整个 decorView（它是全屏大小的，会框到游戏画面上）。

### 7. 自动拦截不要"闪一下"

为了截图必须先让弹窗完成布局，直接拦截会导致用户看到弹窗闪一下。
做法：`Dialog.show` 前把窗口 `alpha` 设为 0，布局照常、`draw` 照常，用户看不到。

### 8. 截图去重

同一个弹窗可能被 `show()` 多次，且每次启动都会再弹一次：

- 内容哈希 5 秒内不重复；
- 同标题只保留最新一张（保存新图前删掉同标题的旧 png/txt）。

### 9. Shizuku 的 UserService 写法

- 类必须**直接继承 AIDL 的 `Stub`**（本身是 Binder），
  **不能**写成 `android.app.Service`，否则 `ClassCastException: cannot be cast to IBinder`。
- 首次绑定约 4 秒，页面要显示"正在连接"并在后台等待后自动刷新。
- `ensureBound` 只在整个进程第一次真正等待，之后快速返回，避免每个命令卡 2 秒。

### 10. 平台限制（不要试图绕过）

- `targetSdk 30+` 应用受包可见性限制，看不到模块 Provider、也看不到 `su` 的某些路径。
- 被隐藏 root 的模块（Shamiko / DenyList）会把 `su` 从应用视图里摘掉，
  这时必须走 Shizuku 通道。
- Toast 拦截只能拦**目标应用自己**弹的；其他应用/系统 UI 弹的拦不到。
- Unity 内部绘制的 UI 不是 Android Dialog，弹窗守卫识别不了。

### 11. 工程习惯

- **禁止用 PowerShell / sed 批量替换源码**（编码会写坏文件，踩过一次）；
  改动一律用编辑工具逐处修改。
- 静态 `Handler`、`SharedPreferences` 等要用 `by lazy` 或在方法内创建，
  不能在 `MainHook.<clinit>` 里初始化（会 NPE）。
- 所有 hook 和文件操作必须包 `try/catch`，异常只记日志，不能影响目标应用。
- 主线程不要做 root 命令或文件 IO，页面先渲染缓存值，后台读好再刷新。
