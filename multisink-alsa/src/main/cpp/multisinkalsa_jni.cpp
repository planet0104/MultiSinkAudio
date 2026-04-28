//
// multisinkalsa_jni.cpp
//
// 把 tinyalsa 的 PCM 接口包成 JNI 给 Java 调，绕开 AudioFlinger / AudioPolicy，
// 直接 ioctl /dev/snd/pcmCxDxp，把 PCM byte[] 灌到指定的 ALSA card+device。
//
// 用法（Java 侧）：
//   long h = TinyAlsaPlayer.nativeOpen(card, device, rate, channels, periodSize, periodCount);
//   if (h == 0) ...错误...
//   int written = TinyAlsaPlayer.nativeWrite(h, byteArr, 0, len);
//   ...
//   TinyAlsaPlayer.nativeClose(h);
//
// 设计要点：
//   - handle 是 reinterpret_cast<jlong> 的 PcmHandle*，0 表示失败
//   - 只支持 S16_LE（最常用），如果以后要支持 S32 / float 再扩
//   - nativeWrite 收到的字节数必须是 frame_size 的整数倍，不是的话内部对齐
//   - JNI 层不持锁，单 handle 单线程使用即可（一个 TinyAlsaTrackPlayer 一个 handle）
//

#include <jni.h>
#include <android/log.h>

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

extern "C" {
#include <tinyalsa/asoundlib.h>
#include <tinyalsa/pcm.h>
#include <tinyalsa/mixer.h>
}

#define LOG_TAG "TinyAlsaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct PcmHandle {
    struct pcm *pcm = nullptr;
    unsigned int card = 0;
    unsigned int device = 0;
    unsigned int rate = 0;
    unsigned int channels = 0;
    unsigned int frame_bytes = 0;   // = channels * 2 (S16_LE)
    int slot = -1;                  // 仅用于日志
};

inline PcmHandle *fromHandle(jlong h) {
    return reinterpret_cast<PcmHandle *>(static_cast<uintptr_t>(h));
}

inline jlong toHandle(PcmHandle *p) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(p));
}

// ---------------- codec unmute helpers ----------------
//
// 我们绕过了 AudioFlinger / audio HAL，直接 ioctl 写 PCM；
// 板载 codec（典型如 RK3588 + ES8388）默认状态是全部 mute——
// DAC 通路、耳机/喇叭开关、输出音量都为 0，必须我们自己拨开。
//
// 实测下来，先用 `tinymix -D <card>` 看一眼控制名字，再在这里
// 一条条尝试设置；找不到的 control 当作"这张卡没有"，静默跳过。
// HDMI 卡（rockchiphdmi）大多没这些 control，对它无害。

void try_set_bool(struct mixer *mx, const char *name, int on) {
    struct mixer_ctl *ctl = mixer_get_ctl_by_name(mx, name);
    if (!ctl) return;   // 这张卡没有该控制项，跳过
    int ret = mixer_ctl_set_value(ctl, 0, on ? 1 : 0);
    LOGI("mixer set BOOL  '%s' = %d -> ret=%d", name, on, ret);
}

void try_set_int_all(struct mixer *mx, const char *name, int value) {
    struct mixer_ctl *ctl = mixer_get_ctl_by_name(mx, name);
    if (!ctl) return;
    unsigned int n = mixer_ctl_get_num_values(ctl);
    int last_ret = 0;
    for (unsigned int i = 0; i < n; ++i) {
        last_ret = mixer_ctl_set_value(ctl, i, value);
    }
    LOGI("mixer set INT   '%s' = %d (n=%u) -> ret=%d", name, value, n, last_ret);
}

void try_set_enum(struct mixer *mx, const char *name, const char *str) {
    struct mixer_ctl *ctl = mixer_get_ctl_by_name(mx, name);
    if (!ctl) return;
    int ret = mixer_ctl_set_enum_by_string(ctl, str);
    LOGI("mixer set ENUM  '%s' = '%s' -> ret=%d", name, str, ret);
}

