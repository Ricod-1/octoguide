<#
    OctoGuide 一键启动 / 停止编排脚本。

    用法（一般通过 start-all.cmd / stop-all.cmd 双击调用）：
      powershell -NoProfile -ExecutionPolicy Bypass -File start-all.ps1          启动前后端
      powershell -NoProfile -ExecutionPolicy Bypass -File start-all.ps1 -DryRun  只打印计划，不启动进程
      powershell -NoProfile -ExecutionPolicy Bypass -File start-all.ps1 -Stop    停止前后端
      powershell -NoProfile -ExecutionPolicy Bypass -File start-all.ps1 -NoPause 结束后不等待按键

    说明：本脚本只负责编排进程，不注入任何应用配置；后端仍按 start-backend.cmd 的原有方式启动，
    日志实时输出在独立的「OctoGuide Backend (8080)」窗口里。
#>
[CmdletBinding()]
param(
    [switch]$DryRun,
    [switch]$Stop,
    [switch]$NoPause
)

$ErrorActionPreference = 'Stop'

# ---------------- 可配置项（按本机环境修改） ----------------
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$JavaHome = 'C:\Program Files\Java\jdk1.8.0_192'
$MavenCmd = 'D:\programli\Java web\java\apache-maven-3.6.3\bin\mvn.cmd'
$MysqlServiceName = 'MySQL'
$BackendPort = 8080
$FrontendPort = 5173
$BackendReadyTimeoutSeconds = 180   # Maven 首次启动较慢，给足时间
$FrontendReadyTimeoutSeconds = 60
# -----------------------------------------------------------

$BackendScript = Join-Path $Root 'start-backend.cmd'
$FrontendScript = Join-Path $Root 'start-frontend.cmd'

try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { }
try { $Host.UI.RawUI.WindowTitle = 'OctoGuide 一键启动' } catch { }

# ---------------- 输出辅助 ----------------
function Write-Head([string]$Text) { Write-Host $Text -ForegroundColor Cyan }
function Write-Step([string]$Text) { Write-Host ('[..] ' + $Text) -ForegroundColor Gray }
function Write-Ok([string]$Text) { Write-Host ('[OK] ' + $Text) -ForegroundColor Green }
function Write-WarnMsg([string]$Text) { Write-Host ('[!]  ' + $Text) -ForegroundColor Yellow }
function Write-Fail([string]$Text) { Write-Host ('[X]  ' + $Text) -ForegroundColor Red }

function Wait-Close {
    if ($NoPause) { return }
    Write-Host ''
    [void](Read-Host '按回车键关闭此窗口')
}

# ---------------- 端口与就绪检测 ----------------
function Test-PortOpen {
    param([int]$Port)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if ($async.AsyncWaitHandle.WaitOne(400, $false)) {
            $client.EndConnect($async)
            return $true
        }
        return $false
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Wait-ForPort {
    param([int]$Port, [int]$TimeoutSeconds, [string]$Label)
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $nextNotice = 10
    while ($stopwatch.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        if (Test-PortOpen -Port $Port) { return $true }
        Start-Sleep -Milliseconds 700
        if ($stopwatch.Elapsed.TotalSeconds -ge $nextNotice) {
            Write-Host ('     已等待 ' + [int]$stopwatch.Elapsed.TotalSeconds + 's / ' + $TimeoutSeconds + 's （' + $Label + ' ' + $Port + '）…') -ForegroundColor DarkGray
            $nextNotice += 10
        }
    }
    return $false
}

function Test-BackendHttp {
    try {
        $response = Invoke-WebRequest -Uri ('http://127.0.0.1:' + $BackendPort + '/api/status') -UseBasicParsing -TimeoutSec 5
        return ($response.StatusCode -eq 200)
    } catch {
        return $false
    }
}

# ---------------- 前置检查与服务 ----------------
function Get-MissingPrerequisites {
    $missing = @()
    if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
        $missing += ('JAVA_HOME 目录无效：' + $JavaHome)
    }
    if (-not (Test-Path -LiteralPath $MavenCmd)) {
        $missing += ('找不到 Maven：' + $MavenCmd)
    }
    if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) {
        $missing += 'PATH 中找不到 npm.cmd（请确认已安装 Node.js）'
    }
    if (-not (Test-Path -LiteralPath $BackendScript)) {
        $missing += ('缺少后端启动脚本：' + $BackendScript)
    }
    if (-not (Test-Path -LiteralPath $FrontendScript)) {
        $missing += ('缺少前端启动脚本：' + $FrontendScript)
    }
    return $missing
}

