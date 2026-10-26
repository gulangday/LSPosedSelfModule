[CmdletBinding()]
param(
    [ValidateSet('project', 'user')]
    [string]$Scope = 'project',
    [string]$Dest,
    [ValidateSet('copy', 'link')]
    [string]$Mode = 'copy',
    [switch]$Force,
    [switch]$Verify,
    [switch]$Update
)

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$utf8 = [System.Text.Encoding]::UTF8

function Read-Text([string]$path) {
    return [System.IO.File]::ReadAllText($path, $utf8)
}

function Find-ProjectRoot([string]$start) {
    $dir = [System.IO.Path]::GetFullPath($start)
    while ($true) {
        if (Test-Path (Join-Path $dir '.git')) { return $dir }
        $parent = Split-Path -Parent $dir
        if ([string]::IsNullOrEmpty($parent) -or $parent -eq $dir) { break }
        $dir = $parent
    }
    return $null
}

function Get-IndexKeys([string]$skillPath) {
    $keys = @()
    foreach ($line in ((Read-Text $skillPath) -split "`r?`n")) {
        if ($line -match '^~~([A-H][0-9]+)\s') { $keys += $Matches[1] }
        elseif ($line -match '^([A-H][0-9]+)\s') { $keys += $Matches[1] }
    }
    return ,$keys
}

function Get-DetailInfo([string]$refDir) {
    $keys = @()
    $noCheck = @()
    foreach ($file in (Get-ChildItem -Path $refDir -Filter '*.md' | Where-Object { $_.Name -notlike '_*' })) {
        $current = $null
        $hasCheck = $false
        foreach ($line in ((Read-Text $file.FullName) -split "`r?`n")) {
            if ($line -match '^## (?:~~)?([A-H][0-9]+)\b') {
                if ($current -and -not $hasCheck) { $noCheck += $current }
                $current = $Matches[1]
                $hasCheck = $false
                $keys += $current
            }
            elseif ($current -and $line -match '自查项') {
                $hasCheck = $true
            }
        }
        if ($current -and -not $hasCheck) { $noCheck += $current }
    }
    return [pscustomobject]@{ Keys = $keys; NoCheck = $noCheck }
}

function Invoke-Verify([string]$root) {
    $skillPath = Join-Path $root 'SKILL.md'
    $refDir = Join-Path $root 'references'
    if (-not (Test-Path $skillPath)) { throw "缺少 SKILL.md：$skillPath" }
    if (-not (Test-Path $refDir)) { throw "缺少 references 目录：$refDir" }

    $fail = 0
    $skill = Read-Text $skillPath
    if ($skill -match '(?m)^name:\s*pitfalls\s*$') { Write-Host '[1/4] frontmatter name: OK' }
    else { Write-Host '[1/4] frontmatter name: FAIL（应为 name: pitfalls）' -ForegroundColor Red; $fail++ }
    if ($skill -match '(?m)^description:\s*\S') { Write-Host '      frontmatter description: OK' }
    else { Write-Host '      frontmatter description: FAIL' -ForegroundColor Red; $fail++ }

    $indexKeys = Get-IndexKeys $skillPath
    $detail = Get-DetailInfo $refDir
    Write-Host "[2/4] 索引 $($indexKeys.Count) 条 / 详情 $($detail.Keys.Count) 条"

    $missingDetail = @($indexKeys | Where-Object { $detail.Keys -notcontains $_ })
    $missingIndex = @($detail.Keys | Where-Object { $indexKeys -notcontains $_ })
    $dupIndex = @($indexKeys | Group-Object | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name })
    $dupDetail = @($detail.Keys | Group-Object | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name })

    if ($missingDetail.Count -eq 0 -and $missingIndex.Count -eq 0 -and $dupIndex.Count -eq 0 -and $dupDetail.Count -eq 0) {
        Write-Host '[3/4] 一致性: OK（索引与详情一一对应）'
    }
    else {
        Write-Host '[3/4] 一致性: FAIL' -ForegroundColor Red
        if ($missingDetail.Count -gt 0) { Write-Host "      索引有但详情缺失: $($missingDetail -join ', ')" -ForegroundColor Red }
        if ($missingIndex.Count -gt 0) { Write-Host "      详情有但索引缺失: $($missingIndex -join ', ')" -ForegroundColor Red }
        if ($dupIndex.Count -gt 0) { Write-Host "      索引重复: $($dupIndex -join ', ')" -ForegroundColor Red }
        if ($dupDetail.Count -gt 0) { Write-Host "      详情重复: $($dupDetail -join ', ')" -ForegroundColor Red }
        $fail++
    }

    if ($detail.NoCheck.Count -eq 0) { Write-Host "[4/4] 自查项: OK（$($detail.Keys.Count)/$($detail.Keys.Count)）" }
    else { Write-Host "[4/4] 自查项缺失: $($detail.NoCheck -join ', ')" -ForegroundColor Red; $fail++ }

    $changelog = Join-Path $root 'CHANGELOG.md'
    if (Test-Path $changelog) {
        if ((Read-Text $changelog) -match '(?m)^## v[0-9]+\.[0-9]+\.[0-9]+') { Write-Host '      CHANGELOG 版本行: OK' }
        else { Write-Host '      CHANGELOG 版本行: FAIL' -ForegroundColor Red; $fail++ }
    }

    if ($fail -eq 0) {
        Write-Host 'VERIFY OK' -ForegroundColor Green
        return $true
    }
    Write-Host "VERIFY FAILED（$fail 项）" -ForegroundColor Red
    return $false
}

