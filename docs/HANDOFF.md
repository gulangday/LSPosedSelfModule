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

## 2026-11-22 · 注入状态改为以当前作用域为准

### 目标
用户取消电影猎手的作用域后，主页仍显示「注入：已检测」。原因是只看了历史 `selfxposed_seen` 标记。

### 改动
| 文件 | 改了什么 |
|---|---|
| `LsposedScope.kt` | `isInScope` 增加 `force`；新增 `cached` 与 `invalidate` |
| `ui/HomeScreen.kt` | 状态按作用域优先：未勾选显示「作用域未勾选」；后台刷新时强制查一次作用域；点击状态强制重查 |

### 关键设计
- 作用域为 `false` 时即使有旧注入标记，也显示「作用域未勾选」。
- 作用域为 `true` 且 `seenAt > 0` 才显示「已检测」。
- 作用域查询失败时显示「已检测（作用域未确认）」或「待检测」，不误报。
- 修复 SQL 拼接：列名已带别名时不再重复拼 `m.` / `s.`，否则查询永远报错返回 null。
- 修复复制文件权限：复制后 `chown` 给模块 App 并设 600；用读写模式打开缓存副本，让 WAL 能正常恢复。

### 验证
| 项 | 方式 | 结果 |
|---|---|---|
| 编译 | `assembleDebug` | ✅ BUILD SUCCESSFUL（versionCode 13 / 2.2.0-beta3） |
| 装机 | `adb install -r` | ⚠️ 未执行：新 APK 已生成 |

---

## 2026-11-22 · 添加应用时提示 LSPosed 作用域状态

### 目标
模块里添加应用后，如果 LSPosed 作用域没勾选，用户没有任何提示，容易误以为模块已经生效。

### 改动
| 文件 | 改了什么 |
|---|---|
| `TargetFiles.kt` | 合并读取增加 `@@E` 段，读取目标应用 `files/selfxposed_seen`，新增 `seenAt` |
| `LsposedScope.kt`（新增） | Root/Shizuku 复制 `modules_config.db`（含 wal/shm）到缓存，只读查询 `scope` 表，返回 true/false/null |
| `ui/PickerScreen.kt` | 添加应用后后台检查作用域与注入标记，按结果弹「未勾选 / 已勾选待注入 / 无法确认」 |
| `ui/HomeScreen.kt` | 卡片增加「注入：已检测 / 待检测 / 模块未启用」，点击重新检查并弹提示 |
| `MainActivity.kt`、`ui/ScreenHost.kt` | 新增 `openLsposed()`，跳转 LSPosed 管理器 |

### 关键设计
- 数据库结构已在设备上确认：`scope(module_pkg_name, app_pkg_name, user_id)`、`modules(module_pkg_name, apk_path)`。
- 查询失败（无 Root、数据库结构变化、复制/打开失败）返回 null，UI 只提示「无法确认」，不误报「未勾选」。
- 有注入标记但作用域查询为 false 时，以「未勾选」提示（旧标记不当作当前有效）。
- 结果缓存 10 秒，避免每个卡片都重复复制数据库。

### 验证
| 项 | 方式 | 结果 |
|---|---|---|
| 编译 | `assembleDebug` | ✅ BUILD SUCCESSFUL（versionCode 13 / 2.2.0-beta3） |
| 数据库结构 | Shizuku 只读读取建表 SQL | ✅ `scope`/`modules` 字段与查询匹配 |
| 装机 | `adb install -r` | ⚠️ 未执行：新 APK 已生成，待安装 |

---

## 2026-11-22 · 净化「隐藏」预览按保存规则显示

### 目标
用户要求「隐藏」预览与点「✓」保存后的命中范围一致，避免预览只隐藏一个、保存却隐藏整页。

### 改动
| 文件 | 改了什么 |
|---|---|
| `MainHook.kt` | 新增 `purifyRuleSelector`（ID → 文字 → 非通用类名），预览和保存共用；`hideButton` 按该规则遍历整页临时隐藏所有命中控件，并套用与保存相同的 70% 跳过；支持成批恢复 |

### 关键设计
- 预览与 `savePurifyRule` 使用同一个 selector，不再分别判断。
- 恢复覆盖：再点「隐藏」、切换候选点、点「✗」、点「✓」都会恢复所有临时隐藏的控件。
- 不可生成规则的控件（系统通用类）：预览不隐藏，与「✓」被拦一致。

### 验证
| 项 | 方式 | 结果 |
|---|---|---|
| 编译 | `assembleDebug` | ✅ BUILD SUCCESSFUL（versionCode 13 / 2.2.0-beta3） |
| 装机 | `adb install -r` | ⚠️ 未执行：无设备连接 |

---

## 2026-11-22 · 净化刷新保留位置、确认后收起选区、删除规则动画

### 目标
三项交互优化：净化页返回刷新保留页面与滚动位置；能正常净化时点「✓」收起选区（不能净化时保留）；删除规则不再整页重建导致弹一下。

### 改动
| 文件 | 改了什么 |
|---|---|
| `MainActivity.kt` | 净化页 `onResume` 改为 `invalidate + refresh()`，保留当前页与 `scrollY` |
| `MainHook.kt` | 新增 `canBuildPurifyRule`；可建规则时点「✓」移除点选层，不可建时保留选区等用户重选 |
| `ui/PurifyScreen.kt` | 删除规则后只对单张卡片做 180ms 淡出再移除，原地更新「规则（n）」，不再整页刷新 |

### 关键设计
- `refresh()` 会记录并恢复 `scrollY`，所以从目标应用返回时不会跳回顶部。
- 删除失败时卡片不动，只有写盘成功才播放动画并移除。
- 规则数标题直接改文本，不重建页面。

### 验证
| 项 | 方式 | 结果 |
|---|---|---|
| 编译 | `assembleDebug` | ✅ BUILD SUCCESSFUL（versionCode 13 / 2.2.0-beta3） |
| 装机 | `adb install -r` | ⚠️ 未执行：无设备连接 |

---

## 2026-11-22 · 净化交互调整与删除「弹窗」功能

### 目标
用户提出四项优化：提示后保留选区、悬浮球不再弹菜单、返回净化页自动刷新、删除功能页的「弹窗」底部提示。

### 改动
| 文件 | 改了什么 |
|---|---|
| `MainHook.kt` | 点「✓ 用它」不再移除点选层，提示/动作卡关闭后选区保留；点悬浮球直接进入点选（净化优先，否则弹窗拾取）；删除弹窗提醒 hook 与相关辅助代码 |
| `MainActivity.kt` | `onResume` 中对净化页 `TargetFiles.invalidate` 后重渲染；删除 `PAGE_APP`、`openApp`、预览弹窗入口 |
| `ui/FeaturesScreen.kt` | 删除「弹窗」卡片及计数逻辑 |
| `ui/ScreenHost.kt` | 删除 `openApp` |
| `Features.kt`、`ConfigClient.kt` | 删除 `POPUP` 常量与弹窗配置默认值 |
| `ui/AppSettingsScreen.kt`、`PopupController.kt` | 删除文件 |
| `README.md` | 删除弹窗提醒描述 |

### 关键设计
- 点选层现在只在按「✗」时关闭；保存规则后仍保留，便于连续处理。
- 悬浮球点击：净化开启 → `startPurifyPicking`；仅拦截弹窗开启 → `startPicking`。
- 删除「弹窗」后，旧 features.conf 里的 `popup=1` 不再有任何执行路径。

