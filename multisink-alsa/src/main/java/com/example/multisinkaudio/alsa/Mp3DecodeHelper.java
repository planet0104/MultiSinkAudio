package com.example.multisinkaudio.alsa;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 把 APK assets/ 里的 mp3 / wav / aac 等音频文件一次性解码成完整 PCM byte[]。
 *
 * 解码完成后通常立即送给 {@link TinyAlsaMixer#addPcmVoice} 播放。
 *
 * 内存占用：3 分钟 stereo 44.1kHz 约 31 MB。SFX 短音效可忽略。
 *
 * 这是同步阻塞 API，应在后台线程调用，不要在 UI 线程执行。
 */
public final class Mp3DecodeHelper {

    private static final String TAG = "Mp3DecodeHelper";
    private static final long TIMEOUT_US = 10_000L;

    public static final class Result {
        public final byte[] pcm;       // S16_LE 字节
        public final int    sampleRate;
        public final int    channels;

        Result(byte[] pcm, int sampleRate, int channels) {
            this.pcm        = pcm;
            this.sampleRate = sampleRate;
            this.channels   = channels;
        }
    }

    private Mp3DecodeHelper() {}

    /**
     * 同步解码 assets/ 下的音频文件。
     * @throws IOException 找不到文件、无音轨、解码出错时抛出
     */
    public static Result decodeAsset(Context ctx, String assetName) throws IOException {
        AssetFileDescriptor afd     = ctx.getAssets().openFd(assetName);
        MediaExtractor      ex      = null;
        MediaCodec          decoder = null;
        try {
            ex = new MediaExtractor();
            ex.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());

            int trackIdx = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIdx = i;
                    fmt = f;
                    break;
                }
            }
            if (trackIdx < 0 || fmt == null) {
                throw new IOException("no audio track in " + assetName);
            }
            ex.selectTrack(trackIdx);

            String mime = fmt.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(fmt, null, null, 0);
            decoder.start();

            int rate     = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

            ByteArrayOutputStream pcmOut = new ByteArrayOutputStream(64 * 1024);
            MediaCodec.BufferInfo info   = new MediaCodec.BufferInfo();

            boolean inputDone  = false;
            boolean outputDone = false;
            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                        int sampleSize = inBuf == null ? -1 : ex.readSampleData(inBuf, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = ex.getSampleTime();
                            decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0);
                            ex.advance();
                        }
                    }
                }

                int outIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outIdx >= 0) {
                    ByteBuffer outBuf = decoder.getOutputBuffer(outIdx);
                    if (info.size > 0 && outBuf != null) {
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        byte[] chunk = new byte[info.size];
                        outBuf.get(chunk);
                        pcmOut.write(chunk);
                    }
                    decoder.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = decoder.getOutputFormat();
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        rate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                }
            }

            byte[] pcm = pcmOut.toByteArray();
            Log.i(TAG, "decoded " + assetName + " → "
                    + pcm.length + " bytes @ " + rate + "Hz/" + channels + "ch");
            return new Result(pcm, rate, channels);

        } finally {
            try { afd.close(); } catch (IOException ignored) {}
            if (decoder != null) {
                try { decoder.stop(); } catch (IllegalStateException ignored) {}
                decoder.release();
            }
            if (ex != null) ex.release();
        }
    }
}