// 给一张 card 的 codec 做"出声前"的 mixer 配置。
// 找不到的 control 全部跳过（不算错），不会让 PCM 打开流程失败。
void apply_codec_unmute(unsigned int card) {
    struct mixer *mx = mixer_open(card);
    if (!mx) {
        LOGW("mixer_open(card=%u) failed: errno=%d %s",
             card, errno, strerror(errno));
        return;
    }
    LOGI("mixer_open(card=%u) ok, name='%s'",
         card, mixer_get_name(mx) ? mixer_get_name(mx) : "(null)");

    // ES8388 (RK 板载) 必备
    try_set_bool(mx, "Left Mixer Left Playback Switch", 1);
    try_set_bool(mx, "Right Mixer Right Playback Switch", 1);
    try_set_bool(mx, "Headphone Switch", 1);
    try_set_bool(mx, "Speaker Switch", 1);
    try_set_int_all(mx, "Output 1 Playback Volume", 30);  // ES8388 max=33
    try_set_int_all(mx, "Output 2 Playback Volume", 30);
    // 喇叭功放 PA。ES8388 上是个 ENUM("Disable"/"Enable")。
    // 没插耳机时靠它出声；插了耳机靠 Headphone Switch，PA 开着也无害。
    try_set_enum(mx, "Speaker Pa Switch", "Enable");

    // 一些常见的"通用"命名，找到就一并打开，找不到也无所谓
    try_set_bool(mx, "Master Playback Switch", 1);
    try_set_bool(mx, "PCM Playback Switch", 1);
    try_set_bool(mx, "Headphone Playback Switch", 1);
    try_set_bool(mx, "Speaker Playback Switch", 1);

    mixer_close(mx);
}

}  // namespace