### 验证
| 项 | 方式 | 结果 |
|---|---|---|
| 编译 | `assembleDebug` | ✅ BUILD SUCCESSFUL（versionCode 13 / 2.2.0-beta3） |
| APK | 检查 dex | ✅ 已无 `PopupController` |
| 装机 | `adb install -r` | ⚠️ 未执行：无设备连接 |

### 遗留问题 / 风险
- 版本号未变（仍为 13 / beta3），覆盖安装时请确认文件时间/SHA。
- 删除文件不可逆；当前目录不是 git 仓库。

---

## 2026-11-22 · 净化禁止按系统通用类保存

### 目标
用户点选一个没有 ID 的容器后，净化自动退化为「按类名」（如 `android.widget.LinearLayout`），结果把整页同类控件全部移除，页面空白。

### 改动
| 文件 | 改了什么 |
|---|---|
| `PurifyRules.kt` | 新增 `isGenericClassName`；`class` 规则命中时跳过系统通用类，旧规则也不会再误伤 |
| `MainHook.kt` | 无 ID 且无文字的通用类控件不再弹动作卡，直接提示改选子控件；「按类名」选项对通用类同样拦截；点选底部增加「占屏 N%」 |
| 版本 | versionCode 12 → 13，versionName 2.2.0-beta2 → 2.2.0-beta3 |

### 关键设计
- 通用类前缀：`android.widget.`、`android.view.`、`android.app.`、`android.webkit.`、`androidx.`、`android.support.`、`com.google.android.material.`。
- 已有误建规则不会自动删除，但运行时不再匹配，不会继续黑屏；建议在净化页长按删除。

### 验证
| 项 | 方式 | 结果 |
|---|---|---|
| 编译 | `assembleDebug` | ✅ BUILD SUCCESSFUL，versionCode 13 / 2.2.0-beta3 |
| 装机 | `adb install -r` | ⚠️ 未执行：无设备连接 |

---

## 2026-11-22 · 净化页补悬浮球开关

### 目标
用户反馈「没有开启悬浮球的设置」。排查发现悬浮球开关只在「拦截弹窗」页、且仅悬浮窗模式下可见；净化页没有入口，选「自动拦截」时净化也拿不到球。

### 改动
| 文件 | 改了什么 |
|---|---|
| `MainHook.kt` | `installPickBall` 改为：净化开启时无视拦截方式都显示悬浮球 |
| `ui/PurifyScreen.kt` | 新增「悬浮球」开关卡片，读写目标应用 `files/selfxposed_ball_off`，可恢复长按关闭的球 |
| `app/module.toml`、`.platform/gradle-backup/app/build.gradle.kts` | versionCode 11 → 12，versionName 2.2.0-beta1 → 2.2.0-beta2 |

### 关键设计
- 球的显隐仍受总开关和「拦截弹窗/净化」任一开启控制；关闭标记仍写目标应用本地文件，重启目标应用生效。
- 拦截方式只影响弹窗拦截（悬浮窗/自动），不冉影响净化点选入口。

### 验证
| 项 | 方式 | 结果 |
|---|---|---|
| 编译 | `assembleDebug` | ✅ BUILD SUCCESSFUL，APK 内含悬浮球开关文案 |
| 装机 | `adb install -r` | ⚠️ 未执行：无设备连接 |

---

## 2026-11-22 · 净化支持同类控件与持续扫描

### 目标
用户希望在一个页面点选净化后，其它页面的同类广告位自动隐藏，不用逐页点选。

### 改动
| 文件 | 改了什么 |
|---|---|
| `PurifyRules.kt` | 新增匹配类型 `id_contains`（控件 ID 包含关键字）；格式不变，老规则兼容 |
| `MainHook.kt` | 点选动作卡新增「移除同类 · ID 包含关键字」与「移除同类 · 按类名」；新增 `OnGlobalLayout` 监听，界面存活期间每 400ms 最多扫描一次规则 |

### 关键设计
- 首页/探索/我的通常是同一个 Activity 换 Fragment，不会重新走 `onCreate`；原实现只在创建后扫 2.4 秒，后续页面必然漏。现在监听器活到界面销毁，新规则也会在下一轮扫描生效。
- 「ID 包含」适合 `home_ad_container` / `feed_ad_container` 这类同前缀 ID；「按类名」适合专用广告容器类，但通用类（如 LinearLayout）会误伤，菜单已标「谨慎」。
- `purifyInstalled` 不再在延迟轮询结束后移除，避免重复安装监听；集合本身是弱引用，界面销毁后自动清理。
- 黑屏保护（占屏 ≥70%）保留；动作仍是 GONE + 尺寸归零，不改 View 树。

### 验证
| 项 | 方式 | 结果 |
|---|---|---|
| 编译 | `assembleDebug`（临时工程 /tmp/lsposed-build） | ✅ BUILD SUCCESSFUL，APK 内含「移除同类」选项 |
| 装机 | `adb install -r` | ⚠️ 未执行：无设备连接 |
| 功能 | 首页点选同类规则后切换 Tab，观察其它页面广告位 | ⚠️ 未验证 |

### 遗留问题 / 风险
- 若多个页面的广告容器 ID 与类名都不同，「同类」仍匹配不到，需要提供各页面详情里的 ID/类名后加针对性规则。
- 持续扫描每 400ms 最多一次，界面根节点很深时仍有轻微开销；若发现卡顿可调大 `PURIFY_LAYOUT_THROTTLE`。

### 下一步建议
- 在首页对广告容器选「移除同类 · ID 包含关键字」，关键字取 ID 里稳定的片段（如 `ad`、`banner`），然后依次切到其它 Tab 验证。

---

## 2026-11-22 · 去广告补齐跳转入口、OkHttp 与总开关

### 目标
用户反馈「某个软件里去广告没生效」。无具体应用与广告类型信息，先修可复现的通用缺口。

### 改动
| 文件 | 改了什么 |
|---|---|
| `MainHook.kt` | 跳转拦截扩到 `Activity.startActivity` / `startActivityForResult` / `Instrumentation.execStartActivity`；新增 OkHttp `newCall` 拦截；DNS 增加 `getByName`；去广告各入口补 `MASTER && AD` 双判与 1 秒开关缓存 |

### 关键设计
- 原有 hook 只挂 `ContextWrapper.startActivity`，但 `Activity` 重写了该方法，广告 SDK 拿 Activity 引用跳转时不会经过；`startActivityForResult` 之前完全没挂。
- OkHttp 用运行时 `findClassIfExists("okhttp3.OkHttpClient", loader)` 反射挂 `newCall`，命中域名后 `call.cancel()`；不新增依赖，没打包 OkHttp 就跳过。
- DNS 同时拦 `getAllByName`（返回空数组）与 `getByName`（返回 0.0.0.0，避免空值把调用方搞崩）。
- 开关读取新增 `isAdOn()`：`Features.MASTER && Features.AD`，缓存 1 秒，避免每个网络请求都读文件（E2/H6）。

### 验证
| 项 | 方式 | 结果 |
|---|---|---|
| 编译 | `assembleDebug`（临时工程 /tmp/lsposed-build，JDK 17 + compileSdk 35 + buildTools 35.0.0） | ✅ BUILD SUCCESSFUL；APK 内含 `okhttp3.OkHttpClient` 新增拦截代码 |
| 装机 | `adb install -r` | ⚠️ 未执行：无设备连接 |
| 功能 | 目标应用启动开屏、捕获日志「去广告：」 | ⚠️ 未验证 |

