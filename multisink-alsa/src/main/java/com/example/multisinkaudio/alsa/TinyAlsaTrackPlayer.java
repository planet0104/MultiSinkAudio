package com.example.multisinkaudio.alsa;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * "tinyalsa 直写" 版的播放器。
 *
 * 流程：
 *      MediaExtractor -> MediaCodec(音频解码) -> PCM byte[] -> {@link TinyAlsaPlayer}
 * PCM 直接写到 /dev/snd/pcmC{card}D{device}p，
 * 完全不经过 AudioFlinger / AudioPolicy / AudioTrack，
 * 也就不会被 ROM 的 audio_policy_configuration.xml 共用 mixPort 的设计卡住。
 *
 * 限制：
 *   - 进程必须能读写 /dev/snd/pcm* 和 /dev/snd/control*；
 *     最干净的方式是改 ueventd.rc 把这两类节点放到 0666（仓库 scripts/setup_device.ps1 干这事）；
 *   - 解码出来的 PCM 是什么 rate / channels 就用什么 rate / channels 打开 ALSA，不重采样；
 *     如果硬件不支持当前 rate（比如 HDMI 只支持 48000），需要调用方自己处理。
 */
public class TinyAlsaTrackPlayer {

    public interface Listener {
        void onError(String message);
        void onOpened(boolean success);
    }

    private static final String TAG = "TinyAlsaTrackPlayer";
    private static final long TIMEOUT_US = 10_000L;

    private final Context context;
    private final String assetName;
    private final int alsaCard;
    private final int alsaDevice;
    private final boolean looping;
    private final int slot;
    private final String logPrefix;
    private final Handler callbackHandler;
    private final Listener listener;

    private MediaExtractor extractor;
    private MediaCodec decoder;

    private long pcmHandle;          // TinyAlsaPlayer.nativeOpen 返回的句柄
    private int pcmRate;
    private int pcmChannels;

    private Thread worker;
    private volatile boolean stopRequested;

    public TinyAlsaTrackPlayer(Context context,
                               String assetName,
                               int alsaCard,
                               int alsaDevice,
                               boolean looping,
                               int slot,
                               Handler callbackHandler,
                               Listener listener) {
        this.context = context.getApplicationContext();
        this.assetName = assetName;
        this.alsaCard = alsaCard;
        this.alsaDevice = alsaDevice;
        this.looping = looping;
        this.slot = slot;
        this.logPrefix = "[TinyAlsa slot=" + slot + " " + assetName
                + " card=" + alsaCard + " dev=" + alsaDevice + "]";
        this.callbackHandler = callbackHandler;
        this.listener = listener;
    }

    public void start() throws IOException {
        Log.i(TAG, logPrefix + " start()");

        AssetFileDescriptor afd = context.getAssets().openFd(assetName);
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(afd.getFileDescriptor(),
                    afd.getStartOffset(), afd.getLength());
        } finally {
            afd.close();
        }