function Ensure-MysqlRunning {
    $service = Get-Service -Name $MysqlServiceName -ErrorAction SilentlyContinue
    if ($null -eq $service) {
        Write-WarnMsg ('未找到名为 ' + $MysqlServiceName + ' 的 MySQL 服务，跳过检查（若使用远程数据库可忽略）')
        return
    }
    if ($service.Status -eq 'Running') {
        Write-Ok 'MySQL 服务正在运行'
        return
    }
    if ($DryRun) {
        Write-Host ('[DryRun] 将尝试启动服务 ' + $MysqlServiceName) -ForegroundColor DarkGray
        return
    }
    Write-Step ('MySQL 未运行，尝试启动服务 ' + $MysqlServiceName + ' …')
    try {
        Start-Service -Name $MysqlServiceName -ErrorAction Stop
        Write-Ok 'MySQL 服务已启动'
    } catch {
        Write-WarnMsg ('无法自动启动 MySQL（通常需要管理员权限）：' + $_.Exception.Message)
        Write-WarnMsg '请以管理员身份执行「net start MySQL」；后端可能因此启动失败。'
    }
}

# ---------------- 启动子窗口 ----------------
function Start-ChildWindow {
    param([string]$ScriptPath, [string]$Label)
    if ($DryRun) {
        Write-Host ('[DryRun] 将新开窗口执行：' + $ScriptPath) -ForegroundColor DarkGray
        return
    }
    Start-Process -FilePath $ScriptPath -WorkingDirectory $Root | Out-Null
    Write-Ok ($Label + '启动命令已发出（独立窗口）')
}

# ---------------- 停止逻辑 ----------------
function Get-ListeningProcessIds {
    param([int]$Port)
    $processIds = @()
    if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
        $processIds = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
                Select-Object -ExpandProperty OwningProcess -Unique)
    } else {
        # 回退方案：解析 netstat -ano（Proto Local Foreign State PID）
        foreach ($line in (netstat -ano | Select-String (':' + $Port + '\s'))) {
            $parts = ($line.ToString().Trim() -split '\s+')
            if ($parts.Length -ge 5 -and $parts[3] -eq 'LISTENING') {
                $processIds += [int]$parts[4]
            }
        }
        $processIds = @($processIds | Select-Object -Unique)
    }
    return @($processIds | Where-Object { $_ -and $_ -gt 0 })
}

function Stop-PortProcesses {
    param([int]$Port, [string]$Label)
    $targets = Get-ListeningProcessIds -Port $Port
    if ($targets.Count -eq 0) {
        Write-Host ('[--] ' + $Label + '端口 ' + $Port + ' 没有正在运行的实例') -ForegroundColor DarkGray
        return 0
    }
    $killed = 0
    foreach ($processId in $targets) {
        try {
            $process = Get-Process -Id $processId -ErrorAction Stop
            Write-Host ('[>>] 结束' + $Label + '进程 PID ' + $processId + '（' + $process.ProcessName + '）') -ForegroundColor Yellow
            Stop-Process -Id $processId -Force -ErrorAction Stop
            $killed++
        } catch {
            Write-WarnMsg ('结束 PID ' + $processId + ' 失败：' + $_.Exception.Message)
        }
    }
    return $killed
}

function Stop-LeftoverWrappers {
    $killed = 0
    try {
        $candidates = @(Get-CimInstance Win32_Process -ErrorAction Stop |
                Where-Object {
                    $_.CommandLine -and
                    $_.CommandLine -like ('*' + $Root + '*') -and
                    ($_.CommandLine -like '*spring-boot:run*' -or
                     $_.CommandLine -like '*vite*' -or
                     $_.CommandLine -like '*start-backend.cmd*' -or
                     $_.CommandLine -like '*start-frontend.cmd*')
                })
    } catch {
        Write-WarnMsg ('跳过残留进程清理：' + $_.Exception.Message)
        return 0
    }
    foreach ($candidate in $candidates) {
        $processId = [int]$candidate.ProcessId
        if ($processId -eq $PID) { continue }
        try {
            Write-Host ('[>>] 结束残留进程 PID ' + $processId + '（' + $candidate.Name + '）') -ForegroundColor Yellow
            Stop-Process -Id $processId -Force -ErrorAction Stop
            $killed++
        } catch {
            Write-WarnMsg ('结束 PID ' + $processId + ' 失败：' + $_.Exception.Message)
        }
    }
    return $killed
}