if ($Verify) {
    $targetRoot = if ($Dest) { $Dest } elseif (Test-Path (Join-Path $here 'SKILL.md')) { $here } else { throw '找不到要校验的目录' }
    $ok = Invoke-Verify $targetRoot
    if (-not $ok) { exit 1 }
    exit 0
}

if (-not $Dest) {
    if ($Scope -eq 'project') {
        # 优先按“当前所在目录”定位项目，找不到再退回脚本所在目录
        $cwd = (Get-Location).Path
        $projectRoot = Find-ProjectRoot $cwd
        if (-not $projectRoot) { $projectRoot = Find-ProjectRoot $here }
        if (-not $projectRoot) { $projectRoot = $cwd }
        $Dest = Join-Path $projectRoot '.dsh\skills\pitfalls'
    }
    else {
        $Dest = Join-Path $env:USERPROFILE '.dsh\skills\pitfalls'
    }
}

$Dest = [System.IO.Path]::GetFullPath($Dest)
if ($Dest -notmatch 'skills\\pitfalls\\?$') {
    throw "拒绝操作非标准目标路径：$Dest（路径必须以 skills\pitfalls 结尾）"
}
$Dest = $Dest.TrimEnd('\')

Write-Host "源目录: $here"
Write-Host "目标:   $Dest"
Write-Host "模式:   $Mode"

if (Test-Path $Dest) {
    if (-not ($Force -or $Update)) {
        Write-Host '目标已存在。确认覆盖加 -Force（会先备份为 .bak-<时间戳>），或加 -Update 表示升级。' -ForegroundColor Yellow
        exit 1
    }
    $bak = $Dest + '.bak-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
    Move-Item -LiteralPath $Dest -Destination $bak
    Write-Host "已备份到: $bak"
}

$parent = Split-Path -Parent $Dest
if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }

if ($Mode -eq 'link') {
    New-Item -ItemType Junction -Path $Dest -Target $here | Out-Null
    Write-Host '已建立目录链接（改源目录立即生效）'
}
else {
    New-Item -ItemType Directory -Path $Dest -Force | Out-Null
    foreach ($name in @('SKILL.md', 'README.md', 'CHANGELOG.md')) {
        Copy-Item -LiteralPath (Join-Path $here $name) -Destination $Dest -Force
    }
    Copy-Item -LiteralPath (Join-Path $here 'references') -Destination $Dest -Recurse -Force
    Write-Host '已复制文件'
}

Write-Host ''
Write-Host '--- 校验 ---'
$ok = Invoke-Verify $Dest
if (-not $ok) { Write-Host '安装完成但校验失败，请按上面提示修复。' -ForegroundColor Red; exit 1 }
Write-Host ''
Write-Host "安装成功。skill 名称: pitfalls"
Write-Host "下次对话即可用；也可手动说「用 pitfalls 查一下 ...」。"
