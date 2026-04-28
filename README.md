# MultiSinkAudio

**MultiSinkAudio** 是一个 Android 演示项目，展示如何在同一台设备上把两路音频流**同时**输出到**不同的 ALSA 声卡**（例如板载耳机孔 + HDMI、或两张独立 USB 声卡）。

---

## 背景与动机

Android 的 `AudioFlinger` / `AudioPolicy` 框架会把所有音频流汇入同一个混音器（mixPort），无法将两条 PCM 流分别路由到不同的物理输出设备。  
本项目完全绕开 Android 音频框架，使用 **tinyalsa** 库通过 JNI 直接向内核 `/dev/snd/pcmC<N>D<M>p` 写入 PCM 数据，从而实现真正的多声卡同时播放。

---

## 项目结构

```
MultiSinkAudio/
├── app/                          # 演示应用（播放器 UI）
│   └── src/main/
│       ├── assets/               # 放置 .mp3 测试音频
│       └── java/com/example/multisinkaudio/
│           └── MainActivity.java
├── multisink-alsa/               # 独立 Android Library 模块
│   └── src/main/
│       ├── java/com/example/multisinkaudio/alsa/
│       │   ├── TinyAlsaPlayer.java        # JNI 薄壳，封装 native 接口
│       │   └── TinyAlsaTrackPlayer.java   # MediaCodec → tinyalsa 直写播放器
│       └── cpp/
│           ├── multisinkalsa_jni.cpp      # JNI 实现（pcm_open / pcm_writei / mixer）
│           ├── CMakeLists.txt
│           └── tinyalsa/                  # tinyalsa 上游源码（静态库）
└── scripts/
    ├── setup_device.ps1          # Windows 一键配置脚本
    └── setup_device.bat          # 双击入口
```

---

## 核心原理

```
MP3 文件
  └─► MediaExtractor
        └─► MediaCodec（软解 MP3 → PCM S16_LE）
              └─► TinyAlsaTrackPlayer
                    └─► JNI → tinyalsa → pcm_open / pcm_writei
                          └─► /dev/snd/pcmC<N>D<M>p  （直接写 ALSA 硬件）
```

每路播放器各自持有一个 `pcm_open` 句柄，运行在独立线程，互不阻塞。  
首次打开声卡时会自动通过 `mixer_open` 配置 codec 混音器（以 ES8388 为例），保证板载输出不静音。

---

## 硬件 & 系统要求

| 条件 | 说明 |
|------|------|
| Android 7.0+（API 24+） | `MediaCodec` / `MediaExtractor` API |
| ARM64（arm64-v8a） | 默认只编译此 ABI，如需其他架构修改 `build.gradle.kts` |
| root 或 userdebug 固件 | 需要 `adb root` + `adb remount` 来修改 `ueventd.rc` |
| `/dev/snd/pcm*` 可写 | 见下方权限配置 |

---

## 快速上手

### 1. 克隆仓库

```bash
git clone https://github.com/<your-org>/MultiSinkAudio.git
cd MultiSinkAudio
```

### 2. 放入测试音频

把两个 `.mp3` 文件分别重命名为 `music_6.mp3`、`music_2.mp3`，放到：

```
app/src/main/assets/
```

### 3. 配置签名（可选）

如果目标设备需要 platform 签名才能获得 `audio` gid，把 `.jks` 文件放到项目根目录并在 `app/build.gradle.kts` 的 `signingConfigs.platform` 中填写对应路径和密码。  
若设备已通过 `ueventd.rc` 放开 `/dev/snd/*` 权限（见第 4 步），普通 debug key 也能正常运行。

### 4. 配置设备权限（一次性）

在 **Windows** 上连接设备后运行：

```bat
scripts\setup_device.bat
```

该脚本会自动完成：
1. `adb root` + `adb remount`
2. 修改设备 `/system/etc/ueventd.rc`，在 `/dev/snd/*` 规则后追加：
   ```
   /dev/snd/pcm*      0666   system   audio
   /dev/snd/control*  0666   system   audio
   ```
3. 临时 `chmod 666 /dev/snd/pcm* /dev/snd/control*`（立即生效，无需重启）
4. 安装 APK

> **说明**：`ueventd.rc` 的改动在设备**重启后永久生效**，之后不再需要重跑脚本。  
> 脚本具备幂等性，重复执行不会插入重复规则。

### 5. 编译并安装

```bash
# 在工程根目录
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

或者直接在 Android Studio 中按 **Run**（先执行一次 `File → Sync Project with Gradle Files`）。

### 6. 使用 App

1. 打开 **MultiSinkAudio**
2. 点击 **"选择 ALSA card"** → 为播放器 1 选择目标声卡
3. 再次点击 → 为播放器 2 选择另一张声卡
4. 点击 **"同时播放"**，两路音频会分别从各自声卡独立输出

---

## 在自己的项目中使用 multisink-alsa 库

```kotlin
// settings.gradle.kts
include(":multisink-alsa")
project(":multisink-alsa").projectDir = file("../MultiSinkAudio/multisink-alsa")

// app/build.gradle.kts
dependencies {
    implementation(project(":multisink-alsa"))
}
```

```java
// 列出系统所有 ALSA card
List<TinyAlsaPlayer.Card> cards = TinyAlsaPlayer.listCards();

// 启动播放
TinyAlsaTrackPlayer player = new TinyAlsaTrackPlayer(
        context, "my_audio.mp3",
        /* alsaCard */ 1, /* alsaDevice */ 0,
        /* looping */ true, /* slot */ 0,
        mainHandler, listener);
player.start();

// 停止
player.stop();
```

---

## 已验证平台

| 设备 | SoC | 声卡 |
|------|-----|------|
| RK3588 开发板 | Rockchip RK3588 | ES8388（板载）+ HDMI |

---

## 注意事项

- **同一声卡独占**：Linux ALSA 默认不允许两个进程同时 `pcm_open` 同一 card+device，多个应用请使用不同声卡。
- **无重采样**：输出 PCM 参数直接沿用 MediaCodec 解码结果（rate / channels），若硬件不支持该采样率会报错。
- **Codec 初始化**：`nativeOpen` 内部已为 ES8388 自动配置 mixer（开启 HP/SPK、设置音量）。其他 codec 如遇静音，可按注释扩展 `apply_codec_unmute()` 函数。
- **SELinux**：脚本配置的是 `permissive` 级别豁免，生产环境建议配置正确的 SELinux 策略。

---

## License

本项目代码以 [MIT License](LICENSE) 发布。  
内嵌的 [tinyalsa](https://github.com/tinyalsa/tinyalsa) 库使用 BSD 许可证，详见 `multisink-alsa/src/main/cpp/tinyalsa/NOTICE`。