### 遗留问题 / 风险
- 未知 SDK 的广告若用自绘 View / 信息流 / HTTPDNS / IP 直连，仍拦不到；需拿到目标包名和「记录请求域名」日志后补黑名单。
- OkHttp 被混淆改名（非 `okhttp3`）或 SDK 自带私有网络栈时，该 hook 不生效。

### 下一步建议
- 装到设备后打开去广告 + 记录请求域名，复现一次，把日志里的广告域名补进默认清单。
- 若开屏仍出现，看日志有没有「已拦广告页跳转」；没有则用净化规则处理对应界面。


---

## 2026-10-25 · 翻译安全开关（带符号不翻）+ 日志可选中/一键复制

### 目标
用户反馈：怕翻译把格式串（`%d`、`{0}`、`<b>`）翻坏导致闪退，要求加一个「带符号的不翻译」开关；日志页要能把日志复制出来（翻译诊断会把抓到的英文字符串写进日志，需要拷出来加进词库）。

日志复制的正确形态（用户明确纠正过一版）：**整块日志像文本框一样可长按选中**（系统全选/拖动手柄选一段），**另加一个「一键复制全部日志」按钮**。不是逐条加按钮。

### 改动
| 文件 | 改了什么 |
|---|---|
| `TranslateDict.kt` | 新增 `CONFIG_FILE`、`RISKY_SYMBOLS`、`hasRiskySymbols`；`shouldSkip(text, skipSymbols)`、`translate(text, dict, skipSymbols)` 多接开关参数（默认 true）；新增 `parseOptions`、`optionsText`、`loadLocalSkipSymbols`、`readRemoteSkipSymbols`、`writeRemoteOptions` |
| `TargetFiles.kt` | 批量命令加 `@@S` 段；`parse` 加 `@@S` 分支；`TargetState` 加 `translateSkipSymbols` |
| `MainHook.kt` | 新增 `translateSkipSymbols(context)`（3 秒缓存）+ `translateSkipSymbolsCache/At`；资源层 hook 与 `applyTranslateRound` 都传入该开关 |
| `ui/TranslateScreen.kt` | 新增「带符号的不翻译」卡片（statusChip 切换 + 说明为什么默认开） |
| `ui/LogsScreen.kt` | 日志整体仍是一个 `TextView`，加 `setTextIsSelectable(true)` 让它可长按选中；新增「一键复制全部日志」按钮（`ClipboardManager` 复制整段）；有日志时给一句操作提示。**不做逐条拆行** |

### 关键设计
- **默认开、可关**：`RISKY_SYMBOLS = "%{}<>&=\$#@|~\`"`，命中整串跳过。普通标点（逗号、句号、括号、连字符）不在此列，照常翻译。关掉能多翻，但可能崩。
- **开关走三处一致**：资源层 hook、控件扫描、`TargetState` 读取都用同一个字段，避免"页面显示开着、实际没生效"。
- **`setTextIsSelectable(true)`**：这是拿到系统「全选 / 复制 / 拖动选择手柄」的唯一方式，自己画按钮做不到"拉动选区"。注意必须同时 `isLongClickable = true`，否则可能被上层拦截。
- **一键复制走 `ClipboardManager`**：直接复制当前显示的整段日志，不依赖选区。

### 验证
| 项 | 方式 | 结果 |
|---|---|---|
| 编译 | `run_task assemble:app:debug` | ✅ BUILD SUCCESSFUL（8 警告均为改动前已有） |
| 功能：带符号的跳过 | 「试翻一下」贴 `共 100 项` 应原样不动 | ⚠️ 未验证 |
| 功能：日志长按选中 | 长按日志文字，应出现系统全选/复制菜单，可拖手柄选一段 | ⚠️ 未验证 |
| 功能：一键复制全部 | 点「一键复制全部日志」 | ⚠️ 未验证 |

### 遗留问题 / 风险
- 翻译页「试翻一下」调用 `TranslateDict.translate(source, dict)` 时没显式传开关，依赖默认值 `true`；若用户在页面把开关关掉，试翻仍按"跳过"计算，与实际不一致（行为安全，但显示不完全一致）。
- 日志页渲染上限 200 条，超出部分不显示（数据仍在，清空前可再看到更早的吗——不能，因为取的是最近 N 条）。
- 全部功能项仍未装机验证。

### 下一步建议
- 装机后先用「试翻一下」验证符号开关，再验日志复制的两种方式。
- 若日志里的英文样本想批量加词，可考虑后续加「从日志一键加入词条」。

---

## 2026-10-25 · 翻译方法一：字符串资源层翻译 + 诊断

### 目标
用户实测：翻译页「试翻一下」有效，但 CodeAssist 界面完全不翻。判断该界面是新框架（Compose）自绘，没有可替换的文字控件。用户同意做「方法一：拦字符串资源」，并要求先加诊断确认。

### 改动
| 文件 | 改了什么 |
|---|---|
| `MainHook.kt` | `handleLoadPackage` 增加 `installTranslateResourceHooks(pkg, loader)`；新增 `installTranslateResourceHooks`（hook `Resources.getString(int)` / `getText(int)` / `getString(int, Object[])`，命中词库直接替换返回值）、`isTranslateOn`（开关缓存）；`applyTranslateRound` 增加诊断计数与结论日志；companion 增加 `translateFlagCache/At`、`TRANSLATE_FLAG_TTL`(2000)、`translateResourceCalls`、`TRANSLATE_DIAG_SAMPLES`(12)、`TRANSLATE_DIAG_DELAY`(6000) |

### 关键设计
- **绕开框架差异**：Compose / 传统 View 最终都要从字符串资源取文字。在 `Resources.getString` 返回值上替换，界面用什么画都无所谓。这是对原有「改文字控件」的补充，两条并行。
- **性能**：资源层 hook 每秒可能被调用上千次，所以开关状态走 2 秒缓存（`isTranslateOn`），词库走既有 3 秒缓存，不重复读文件。
- **幂等**：译文是中文，`TranslateDict.shouldSkip` 判定无英文字母直接返回，不会反复翻。
- **诊断**：进程内计数取字符串次数，前 12 条样本写 Xposed 日志；启动 6 秒后往模块日志写一条结论——`0 次` 说明文字写死在代码里（资源层也救不了），`N 次` 说明这条通道是通的。

### 验证
| 项 | 方式 | 结果 |
|---|---|---|
| 编译 | `run_task assemble:app:debug` | ✅ BUILD SUCCESSFUL（8 警告均为改动前已有） |
| 功能：资源层翻译生效 | 开翻译 → 进 CodeAssist 设置页 | ⚠️ 未验证 |
| 功能：诊断结论准确 | 模块 → 翻译页 → 查看日志，看「取了 N 次文字」 | ⚠️ 未验证 |

### 遗留问题 / 风险
- **可能翻坏不该翻的**：资源字符串也被用于日志、键名、JSON、拼接格式串。`shouldSkip` 只挡了网址/邮箱/版本号/无字母/超长，其余仍会尝试翻译。
- **格式串风险**：带 `%s` / `%d` 的资源经拆词翻译后，占位符顺序可能错乱，可能导致 `String.format` 抛异常。**这是当前最大风险**，若目标应用出现闪退，优先怀疑这里。
- 若应用文字写死在代码里（不用资源），方法一同样无效，只剩截图识别方案。
- 功能项仍未装机验证。

