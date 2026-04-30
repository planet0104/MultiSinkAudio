package com.example.multisinkaudio.alsa;

import android.os.Handler;
import android.util.Log;

/**
 * 直接接收已解码的 S16_LE PCM byte[] 进行 ALSA 播放，无需 MediaCodec / MediaExtractor。
 *
 * 适用于 Unity 通过 JNI 传入 AudioClip.GetData() 转换后的 PCM 数据。
 *
 * 流程：
 *   C# AudioClip.GetData() → float[] → 转 S16_LE byte[] → JNI → TinyAlsaPcmPlayer
 *       → TinyAlsaPlayer.nativeOpen → nativeWrite → nativeClose
 *
 * 比 TinyAlsaTrackPlayer 简单得多：省去 MediaExtractor + MediaCodec 整条解码管线。
 */
public class TinyAlsaPcmPlayer {

    public interface Listener {
        void onError(String message);
        void onFinished();
    }

    private static final String TAG = "TinyAlsaPcmPlayer";
    private static final int CHUNK_BYTES = 8192;

    private final byte[]  pcmData;
    private final int     sampleRate;
    private final int     channels;
    private final int     alsaCard;
    private final int     alsaDevice;
    private final boolean looping;
    private final int     slot;
    private final String  logPrefix;
    private final Handler callbackHandler;
    private final Listener listener;

    private Thread           worker;
    private volatile boolean stopRequested;
    private long             pcmHandle;

    public TinyAlsaPcmPlayer(byte[]   pcmData,
                             int      sampleRate,
                             int      channels,
                             int      alsaCard,
                             int      alsaDevice,
                             boolean  looping,
                             int      slot,
                             Handler  callbackHandler,
                             Listener listener) {
        this.pcmData         = pcmData;
        this.sampleRate      = sampleRate;
        this.channels        = channels;
        this.alsaCard        = alsaCard;
        this.alsaDevice      = alsaDevice;
        this.looping         = looping;
        this.slot            = slot;
        this.logPrefix       = "[TinyAlsaPcm slot=" + slot
                + " rate=" + sampleRate + " ch=" + channels
                + " card=" + alsaCard + " dev=" + alsaDevice + "]";
        this.callbackHandler = callbackHandler;
        this.listener        = listener;
    }

    /** 打开 ALSA 并在后台线程开始写 PCM，立即返回。 */
    public void start() {
        pcmHandle = TinyAlsaPlayer.nativeOpen(
                alsaCard, alsaDevice,
                sampleRate, channels,
                /* periodSize  */ 1024,
                /* periodCount */ 4,
                slot);
        if (pcmHandle == 0) {
            Log.e(TAG, logPrefix + " nativeOpen failed");
            postError("tinyalsa pcm_open failed (card=" + alsaCard + " dev=" + alsaDevice + ")");
            return;
        }
        Log.i(TAG, logPrefix + " nativeOpen ok, handle=" + pcmHandle);

        stopRequested = false;
        worker = new Thread(this::pumpLoop, "TinyAlsaPcm-" + slot);
        worker.start();
    }

    private void pumpLoop() {
        try {
            do {
                int offset = 0;
                while (offset < pcmData.length && !stopRequested) {
                    int chunk   = Math.min(CHUNK_BYTES, pcmData.length - offset);
                    int written = TinyAlsaPlayer.nativeWrite(pcmHandle, pcmData, offset, chunk);
                    if (written < 0) {
                        throw new RuntimeException("nativeWrite error errno=" + (-written));
                    }
                    if (written == 0) break; // alignment issue, skip remainder
                    offset += written;
                }
            } while (looping && !stopRequested);

            // 等待 ALSA 硬件 buffer 排空（不然末尾会被 pcm_close 丢掉，前几声没声音）
            //
            // buffer_size=4096 frames @ rate Hz，最多耗时 4096/rate 秒。
            // 多 sleep 一倍作为安全边界，HDMI / 板载 codec 的 DAC 也需要这段时间才能稳定输出。
            if (!stopRequested && pcmData.length > 0) {
                long drainMs = 2L * 4096L * 1000L / Math.max(1, sampleRate);
                try { Thread.sleep(drainMs); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                Log.i(TAG, logPrefix + " drain wait " + drainMs + "ms done");
            }

        } catch (Exception e) {
            Log.e(TAG, logPrefix + " pumpLoop error", e);
            postError(e.getMessage());
        } finally {
            if (pcmHandle != 0) {
                TinyAlsaPlayer.nativeClose(pcmHandle);
                pcmHandle = 0;
            }
            Log.i(TAG, logPrefix + " pumpLoop exited, stopRequested=" + stopRequested);
        }

        if (!stopRequested) {
            postFinished();
        }
    }

    /** 请求停止；会等待后台线程退出（最多 500 ms）。 */
    public void stop() {
        stopRequested = true;
        if (worker != null) {
            try { worker.join(500); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
        // 若线程还没来得及关，这里兜底
        if (pcmHandle != 0) {
            TinyAlsaPlayer.nativeClose(pcmHandle);
            pcmHandle = 0;
        }
    }

    public boolean isPlaying() {
        return worker != null && worker.isAlive();
    }

    // ── 回调 ────────────────────────────────────────────────────────────────

    private void postError(String msg) {
        if (listener == null) return;
        if (callbackHandler != null) callbackHandler.post(() -> listener.onError(msg));
        else listener.onError(msg);
    }

    private void postFinished() {
        if (listener == null) return;
        if (callbackHandler != null) callbackHandler.post(listener::onFinished);
        else listener.onFinished();
    }
}
