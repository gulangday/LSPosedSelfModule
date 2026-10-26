# pitfalls · 安装与维护

一个 DSH skill：按「索引 → 详情」两级检索本项目踩坑规则，避免把整份规则塞进上下文。

## 目录结构

```
pitfalls-skill/
├─ SKILL.md          索引区（agent 常驻读这个）
├─ README.md         本文件，安装时看，不会进上下文
├─ CHANGELOG.md      版本与新增记录
├─ install.ps1       安装 / 校验脚本
└─ references/
   ├─ _UPDATE.md     新增规则的流程
   └─ *.md           15 个主题的详情
```

## 安装

在 `pitfalls-skill` 目录所在机器上执行。有 `pwsh`（PowerShell 7）就用 `pwsh`，没有就把 `pwsh` 换成 `powershell`：

```powershell
# 装到当前项目（从当前所在目录向上找 .git；找不到则退回脚本所在目录）
pwsh -File .\install.ps1

# 装到用户级，所有项目可见
pwsh -File .\install.ps1 -Scope user

# 说明：link 模式让源目录即装即生效，改规则不用重装
pwsh -File .\install.ps1 -Mode link

# Windows 自带 5.1 时（没有 pwsh）
powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1
```

脚本已存为 UTF-8 带 BOM，5.1 直接跑不会乱码。

目标路径：

| Scope | 实际路径 |
|---|---|
| project | `<最近含 .git 的目录>\.dsh\skills\pitfalls` |
| user | `%USERPROFILE%\.dsh\skills\pitfalls` |

已存在同名目录时脚本**默认中止**（不会删你的东西）；确认覆盖加 `-Force`，脚本会先备份成 `.bak-<时间戳>`。

## 让 agent 自己装

把整段指令发给 agent 即可：

> 把 `docs/pitfalls-skill/` 安装成 DSH skill：读同目录 README.md，
> 执行 `pwsh -File docs/pitfalls-skill/install.ps1`，然后跑 `-Verify` 确认全部通过，
> 最后把安装路径和校验结果告诉我。

它能自己完成：找 project root → 建目录 → 复制/link → 校验。

## 验证

```powershell
pwsh -File .\install.ps1 -Verify
```

检查四项：frontmatter 可解析、索引与详情 key 一一对应、无重复 key、每条详情都有「自查项」。输出 `VERIFY OK` 即通过。

安装后不用重启会话：skill 目录是动态扫描的，下一次对话就能看到 `pitfalls`。若你的 harness 有缓存，开新会话即可。

## 使用

agent 侧的触发方式（已写进 SKILL.md description）：遇到 UI / 权限 / 存储 / hook / 性能 / 构建 / 产品语义问题时自动检索。

手动触发也可以：

> 用 pitfalls 查一下「悬浮球点不到」

## 新增规则

直接告诉 agent 你要加什么，例如：

> 给 pitfalls 加条规则：目标应用卸载后 features.conf 还在，重装后旧开关残留；原因是卸载没清 files 目录；做法是在卸载广播里删文件。

agent 会读 `references/_UPDATE.md`，走完判重 → 编号 → 详情 → 索引 → CHANGELOG → 自校验 → 回报，最后告诉你新 key 和版本号。

## 卸载

删除安装目录即可：

- project: `<项目>\.dsh\skills\pitfalls`
- user: `%USERPROFILE%\.dsh\skills\pitfalls`

link 模式注意只删链接，别删源目录。

## 局限

- 内容绑定本项目（LSPosed 自模块 selfxposed），换项目需要改写 references。
- 索引未命中时 agent 应当停下询问，而不是套用相似规则。
