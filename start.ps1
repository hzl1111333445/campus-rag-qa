<#
.SYNOPSIS
    校园规章制度 RAG 智能问答系统 —— 一键构建并启动（单 jar：后端接口 + 前端页面同源）。

.DESCRIPTION
    做三件事：
      1) 构建前端（npm run build）并把产物拷进后端的 static 目录；
      2) 用 Maven 打成可执行 jar；
      3) 启动 jar 并轮询 /api/health，就绪后打印访问地址与演示账号。

.PARAMETER Port
    端口，默认 8081。

.PARAMETER SkipBuild
    跳过前端构建与 jar 打包，直接启动已有的 jar（改完后端小改动时用）。

.PARAMETER Stop
    停掉本脚本启动的实例（按端口找进程）。

.EXAMPLE
    .\start.ps1
    .\start.ps1 -Port 8090
    .\start.ps1 -SkipBuild
    .\start.ps1 -Stop
#>
param(
    [int]$Port = 8081,
    [switch]$SkipBuild,
    [switch]$Stop
)

# 注意：这里不用 'Stop'。npm / mvn / java -version 这些原生命令会把警告写到 stderr，
# 在 Stop 模式下会被当成"终止性错误"直接打断脚本。改为 Continue + 自己检查 $LASTEXITCODE。
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar = Join-Path $root 'demo\target\campus-rag-0.1.0-SNAPSHOT.jar'
$logFile = Join-Path $root 'logs\app.log'

function Write-Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }
function Write-Ok($text) { Write-Host "    $text" -ForegroundColor Green }
function Write-Warn2($text) { Write-Host "    $text" -ForegroundColor Yellow }

# ---------- 停掉实例 ----------
if ($Stop) {
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if (-not $conn) {
        Write-Warn2 "端口 $Port 上没有正在监听的进程"
        return
    }
    $conn | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
    Write-Ok "已停止占用 $Port 的进程（PID: $($conn.OwningProcess -join ', ')）"
    return
}

# ---------- 环境检查 ----------
Write-Step '检查环境'

$javaExe = $null
foreach ($candidate in @(
        'E:\cal\code\jdk21\bin\java.exe',
        "$env:JAVA_HOME\bin\java.exe"
    )) {
    if ($candidate -and (Test-Path $candidate)) { $javaExe = $candidate; break }
}
if (-not $javaExe) {
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) { $javaExe = $cmd.Source }
}
if (-not $javaExe) {
    throw ' 找不到 java.exe：请安装 JDK 21，或把 JAVA_HOME 指到 JDK 21'
}
$javaVersion = cmd /c "`"$javaExe`" -version 2>&1" | Select-Object -First 1
Write-Ok "java: $javaExe"
Write-Ok $javaVersion.Trim()
if ($javaVersion -notmatch '21|22|23|24') {
    Write-Warn2 '注意：项目要求 JDK 21+，当前版本可能编译不过（record/pattern matching 会用不到）'
}

$mysqlExe = 'E:\cal\MySQL\MySQL Server 8.0\bin\mysql.exe'
if (Test-Path $mysqlExe) { Write-Ok "mysql client: $mysqlExe" }
else { Write-Warn2 ' 没找到 mysql 客户端，跳过数据库连通性检查（应用启动时如果连不上会自己报错）' }

if (-not (Test-Path $logFile)) { New-Item -ItemType Directory -Force -Path (Split-Path $logFile) | Out-Null }

# ---------- 构建 ----------
if (-not $SkipBuild) {
    Write-Step '构建前端（Vue 3 + Vite）'
    Push-Location (Join-Path $root 'frontend')
    try {
        if (-not (Test-Path 'node_modules')) {
            Write-Ok '首次运行，安装前端依赖（npm install）…'
            & npm install
            if ($LASTEXITCODE -ne 0) { throw 'npm install 失败' }
        }
        & npm run build
        if ($LASTEXITCODE -ne 0) { throw 'npm run build 失败' }
    }
    finally { Pop-Location }

    Write-Step '把前端产物拷进后端 static（让一个 jar 同时提供页面和接口）'
    $staticDir = Join-Path $root 'demo\src\main\resources\static'
    $distDir = Join-Path $root 'frontend\dist'
    if (-not (Test-Path $distDir)) { throw " 没找到 $distDir，前端构建可能没成功" }
    if ((Test-Path (Join-Path $staticDir 'index.html')) -and -not (Test-Path (Join-Path $staticDir 'legacy-debug.html'))) {
        Move-Item (Join-Path $staticDir 'index.html') (Join-Path $staticDir 'legacy-debug.html')
    }
    Remove-Item -Recurse -Force (Join-Path $staticDir 'assets') -ErrorAction SilentlyContinue
    Copy-Item (Join-Path $distDir 'index.html') $staticDir -Force
    Copy-Item -Recurse (Join-Path $distDir 'assets') $staticDir -Force
    Write-Ok 'static/ 已更新'

    Write-Step '打包可执行 jar（mvn package -DskipTests）'
    Push-Location (Join-Path $root 'demo')
    try {
        $env:JAVA_HOME = Split-Path (Split-Path $javaExe -Parent) -Parent
        $env:PATH = (Split-Path $javaExe -Parent) + ';' + $env:PATH
        & mvn -o -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'mvn package 失败（先跑一次不带 -o 的 mvn 让依赖下载完）' }
    }
    finally { Pop-Location }
}
else {
    Write-Step '跳过构建（-SkipBuild）'
}

if (-not (Test-Path $jar)) {
    throw " 没找到 jar：$jar`n     先执行 .\start.ps1（不带 -SkipBuild）打一次包"
}
Write-Ok "jar: $jar ($([math]::Round((Get-Item $jar).Length / 1MB, 1)) MB)"

