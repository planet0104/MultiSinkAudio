# =====================================================================
#  MultiSinkAudio - RK3399 一键配置脚本
# ---------------------------------------------------------------------
#  RK3399 板子（多为 Android 7/8 老系统）跟 RK3588 流程几乎一样，
#  差别在 ueventd.rc 的实际位置：
#    - 较老的 7.x：规则可能在 ramdisk 的 /ueventd.rc（不能在线 patch）
#    - 8.x Treble：规则通常在 /vendor/etc/ueventd.rc
#    - 也有 ROM 把厂商规则单独放成 ueventd.rk3399.rc
#
#  这个脚本会自动探测，按"哪个文件里有 /dev/snd/* 规则就 patch 哪个"
#  的策略写规则。如果 /dev/snd/* 规则只存在于 ramdisk（无法在线改），
#  会退化成"只做当场 chmod，每次重启后再跑一次"。
#
#  前提（用户已确认）：
#    - 设备已 root
#    - adb 已连接
#
#  幂等：可重复运行；已 patch 过的 ueventd 文件会被识别并跳过。
#
#  用法：
#    powershell -ExecutionPolicy Bypass -File scripts\setup_device_rk3399.ps1
#    或者直接双击 scripts\setup_device_rk3399.bat
# =====================================================================

$ErrorActionPreference = 'Stop'

# 进到工程根目录，确保 APK 路径解析正确
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
Set-Location $projectRoot

# 注意：marker 字符串故意保留原始名 "TestAudioDevice"，跟 RK3588 脚本保持一致；
# 这样同一台板子先跑过 RK3588 脚本再跑 RK3399 脚本（或反过来）也不会重复插入。
$marker  = '# AUDIO_PERM_PATCH (TestAudioDevice)'
$rulePcm = '/dev/snd/pcm*             0666   system     audio'
$ruleCtl = '/dev/snd/control*         0666   system     audio'
$tmpFile = Join-Path $env:TEMP ('ueventd.rc.{0}' -f [System.Guid]::NewGuid().Guid)

# RK3399 上常见的 ueventd 文件位置（按优先级）
$ueventdCandidates = @(
    '/system/etc/ueventd.rc',
    '/vendor/etc/ueventd.rc',
    '/vendor/ueventd.rc',
    '/odm/etc/ueventd.rc',
    '/system/etc/ueventd.rk3399.rc',
    '/vendor/etc/ueventd.rk3399.rc',
    '/ueventd.rc',
    '/ueventd.rk3399.rc'
)

function Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }
function Info($msg) { Write-Host "  $msg" }
function Warn($msg) { Write-Host "  $msg" -ForegroundColor Yellow }
function Die($msg) {
    Write-Host "  [错误] $msg" -ForegroundColor Red
    if (Test-Path $tmpFile) { Remove-Item $tmpFile -ErrorAction SilentlyContinue }
    exit 1
}

# 包一层，避免 adb shell 执行子命令时把退出码丢失
function Adb-Shell($cmd) {
    return (& adb shell $cmd 2>&1 | Out-String)
}

# --- 1. 检查 adb 设备 ----------------------------------------------------
Step '1/6 检查 adb 设备'
$devices = & adb devices | Select-String -Pattern '\bdevice$'
if (-not $devices) { Die '没有 adb 设备连接。' }
$devices | ForEach-Object { Info $_.Line.Trim() }

# --- 2. adb root + remount ---------------------------------------------
Step '2/6 提权 adbd 并 remount /system /vendor'
& adb root | Out-Null
Start-Sleep -Milliseconds 800
& adb wait-for-device

# RK3399 上有的 ROM 是单分区，有的是 Treble（/vendor 独立），
# adb remount 不带参数时通常会把所有可写分区一起 remount。
$remount = & adb remount 2>&1 | Out-String
Info ($remount.Trim())
if ($LASTEXITCODE -ne 0) {
    Warn 'adb remount 返回非 0，可能 dm-verity 还在锁。'
    Warn '继续走当场 chmod 路径，但 ueventd.rc 改动可能写不进去。'
}

# --- 3. 定位放着 /dev/snd 规则的 ueventd 文件 -----------------------------
Step '3/6 定位 ueventd 配置文件'

$targetFile = $null    # 真正要 patch 的文件
$readonlyHit = $null   # 命中但不可写（比如 /ueventd.rc 在 ramdisk）

foreach ($f in $ueventdCandidates) {
    $exists = (Adb-Shell "[ -f '$f' ] && echo yes || echo no").Trim()
    if ($exists -ne 'yes') { continue }

    $hasSnd = (Adb-Shell "grep -q '/dev/snd' '$f' && echo yes || echo no").Trim()
    if ($hasSnd -ne 'yes') {
        Info "存在但无 /dev/snd 规则：$f"
        continue
    }

    # 试探可不可写：直接 touch 一下
    $writeProbe = (Adb-Shell "touch '$f' >/dev/null 2>&1 && echo ok || echo ro").Trim()
    if ($writeProbe -eq 'ok') {
        Info "命中且可写：$f"
        $targetFile = $f
        break
    } else {
        Info "命中但只读：$f"
        if (-not $readonlyHit) { $readonlyHit = $f }
    }
}

