package com.example.multisinkaudio.alsa;

import android.util.Log;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Java 软件混音器：单 PCM 输出，多 voice 叠加。
 *
 * 设计目标：解决 tinyalsa 在同一 /dev/snd/pcm* 节点上无法并发 pcm_open 的限制。
 * 混音线程独占 PCM，所有音源以 voice 形式累加后写入。
 *
 * 输出固定 48000Hz / stereo / S16_LE。
 * 调用方添加 voice 时，若 source rate / channels 不同，会一次性 resample / 声道转换。
 *
 * 性能：8 路 stereo @ 48kHz → 768k 加法 / 秒，RK3588 单核负载 < 1%。
 */
public class TinyAlsaMixer {

    private static final String TAG = "TinyAlsaMixer";

    // 输出格式（HDMI / 板载 codec 通用值）
    public  static final int OUT_RATE     = 48000;
    public  static final int OUT_CHANNELS = 2;
    private static final int PERIOD_SIZE  = 1024;
    private static final int PERIOD_COUNT = 4;

    // ── 状态 ───────────────────────────────────────────────────────────────
    private static final ConcurrentHashMap<Integer, Voice> voices       = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> namedVoices = new ConcurrentHashMap<>();
    private static final AtomicInteger voiceIdGen = new AtomicInteger(1);

    private static int      currentCard;
    private static int      currentDevice;
    private static long     pcmHandle;
    private static Thread   mixerThread;
    private static volatile boolean stopRequested;

    /**
     * 主音量 / Master volume。作用于整个 mixer 最终输出，
     * 在 clamp 之前统一乘进去；范围 0.0~1.0（常态），允许到 1.5 做轻度 boost
     * （超过 1.0 时 clamp 会更频繁触发，可能产生轻微失真）。
     */
    private static volatile float masterVolume = 1.0f;

    // ── Voice 数据结构 ─────────────────────────────────────────────────────
    private static final class Voice {
        final int     id;
        final short[] data;        // 已 resample 到 OUT_RATE / OUT_CHANNELS
        volatile int  pos;         // 当前播放位置（按 short 索引，不是 frame）
        final boolean loop;
        volatile float volume;
        final String  name;        // 可空
        volatile boolean done;