# ---------- 启动 ----------
Write-Step "启动服务（端口 $Port）"
$existing = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($existing) {
    Write-Warn2 "端口 $Port 已被 PID $($existing.OwningProcess -join ',') 占用，先执行 .\start.ps1 -Stop -Port $Port"
    throw " 端口占用"
}

# 用 cmd 的 start /b 启动：java 进程不继承当前 PowerShell 的 stdout/stderr 句柄。
# 用 Start-Process -RedirectStandardOutput 的写法在这里会出问题——子进程一直握着句柄，
# 调用方（尤其是 CI / 管道）永远等不到脚本退出。
$launch = 'start "" /b "' + $javaExe + '" -jar "' + $jar + '" --server.port=' + $Port +
    ' > "' + $logFile + '" 2>&1'
cmd /c $launch
Write-Ok "已启动（后台），日志 $logFile"

$health = $null
for ($i = 1; $i -le 60; $i++) {
    Start-Sleep -Milliseconds 1000
    try {
        $health = Invoke-RestMethod -Uri "http://localhost:$Port/api/health" -TimeoutSec 2
        if ($health.status -eq 'UP') { break }
    }
    catch { $health = $null }
}
if (-not $health) {
    Write-Warn2 "60 秒内没等到 /api/health 就绪，最后 20 行日志："
    Get-Content $logFile -Tail 20 -ErrorAction SilentlyContinue
    throw ' 启动失败'
}

Write-Step '就绪'
$listen = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listen) {
    $pidDir = Join-Path $root 'logs'
    if (-not (Test-Path $pidDir)) { New-Item -ItemType Directory -Force -Path $pidDir | Out-Null }
    $pidFile = Join-Path $pidDir 'app.pid'
    Set-Content -Path $pidFile -Value $listen.OwningProcess -Encoding ASCII
    Write-Ok "进程 PID：$($listen.OwningProcess)（已写入 logs/app.pid）"
}
Write-Ok "应用：$($health.appName)   Java：$($health.javaVersion)"
Write-Host ""
Write-Host "  页面（浏览器打开）：  http://localhost:$Port/" -ForegroundColor White
Write-Host "  健康检查：            http://localhost:$Port/api/health" -ForegroundColor White
Write-Host "  演示账号：            admin / admin123（管理员）   student / student123（学生）" -ForegroundColor White
Write-Host ""
Write-Host "  停止：.\start.ps1 -Stop -Port $Port" -ForegroundColor DarkGray