extern "C" {

/*
 * Java 包路径由 javah 规则决定：
 *   com.example.multisinkaudio.alsa.TinyAlsaPlayer
 *   -> Java_com_example_multisinkaudio_alsa_TinyAlsaPlayer_<methodName>
 *
 * 这里方法都是 static native，所以第二个参数是 jclass 而不是 jobject。
 */

JNIEXPORT jlong JNICALL
Java_com_example_multisinkaudio_alsa_TinyAlsaPlayer_nativeOpen(
        JNIEnv *env, jclass /*clazz*/,
        jint card, jint device,
        jint rate, jint channels,
        jint periodSize, jint periodCount,
        jint slot) {

    if (card < 0 || device < 0 || rate <= 0 || channels <= 0
            || periodSize <= 0 || periodCount <= 0) {
        LOGE("[slot=%d] nativeOpen invalid args: card=%d device=%d rate=%d ch=%d period=%d/%d",
             slot, card, device, rate, channels, periodSize, periodCount);
        return 0;
    }

    auto *h = new (std::nothrow) PcmHandle();
    if (!h) {
        LOGE("[slot=%d] nativeOpen: OOM", slot);
        return 0;
    }
    h->card = static_cast<unsigned int>(card);
    h->device = static_cast<unsigned int>(device);
    h->rate = static_cast<unsigned int>(rate);
    h->channels = static_cast<unsigned int>(channels);
    h->frame_bytes = h->channels * 2u;   // S16_LE
    h->slot = slot;

    struct pcm_config cfg = {};
    cfg.channels = h->channels;
    cfg.rate = h->rate;
    cfg.period_size = static_cast<unsigned int>(periodSize);
    cfg.period_count = static_cast<unsigned int>(periodCount);
    cfg.format = PCM_FORMAT_S16_LE;
    cfg.start_threshold = 0;        // 用 tinyalsa 默认
    cfg.stop_threshold = 0;
    cfg.silence_threshold = 0;
    cfg.silence_size = 0;

    LOGI("[slot=%d] pcm_open(card=%u device=%u rate=%u ch=%u period=%u*%u fmt=S16_LE)",
         slot, h->card, h->device, h->rate, h->channels,
         cfg.period_size, cfg.period_count);

    h->pcm = pcm_open(h->card, h->device, PCM_OUT, &cfg);
    if (!h->pcm || !pcm_is_ready(h->pcm)) {
        const char *err = h->pcm ? pcm_get_error(h->pcm) : "pcm_open returned null";
        LOGE("[slot=%d] pcm_open failed: %s (errno=%d %s)",
             slot, err ? err : "(null)", errno, strerror(errno));
        if (h->pcm) {
            pcm_close(h->pcm);
        }
        delete h;
        return 0;
    }

    LOGI("[slot=%d] pcm_open OK, buffer_size=%u frames, frame_bytes=%u",
         slot, pcm_get_buffer_size(h->pcm), h->frame_bytes);

    // 出声前把 codec 的 mixer 配好（ES8388 上必做，否则全静音）
    apply_codec_unmute(h->card);

    return toHandle(h);
}

/**
 * 单独对一张卡做 codec unmute（不打开 PCM）。
 * 主要给 Java 侧的"我想确保 mixer 是开着的"使用，例如启动时统一拨一遍。
 */
JNIEXPORT void JNICALL
Java_com_example_multisinkaudio_alsa_TinyAlsaPlayer_nativeApplyCodecUnmute(
        JNIEnv *env, jclass /*clazz*/,
        jint card) {
    if (card < 0) return;
    apply_codec_unmute(static_cast<unsigned int>(card));
}

/**
 * 写入 PCM 字节流。
 * @return 实际写入的字节数 (>=0)；负值表示错误，对应 -errno（与 tinyalsa 一致）
 */
JNIEXPORT jint JNICALL
Java_com_example_multisinkaudio_alsa_TinyAlsaPlayer_nativeWrite(
        JNIEnv *env, jclass /*clazz*/,
        jlong handle, jbyteArray data, jint offset, jint length) {

    auto *h = fromHandle(handle);
    if (!h || !h->pcm) {
        LOGE("nativeWrite: invalid handle");
        return -EINVAL;
    }
    if (length <= 0) return 0;
    if (!data) {
        LOGE("[slot=%d] nativeWrite: null data", h->slot);
        return -EINVAL;
    }

    jsize total = env->GetArrayLength(data);
    if (offset < 0 || length < 0 || offset + length > total) {
        LOGE("[slot=%d] nativeWrite: bad range offset=%d length=%d total=%d",
             h->slot, offset, length, total);
        return -EINVAL;
    }

    // 字节数对齐到 frame
    jint aligned = length - (length % static_cast<jint>(h->frame_bytes));
    if (aligned <= 0) {
        return 0;   // 一个 frame 都凑不齐
    }

    // 拷贝出来。这里宁可拷一次也不要 GetByteArrayElements 长锁数组，
    // 因为 pcm_writei 是阻塞调用，可能持续几十毫秒。
    std::vector<int8_t> buf(aligned);
    env->GetByteArrayRegion(data, offset, aligned, buf.data());

    unsigned int frame_count = static_cast<unsigned int>(aligned) / h->frame_bytes;
    int ret = pcm_writei(h->pcm, buf.data(), frame_count);
    if (ret < 0) {
        LOGW("[slot=%d] pcm_writei failed: ret=%d err=%s",
             h->slot, ret, pcm_get_error(h->pcm));
        return ret;   // 透传负值
    }
    // tinyalsa 返回的是写入的 frame 数
    return static_cast<jint>(static_cast<unsigned int>(ret) * h->frame_bytes);
}

JNIEXPORT void JNICALL
Java_com_example_multisinkaudio_alsa_TinyAlsaPlayer_nativeClose(
        JNIEnv *env, jclass /*clazz*/,
        jlong handle) {
    auto *h = fromHandle(handle);
    if (!h) return;
    LOGI("[slot=%d] nativeClose", h->slot);
    if (h->pcm) {
        pcm_close(h->pcm);
        h->pcm = nullptr;
    }
    delete h;
}

/**
 * 列出 /proc/asound/cards 内容；纯字符串，Java 侧自己 split。
 */
JNIEXPORT jstring JNICALL
Java_com_example_multisinkaudio_alsa_TinyAlsaPlayer_nativeReadCardsProc(
        JNIEnv *env, jclass /*clazz*/) {
    FILE *fp = fopen("/proc/asound/cards", "r");
    if (!fp) {
        LOGW("open /proc/asound/cards failed: %s", strerror(errno));
        return env->NewStringUTF("");
    }
    std::string content;
    char line[512];
    while (fgets(line, sizeof(line), fp)) {
        content.append(line);
    }
    fclose(fp);
    return env->NewStringUTF(content.c_str());
}

/**
 * 读取 /proc/asound/pcm （列出每张 card 的 pcm device 是 playback 还是 capture）。
 */
JNIEXPORT jstring JNICALL
Java_com_example_multisinkaudio_alsa_TinyAlsaPlayer_nativeReadPcmProc(
        JNIEnv *env, jclass /*clazz*/) {
    FILE *fp = fopen("/proc/asound/pcm", "r");
    if (!fp) {
        LOGW("open /proc/asound/pcm failed: %s", strerror(errno));
        return env->NewStringUTF("");
    }
    std::string content;
    char line[512];
    while (fgets(line, sizeof(line), fp)) {
        content.append(line);
    }
    fclose(fp);
    return env->NewStringUTF(content.c_str());
}

}   // extern "C"
