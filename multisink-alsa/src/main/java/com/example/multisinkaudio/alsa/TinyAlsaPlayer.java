package com.example.multisinkaudio.alsa;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TinyAlsa 的 Java 薄壳，封装 native 接口。
 *
 * 这条路径完全绕开 AudioFlinger / AudioPolicy / AudioTrack，
 * 直接 ioctl /dev/snd/pcmCxDxp，所以：
 *   - 不需要 setPreferredDevice / AudioPolicy；
 *   - 不会被系统 audio policy 强制改路；
 *   - 但需要进程对 /dev/snd/pcm* 有读写权限 —— 这通常意味着：
 *       a) APK 用 platform key 签名，被划进 audio gid，或
 *       b) root + chmod 666 /dev/snd/pcm*，或
 *       c) ROM 修过 selinux + ueventd 把权限放开。
 */
public final class TinyAlsaPlayer {

    private static final String TAG = "TinyAlsaPlayer";

    static {
        try {
            System.loadLibrary("multisinkalsa");
            Log.i(TAG, "libmultisinkalsa.so loaded");
        } catch (Throwable t) {
            Log.e(TAG, "failed to load libmultisinkalsa.so", t);
        }
    }

    /** 一张 ALSA card 的简要信息。 */
    public static final class Card {
        public final int index;
        public final String id;     // 如 "rockchiphdmi0"
        public final String name;   // 如 "rockchip-hdmi0"

        public Card(int index, String id, String name) {
            this.index = index;
            this.id = id;
            this.name = name;
        }

        @Override
        public String toString() {
            return "card " + index + " [" + id + "] " + name;
        }
    }

    private TinyAlsaPlayer() {}

    /**
     * 打开 /dev/snd/pcmC{card}D{device}p。
     *
     * @param card        ALSA card 索引（0,1,2...）
     * @param device      ALSA device 索引（一般是 0）
     * @param rate        采样率（48000 / 44100）
     * @param channels    声道数（1 mono / 2 stereo）
     * @param periodSize  一个 period 内的 frame 数（建议 1024）
     * @param periodCount period 数（建议 4）
     * @param slot        仅用于 native 端打日志用的标识
     * @return 句柄；0 表示失败
     */
    public static native long nativeOpen(int card, int device,
                                         int rate, int channels,
                                         int periodSize, int periodCount,
                                         int slot);

    /**
     * 写入 PCM 字节流（S16_LE）。
     *
     * @return 实际写入的字节数（>=0）；负值是 -errno（来自 tinyalsa 的 pcm_writei 错误码）
     */
    public static native int nativeWrite(long handle, byte[] data, int offset, int length);

    public static native void nativeClose(long handle);

    public static native String nativeReadCardsProc();

    public static native String nativeReadPcmProc();

    /**
     * 给指定 card 的 codec 做"出声前"必备 mixer 配置（DAC 通路、HP/SPK 开关、输出音量）。
     *
     * 我们绕开了 AudioFlinger / audio HAL，板载 codec（如 ES8388）默认全静音；
     * 这个方法等价于在 shell 里跑：
     *   tinymix -D N 'Left Mixer Left Playback Switch' 1
     *   tinymix -D N 'Right Mixer Right Playback Switch' 1
     *   tinymix -D N 'Headphone Switch' 1
     *   tinymix -D N 'Speaker Switch' 1
     *   tinymix -D N 'Output 1 Playback Volume' 30
     *   tinymix -D N 'Output 2 Playback Volume' 30
     *   tinymix -D N 'Speaker Pa Switch' Enable
     *
     * 找不到的 control（HDMI 卡上几乎没有）会被静默跳过，对调用方零负担。
     * nativeOpen() 内部已经会自动调一次，这个方法主要供"我想手动重设一遍"使用。
     */
    public static native void nativeApplyCodecUnmute(int card);

    /** 解析 /proc/asound/cards。匹配示例：` 0 [rockchiphdmiin ]: rockchip_hdmiin - rockchip,hdmiin` */
    public static List<Card> listCards() {
        String text = nativeReadCardsProc();
        List<Card> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;

        Pattern p = Pattern.compile(
                "^\\s*(\\d+)\\s*\\[([^\\]]+)\\]\\s*:\\s*(.*)$",
                Pattern.MULTILINE);
        Matcher m = p.matcher(text);
        while (m.find()) {
            try {
                int idx = Integer.parseInt(m.group(1));
                String id = m.group(2).trim();
                String name = m.group(3).trim();
                out.add(new Card(idx, id, name));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }
}