        int trackIdx = -1;
        MediaFormat fmt = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                trackIdx = i;
                fmt = f;
                break;
            }
        }
        if (trackIdx < 0 || fmt == null) {
            releaseInternals();
            throw new IOException("no audio track in " + assetName);
        }
        extractor.selectTrack(trackIdx);

        String mime = fmt.getString(MediaFormat.KEY_MIME);
        decoder = MediaCodec.createDecoderByType(mime);
        decoder.configure(fmt, null, null, 0);
        decoder.start();
        Log.i(TAG, logPrefix + " decoder started, mime=" + mime);

        // 解码出来的 PCM 参数（在 INFO_OUTPUT_FORMAT_CHANGED 时再校准一次）
        pcmRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        pcmChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        if (pcmChannels < 1) pcmChannels = 2;

        // 打开 ALSA PCM
        // period_size=1024 frames、period_count=4 是兼容性比较好的值
        pcmHandle = TinyAlsaPlayer.nativeOpen(
                alsaCard, alsaDevice,
                pcmRate, pcmChannels,
                /* periodSize  */ 1024,
                /* periodCount */ 4,
                slot);
        boolean ok = pcmHandle != 0;
        Log.i(TAG, logPrefix + " nativeOpen handle=" + pcmHandle
                + " rate=" + pcmRate + " ch=" + pcmChannels);
        postOpened(ok);
        if (!ok) {
            releaseInternals();
            throw new IOException("tinyalsa pcm_open failed (card=" + alsaCard
                    + " dev=" + alsaDevice + ")");
        }

        stopRequested = false;
        worker = new Thread(this::pumpLoop, "TinyAlsa-" + assetName);
        worker.start();
    }

    private void pumpLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;

        // 给 nativeWrite 复用的 byte[]
        byte[] reuseBuf = new byte[8192];

        try {
            while (!stopRequested && !outputDone) {
                if (!inputDone) {
                    int inIdx = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                        if (inBuf == null) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, 0);
                        } else {
                            int sampleSize = extractor.readSampleData(inBuf, 0);
                            if (sampleSize < 0) {
                                if (looping && !stopRequested) {
                                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                                    decoder.queueInputBuffer(inIdx, 0, 0, 0, 0);
                                } else {
                                    decoder.queueInputBuffer(inIdx, 0, 0, 0,
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                    inputDone = true;
                                }
                            } else {
                                long pts = extractor.getSampleTime();
                                decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                int outIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outIdx >= 0) {
                    ByteBuffer outBuf = decoder.getOutputBuffer(outIdx);
                    if (info.size > 0 && outBuf != null && !stopRequested) {
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);

                        if (reuseBuf.length < info.size) {
                            reuseBuf = new byte[info.size];
                        }
                        outBuf.get(reuseBuf, 0, info.size);

                        int remaining = info.size;
                        int offset = 0;
                        while (remaining > 0 && !stopRequested) {
                            int written = TinyAlsaPlayer.nativeWrite(
                                    pcmHandle, reuseBuf, offset, remaining);
                            if (written < 0) {
                                throw new IOException(
                                        "pcm_writei error " + written
                                                + " (errno=-" + (-written) + ")");
                            }
                            if (written == 0) {
                                // 一帧都凑不齐，丢掉这块；对齐问题
                                break;
                            }
                            offset += written;
                            remaining -= written;
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = decoder.getOutputFormat();
                    Log.i(TAG, logPrefix + " decoder output format: " + newFormat);
                    int newRate = safeInt(newFormat, MediaFormat.KEY_SAMPLE_RATE, pcmRate);
                    int newCh = safeInt(newFormat, MediaFormat.KEY_CHANNEL_COUNT, pcmChannels);
                    if (newRate != pcmRate || newCh != pcmChannels) {
                        Log.w(TAG, logPrefix + " PCM format changed: "
                                + pcmRate + "Hz/" + pcmChannels + "ch -> "
                                + newRate + "Hz/" + newCh + "ch — reopening pcm");
                        // 重新打开 PCM 以匹配解码器实际输出
                        if (pcmHandle != 0) {
                            TinyAlsaPlayer.nativeClose(pcmHandle);
                            pcmHandle = 0;
                        }
                        pcmRate = newRate;
                        pcmChannels = newCh;
                        pcmHandle = TinyAlsaPlayer.nativeOpen(
                                alsaCard, alsaDevice,
                                pcmRate, pcmChannels,
                                1024, 4, slot);
                        if (pcmHandle == 0) {
                            throw new IOException("re-open pcm failed after format change");
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, logPrefix + " pumpLoop error", e);
            postError("播放出错: " + e.getMessage());
        }
        Log.i(TAG, logPrefix + " pumpLoop exited"
                + " stopRequested=" + stopRequested
                + " inputDone=" + inputDone
                + " outputDone=" + outputDone);
    }

    private static int safeInt(MediaFormat f, String key, int fallback) {
        try {
            if (f.containsKey(key)) return f.getInteger(key);
        } catch (Exception ignored) {
        }
        return fallback;
    }

    public void stop() {
        Log.i(TAG, logPrefix + " stop() begin");
        stopRequested = true;
        if (worker != null) {
            try {
                worker.join(800);
                Log.i(TAG, logPrefix + " worker joined");
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
        if (pcmHandle != 0) {
            TinyAlsaPlayer.nativeClose(pcmHandle);
            pcmHandle = 0;
        }
        releaseInternals();
        Log.i(TAG, logPrefix + " stop() end");
    }

    private void releaseInternals() {
        if (decoder != null) {
            try { decoder.stop(); } catch (IllegalStateException ignored) {}
            decoder.release();
            decoder = null;
            Log.i(TAG, logPrefix + " decoder released");
        }
        if (extractor != null) {
            extractor.release();
            extractor = null;
            Log.i(TAG, logPrefix + " extractor released");
        }
    }

    public boolean isPlaying() {
        return worker != null && worker.isAlive() && pcmHandle != 0;
    }

    private void postError(String msg) {
        if (listener == null) return;
        if (callbackHandler != null) {
            callbackHandler.post(() -> listener.onError(msg));
        } else {
            listener.onError(msg);
        }
    }

    private void postOpened(boolean ok) {
        if (listener == null) return;
        if (callbackHandler != null) {
            callbackHandler.post(() -> listener.onOpened(ok));
        } else {
            listener.onOpened(ok);
        }
    }
}
