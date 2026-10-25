# 自用模块（LSPosed）2.0.0

## 功能
- 首页展示已安装的启动器应用（并固定展示酷狗概念版 `com.kugou.android.lite`）。
- 每个应用独立保存总开关、弹窗开关、弹窗文字、显示时长、淡出时长和日志。
- 目标应用中的提示弹窗为底部居中、白底黑字；默认显示 5 秒，再淡出 1 秒。
- 切换目标应用内部 Activity 不会重置已经启动的弹窗计时。
- 日志通过模块 ContentProvider 保存，可在模块首页查看。

## 导入
使用 Android Studio 打开项目根目录，等待 Gradle 同步后构建 APK。
需要可访问 Maven Central 的网络环境，以解析 Android Gradle Plugin、Kotlin 插件和 Xposed API 依赖。

## LSPosed 配置
1. 安装构建的 APK。
2. 在 LSPosed 管理器启用模块。
3. 在模块作用域里勾选要修改的目标应用。
4. 回到“自用模块”，为同一应用开启模块开关。
5. 点击应用卡片配置弹窗。

## 重要限制
LSPosed 没有向普通模块应用提供一个稳定、公开、可直接读取“当前每个应用是否已勾选作用域”的通用接口。因此，应用内开关启用时会提示用户先确认作用域；不能声称该提示是自动检测结果。若未勾选作用域，模块不会被注入目标应用，功能自然不会执行。

## 安全提示
仅对你有权修改、且已明确勾选作用域的应用使用。此项目源码不包含目标应用的修改包或私有数据。


## 优化版 8 说明
- 目标应用卡片严格划分为左右等宽区域：左半区进入目标设置，右半区只用于开关。
- 将 versionCode/versionName 更新为 8 / 2.0.8，便于识别新构建。
- Provider authority 使用 `${applicationId}.config`，应解析为 `com.example.selfxposed.config`。
- 需要在 Android Studio 重新构建并安装新 APK；本压缩包尚未在本环境完成 Android Gradle 编译或真机验证。