# ---------------- 停止模式 ----------------
if ($Stop) {
    Write-Head '=== OctoGuide 停止前后端 ==='
    $total = 0
    $total += Stop-PortProcesses -Port $BackendPort -Label '后端'
    $total += Stop-PortProcesses -Port $FrontendPort -Label '前端'
    $total += Stop-LeftoverWrappers
    Write-Host ''
    if ($total -gt 0) {
        Write-Ok ('已结束 ' + $total + ' 个进程')
    } else {
        Write-Ok '没有正在运行的实例，无需停止'
    }
    Write-Host '提示：若日志窗口仍停留在「请按任意键继续」，直接关闭该窗口即可。' -ForegroundColor DarkGray
    Wait-Close
    exit 0
}

# ---------------- 启动模式 ----------------
Write-Head '=== OctoGuide 一键启动（后端 8080 / 前端 5173）==='
if ($DryRun) {
    Write-WarnMsg 'DryRun 模式：只打印计划，不会真正启动任何进程'
}
Write-Host ''

$missing = Get-MissingPrerequisites
if ($missing.Count -gt 0) {
    Write-Fail '前置检查未通过：'
    foreach ($item in $missing) {
        Write-Host ('     - ' + $item) -ForegroundColor Red
    }
    Wait-Close
    exit 1
}
Write-Ok '工具链检查通过（JAVA_HOME / Maven / npm）'

Ensure-MysqlRunning

# 后端
$backendState = ''
if (Test-PortOpen -Port $BackendPort) {
    $backendState = '已运行（端口已在监听，跳过启动）'
    Write-Ok '后端已运行，跳过启动'
} elseif ($DryRun) {
    Start-ChildWindow -ScriptPath $BackendScript -Label '后端'
    $backendState = 'DryRun：未实际启动'
} else {
    Write-Step '启动后端（独立窗口，该窗口即后端实时日志窗口）'
    Start-ChildWindow -ScriptPath $BackendScript -Label '后端'
    Write-Step ('等待后端就绪（最多 ' + $BackendReadyTimeoutSeconds + ' 秒）…')
    if (Wait-ForPort -Port $BackendPort -TimeoutSeconds $BackendReadyTimeoutSeconds -Label '后端') {
        if (Test-BackendHttp) {
            $backendState = '启动成功（/api/status 正常）'
            Write-Ok '后端已完成启动，/api/status 可访问'
        } else {
            $backendState = '端口已就绪，但 /api/status 自检未通过'
            Write-WarnMsg '/api/status 自检未通过，请查看后端日志窗口'
        }
    } else {
        $backendState = ('启动超时（' + $BackendReadyTimeoutSeconds + 's 内未监听端口）')
        Write-WarnMsg '后端仍在启动中或已失败，请查看后端日志窗口（未终止该进程）'
    }
}

# 前端
$frontendState = ''
if (Test-PortOpen -Port $FrontendPort) {
    $frontendState = '已运行（端口已在监听，跳过启动）'
    Write-Ok '前端已运行，跳过启动'
} elseif ($DryRun) {
    Start-ChildWindow -ScriptPath $FrontendScript -Label '前端'
    $frontendState = 'DryRun：未实际启动'
} else {
    Write-Step '启动前端（独立窗口）'
    Start-ChildWindow -ScriptPath $FrontendScript -Label '前端'
    Write-Step ('等待前端就绪（最多 ' + $FrontendReadyTimeoutSeconds + ' 秒）…')
    if (Wait-ForPort -Port $FrontendPort -TimeoutSeconds $FrontendReadyTimeoutSeconds -Label '前端') {
        $frontendState = '启动成功'
        Write-Ok '前端已完成启动'
    } else {
        $frontendState = '启动超时'
        Write-WarnMsg '前端仍在启动中或已失败，请查看前端窗口（未终止该进程）'
    }
}

Write-Host ''
Write-Head '=== 启动汇总 ==='
Write-Host ('  后端地址    http://localhost:' + $BackendPort + '    ' + $backendState)
Write-Host ('  前端地址    http://localhost:' + $FrontendPort + '    ' + $frontendState)
Write-Host '  后端日志    标题为「OctoGuide Backend (8080)」的窗口，日志实时滚动'
Write-Host '  停止方式    双击 stop-all.cmd（或执行 start-all.ps1 -Stop）'
Wait-Close
exit 0