### 下一步建议
- 装机后先看诊断日志的计数，确认通道是否通。
- 若出现闪退，给「资源层翻译」加白名单：只翻纯字母+空格的字符串，含 `%`、`{`、`}`、`=`、`<`、`>` 的一律跳过。

---

## 2026-10-25 · 翻译改进：长句拆词翻译 + 补开发词库

### 目标
用户实测截图反馈：CodeAssist 设置页大片英文没翻出来（`Provider`、`Used when the provider is Anthropic.`、`Optional. Leave blank to use the newest model...`、`Anthropic API key` 等）。要求改到能翻出来。

### 根因（三条，都不在词库大小上）
1. **只做整串匹配**：`translate()` 拿整串查表，查不到就 `return null`。所以「短的词恰好收进词库」能翻，「长句子」整句放弃、一个字都不动——这是主因。
2. **长度上限太严**：`shouldSkip` 里 `value.length > 120` 就跳过，而设置页那句说明有 170+ 字符，直接被挡。
3. **词库缺开发类词**：`provider` / `api key` / `model` / `gateway` / `token` 这类完全没有。

### 改动
| 文件 | 改了什么 |
|---|---|
| `TranslateDict.kt` | `translate()` 改为两步：`exactTranslate()`（原整串逻辑，抽出成私有方法）→ 失败再 `wordTranslate()`（拆词）。新增 `WORD` 正则、`MAX_WORDS`(4)、`exactTranslate`、`wordTranslate`；`shouldSkip` 长度上限 120 → 400；`BUILTIN_RAW` 追加约 250 条（开发类词 + 句子里的小词：`is/are/when/which/this/your/and/or/of/to/for/use/set/get` 等） |
| `ui/TranslateScreen.kt` | 新增「试翻一下」卡片：贴一句英文点翻译，用和 hook 同一套逻辑当场出结果，不用来回切软件验证；底部说明补一条「长句子会拆成词翻，结果可能中英混着」 |

### 关键设计
- **两步翻译**：先整串（「Settings:」仍能翻成「设置：」），失败才拆词。整串优先保证常用词翻得准。
- **只在有空格时才拆词**：`text.none { it == ' ' || '\t' || '\n' }` 直接返回 null。单个 token（包名、变量名、`api_key` 这类标识符）**只允许整串精确命中**，避免把标识符改坏——这是防止翻出事故的关键限制。
- **词组最长优先**：一次最多合并 `MAX_WORDS`(4) 个词查表，所以 `api key`、`check for updates` 这类词组优先于单词命中。
- **词间必须纯空白才合并**：`abc-def`、`a_b` 这种带标点的不会被硬凑成词组。
- **一个词都没命中就返回 null**：保持原文不动，不产生半截改动。
- **幂等**：译文是中文，`shouldSkip` 判定无英文字母直接返回，不会反复翻。

### 验证
| 项 | 方式 | 结果 |
|---|---|---|
| 编译 | `run_task assemble:app:debug` | ✅ BUILD SUCCESSFUL（8 警告均为改动前已有） |
| 功能：长句能翻出中文 | 翻译页「试翻一下」贴 `Used when the provider is Anthropic.` | ⚠️ 未验证 |
| 功能：CodeAssist 设置页生效 | 开翻译后进 CodeAssist 设置 | ⚠️ 未验证 |
| 功能：标识符不被改坏 | 观察包名、`sk-ant-...` 等 | ⚠️ 未验证 |

### 遗留问题 / 风险
- **结果是中英混杂**：拆词翻译的必然结果，例如「使用 当 the 提供方 是 Anthropic.」。这是「能翻出大半」换来的代价，比整句英文可读，但不是通顺中文。
- **`on` / `off` 在句子里会怪**：这两个词为开关状态映射成「开/关」，出现在句子中间（如 `Used on Gemini`）会读着别扭。留作已知问题。
- **仍会丢样式**：`textView.text = 译文` 只保留纯文本，颜色/加粗会掉。
- **网页、Flutter/Unity 自绘界面、游戏、图片里的字仍然翻不到**。
- 词库目前约 750 条（500 + 250），长尾词仍需用户自己补。

### 下一步建议
- 装机后先用「试翻一下」验证逻辑，再进 CodeAssist 看实际效果；对不理想的具体词用「自己加词条」补。
- 若「中英混杂」影响阅读，下一版可考虑：句子里没命中的词也做音译/保留但去掉冠词，或引入简单的句式归并。

---

## 2026-10-25 · 翻译功能（纯离线词库，把英文界面换中文）

### 目标
用户要能翻译别的软件的英文界面。讨论后定：纯离线（不联网、不闪、文字不出手机），内置大词库 + 自己加词条 + 导入导出。

### 改动
| 文件 | 改了什么 |
|---|---|
| `TranslateDict.kt`（新增） | 内置词库（约 500 条常用界面词，整句优先）；`parse`、`loadLocal`、`loadLocalUser`、`saveLocalUser`、`readRemoteUser`、`writeRemoteUser`、`shouldSkip`、`translate`、`exportText`、`builtinCount` |
| `Features.kt` | 新增 `TRANSLATE = "translate"` |
| `MainHook.kt` | `onActivityResumed` 调 `installTranslateHooks`；新增 `installTranslateHooks`、`translateDict`、`applyTranslateRound`；companion 加 `translateInstalled`（weak set）、`translateInstalledLock`、`translateDictCache/At`、`TRANSLATE_DICT_TTL`(3000)、`TRANSLATE_SCAN_DELAY`(700)、`TRANSLATE_SCAN_ROUNDS`(4)、`TRANSLATE_PRE_DRAW_ROUNDS`(3)、`translateLogged`、`translateVerboseLogged` |
| `TargetFiles.kt` | `TargetState` 加 `translateUser`；批量命令加 `@@T` 段；`parse` 加 `@@T` 分支；返回时 `TranslateDict.parse(...)` |
| `ui/TranslateScreen.kt`（新增） | 翻译页：返回行、无能力 → `lockedCard`、`master` 未开 → 置灰、翻译开关 chip、「能翻什么」说明、词库概况、加词条表单、自定义词条列表（长按删 + 二次确认）、导入导出、看日志 |
| `ui/FeaturesScreen.kt` | 加「翻译」功能卡，走 `pickTarget(Features.TRANSLATE)` |
| `ui/ScreenHost.kt` | 新增 `openTranslate`、`exportTranslateDict`、`importTranslateDict` |
| `MainActivity.kt` | `PAGE_TRANSLATE` 常量、`import TranslateScreen`、`updateNav` 归「功能」组、`renderPage` 分支、`openTranslate`、词条导入导出（`REQ_DICT_EXPORT`=1003 / `REQ_DICT_IMPORT`=1004 + `pendingDictExportContent` / `pendingDictImportPackage` + `onActivityResult` 两个分支） |

