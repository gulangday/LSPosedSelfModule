# 开发记录与踩坑清单

环境：`D:\DeepSeekHarness\projeckt`，`com.example.selfxposed`；JDK17 + `gradlew assembleDebug`；Redmi/Android 14 + LSPosed 1.9.2 + MIUI。

## 坑

1. **模块完全不工作**：类静态初始化里 `new Handler` → `MainHook.<clinit>` NPE（注入时主线程 Looper 未就绪）。改 `by lazy`。
2. **弹窗闪一下又出现**：挂在 Splash 上，启动页销毁即消失。跳过 splash/welcome 类 Activity，延迟 400ms 再挂。
3. **模块被划掉后不弹**：MIUI 拦截唤醒（`WakePathChecker Reject`）。需开系统「自启动」+ 模块内「后台常驻」，缺一不可。
4. **XSharedPreferences 不可用**：LSPosed 1.9.2 下返回 null（stat 都失败，加 `xposedsharedprefs` 也没用）。跨进程只能走 Provider。
5. **LSPosed 图标是默认图**：只有 anydpi-v26 自适应图标 + 管理器按 versionCode 缓存图标。补 `mipmap-*dpi` PNG 并升 versionCode。
6. **重装后主进程不注入**：只有 `:message` 子进程有日志。去 LSPosed 重勾一次作用域，不是代码问题。
7. **调试观测**：先 `force-stop` 确认无进程再 `logcat -c`；`/proc/pid/maps` 查不到模块 APK 属正常；直接改 `shared_prefs/*.xml` 会被内存缓存覆盖。

## 验证

```bash
# 无触摸（MIUI 禁 adb input tap）
adb shell am start -n com.example.selfxposed/.MainActivity \
  --ez preview_popup true --es preview_text "测试" --ei preview_show_ms 5000
# 端到端：force-stop 模块→force-stop 目标→启动→3.2s 截图→日志含 "显示弹窗 attached=true"
```

## 发布清单

- [ ] 改图标/资源 → 升 versionCode
- [ ] 装完确认：常驻开关、系统自启动
- [ ] 不弹先重勾作用域
- [ ] 跑一次 `attached=true`