        Voice(int id, short[] data, boolean loop, float volume, String name) {
            this.id     = id;
            this.data   = data;
            this.loop   = loop;
            this.volume = volume;
            this.name   = name;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  生命周期
    // ════════════════════════════════════════════════════════════════════════

    /** 启动混音器（独占 PCM）。同一参数重复调用幂等。 */
    public static synchronized void start(int card, int device) {
        if (mixerThread != null && mixerThread.isAlive()) {
            if (card == currentCard && device == currentDevice) return;
            stop(); // 切换 card 需要重启
        }

        currentCard   = card;
        currentDevice = device;
        pcmHandle = TinyAlsaPlayer.nativeOpen(card, device,
                OUT_RATE, OUT_CHANNELS,
                PERIOD_SIZE, PERIOD_COUNT,
                /* slot */ -1);
        if (pcmHandle == 0) {
            Log.e(TAG, "start() failed: pcm_open(card=" + card + " dev=" + device + ")");
            return;
        }
        Log.i(TAG, "started: card=" + card + " dev=" + device
                + " " + OUT_RATE + "Hz/" + OUT_CHANNELS + "ch period=" + PERIOD_SIZE);

        stopRequested = false;
        mixerThread = new Thread(TinyAlsaMixer::pumpLoop, "TinyAlsaMixer");
        mixerThread.start();
    }

    /** 停止混音器，释放 PCM。 */
    public static synchronized void stop() {
        stopRequested = true;
        if (mixerThread != null) {
            try { mixerThread.join(800); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            mixerThread = null;
        }
        if (pcmHandle != 0) {
            TinyAlsaPlayer.nativeClose(pcmHandle);
            pcmHandle = 0;
        }
        voices.clear();
        namedVoices.clear();
        Log.i(TAG, "stopped");
    }

    public static boolean isRunning() {
        return mixerThread != null && mixerThread.isAlive() && pcmHandle != 0;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Voice 操作
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 添加一段 PCM 进入混音池。
     *
     * @param pcm        S16_LE PCM 字节数组
     * @param srcRate    采样率（不限，将被 resample）
     * @param srcChannels声道数（1/2，将被转 stereo）
     * @param loop       是否循环播放
     * @param volume     音量 0.0~1.0
     * @param name       可空。若非空，会自动替换同名的旧 voice（用于 BGM/Sustained）
     * @return voiceId   用于后续 stop。返回 0 表示 mixer 未启动失败。
     */
    public static int addPcmVoice(byte[] pcm, int srcRate, int srcChannels,
                                  boolean loop, float volume, String name) {
        if (!isRunning()) {
            Log.w(TAG, "addPcmVoice but mixer not running");
            return 0;
        }
        if (pcm == null || pcm.length < 2) return 0;

        // byte[] (S16_LE) → short[]
        int sampleCount = pcm.length / 2;
        short[] s16 = new short[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            s16[i] = (short) ((pcm[i * 2] & 0xFF) | (pcm[i * 2 + 1] << 8));
        }

        // 声道转换 → stereo
        short[] stereo = (srcChannels == OUT_CHANNELS)
                ? s16
                : convertChannels(s16, srcChannels, OUT_CHANNELS);

        // resample → OUT_RATE
        short[] data = (srcRate == OUT_RATE)
                ? stereo
                : resample(stereo, srcRate, OUT_RATE, OUT_CHANNELS);

        int id = voiceIdGen.getAndIncrement();
        Voice v = new Voice(id, data, loop, clamp01(volume), name);

        // 如果同名旧 voice 还在，先把它标记为 done。
        // pumpLoop 检查 done 后会跳过该 voice，从而避免在
        // 「voices.put 新 → voices.remove 旧」这几微秒的 race 窗口里
        // 同时把新旧两路一起混进 mix 造成短促"重音"。
        if (name != null) {
            Integer existingId = namedVoices.get(name);
            if (existingId != null) {
                Voice existing = voices.get(existingId);
                if (existing != null) existing.done = true;
            }
        }

        voices.put(id, v);

        if (name != null) {
            Integer oldId = namedVoices.put(name, id);
            if (oldId != null) voices.remove(oldId);
        }

        // 诊断：转换后 short[] 的幅度统计，定位字节序 / resample / 转声道是否破坏数据
        int s16Peak = peakAbsShort(s16);
        int stPeak  = (stereo == s16) ? s16Peak : peakAbsShort(stereo);
        int dPeak   = (data == stereo) ? stPeak : peakAbsShort(data);
        Log.i(TAG, "addPcmVoice id=" + id + " name=" + name
                + " loop=" + loop + " vol=" + volume
                + " src=" + srcRate + "Hz/" + srcChannels + "ch"
                + " in_samples=" + sampleCount + " peakAbs(s16)=" + s16Peak
                + " → afterChannels=" + stereo.length + " peak=" + stPeak
                + " → afterResample=" + data.length + " peak=" + dPeak
                + " out_seconds=" + String.format("%.3f", (double) data.length / OUT_CHANNELS / OUT_RATE));
        if (dPeak < 16) {
            Log.w(TAG, "addPcmVoice ⚠ voice id=" + id
                    + " 数据峰值过低（" + dPeak + "），即便混入 mixer 也会近似静音。");
        }
        return id;
    }

    private static int peakAbsShort(short[] arr) {
        int peak = 0;
        // 抽样 1/8（避免大数据扫全量）
        int step = Math.max(1, arr.length / 4096);
        for (int i = 0; i < arr.length; i += step) {
            int a = arr[i] < 0 ? -arr[i] : arr[i];
            if (a > peak) peak = a;
        }
        return peak;
    }

    public static void removeVoice(int voiceId) {
        Voice v = voices.remove(voiceId);
        if (v != null && v.name != null) namedVoices.remove(v.name, voiceId);
    }

    public static void removeByName(String name) {
        Integer id = namedVoices.remove(name);
        if (id != null) voices.remove(id);
    }

    public static void removeAll() {
        voices.clear();
        namedVoices.clear();
    }

    public static int activeVoiceCount() {
        return voices.size();
    }

    /** 查询是否存在指定名字的活跃 voice（典型用例：BGM 重复点击判重）。 */
    public static boolean hasNamedVoice(String name) {
        return name != null && namedVoices.containsKey(name);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  音量控制
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 设置 mixer 主音量（线性增益）。
     *
     * 范围会被 clamp 到 [0, 1.5]：
     *   - 0.0   静音
     *   - 1.0   原始电平（默认）
     *   - >1.0  软 boost（混音超过 32767 时自动 clamp，会有轻微失真）
     *
     * 这条音量是对最终混音结果的"总闸门"，不需要 mixer 处于运行中也可设置。
     */
    public static void setMasterVolume(float volume) {
        float v = volume;
        if (v < 0f)   v = 0f;
        if (v > 1.5f) v = 1.5f;
        masterVolume = v;
        Log.i(TAG, "master volume = " + v);
    }

    public static float getMasterVolume() {
        return masterVolume;
    }

    /**
     * 修改一个已存在 voice 的音量（不打断播放）。
     * @return true 成功；false 没找到该 voice
     */
    public static boolean setVoiceVolume(int voiceId, float volume) {
        Voice v = voices.get(voiceId);
        if (v == null) return false;
        v.volume = clamp01(volume);
        return true;
    }

    /**
     * 按名字修改 voice 音量（典型用例：调 BGM 音量保持 SFX 不变）。
     * @return true 成功；false 没找到该 name
     */
    public static boolean setVoiceVolumeByName(String name, float volume) {
        if (name == null) return false;
        Integer id = namedVoices.get(name);
        if (id == null) return false;
        return setVoiceVolume(id, volume);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  混音线程
    // ════════════════════════════════════════════════════════════════════════

    private static void pumpLoop() {
        int   samplesPerPeriod = PERIOD_SIZE * OUT_CHANNELS;
        int[] mix              = new int[samplesPerPeriod];
        byte[] outBuf          = new byte[samplesPerPeriod * 2];

        // 诊断：每秒（约）打印一次混音器状态，避免日志爆炸
        // 48000Hz / 1024 frames per period ≈ 47 周期 / 秒
        final int  STATS_PERIOD = (OUT_RATE / Math.max(1, PERIOD_SIZE));
        int        periodIdx    = 0;
        long       totalWritten = 0;
        int        intervalPeak = 0;     // 这一秒里跨周期累计的最大 peak
        int        framesWithSound = 0;  // 这一秒里有声音的周期数

        try {
            while (!stopRequested) {
                Arrays.fill(mix, 0);

                int liveVoices = 0;
                // 累加每个 voice
                for (Voice v : voices.values()) {
                    if (v.done) continue;
                    int written = mixVoice(v, mix, samplesPerPeriod);
                    if (written < 0) v.done = true; // 非循环且播完
                    else liveVoices++;
                }

                // 清理 done voice
                for (Iterator<Map.Entry<Integer, Voice>> it = voices.entrySet().iterator();
                     it.hasNext(); ) {
                    Map.Entry<Integer, Voice> e = it.next();
                    if (e.getValue().done) {
                        if (e.getValue().name != null) namedVoices.remove(e.getValue().name, e.getKey());
                        it.remove();
                    }
                }

                // master volume 应用 → clamp → 转 byte[]，同时记录本周期的输出 peak
                final float mv = masterVolume;
                final boolean applyMv = mv != 1.0f;
                int periodPeak = 0;
                for (int i = 0; i < samplesPerPeriod; i++) {
                    int s = mix[i];
                    if (applyMv) s = (int) (s * mv);
                    if (s >  32767) s =  32767;
                    if (s < -32768) s = -32768;
                    outBuf[i * 2]     = (byte)  (s & 0xFF);
                    outBuf[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
                    int a = s < 0 ? -s : s;
                    if (a > periodPeak) periodPeak = a;
                }
                if (periodPeak > intervalPeak) intervalPeak = periodPeak;
                if (periodPeak > 32) framesWithSound++;

                // 写 ALSA（即使无 voice 也写静音，保持 PCM 设备活跃，避免下次 underrun）
                int written = TinyAlsaPlayer.nativeWrite(pcmHandle, outBuf, 0, outBuf.length);
                if (written < 0) {
                    Log.e(TAG, "nativeWrite error errno=" + (-written));
                    break;
                }
                totalWritten += written;
                periodIdx++;

                // 节流日志：~每秒一次 + 任何「刚有声音」的转折点
                if (periodIdx % STATS_PERIOD == 0) {
                    Log.i(TAG, "pump stats period#" + periodIdx
                            + " card=" + currentCard + "/dev=" + currentDevice
                            + " liveVoices=" + liveVoices + "(map=" + voices.size() + ")"
                            + " intervalPeak=" + intervalPeak + "/32767"
                            + " soundyPeriods=" + framesWithSound + "/" + STATS_PERIOD
                            + " bytesWritten(total)=" + totalWritten);
                    intervalPeak    = 0;
                    framesWithSound = 0;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "pumpLoop error", e);
        }
        Log.i(TAG, "pumpLoop exited stopRequested=" + stopRequested
                + " totalBytesWritten=" + totalWritten);
    }

    /**
     * 把一个 voice 的下一段样本累加到 mix 缓冲。
     * 处理跨 buffer 边界 + 循环。
     *
     * @return >= 0 正常；-1 表示这个 voice 在本帧结束（非循环播完）
     */
    private static int mixVoice(Voice v, int[] mix, int samplesPerPeriod) {
        float volume = v.volume;
        int   pos    = v.pos;
        int   total  = v.data.length;

        for (int i = 0; i < samplesPerPeriod; i++) {
            if (pos >= total) {
                if (v.loop) {
                    pos = 0;
                } else {
                    v.pos = pos;
                    return -1;
                }
            }
            mix[i] += (int) (v.data[pos] * volume);
            pos++;
        }
        v.pos = pos;
        return 0;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  工具：声道 / 采样率
    // ════════════════════════════════════════════════════════════════════════

    private static short[] convertChannels(short[] src, int srcCh, int dstCh) {
        if (srcCh == dstCh) return src;
        int     frames = src.length / srcCh;
        short[] dst    = new short[frames * dstCh];

        if (srcCh == 1 && dstCh == 2) {
            for (int i = 0; i < frames; i++) {
                dst[i * 2]     = src[i];
                dst[i * 2 + 1] = src[i];
            }
        } else if (srcCh == 2 && dstCh == 1) {
            for (int i = 0; i < frames; i++) {
                dst[i] = (short) ((src[i * 2] + src[i * 2 + 1]) >> 1);
            }
        } else {
            // 多声道 → 取前 dstCh 个声道
            for (int i = 0; i < frames; i++) {
                for (int c = 0; c < dstCh; c++) {
                    dst[i * dstCh + c] = (c < srcCh) ? src[i * srcCh + c] : 0;
                }
            }
        }
        return dst;
    }

    /** 简单线性插值 resample。游戏音效精度足够。 */
    private static short[] resample(short[] src, int srcRate, int dstRate, int channels) {
        if (srcRate == dstRate) return src;
        int     srcFrames = src.length / channels;
        int     dstFrames = (int) ((long) srcFrames * dstRate / srcRate);
        short[] dst       = new short[dstFrames * channels];
        double  ratio     = (double) srcRate / dstRate;

        for (int i = 0; i < dstFrames; i++) {
            double srcPosF = i * ratio;
            int    idx     = (int) srcPosF;
            double frac    = srcPosF - idx;
            int    nextIdx = Math.min(idx + 1, srcFrames - 1);
            for (int c = 0; c < channels; c++) {
                short a = src[idx     * channels + c];
                short b = src[nextIdx * channels + c];
                dst[i * channels + c] = (short) (a * (1 - frac) + b * frac);
            }
        }
        return dst;
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