### 关键设计
- **纯离线**：只查本地对照表，不联网。翻不到就保持英文原样，**不硬翻**——避免三件事：界面闪动、把用户名/网址翻坏、文字被发到服务器。
- **不翻的内容**（`shouldSkip`）：网址、邮箱、版本号、超长文本、不含任何英文字母的字符串。
- **整串匹配**：按整个字符串查表，不做词内替换。结尾的 `:` `：` `?` `！` `…` 和单个句点先剥掉再查、查到后补回，所以「Settings:」能命中「settings」。
- **输入框不碰**：`EditText` 直接跳过，否则用户正在打的字会被换掉。
- **防闪**：`OnPreDraw` 在画面出来前替换（`TRANSLATE_PRE_DRAW_ROUNDS`=3 帧后摘监听），再补 4 轮延迟扫描兜底后加载的文字。
- **不会反复翻**：译文是中文，`shouldSkip` 判定无英文字母直接返回，天然幂等。
- **数据落点**：用户词条写目标应用 `files/selfxposed_translate_user.conf`（`英文小写=中文`），符合 §3；内置词库编译进代码。
- **`@@T` 协议位**：随 `TargetFiles` 那条批量命令一次取回，页面不用单独跑 root。
- **接口变化（需审查）**：`ScreenHost` 新增 3 个方法，`MainActivity` 已全部实现。

### 验证
| 项 | 方式 | 结果 |
|---|---|---|
| 编译 | `run_task assemble:app:debug` | ✅ BUILD SUCCESSFUL（8 警告均为改动前已有） |
| 功能：英文界面变中文 | 开翻译 → 进纯英文界面看按钮/菜单 | ⚠️ 未验证 |
| 功能：输入框内容不被替换 | 在目标应用输入框打字 | ⚠️ 未验证 |
| 功能：加词条后生效 | 加一条 → 重启目标应用 | ⚠️ 未验证 |
| 功能：词条导入导出 | 导出文本再导入 | ⚠️ 未验证 |

### 遗留问题 / 风险
- **功能项全部未装机验证**，`MainHook` 是注入代码，未验证前不认为可用（§4.1）。
- **会丢样式**：`textView.text = translated` 会把原本文字样式（颜色、加粗、部分富文本）换成纯文本，个别界面可能掉色。
- **只做整串匹配**：词库没有的整句不翻，也不拆词拼——刻意选择，拆词容易翻错。
- **翻不到的地方**：网页（WebView）、Flutter / Unity 自绘界面、游戏画面、图片里的字。页面已写明。
- **可能闪一下**：软件自己刷新文字时短暂回英文，下一轮再翻回来。
- 内置词库约 500 条，覆盖常见按钮/菜单/提示；长尾词需用户自己补。

### 下一步建议
- 装机后先找纯英文软件，看按钮和菜单变成什么样，确认没把不该翻的翻掉。
- 反复翻不到的词用「自己加词条」补，再导出备份。
- 以后若要联网翻整句，`translate()` 是唯一入口，加一层「词库没命中就查网络 + 结果缓存」即可。

---

## 2026-10-25 · 净化规则显示控件小图 + 相同规则去重

### 目标
用户原话三点：规则页要能看到被隐藏控件的图片（小图，点了放大）；所有动作（隐藏/移除/改文字/改尺寸）都拍；相同的规则只留最新一条。

### 改动
| 文件 | 改了什么 |
|---|---|
| `PurifyRules.kt` | 新增 `SHOT_DIR`（目标应用 `files/selfxposed_purify_shots`）；新增 `dedupe`、`syncShots`、`localShot`、`deleteShotRemote`、`pruneShotsRemote` |
| `MainHook.kt` | 新增 `capturePurifyShot`（隐藏前拍照，宽 ≤ `PURIFY_SHOT_MAX_WIDTH`=480）；`applyPurifyRound` 在应用动作前调用它；`savePurifyRule` 落盘前走 `dedupe` |
| `ui/UiKit.kt` | 新增 `imageDialog(title, bitmap)`：点小图放大查看 |
| `ui/PurifyScreen.kt` | 规则卡片加小图（高 64dp，`FIT_START`），点击放大；新增 `loadShots`；`save()` 写盘前 `dedupe` |
| `ui/PurifyRuleScreen.kt` | 新增「控件样子」卡片（高 160dp，点击放大）；新增 `loadShot` |

### 关键设计
- **拍图时机**：必须在 `applyPurifyAction` 之前。控件一旦 `GONE`/尺寸归零就画不出来，拍了也是空白。
- **一规则一图**：文件名就是规则 ID（`<ruleId>.png`），已存在就跳过，避免每次界面变化重复拍照浪费 CPU。
- **跨进程读图**：目标应用目录是私有的，模块进程直接读不到。`syncShots` 用一条 root/Shizuku 命令把整个目录 `cp` 到模块 `cacheDir/purify_shots`，再在本地 `BitmapFactory` 解码。**先清空本地再拷**，避免删掉的规则还显示旧图。
- **图不会越攒越多**：进净化页时 `pruneShotsRemote(pkg, 现存规则ID列表)` 删掉不属于任何现存规则的图。
- **去重规则**：`dedupe` 按 `activity|type|value|action|arg` 五个字段判同，**倒序扫描后反转**，所以保留的是列表末尾那条（= 最新添加的）。

### 验证
| 项 | 方式 | 结果 |
|---|---|---|
| 编译 | `run_task assemble:app:debug` | ✅ BUILD SUCCESSFUL（8 警告均为改动前已有） |
| 功能：新规则显示小图 | 目标应用里点选隐藏一个控件 → 回净化页看 | ⚠️ 未验证 |
| 功能：点小图放大 | 净化页/详情页点图片 | ⚠️ 未验证 |
| 功能：相同规则只留一条 | 同一控件连续点选两次相同的隐藏 | ⚠️ 未验证 |

### 遗留问题 / 风险
- **老规则没有图**：拍图是本次新增的，之前存的规则当时没拍，只能删掉重新点选。页面不会留空白框（无图时图片控件保持 `GONE`）。
- 进净化页时才开始拷图，第一次打开会稍等一下才显示。
- `syncShots` 每次都清空重拷，规则多时（几十条）会有一次轻微的 IO 峰值；目前规则上限 200 条，可接受。
- 删除规则时没有立刻删图，靠下次进净化页的 `pruneShotsRemote` 兜底清理（`deleteShotRemote` 已写好但当前没调用点）。

---

## 2026-10-25 · 黑屏保护可见化（规则列表标注 + 规则详情页）

### 目标
用户原话：黑屏保护触发后没有任何提示，在规则列表里只看到"未命中"，分不清是"没匹配到"还是"匹配到了但被拦下"；希望在那条规则后面解释原因，并且点规则能跳到对应详情页。

### 改动
| 文件 | 改了什么 |
|---|---|
| `PurifyRules.kt` | 新增 `SKIPPED_FILE`；`Rule` 加运行时字段 `skipped`（**不参与 `toLine()`**）；`parse` / `parseLine` 多接 `skipped` 参数；新增 `parseSkipped`、`loadLocalSkipped`、`saveLocalSkipped`（空 map 时删文件）；`loadLocal` 合并跳过原因；`readRemote` 改为一条命令取三个文件，新增私有 `sectionOf(raw, start, end)` |
| `TargetFiles.kt` | 批量命令加 `@@K` 段读 `selfxposed_purify_skipped.conf`；`parse` 加 `@@K` 分支；把 `parseSkipped(...)` 结果传给 `PurifyRules.parse` |
| `MainHook.kt` | `applyPurifyRound` 收集 `skippedNow`（`"占屏 N%，已跳过"`）与 `effectiveNow`；新增 `recordPurifySkipped()`；companion 加 `purifySkippedLock` / `purifySkippedCache` / `purifySkippedFlushScheduled` / `PURIFY_SKIPPED_FLUSH_MS`(1200L) |
| `ui/PurifyScreen.kt` | `ruleCard` 在命中数下方显示 `⚠ 未生效：<原因>`（`Palette.warning` + `warningSoft`）+ 一句解释；卡片**点击**改为进规则详情页，长按仍是删除 |
| `ui/PurifyRuleScreen.kt`（新增） | 规则详情页：返回行、无能力 → `lockedCard`、跳过原因高亮卡（含 4 步处置指引）、规则字段卡（开关 + 匹配方式/值/动作/参数/生效界面/命中次数/规则 ID）、未命中与已关闭提示、删除（二次确认）、看日志 |
| `ui/ScreenHost.kt` | 新增 `openPurifyRule(pkg, name, ruleId)` |
| `MainActivity.kt` | `PAGE_PURIFY_RULE` 常量、`selectedRuleId` 字段、`updateNav` 归到「功能」组、`renderPage` 分支、`openPurifyRule` 实现、`import PurifyRuleScreen` |

