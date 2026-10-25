# 自用模块（LSPosed）2.1.0

一个按应用配置的自定义弹窗模块：在目标应用打开时，在底部显示一条白底黑字的提示，
显示时长与淡出时长都可以单独调整。

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

1. 打开「后台常驻」：模块会以前台服务常驻，尽量避免被系统回收；
2. 在系统的「自启动管理」里允许本模块自启动：这样即使被划掉，目标应用仍能把它唤醒。

两项都开启后，把模块从最近任务划掉，目标应用照样能正常弹窗（已实测）。
只做第 1 步不够：划掉模块等价于强制停止，前台服务也会随之结束。

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

- 2.1.0（versionCode 9）：自绘弹窗、多应用、页面拆分、图标与主题。
- 2.0.x（versionCode 8）：单目标、系统 Toast、单文件 Activity。
