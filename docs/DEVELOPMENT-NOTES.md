# 开发记录与踩坑清单

> 每次开发遇到的问题都追加到这里：先写现象，再写原因和修法。
> 目的是让下一个改这个仓库的人（包括未来的我）不用重新踩一遍。

## 环境

- 项目：`D:\DeepSeekHarness\projeckt`，包名 `com.example.selfxposed`，版本 2.1.1 / versionCode 10
- 构建：`JAVA_HOME=D:\JAVA\Java17` + `gradlew.bat assembleDebug`（AGP 8.7.3 / Gradle 8.9 / Kotlin 2.0.21）
- 测试机：Redmi 22041211AC，Android 14（SDK 34），MIUI + LSPosed 1.9.2（API 100）+ Magisk Zygisk，无线 adb

## 踩过的坑

### 1. 模块完全不工作：静态初始化里创建 Handler

- **现象**：目标应用里一条 `正在注入` 日志都没有；LSPosed 日志里出现
  `MainHook.<clinit>` 的 `NullPointerException: Looper.mQueue`。
- **原因**：LSPosed 加载模块类时，被注入进程的主线程 Looper 可能还没就绪。
- **修法**：`Handler` 改成 `by lazy`，在真正需要（主线程回调）时才创建。
  `PopupController` 里的 Handler 同理。
- **教训**：模块代码里任何静态初始化都不要碰 Looper / Handler / 主线程相关的对象。

### 2. 弹窗"闪一下又出现"：挂在了启动页上

- **现象**：打开目标应用时弹窗出现，进入首页瞬间消失，随后又出现并开始计时。
- **原因**：弹窗挂在 `SplashActivity` 的 DecorView 上，启动页销毁时窗口跟着销毁；
  主界面 resume 后又被迁移回来。
- **修法**：跳过类名含 `splash / welcome / guide / loading / launch` 的 Activity；
  并在 Activity 稳定后延迟 400ms 再挂载（期间若切换 Activity 则取消重排）。

### 3. 模块被划掉后不弹窗：MIUI 拦截跨应用唤醒

- **现象**：模块进程存活时 5/5 正常；把模块从最近任务划掉后，目标应用不再弹窗。
- **日志特征**：`WakePathChecker: MIUILOG-AutoStart ... Reject` +
  `Unable to launch app for com.example.selfxposed.config: caller is ...kugou...`
- **结论**：这是系统在拦"唤醒已停止的模块进程"，不是模块代码的 bug。
- **修法**（已实现，两步都要）：
  1. 模块内「设置 → 后台与自启动」打开「后台常驻」（前台服务 + 通知）；
  2. 在系统的自启动管理里允许本模块自启动。
- **实测**：两项都开之后，强制停止模块再启动目标应用，弹窗正常（截图见 `shots/`）。

### 4. 尝试过但不可行：XSharedPreferences 直读

- **动机**：绕开 ContentProvider，就不怕进程被唤醒拦截。
- **实测**：LSPosed 1.9.2 下 `XSharedPreferences` 返回 `null`，而且目标进程连
  `stat("/data/data/com.example.selfxposed/shared_prefs/module_config.xml")` 都是 false，
  即使补了 `xposedsharedprefs` 元数据也一样。
- **结论**：跨进程读配置只能走 ContentProvider + 自启动授权。代码里保留了文件直读作为
  快速路径，但它在本机不生效，不要依赖它。

### 5. LSPosed 模块列表显示系统默认图标

- **现象**：桌面图标正常，LSPosed 里是默认图标。
- **原因**：两个叠加
  1. 只有 `mipmap-anydpi-v26` 的自适应图标（XML），第三方工具按密度取图取不到；
  2. LSPosed 管理器**按 versionCode 缓存**模块图标，改了图标不升版本不会刷新。
- **修法**：补 `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`，
  并把 `versionCode` 从 9 升到 10。
- **教训**：以后每次改图标/包内资源，都升 `versionCode`。

### 6. 重装 APK 后目标主进程不再被注入

- **现象**：只有 `:message` 子进程有 `Loading xposed` / `正在注入`，主进程没有。
- **结论**：LSPosed 1.9.2 + MIUI 的环境问题，重装模块后常见。
- **修法**：在 LSPosed 里把目标应用的作用域**取消勾选再勾选**一次（或重启）。
  不是代码 bug，不要在这上面改代码。

### 7. 调试观测的三个坑

- `adb logcat -c` 之前进程已经启动 → 注入日志被清掉，误判成"没注入"。
  正确顺序：先 `force-stop` 目标 → 确认 `pidof` 为空 → 清日志 → 启动。
- 用 `/proc/<pid>/maps` 查模块 APK 是**无效**的：LSPosed 从内存 dex 加载，不会映射 APK。
- 直接改 `/data/data/<pkg>/shared_prefs/*.xml` 会被运行中进程的**内存缓存覆盖**，
  调试开关应该走 App 自己的代码路径（本项目提供 `--ez keep_alive true` 入口）。

## 验证方法

**无触摸测试**（MIUI 禁止 `adb shell input tap`，报 INJECT_EVENTS）：

```bash
# 弹窗渲染与计时预览
adb shell am start -n com.example.selfxposed/.MainActivity \
  --ez preview_popup true --es preview_text "测试" \
  --ei preview_show_ms 5000 --ei preview_fade_ms 1000

# 开关后台常驻
adb shell am start -n com.example.selfxposed/.MainActivity --ez keep_alive true
```

**端到端**：`force-stop 模块` → `force-stop 目标` → `am start` 目标 → 3.2s 后 `screencap`
→ 确认截图底部有弹窗，同时 logcat 有
`自用模块: <包名> 显示弹窗 attached=true showMs=... fadeMs=...`。

**日志位置**：logcat 的 `LSPosed-Bridge` 标签 + 模块内「设置 → 实时日志」（存 SharedPreferences，
最多 300 条）。

## 发布检查清单

- [ ] 改了图标/资源 → 升 `versionCode`
- [ ] 安装后：确认常驻开关、系统自启动权限
- [ ] 若目标应用突然不弹：先在 LSPosed 里重勾一次作用域
- [ ] 真机验证一次 `attached=true` 日志