### 关键设计
- **第三个旁路文件**：`files/selfxposed_purify_skipped.conf`，格式 `规则id=占屏 92%，已跳过`。规则、命中数、跳过原因分成三个文件，因为三者由不同进程在不同时机写，合在一起会互相覆盖（UI 写规则时会把 hook 刚记的命中数/跳过原因抹掉）。
- **`Rule.skipped` 不落规则文件**：它是运行时状态，只在 `parse` 时从 `SKIPPED_FILE` 合并进来。写规则时如果把它序列化进去，模块 UI 就会把 hook 的状态回写污染规则本身。
- **主线程不做 IO**：`applyPurifyRound` 跑在 `OnPreDraw` 回调（主线程），所以 `recordPurifySkipped` 只在内存里累积，延迟 `PURIFY_SKIPPED_FLUSH_MS`(1.2s) 合并后交给后台线程写一次盘。频繁写盘会拖慢目标应用的每一帧。
- **自动清除**：某条规则本轮成功生效（`effectiveNow`）就不在 `skippedNow` 里，会被从跳过文件移除，保护解除后 ⚠ 标记自动消失，不会一直挂着旧状态。
- **新增协议位**：`@@K`（跳过原因段），与既有 `@@F/@@M/@@B/@@U/@@A/@@W/@@P/@@Y/@@R` 并列。
- **接口变化（需审查）**：`ScreenHost` 新增 `openPurifyRule`，`MainActivity` 已实现；`PAGE_PURIFY_RULE` 已注册到 `updateNav` 与 `renderPage`，`handleBack` 走通用导航栈无需改动。

### 验证
| 项 | 方式 | 结果 |
|---|---|---|
| 编译 | `run_task assemble:app:debug` | ✅ BUILD SUCCESSFUL（8 警告均为改动前已有） |
| 泄漏检查 | `search_text "PAGE_PURIFY_RULE"` 确认常量/用法/实现齐全 | ✅ |
| 功能：规则卡片显示 ⚠ 跳过原因 | 目标应用内隐藏一个全屏容器后回模块查看 | ⚠️ 未验证 |
| 功能：点卡片进规则详情页 | 净化页点规则卡片 | ⚠️ 未验证 |
| 功能：详情页开关 / 删除 / 返回 | 详情页逐项操作 | ⚠️ 未验证 |

### 修复的既有 bug
`PurifyRules.readRemote` 原先用 `sectionOf(raw, "@@H", null)` 取规则文本，那会从 `@@H` 一直取到结尾，把命中数和跳过原因的内容也当成规则行解析。已改为 `raw.substringBefore("@@H")`。**这个 bug 在本次改动之前就存在**（当时只有两段，末尾的命中数行会被 `parseLine` 以「字段不足 7 段」丢弃，所以没暴露）；加入第三段后必须修，否则跳过原因会被误解析。

### 遗留问题 / 风险
- **功能项全部未装机验证**，`MainHook` 是注入代码，未验证前不认为可用（§4.1）。
- 跳过原因只反映**最近一次扫描**的结果。如果目标应用某次扫描没走到（例如界面没变化），旧标记会保留，可能显示已经过期的原因。
- 跳过文件由 hook 进程写；如果用户从未触发过保护，文件不存在，UI 读到空 map，全部规则显示正常（符合预期）。
- 详情页的开关/删除都是"读出全部规则 → 改一条 → 整体写回"，与净化页的写回逻辑一致；两个界面同时改会有后写覆盖前写的窗口，但单用户手动操作基本不会撞上。

### 下一步建议
- 装机按上表三项逐条验证。
- 若"原因过期"成为问题，可给跳过记录加时间戳（`规则id=时间戳|原因`），UI 上显示"最近一次：X 分钟前"。

---

## 2026-10-25 · 净化功能（阶段二：悬浮球点选 + 规则应用）

### 目标
参考 `firesahc/Xposed-GodMode-Pro` 的能力（点选控件后隐藏/改写），在自用模块里做一个同类功能，放在功能页，名字叫「净化」，每个目标应用独立一页。用户拍板：做阶段二（可视化点选）、每应用分开、动作支持隐藏+移除+改文字+改尺寸、要导入导出、悬浮球点二选一。

### 改动
| 文件 | 改了什么 |
|---|---|
| `Features.kt` | 新增 `PURIFY = "purify"` 开关键 |
| `PurifyRules.kt`（新增） | 规则模型与持久化：`Rule`、行格式 `id\|on\|hits\|activity\|type\|value\|action\|arg`、转义/解析、`loadLocal`/`loadLocalHits`/`saveLocalHits`（hook 侧直读文件）、`readRemote`/`writeRemote`/`clearHitsRemote`（UI 侧走 RootShell）、`exportText`、`matches`、`newId` |
| `TargetFiles.kt` | `TargetState` 加 `purifyRules`；批量命令加 `@@P`（规则）/`@@Y`（命中数）两段；`parse` 加两个分支 |
| `ui/PurifyScreen.kt`（新增） | 净化页：返回行、无能力→`lockedCard`、`master` 未开→置灰、净化总开关 chip、用法卡、规则列表（开关/命中数/长按删+二次确认）、清空命中数、导入导出、限制说明、看日志 |
| `ui/FeaturesScreen.kt` | 加「净化」功能卡，走 `pickTarget(Features.PURIFY)` |
| `ui/ScreenHost.kt` | 新增 `openPurify`、`exportPurifyRules`、`importPurifyRules` |
| `MainActivity.kt` | `PAGE_PURIFY` 常量、`updateNav` 归到「功能」组、`renderPage` 分支、`openPurify`、SAF 导入导出（`ACTION_CREATE_DOCUMENT`/`ACTION_OPEN_DOCUMENT`）+ `onActivityResult`（导入按 id 去重后追加）+ `REQ_EXPORT`/`REQ_IMPORT` + `import PurifyScreen` |
| `MainHook.kt` | 悬浮球显示条件扩为 `master && (block \|\| purify)`；`onActivityResumed` 调 `applyPurifyRules`；`createPickBall` 的 ACTION_UP 改为 `showBallMenu`（拦截弹窗/点选净化二选一）；新增净化段：`applyPurifyRules`、`purifyRules`（2 秒缓存）、`applyPurifyRound`、`walkViews`、`isOwnView`、`idNameOf`、`applyPurifyAction`、`parsePurifySize`、`showBallMenu`、`findActivity`、`startPurifyPicking`、`buildHitChain`、`findContainingChild`、`isAlive`、`drawPurifyTarget`、`describePurifyView`、`showPurifyActionCard`、`askPurifyText`、`askPurifySize`、`savePurifyRule` |