if (-not $targetFile -and -not $readonlyHit) {
    # 一份 /dev/snd 规则都没找到，按 RK3588 脚本的兜底策略，写到 /system/etc/ueventd.rc 末尾
    Warn '没有任何 ueventd 文件包含 /dev/snd 规则，回退到 /system/etc/ueventd.rc 追加。'
    $sysExists = (Adb-Shell "[ -f /system/etc/ueventd.rc ] && echo yes || echo no").Trim()
    if ($sysExists -eq 'yes') {
        $writeProbe = (Adb-Shell "touch /system/etc/ueventd.rc >/dev/null 2>&1 && echo ok || echo ro").Trim()
        if ($writeProbe -eq 'ok') {
            $targetFile = '/system/etc/ueventd.rc'
        }
    }
}

# --- 4. patch ueventd.rc -----------------------------------------------
Step '4/6 修改 ueventd 规则'

if (-not $targetFile) {
    if ($readonlyHit) {
        Warn ("/dev/snd 规则只在只读文件里：{0}" -f $readonlyHit)
        Warn 'ramdisk 里的 ueventd 不能在线 patch；本次只做当场 chmod。'
        Warn '后续每次重启后请重跑本脚本（或手工 chmod 666 /dev/snd/pcm* /dev/snd/control*）。'
    } else {
        Warn '没有可写的 ueventd 文件，跳过持久化，仅做当场 chmod。'
    }
} else {
    Info "Patch 目标：$targetFile"

    & adb pull $targetFile $tmpFile | Out-Null
    if (-not (Test-Path $tmpFile)) { Die "拉取 $targetFile 失败。" }

    $content = Get-Content $tmpFile -Raw
    if ($content -match [regex]::Escape($marker)) {
        Info "$targetFile 已经包含本工具的 patch，跳过修改。"
    } else {
        Info '插入 pcm/control 0666 规则...'
        $lines = Get-Content $tmpFile
        $out = New-Object System.Collections.Generic.List[string]
        $patched = $false
        foreach ($line in $lines) {
            $out.Add($line)
            if ((-not $patched) -and ($line -match '^\s*/dev/snd/')) {
                $out.Add($marker)
                $out.Add($rulePcm)
                $out.Add($ruleCtl)
                $patched = $true
            }
        }
        if (-not $patched) {
            Warn '未匹配到 /dev/snd/ 起始行，把规则追加到文件末尾。'
            $out.Add('')
            $out.Add($marker)
            $out.Add($rulePcm)
            $out.Add($ruleCtl)
        }
        # 保留 LF 换行，避免 ueventd 解析 CRLF 出错
        [System.IO.File]::WriteAllLines($tmpFile, $out, (New-Object System.Text.UTF8Encoding($false)))
        & adb push $tmpFile $targetFile | Out-Null
        if ($LASTEXITCODE -ne 0) { Die "push $targetFile 失败。" }
        # 修正属主与权限（ueventd.rc 自身需要 0644 root:root）
        & adb shell "chown root:root $targetFile; chmod 0644 $targetFile" | Out-Null
        Info "已写入 $targetFile"
    }

    Info "当前 $targetFile 中的 snd 规则："
    & adb shell "grep -E 'snd|AUDIO_PERM' $targetFile"
}
Remove-Item $tmpFile -ErrorAction SilentlyContinue

# --- 5. 现场 chmod ------------------------------------------------------
Step '5/6 现场 chmod 666 /dev/snd/pcm* /dev/snd/control*'
& adb shell 'chmod 666 /dev/snd/pcm* /dev/snd/control* 2>/dev/null; ls -l /dev/snd/'

# --- 6. 安装 APK -------------------------------------------------------
Step '6/6 安装 APK'

# 历史包名清理（与 RK3588 脚本保持一致）
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
if ($targetFile) {
    Write-Host "  - 已 patch $targetFile，重启后 /dev/snd/pcm*/control* 自动是 0666。" -ForegroundColor Green
} else {
    Write-Host '  - 未能持久化 ueventd 规则；每次重启后请重跑本脚本。' -ForegroundColor Yellow
}
Write-Host '  - 当前会话已经 chmod 666，本次启动 app 直接可用，不必重启。' -ForegroundColor Green

$revert = @'

  恢复 ueventd 配置（如有需要）：
    adb root && adb remount
    adb shell "for f in /system/etc/ueventd.rc /vendor/etc/ueventd.rc /vendor/ueventd.rc /odm/etc/ueventd.rc; do [ -f \$f ] && sed -i '/AUDIO_PERM_PATCH/,+2d' \$f; done"
'@
Write-Host $revert
