<#
.SYNOPSIS
    一键启动校园 RAG 问答系统（演示 / 新机器复现用）。

.DESCRIPTION
    做四件事：
      1. 检查 MySQL 能不能连
      2. 建库建表（可重复执行，schema.sql 里都是 IF NOT EXISTS）
      3. 后台启动 Spring Boot，等 /api/health 变成 UP
      4. 如果库里还没有文档，批量导入语料并向量化（失败的文件自动删掉，不污染库）

.PARAMETER MysqlPassword
    MySQL root 密码，默认 123456（按自己机器改）

.PARAMETER Port
    服务端口，默认 8081

.PARAMETER Reset
    清空 kb_chunk / kb_document 后重新导入（做演示前想回到干净状态时用）

.EXAMPLE
    .\start.ps1
    .\start.ps1 -Reset
    .\start.ps1 -MysqlPassword 你的密码 -Port 8090
#>
param(
    [string]$MysqlPassword = '123456',
    [int]$Port = 8081,
    [switch]$Reset
)

$ErrorActionPreference = 'Stop'

# ---------- 路径与工具 ----------
$root  = Split-Path -Parent $PSCommandPath
$demo  = Join-Path $root 'demo'
$mysql = 'E:\cal\MySQL\MySQL Server 8.0\bin\mysql.exe'
$jdk   = 'E:\cal\code\jdk21'
$base  = "http://localhost:$Port"
$log   = Join-Path $env:TEMP 'campus-demo.log'

# Maven 要用 JDK 21（本机 PATH 里默认是 Java 8）
if (Test-Path $jdk) {
    $env:JAVA_HOME = $jdk
    $env:PATH = (Join-Path $jdk 'bin') + ';' + $env:PATH
}

function Step($n, $text) {
    Write-Host ""
    Write-Host "== [$n] $text" -ForegroundColor Cyan
}
function Ok($text)   { Write-Host "   OK  $text" -ForegroundColor Green }
function Warn($text) { Write-Host "   ...  $text" -ForegroundColor Yellow }

# ---------- 1. MySQL ----------
Step '1/5' '检查 MySQL'
if (-not (Test-Path $mysql)) { throw "找不到 mysql.exe：$mysql（改脚本里的 `$mysql 变量）" }
$env:MYSQL_PWD = $MysqlPassword
$ver = & $mysql -h 127.0.0.1 -P 3306 -u root -N -B -e "SELECT VERSION();" 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "MySQL 连接失败（密码错了或服务没起）：`n$ver"
}
Ok "MySQL $ver 连接正常"

# ---------- 2. 建库建表 ----------
Step '2/5' '建库建表'
$schema = Join-Path $demo 'src\main\resources\db\schema.sql'
if (-not (Test-Path $schema)) { throw "找不到 schema.sql：$schema" }
# 用 cmd 做输入重定向，保证 UTF-8 中文注释不被 PowerShell 破坏
cmd /c "`"$mysql`" -h 127.0.0.1 -P 3306 -u root --default-character-set=utf8mb4 < `"$schema`"" | Out-Null
Ok '表结构已就绪'

if ($Reset) {
    Warn 'Reset：清空 kb_chunk / kb_document'
    & $mysql -h 127.0.0.1 -P 3306 -u root --default-character-set=utf8mb4 -B campus_rag `
        -e "DELETE FROM kb_chunk; DELETE FROM kb_document;" | Out-Null
    Ok '已清空'
}

# ---------- 3. 启动服务 ----------
Step '3/5' "启动 Spring Boot（端口 $Port）"
$listening = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($listening) {
    Warn "端口 $Port 已在监听，跳过启动（想重启先手动关掉旧实例）"
} else {
    $cmdline = "cd /d `"$demo`" && mvn -q -DskipTests spring-boot:run -Dspring-boot.run.arguments=--server.port=$Port > `"$log`" 2>&1"
    Start-Process -FilePath 'cmd' -ArgumentList '/c', $cmdline -WindowStyle Hidden
    Ok "已后台启动，日志：$log"
}