### 关键设计
- **数据落点**：目标应用自己的目录，符合 §3。规则 `files/selfxposed_purify.conf`，命中数 `files/selfxposed_purify_hits.conf`。两个文件分开，避免 UI 写规则时覆盖 hook 刚记下的命中数。
- **通道**：hook 与目标应用同进程，直接 `File(context.filesDir, ...)` 读写，零 root；模块 UI 通过 `RootShell`（Shizuku / su）读写，写完 `chown` + `chmod 660`（D5）。
- **三层开关**：主页 `master` → `purify` → 单条规则 `on`。三层都判（H6）；页面状态 = `canUseRoot && master && purify`（H3）；默认全关（H1）。
- **规则匹配**：类型 `id`/`text`/`desc`/`class`，优先资源 id（最稳，避免 RecyclerView 复用误伤）。`activity` 字段可限定界面，点选生成的规则默认留空 = 全应用生效。
- **扫描策略**：`onActivityResumed` 后延迟 600ms 起扫 4 轮，深度上限 24，限次避免死循环（F3/E8）。模块自己的视图用 tag 前缀 `selfxposed_` 排除，防止涂自己的悬浮球。
- **点选**：全屏透明 `FrameLayout` 挂到 `decorView`。手指落在哪，就把该点所在的**整条祖先链**取出来（`chain[0]=decor`，末尾=最深一层），默认停在最深一层；底部固定工具条 `▲ 父级` / `▼ 子级` / `✓ 就用它` / `✗ 取消` 用来在链上逐级进退，标签实时显示 `类名 #id 「文字」 宽×高dp` 和层级序号 `(3/7)`。只有点 `✓` 才弹动作卡并落盘。
  - 为什么不用「抬手即弹卡」：抬手弹卡时用户没法再调整层级，而点在全屏容器空白处时「最深」就是整个屏幕，必须给一个往上退的手段。
- **新增常量**：`PURIFY_PICK_TAG`、`PURIFY_SCAN_DELAY`(600L)、`PURIFY_SCAN_ROUNDS`(4)、`PURIFY_MAX_DEPTH`(24)、`purifyCache`/`purifyCacheAt`(2 秒)、`REQ_EXPORT`(1001)、`REQ_IMPORT`(1002)。
- **接口变化（需审查）**：`ScreenHost` 新增 3 个抽象方法，`MainActivity` 已全部实现；后续新增页面须同步。

### 验证
| 项 | 方式 | 结果 |
|---|---|---|
| 编译 | `run_task assemble:app:debug` | ✅ BUILD SUCCESSFUL（8 警告均为改动前已有） |
| 装机 | `adb install -r` | ⚠️ 未验证 |
| 功能：点选生成规则并隐藏控件 | 目标应用内点悬浮球→点选净化→选控件→隐藏 | ⚠️ 未验证 |
| 功能：规则在净化页显示、命中数累加 | 净化页查看 | ⚠️ 未验证 |
| 功能：重启目标应用后规则仍生效 | 冷启动目标应用 | ⚠️ 未验证 |

### 遗留问题 / 风险
- **全部功能项未装机验证**。`MainHook` 属注入代码，必须实机验证；未验证前不要认为可用（约定 §4.1）。
- **UI 约定偏差**：`MainHook` 侧的二选一菜单与动作卡用的是 `android.app.AlertDialog`，不是模块自绘 `ui.dialog`（hook 在目标进程内拿不到 `ui` 包）。与既有 `showConfirmCard` 写法一致，但与「模块内提示统一样式」的精神有出入，需要时再自绘。
- **已知拦不到**（页面已写明）：悬浮窗、WebView 内部、Flutter/Unity 自绘界面；这与 GodMode 自身承认的限制一致（E9）。
- 点选时如果手指落在**全屏容器的空白处**，链上最深一层就是这个容器本身（真的没有更深的控件），此时用 `▲ 父级` 没有意义、`▼ 子级` 也无法再深入——需要把手指移到具体控件上重新点。标签里的 `宽×高dp` 可用来判断是否选到了整屏。
- `ACTION_REMOVE` 移出的控件可能被应用重新 add 回来（下一轮扫描会再移除，但会闪）。
- 点选生成的规则不限定 `activity`，若两页有同名控件会一起生效；需要精细控制可导出后手改 `activity` 字段再导入。
- `purify_hits.conf` 由 hook 进程写，UI 侧「清空命中数」删文件后可能被进程内缓存优先级影响，需实机确认。

### 修复记录（用户实测反馈两条）

**问题 1：点选时整个界面被选中，无法精确选中单个元素**
- 根因：原 `findDeepestChild` 只返回「触摸点下最深的那个子 View」。手指落在全屏容器的空白处时，「最深」就是容器本身（App 内容根通常是全屏 `FrameLayout`/`ConstraintLayout`），于是框住整屏；而且没有任何往上退一级的手段。
- 修复：改为 `buildHitChain` 取「触摸点所在的整条祖先链」，默认停在最深一层；新增底部工具条 `▲ 父级` / `▼ 子级` / `✓ 就用它` / `✗ 取消`。标签显示 `类名 #id 「文字」 宽×高dp` 与层级 `(n/总数)`。位移超过 8dp 才重算链，轻微抖动不会重置用户已经调好的层级。

**问题 2：点选净化之后没过一会目标应用闪退**
- 主嫌疑：`showPurifyActionCard` / `askPurifyText` / `askPurifySize` / `showBallMenu` 都用 `AlertDialog.Builder(activity|context)`。点选期间目标 App 自己切页面（广告页、引导页）会让 Activity `isFinishing`/`isDestroyed`，此时弹窗抛 `BadTokenException` 直接崩掉目标进程。原代码没有任何存活判断。
- 次嫌疑：`applyPurifyRound` 边遍历 View 树边执行 `removeView`，改掉了正在遍历的结构；`ACTION_REMOVE` 也可能移除 App 仍在引用的 View。
- 修复：
  1. 新增 `isAlive(activity)`（`非 null && !isFinishing && !isDestroyed`），在 `showBallMenu`、`startPurifyPicking`、`showPurifyActionCard`、`askPurifyText`、`askPurifySize`、`savePurifyRule` 入口全部前置判断。
  2. `showBallMenu` 改为先从 context 解析出 Activity 再用它弹窗（原来直接用 `context`，可能是 Application 上下文）。
  3. `applyPurifyRound` 改两阶段：先收集 `(控件, 规则)`，遍历结束后统一应用；应用前检查 `view.parent != null`。
  4. `walkViews` 每个子 View 单独 try/catch，`childCount` 取快照，个别控件异常不中断整棵树。
- 验证：`assemble:app:debug` ✅。**是否真的解决了闪退需要装机复测**——上一轮没有拿到崩溃堆栈，上述是按代码里最可能的路径做的防御性修复；若仍崩，必须抓 logcat 定位。

