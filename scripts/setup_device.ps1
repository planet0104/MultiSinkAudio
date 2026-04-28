# =====================================================================
#  MultiSinkAudio - 一键配置脚本
# ---------------------------------------------------------------------
#  在新板子上做这些事（任何步骤失败都会终止）：
#    1) adb root + adb remount，让 /system 可写
#    2) 拉取 /system/etc/ueventd.rc，在 /dev/snd/* 规则后追加：
#         /dev/snd/pcm*       0666 system audio
#         /dev/snd/control*   0666 system audio
#       这样开机后 ueventd 自动放权，app 不再需要任何 root 操作
#    3) 现场 chmod 666 /dev/snd/pcm* /dev/snd/control*
#       (ueventd.rc 改动要重启才生效，先临时放一次让本次启动也能用)
#    4) 安装 APK：app/build/outputs/apk/debug/app-debug.apk
#       (顺带把以前 flavor 时代留下的 .playera / .playerb 包卸掉，干净一点)
#
#  幂等：可以重复运行，已经 patch 过的 ueventd.rc 会被识别并跳过。
#
#  适用范围：root 过的 RK3588 等 userdebug 板子；走 adb remount 时
#  必须 dm-verity 已关 / overlay 可用，否则 remount 会失败。
#
#  用法：
#    powershell -ExecutionPolicy Bypass -File scripts\setup_device.ps1
#    或者直接双击 scripts\setup_device.bat
# =====================================================================

$ErrorActionPreference = 'Stop'

# 进到工程根目录，确保 APK 路径解析正确
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
Set-Location $projectRoot

# 注意：marker 字符串故意保留原始名 "TestAudioDevice"，不能改。
# 这是写进设备 /system/etc/ueventd.rc 的稳定标识；改了之后老设备上的旧 marker
# 不再匹配，脚本会重复插入一组规则，留下垃圾。
$marker  = '# AUDIO_PERM_PATCH (TestAudioDevice)'
$rulePcm = '/dev/snd/pcm*             0666   system     audio'
$ruleCtl = '/dev/snd/control*         0666   system     audio'
$tmpFile = Join-Path $env:TEMP ('ueventd.rc.{0}' -f [System.Guid]::NewGuid().Guid)

function Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }
function Info($msg) { Write-Host "  $msg" }
function Warn($msg) { Write-Host "  $msg" -ForegroundColor Yellow }
function Die($msg) {
    Write-Host "  [错误] $msg" -ForegroundColor Red
    if (Test-Path $tmpFile) { Remove-Item $tmpFile -ErrorAction SilentlyContinue }
    exit 1
}

# --- 1. 检查 adb 设备 ----------------------------------------------------
Step '1/5 检查 adb 设备'
$devices = & adb devices | Select-String -Pattern '\bdevice$'
if (-not $devices) { Die '没有 adb 设备连接。' }
$devices | ForEach-Object { Info $_.Line.Trim() }

# --- 2. adb root + remount ----------------------------------------------
Step '2/5 提权 adbd 并 remount /system'
& adb root | Out-Null
Start-Sleep -Milliseconds 800
& adb wait-for-device
$remount = & adb remount 2>&1 | Out-String
Info ($remount.Trim())
if ($LASTEXITCODE -ne 0) {
    Die 'adb remount 失败。这块板子可能没开 overlay 或 dm-verity 还在锁。'
}

# --- 3. patch ueventd.rc -----------------------------------------------
Step '3/5 修改 /system/etc/ueventd.rc'
& adb pull /system/etc/ueventd.rc $tmpFile | Out-Null
if (-not (Test-Path $tmpFile)) { Die '拉取 ueventd.rc 失败。' }

$content = Get-Content $tmpFile -Raw
if ($content -match [regex]::Escape($marker)) {
    Info 'ueventd.rc 已经包含本工具的 patch，跳过修改。'
} else {
    Info '插入 pcm/control 0666 规则...'
    # 在 "/dev/snd/* ..." 规则那行后面插入新规则
    $lines = Get-Content $tmpFile
    $out = New-Object System.Collections.Generic.List[string]
    $patched = $false
    foreach ($line in $lines) {
        $out.Add($line)
        if ((-not $patched) -and ($line -match '^\s*/dev/snd/\*\s+')) {
            $out.Add($marker)
            $out.Add($rulePcm)
            $out.Add($ruleCtl)
            $patched = $true
        }
    }
    if (-not $patched) {
        # 这板子的 ueventd.rc 没有 /dev/snd/* 行，直接追加到末尾
        Warn '未找到 "/dev/snd/* ..." 行，把规则追加到 ueventd.rc 末尾。'
        $out.Add('')
        $out.Add($marker)
        $out.Add($rulePcm)
        $out.Add($ruleCtl)
    }
    # 写回。注意保留 LF 换行符，避免 ueventd 解析 CRLF 出错。
    [System.IO.File]::WriteAllLines($tmpFile, $out, (New-Object System.Text.UTF8Encoding($false)))
    & adb push $tmpFile /system/etc/ueventd.rc | Out-Null
    if ($LASTEXITCODE -ne 0) { Die 'push ueventd.rc 失败。' }
    Info '已写入 /system/etc/ueventd.rc'
}
Remove-Item $tmpFile -ErrorAction SilentlyContinue

# 打出关键几行让用户肉眼确认
Info '当前 ueventd.rc 中的 snd 规则：'
& adb shell "grep -E 'snd|AUDIO_PERM' /system/etc/ueventd.rc"

# --- 4. 现场 chmod ------------------------------------------------------
Step '4/5 现场 chmod 666 /dev/snd/pcm* /dev/snd/control*'
& adb shell 'chmod 666 /dev/snd/pcm* /dev/snd/control* 2>/dev/null; ls -l /dev/snd/'

# --- 5. 安装 APK -------------------------------------------------------
Step '5/5 安装 APK'

# 历史包名（flavor 时代的 .playera/.playerb，以及上一轮的 testaudiodevice）。
# 如果设备上还装着，顺手卸了避免和新包名 com.example.multisinkaudio 共存。
foreach ($legacy in @('com.example.testaudiodevice.playera',
                      'com.example.testaudiodevice.playerb',
                      'com.example.testaudiodevice')) {
    $listed = & adb shell "pm list packages $legacy" 2>$null
    if ($listed -match [regex]::Escape($legacy)) {
        Info "卸载旧 flavor 包 $legacy"
        & adb uninstall $legacy | Out-Null
    }
}

$apk = 'app\build\outputs\apk\debug\app-debug.apk'
if (Test-Path $apk) {
    Info "安装 $apk"
    & adb install -r $apk
} else {
    Warn "$apk 不存在，先跑：.\gradlew.bat :app:assembleDebug"
}

Write-Host ''
Write-Host '=== 完成 ===' -ForegroundColor Green
Write-Host '  - ueventd.rc 已 patch；下次重启后 /dev/snd/pcm*/control* 自动是 0666。' -ForegroundColor Green
Write-Host '  - 当前会话已经 chmod 666，本次启动 app 直接可用，不必重启。' -ForegroundColor Green

$revert = @'

  恢复 ueventd.rc 出厂状态（如有需要）：
    adb root && adb remount
    adb shell "sed -i '/AUDIO_PERM_PATCH/,+2d' /system/etc/ueventd.rc"
'@
Write-Host $revert