# ---------- 4. 等健康检查 ----------
Step '4/5' '等待服务就绪（首次编译约 20~40 秒）'
$ready = $false
for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep -Seconds 2
    try {
        $h = Invoke-RestMethod -Uri "$base/api/health" -TimeoutSec 2
        if ($h.code -eq 0) { $ready = $true; break }
    } catch { }
    if ($i % 5 -eq 4) { Warn "还在启动… 已等 $(($i + 1) * 2) 秒" }
}
if (-not $ready) { throw "服务 180 秒内没起来，看日志：$log" }
Ok "服务已就绪：$base"

# ---------- 5. 导入语料（只在库里没文档时做） ----------
Step '5/5' '检查并导入语料'
$docCount = (& $mysql -h 127.0.0.1 -P 3306 -u root -N -B campus_rag -e "SELECT COUNT(*) FROM kb_document;").Trim()
$chunkCount = (& $mysql -h 127.0.0.1 -P 3306 -u root -N -B campus_rag -e "SELECT COUNT(*) FROM kb_chunk;").Trim()
Write-Host "   当前：${docCount} 份文档 / ${chunkCount} 个块"

if ([int]$docCount -gt 0 -and -not $Reset) {
    Warn '库里已有数据，跳过导入（想重建：.\start.ps1 -Reset）'
} else {
    # 语料：OCR 出来的 txt（去重）+ 原生文本 PDF（扫描件会在后端失败，随即删除）
    $files = @()
    $files += Get-ChildItem (Join-Path $root 'docs-src-ocr') -Recurse -Filter *.txt -ErrorAction SilentlyContinue |
        Group-Object Name | ForEach-Object { $_.Group[0] }
    $files += Get-ChildItem (Join-Path $root 'docs-src') -Recurse -Filter *.pdf -ErrorAction SilentlyContinue
    Write-Host "   待导入 $($files.Count) 个文件"

    $okCount = 0; $skip = 0; $n = 0
    foreach ($f in $files) {
        $n++
        Write-Host ("   [{0}/{1}] {2}" -f $n, $files.Count, $f.Name) -NoNewline
        $respFile = Join-Path $env:TEMP 'campus-upload.json'
        & curl.exe -s -o $respFile -F "file=@$($f.FullName)" "$base/api/document/upload" | Out-Null
        $json = Get-Content $respFile -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($json.status -eq 2 -and $json.chunkCount -gt 0) {
            Write-Host "  -> $($json.chunkCount) 块" -ForegroundColor Green
            $okCount++
        } else {
            Write-Host "  -> 跳过（扫描件/解析失败），已删除该记录" -ForegroundColor Yellow
            if ($json.id) {
                & curl.exe -s -X DELETE "$base/api/document/$($json.id)" | Out-Null
            }
            $skip++
        }
    }
    Ok "导入完成：成功 $okCount 份，跳过 $skip 份"

    Write-Host "   正在向量化（每批 16 条，约 6~10 秒）…" -NoNewline
    $embFile = Join-Path $env:TEMP 'campus-embed.json'
    & curl.exe -s -o $embFile "$base/api/dev/embed-all?maxChunks=5000" | Out-Null
    $emb = Get-Content $embFile -Raw -Encoding UTF8 | ConvertFrom-Json
    Write-Host ("  完成：{0} 条向量 / {1} ms" -f $emb.embeddedThisRun, $emb.elapsedMs) -ForegroundColor Green
}

# ---------- 收尾 ----------
Write-Host ""
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " 打开浏览器访问：$base" -ForegroundColor White
Write-Host "   - 智能问答：问「转专业需要满足什么条件」" -ForegroundColor Gray
Write-Host "   - 拒答演示：问「食堂几点开门」（库里没有）" -ForegroundColor Gray
Write-Host "   - 检索对比：看五种检索方式的排名差异" -ForegroundColor Gray
Write-Host "   - 评测看板：点一下跑 36 道题" -ForegroundColor Gray
Write-Host "==================================================" -ForegroundColor Cyan
