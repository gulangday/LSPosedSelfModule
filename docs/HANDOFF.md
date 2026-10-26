# 交接文档

用于多步骤改造后的交接。目的是让接手的人（或下一个模型）不用读全部代码，就能知道：改了什么、怎么验证的、还差什么。

## 模板（复制这一节）

```markdown
## YYYY-MM-DD · 标题（一句话）

### 目标
本次要解决的问题 / 用户原话要点。

### 改动
| 文件 | 改了什么 |
|---|---|
| `MainHook.kt` | … |
| `ui/XxxScreen.kt` | … |

### 关键设计
- 数据落在哪（模块 Provider / 目标应用本地文件 / 模块 prefs）
- 用了哪条通道（Provider / Shizuku / 直接 su）
- 新增的常量、标记、状态字段

### 验证
| 项 | 方式 | 结果 |
|---|---|---|
| 编译 | `gradlew assembleDebug` | ✅ |
| 装机 | `adb install -r` | ✅ |
| 功能 | 启动 X 应用，观察 Y | ✅ / ⚠️ 未验证 |

### 遗留问题 / 风险
- …

### 下一步建议
- …
```

## 约定

1. **只记事实**：写"验证过什么"，不要写"应该可以"。
2. 未验证项必须标 ⚠️，并说明原因（例如：adb 模拟点击被系统忽略，需人工验证）。
3. 涉及接口变化（`ScreenHost`、`ConfigClient` 常量、AIDL）必须单独列出，方便审查者核对调用方是否同步。
4. 一节对应一次改造，新的写在最上面（倒序）。

---

## 2026-10-25 · 弹窗拦截完整链路（悬浮球 / 自动两种方式）

### 目标
把"去更新"演化成通用的弹窗拦截：支持点选学习规则、自动学习规则、关键字、截图回溯、以及 Root/Shizuku/非Root 三种工作模式。

### 改动
| 文件 | 改了什么 |
|---|---|
| `MainHook.kt` | 弹窗守卫、悬浮球、点选拾取、描边、截图、规则读写、自动询问卡、长按规则菜单 |
| `ShizukuBridge.kt`（新增） | Shizuku UserService 绑定、权限、执行命令 |
| `RemoteService.kt`（新增） | Shizuku 远程进程；必须继承 `IRemoteService.Stub` |
| `IRemoteService.aidl`（新增） | `runCommand(String)` |
| `RootShell.kt`（新增） | 统一命令通道：Shizuku → 直接 su（绝对路径、候选列表）→ 失败返回空 |
| `WorkMode.kt`（新增） | Root / Shizuku / 非Root，文件存储（避免 SharedPreferences 缓存） |
| `Keywords.kt`、`HintStyle.kt`、`HintStyleWriter.kt`、`DialogRules.kt`（新增） | 关键字、提示样式、规则读写 |
| `MainActivity.kt` | 三模式选择、路由、横屏侧边栏、系统返回键修复 |
| `ui/UpdateBlockScreen.kt` | 拦截方式分段切换（局部刷新）、悬浮球开关、提示样式归属 |
| `ui/DialogRulesScreen.kt` | 规则 + 截图记录列表（缩略图 + 标题 + 时间），清除规则同时删截图 |
| `ui/HintStyleScreen.kt`、`ui/KeywordScreen.kt`（新增） | 预设/自定义样式、关键字与模板 |
| `data/ModuleRepository.kt` | Hook 状态缓存 + 后台刷新（避免主线程 su 卡启动） |

### 关键设计
- 规则、关键字、拦截方式、截图都存在**目标应用自己的目录**，因为 targetSdk 35 的应用调不到模块 Provider。
- 读取这些数据用 `RootShell`：优先 Shizuku（本机 Shizuku 以 root 启动，uid=0），其次直接 su，最后空。
- Shizuku 的 UserService 必须直接实现 Binder（写成 `android.app.Service` 会 `ClassCastException`）。
- Shizuku 首次绑定约 4 秒，页面显示"正在连接"并后台等待后自动刷新。

### 验证
| 项 | 方式 | 结果 |
|---|---|---|
| 编译 | `gradlew assembleDebug` | ✅ |
| 装机 | `adb install -r` | ✅ |
| 规则拦截 + 截图 | 启动游戏，日志出现"已保存拦截截图" | ✅ |
| 规则页读取 | Shizuku 下读到 `mode=block` 并显示截图 | ✅ |
| 悬浮球 | 游戏内右侧出现「拦」，弹窗时挂到弹窗右上角 | ✅ |
| 点选拾取（手动点击） | 需人工：点球 → 点弹窗 → 底部信息条 | ⚠️ 未验证（adb 模拟点击被系统忽略） |
| 描边范围 | 已改为框内容卡片，需人工确认 | ⚠️ 未验证 |

### 遗留问题 / 风险
- Unity 内部绘制的 UI 无法识别，只能拦 Android 原生 `Dialog`。
- 点选拾取依赖 `Dialog.dispatchTouchEvent`，自绘浮层（非 Dialog）拦不到。
- 横屏侧边栏布局只在 792dp 宽度下验证过，平板未测。
- Shizuku 若以 shell 身份启动（非 root），读取其他应用私有目录会失败，只能走 `su` 借道。

### 下一步建议
- 人工验证点选拾取与描边范围。
- 若拦截记录变多，考虑规则页分页或按时间折叠。
