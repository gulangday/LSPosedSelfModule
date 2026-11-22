---
name: pitfalls
description: 本项目（LSPosed 自模块 selfxposed）踩坑速查索引。出现 UI 裁切、开关状态不对、返回键回错页、提示重复、Shizuku 崩溃、remote 进程堆积、命令卡 2 秒、root 探测失败、配置丢失、值读成 1@@M、写完文件 hook 读不到、hook 不生效或重复、弹窗闪烁、悬浮球点不到、截图重复、冷启动慢、卡顿 ANR、编译 JDK 报错、装机失败、改了没生效、功能默认开启、三层开关、自启动、Toast 拦截范围、LSPosed 作用域不对、取消作用域仍显示已注入、读不到作用域数据库、净化预览与保存不一致、删除规则闪动等问题时，先查本文件索引，命中 key 后只读 references 对应小节；索引未命中必须停下询问用户，禁止编造规则。新增规则读 references/_UPDATE.md。
---

# 用法（硬协议）

1. 只查本文件索引，**禁止**整篇读 `references/`。
2. 命中：`grep -rn "^## <key> " references/` 拿文件与行号，再 `read` 只读那一节。
3. **未命中**：回复「索引未收录：<用户描述>」，列出最接近的 3 个 key，然后**停下等指示**。禁止猜测、禁止套用相似规则。
4. 单次最多精读 2 条，第 3 条先说明理由。
5. 索引与详情冲突以详情为准，并提示用户更新索引。
6. 收到「添加 / 修改 / 废弃规则」→ 读 `references/_UPDATE.md`，按其中 8 步执行。

# 索引（key · 症状 → 对策）

### A 流程协作

A1 清单没等确认就动手 → 等到「开始 / 可以 / ok」再改文件
A2 规则写进 README 就没用 → 硬约束写 AGENTS.md
A3 批量替换后中文乱码 → 用 edit 逐处改
A4 删除清空一点就没 → 先二次确认
A5 点"取消"却执行删除 → 显式写 onConfirm

### B 布局与裁切

B1 右侧状态标签被裁切 → 重置 LayoutParams(-2,-2)
B5 长标题被裁、布局错位 → 文本 0 宽 + weight 1

### B 开关与状态显示

B2 开关字体样式不统一 → 统一 statusChip
B3 总开关关了子项还能点 → 整页 lockedCard + return
B9 功能页计数与主页不一致 → 统一 master && feature

### B 导航与刷新

B4 返回键回错页面 → 按钮复用 handleBack
B8 切换开关后滚动跳顶 → 局部更新或恢复 scrollY
B10 删除净化规则整页闪动 → 单卡动画 + 原地更新标题

### B 提示与交互

B6 提示条弹两次 → 自绘提示只 show 一次
B7 提示样式不统一 → 统一 host.toast()
B11 净化隐藏预览与保存不一致 → 预览复用保存规则 + 同套黑屏保护

### C Shizuku 服务

C1 UserService 崩溃 → 继承 IRemoteService.Stub
C2 上百 remote 进程、别家闪退 → daemon(true)
C6 每个命令卡 2 秒 → waitedOnce 只等一次

### C Root 与权限

C3 请求不到 root → 多路径探测 + 提示切 Shizuku
C4 自启动显示假"已开启" → 只做跳转引导
C5 常驻通知不显示 → 检测 POST_NOTIFICATIONS 并引导
C7 su 路径写死探测失败 → 多路径 + isToolError 过滤

### D 配置存储与解析

D1 配置读不到或重启丢失 → 写目标应用 files 或放宽 0644
D2 读出来是 `1@@M` → 每个 cat 后补 echo
D3 显示未开启但功能正常 → 修解析 + invalidate
D4 30 秒内全显示未开启 → 只缓存带 @@F 的结果

### D 文件权限与可见性

D5 写完文件 hook 读不到 → chown + chmod 660
D6 hook 读的配置读不到 → 改写目标应用本地文件
D7 页面慢要 4~5 秒 → 合并一条命令 + TTL 缓存
D8 读 LSPosed 作用域总为空 → WAL 三件套 + chown + 读写打开 + SQL 别名

### E Hook 安装与开关

E1 版本伪装无效 → 提前到 handleLoadPackage
E2 显示已开启但 hook 不执行 → master && feature 双判
E7 hook 重复安装 → 包名 + 进程名一次性标记
E10 想自动勾选 LSPosed 作用域 → 不可行，改手动引导 + 状态检测

### E Hook 窗口与视觉

E3 拦截时弹窗闪一下 → show 前 alpha = 0
E4 悬浮球点不到 → 球挂到 decorView
E5 描边框住整个屏幕 → 只框内容 card

### E 副作用与稳定性

E6 截图重复 → 内容哈希 + 5 秒窗
E8 hook 异常拖崩目标应用 → 全体 try/catch 只记日志
E9 承诺能拦 Unity / Flutter → 写进限制说明

### F 性能与主线程

F1 冷启动 4.9 秒 → peek 先渲染再后台刷新
F2 页面卡顿 → 合并命令 + 缓存
F3 ANR 或掉帧 → 后台线程 + 回主线程更新
F4 缓存导致显示不一致 → 写成功即 invalidate
F5 无限刷新 → 只在值变化时刷新

### G 构建与装机

G1 编译报 JDK 错误 → 设 JAVA_HOME 后构建
G2 找不到安装包 → app/build/outputs/apk/debug
G3 装机失败 → adb reconnect 后再装
G4 静态 Handler NPE → by lazy 或方法内创建
G5 改了代码没生效 → 重启目标进程
G6 模块图标不更新 → 提升 versionCode

### H 功能语义与产品规则

H1 新功能默认就开着 → 默认全关，按应用开启
H2 主页开关语义混乱 → 主页只做 master
H3 显示已开启但不可用 → master && feature && canUseRoot
H4 Toast 拦截拦不到系统 Toast → 写进限制说明
H5 自启动被当成必须 → 改"建议开启（非必需）"
H6 三层开关漏一层 → master → feature → 选项
H7 删除类操作无确认 → 二次确认弹窗
H8 取消作用域后仍显示已注入 → 先查 scope DB，再看 seen
H9 添加应用后不提示作用域 → 添加后查 scope/seen 并弹提示

# 未命中回复模板

> 索引里没有「<用户描述>」这条。最接近的是：C5（常驻通知不显示）、C4（自启动假状态）、C3（请求不到 root）。
> 要我按哪条处理，还是先把这条新坑补进 skill？