**问题 3：规则生效了，但重开目标应用时被删掉的区域会「先闪出来再消失」**
- 根因：`applyPurifyRules` 只在 `onActivityResumed` 之后延迟 600ms 才开始扫描。第一帧早就画完了，所以能看到控件先渲染、再被改成 GONE。
- 修复：改用 `ViewTreeObserver.OnPreDrawListener` 在**每一帧真正绘制之前**应用规则（`PURIFY_PRE_DRAW_ROUNDS` = 3 帧后自动摘掉监听）。在 `onPreDraw` 里把控件设成 `GONE`，这一帧就不会绘制它，彻底不闪。原来的延迟扫描保留作兜底，用于首帧之后异步插入的广告位 / 列表内容。

**问题 4：123 网盘里点选任何控件都只能选中整个屏幕，◀▶ 怎么调都没用**
- 根因：原 `buildHitChain` 只能沿**祖先链**上下移动。广告 SDK 常见做法是在全屏容器上再盖一层全屏触摸拦截器，导致「点到的控件」和「它的所有祖先」都是全屏——链上怎么调都是整屏，天然无解。
- 修复：改为 `collectHitCandidates`，递归收集**整棵树中所有包含触摸点**的控件，按面积升序排序（同面积时层级更深的优先），最多保留 `PURIFY_MAX_CANDIDATES`(40) 个。默认 index = 0，即**面积最小的那个（最精确）**；按钮语义随之改为 `◀ 更小` / `更大 ▶`。这样即使点到全屏拦截层，也能在同位置的小兄弟控件之间切换。
- 附带：`containsPoint`、`depthOf` 全部单独 try/catch，避免个别控件异常中断整次收集。

**问题 5：移除规则后重启目标应用仍然黑屏**
- 根因：上一轮只把 `removeView` 换成 `GONE`，没拦住「隐藏/移除的其实是整个界面容器」这条路径。容器一旦 `GONE`，界面自然整片空白。
- 修复：`applyPurifyRound` 应用动作前做占屏检查——`hide` / `remove` 动作的目标控件占 `decorView` 面积 ≥ `PURIFY_LARGE_PERCENT`(70%) 时**直接跳过**，并每规则每进程记一次日志（`purifySkipLogged` 去重）。
- **重要行为变更**：已保存的旧规则同样受此保护，不需要用户回去删规则；但反过来说，原本"隐藏整个容器"能生效的规则从此不再生效。
- 验证：`assemble:app:debug` ✅；**实机未验证**。

**问题 6：方框旁文字挡视线 → 改为底部「详情」按钮**
- 删除点选层的长按识别逻辑（`pickHandler`、`longPressAction`）与常量 `PURIFY_LONG_PRESS_MS`。
- 底部工具条重构成 `barRow`（内层 `buttonStrip` + 最右侧 `dragHandle`），按钮改为 6 个：`◀ 更小` / `更大 ▶` / `隐藏`(切换) / `详情` / `✓ 用它` / `✗ 取消`。
- 「详情」调 `showPurifyDetails`，弹独立对话框展示类名、ID、文字、描述、dp+px 尺寸、占屏百分比、候选序号。
- 计数条（`counter`）只保留 `候选 3/7 · 底部「详情」看信息`，贴在工具条上方，不跟随方框。

**问题 7：详情界面配色随目标应用主题变化**
- 根因：用的是系统 `android.app.AlertDialog`，它继承目标应用的主题。
- 修复：改为自绘 `Dialog` —— 半透明遮罩 + 圆角卡片，固定使用模块 `Palette.surface` / `ink` / `muted` / `accent`，正文套 `ScrollView` 防长内容溢出。

**问题 8：隐藏/显示改为可切换的预览**
- 工具条新增 `hideButton`：点一次把当前选中控件设 `GONE`、按钮变「显示」；再点一次恢复 `VISIBLE`。
- `previewHidden` 记录预览对象；换控件（`ACTION_DOWN` / `ACTION_MOVE` 重算候选）走 `restorePreview()` 自动恢复；「✓ 用它」和「✗ 取消」也都会恢复，不留"半隐藏"状态。
- 说明：这是**临时预览**，不写规则；要持久化仍走「✓ 用它 → 隐藏/移除」。

**问题 10：拖动工具条时「隐藏」「详情」文字上下疯狂抖动**
- 根因：按钮放在 `wrap_content` 的 `buttonStrip` 里，却用 `LinearLayout.LayoutParams(0, -2, 1f)` 分配宽度。父容器宽度未知时 weight 会退化，按钮测量宽度在 0 和真实值之间反复变化；文字宽度不够就换行，高度随之跳变 → 表现为上下抖。拖动时每一帧都触发重新布局，抖动被放大。
- 修复：`barRow` / `buttonStrip` 全部改为 `wrap_content`；每个按钮固定尺寸 `dp(36) × dp(36)`、`isSingleLine = true`、去掉 padding。彻底不再依赖 weight。

**问题 11：点「✓ 用它」弹出的动作卡仍是黑底**
- 根因：`showPurifyActionCard` / `askPurifyText` / `askPurifySize` / `showBallMenu` 用的都是系统 `android.app.AlertDialog`，它继承目标应用主题（很多 App 是深色主题），所以是黑底白字。
- 修复：新增统一的自绘对话框 `showModuleDialog(activity, title, message, body, cancelable, menu, buttons)`，半透明遮罩 + 圆角卡片 + `Palette.surface/ink/muted/accent` 配色，支持「竖排菜单项」和「底部横排按钮」两种形态。以上四处 + `showPurifyDetails` 全部改为调用它（`showPurifyDetails` 里原先那套手写绘制代码已删除，不再有两份实现）。
- 按钮配色规则：只有 1 个按钮时用次要色（通常是「取消」），多个按钮时最后一个用主色（accent）。

**问题 12：动作卡里的「隐藏」删除（底部工具条已有）**
- 动作卡菜单只剩：`移除（隐藏并压成 0 尺寸）` / `改文字` / `改尺寸` + `取消`。
- 注意：`PurifyRules.ACTION_HIDE` 不再由 UI 产生，但**已存在的 hide 规则仍然生效**（扫描逻辑未改）。如果以后要"只隐藏不压尺寸"的持久规则，需要把该项加回来。

**问题 13：工具条只显示图标，长按才显示文字**
- 六个按钮改为图标：`◀`(更小) `▶`(更大) `◉`(隐藏，切换后变 `○`) `ⓘ`(详情) `✓`(用它) `✗`(取消)，文字存在 `view.tag` 里。
- 每个按钮 `setOnLongClickListener` → `showIconHint(label)`：在工具条正上方弹一个小标签，淡入 120ms，停留 `PURIFY_ICON_HINT_MS`(1500ms) 后 300ms 淡出。位置按 `barParams` + 实测宽度居中，拖到哪就跟到哪。
- 计数条文案同步为 `候选 n/m · 长按图标看名称`。

**问题 9：底部工具条可拖动**
- `dragHandle`（`⠿`）的 `OnTouchListener` 拖动时改 `barParams.leftMargin/topMargin`，`barRow` 的 LayoutParams 由原来的 `BOTTOM` 居中对齐改为 `TOP | START` 绝对定位；移动量限制在 `overlay` 边界内。
- 初始位置在 `overlay.post {}` 里设为底部居中（`maxTop - dp(24)`）。

### 下一步建议
- 装到设备上按上表逐项验证，重点看：点选命中精度、隐藏后布局是否塌陷、重启后规则是否生效。
- 验证通过后可考虑：规则编辑（改匹配值/限定界面）、按界面自动填 `activity`、命中高亮演示模式。
- `MainActivity.kt.bak` 是旧备份，与主文件已不一致，确认无用后可删。

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
