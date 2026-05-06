package com.example.multisinkaudio;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.multisinkaudio.alsa.Mp3DecodeHelper;
import com.example.multisinkaudio.alsa.TinyAlsaMixer;
import com.example.multisinkaudio.alsa.TinyAlsaPlayer;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单声卡 + 软件混音 demo。
 *
 * 一切音源（BGM / SFX / 正弦波）都通过 {@link TinyAlsaMixer} 写到同一张 ALSA card，
 * 互相不抢 PCM 节点，可叠加：
 *   - BGM 用 loop=true 命名 voice，可以单独 stop 而保留特效；
 *   - SFX 和 sine 每次新建 voice，自然播完就退出。
 *
 * 要求：进程对 /dev/snd/pcm* / /dev/snd/control* 有读写权限
 *      （参考 scripts/setup_device_rk3399.bat）。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQ_RECORD_AUDIO = 1001;

    /** 长 BGM：循环、可单独停止。 */
    private static final String BGM_ASSET = "music_6.mp3";
    /** 短 SFX：按一次播一次，自然播完。 */
    private static final String SFX_ASSET = "26.mp3";
    /** mixer 中 BGM voice 的固定名字，按名替换 / 移除。 */
    private static final String BGM_VOICE_NAME = "bgm";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView statusTextView;
    private TextView masterVolumeLabel;

    private List<TinyAlsaPlayer.Card> alsaCards;
    private SelectedCard selectedCard;

    // 预解码缓存：static 化，跨 Activity 重建保留。
    // 这是修复 OOM 的关键：避免横竖屏切换 / 系统重建 Activity 时反复解码 35MB BGM。
    private static volatile Mp3DecodeHelper.Result bgmDecoded;
    private static volatile Mp3DecodeHelper.Result sfxDecoded;
    private static volatile byte[] sineCached;

    // 同一资源的解码互斥：避免 prefetch + 用户连点导致并发解码 → 多个并发回调
    // 都触发 addPcmVoice → 听起来 BGM "重音/抖动"。
    // add() 返回 true 才真正启动解码线程。
    private static final Set<String> decodingAssets =
            Collections.synchronizedSet(new HashSet<>());

    // 用户显式按过"播放 BGM"，但当时还在解码：解码完成后自动播放一次（仅一次）。
    private boolean pendingBgmPlayback;

    // 自增计数器，方便在日志里把每一次"用户动作"和后续 addPcmVoice 串起来。
    private static final AtomicLong actionSeq = new AtomicLong(0);

    private final Runnable periodicRefresh = new Runnable() {
        @Override public void run() {
            refreshStatus();
            mainHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        statusTextView      = findViewById(R.id.statusTextView);
        masterVolumeLabel   = findViewById(R.id.masterVolumeLabel);

        Button selectButton    = findViewById(R.id.selectAudioDeviceButton);
        Button playBgmButton   = findViewById(R.id.playBgmButton);
        Button stopBgmButton   = findViewById(R.id.stopBgmButton);
        Button playSfxButton   = findViewById(R.id.playSfxMixButton);
        Button stopAllButton   = findViewById(R.id.stopAllButton);
        SeekBar masterVolumeSeekBar = findViewById(R.id.masterVolumeSeekBar);

        selectButton.setOnClickListener(v -> showCardPicker());
        playBgmButton.setOnClickListener(v -> playBgm());
        stopBgmButton.setOnClickListener(v -> stopBgm());
        playSfxButton.setOnClickListener(v -> playSfxAndSine());
        stopAllButton.setOnClickListener(v -> stopAll());

        // SeekBar progress 0~150，对应 master volume 0.0~1.5
        masterVolumeSeekBar.setProgress(Math.round(TinyAlsaMixer.getMasterVolume() * 100));
        updateMasterVolumeLabel(masterVolumeSeekBar.getProgress());
        masterVolumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                TinyAlsaMixer.setMasterVolume(progress / 100f);
                updateMasterVolumeLabel(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });

        ensureRecordAudioGranted();
        refreshAlsaCards();
        refreshStatus();
        prefetchAssetsAsync();
        mainHandler.postDelayed(periodicRefresh, 1000);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(periodicRefresh);
        TinyAlsaMixer.stop();
        super.onDestroy();
    }

    // ════════════════════════════════════════════════
    //   权限：仅做 audio gid 兜底，主要靠 ueventd 0666
    // ════════════════════════════════════════════════
    private void ensureRecordAudioGranted() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "RECORD_AUDIO already granted");
            return;
        }
        ActivityCompat.requestPermissions(this,
                new String[] { Manifest.permission.RECORD_AUDIO }, REQ_RECORD_AUDIO);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this)
                    .setTitle("权限已授予")
                    .setMessage("如设备已经把 /dev/snd/* 放到 0666，这一步可忽略；"
                            + "否则请杀掉 app 重启让 audio gid 生效。")
                    .setPositiveButton("好", null)
                    .show();
        }
    }

    // ════════════════════════════════════════════════
    //   选 ALSA card
    // ════════════════════════════════════════════════
    private void refreshAlsaCards() {
        try {
            alsaCards = TinyAlsaPlayer.listCards();
        } catch (Throwable t) {
            alsaCards = null;
        }
    }

    private void showCardPicker() {
        refreshAlsaCards();
        if (alsaCards == null || alsaCards.isEmpty()) {
            Toast.makeText(this, "读不到 /proc/asound/cards", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[alsaCards.size()];
        for (int i = 0; i < alsaCards.size(); i++) {
            labels[i] = alsaCards.get(i).toString();
        }
        new AlertDialog.Builder(this)
                .setTitle("选择 ALSA card（device 默认 0）")
                .setItems(labels, (dialog, which) -> {
                    TinyAlsaPlayer.Card c = alsaCards.get(which);
                    SelectedCard newCard = new SelectedCard(c.index, 0, c.id, c.name);

                    // 切换到新 card：先停 mixer，避免上一张 card 还在被独占
                    if (selectedCard != null
                            && (selectedCard.card != newCard.card
                                || selectedCard.device != newCard.device)) {
                        TinyAlsaMixer.stop();
                    }
                    selectedCard = newCard;
                    Log.i(TAG, "selected " + selectedCard.label());
                    Toast.makeText(this, "已选择 " + selectedCard.label(),
                            Toast.LENGTH_SHORT).show();
                    refreshStatus();
                })
                .show();
    }

    // ════════════════════════════════════════════════
    //   播放 / 停止
    // ════════════════════════════════════════════════
    private boolean ensureMixerStarted() {
        if (selectedCard == null) {
            Toast.makeText(this, "请先选择 ALSA card", Toast.LENGTH_LONG).show();
            return false;
        }
        if (!TinyAlsaMixer.isRunning()) {
            TinyAlsaMixer.start(selectedCard.card, selectedCard.device);
            if (!TinyAlsaMixer.isRunning()) {
                Toast.makeText(this,
                        "Mixer 启动失败：pcm_open card=" + selectedCard.card + " 失败"
                                + "（检查 /dev/snd/pcm* 权限）",
                        Toast.LENGTH_LONG).show();
                return false;
            }
        }
        return true;
    }

    private void playBgm() {
        long seq = actionSeq.incrementAndGet();
        Log.i(TAG, "[#" + seq + "] playBgm() click"
                + " bgmDecoded=" + (bgmDecoded != null)
                + " decoding=" + decodingAssets.contains(BGM_ASSET)
                + " pending=" + pendingBgmPlayback);

        if (!ensureMixerStarted()) return;

        if (bgmDecoded == null) {
            // 关键：解码完成后是否要自动播 → 只置一个 flag，等待中重复点击不会再启动新解码。
            pendingBgmPlayback = true;
            if (decodingAssets.add(BGM_ASSET)) {
                Toast.makeText(this, "BGM 解码中，解码完成后自动播放一次", Toast.LENGTH_SHORT).show();
                Log.i(TAG, "[#" + seq + "] playBgm: decode start (pending auto-play)");
                decodeAsync(BGM_ASSET, r -> {
                    bgmDecoded = r;
                    decodingAssets.remove(BGM_ASSET);
                    if (pendingBgmPlayback) {
                        pendingBgmPlayback = false;
                        Log.i(TAG, "[#" + seq + "] playBgm: decode done, auto-play");
                        playBgm();
                    } else {
                        Log.i(TAG, "[#" + seq + "] playBgm: decode done, no pending play");
                    }
                });
            } else {
                Toast.makeText(this, "BGM 已经在解码（不再重复触发），完成后自动播放",
                        Toast.LENGTH_SHORT).show();
                Log.i(TAG, "[#" + seq + "] playBgm: decode already in progress, skip");
            }
            return;
        }

        pendingBgmPlayback = false;
        Log.i(TAG, "[#" + seq + "] playBgm: addPcmVoice"
                + " pcm@" + System.identityHashCode(bgmDecoded.pcm)
                + " len=" + bgmDecoded.pcm.length);
        TinyAlsaMixer.addPcmVoice(bgmDecoded.pcm, bgmDecoded.sampleRate, bgmDecoded.channels,
                /* loop */ true, /* vol */ 0.6f, BGM_VOICE_NAME);
        Toast.makeText(this, "BGM 已开始（循环）", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void stopBgm() {
        // 用户显式停 → 取消任何正在 pending 的"解码完成后自动播放"。
        pendingBgmPlayback = false;
        TinyAlsaMixer.removeByName(BGM_VOICE_NAME);
        Toast.makeText(this, "已停止 BGM（特效继续播）", Toast.LENGTH_SHORT).show();
        Log.i(TAG, "stopBgm: removeByName(bgm)");
        refreshStatus();
    }

    /** 同时播 SFX（短 mp3）和 880Hz / 1s 正弦波；不影响 BGM。 */
    private void playSfxAndSine() {
        long seq = actionSeq.incrementAndGet();
        Log.i(TAG, "[#" + seq + "] playSfxAndSine() click"
                + " sfxDecoded=" + (sfxDecoded != null)
                + " decoding=" + decodingAssets.contains(SFX_ASSET));

        if (!ensureMixerStarted()) return;

        if (sfxDecoded == null) {
            if (decodingAssets.add(SFX_ASSET)) {
                Toast.makeText(this, "SFX 解码中，请稍后再试", Toast.LENGTH_SHORT).show();
                decodeAsync(SFX_ASSET, r -> {
                    sfxDecoded = r;
                    decodingAssets.remove(SFX_ASSET);
                });
            } else {
                Toast.makeText(this, "SFX 仍在解码", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (sineCached == null) {
            sineCached = synthSine(880.0, 1.0, 44100, 2, 0.4);
        }
        Log.i(TAG, "[#" + seq + "] playSfxAndSine: addPcmVoice sfx pcm@"
                + System.identityHashCode(sfxDecoded.pcm) + " len=" + sfxDecoded.pcm.length);
        TinyAlsaMixer.addPcmVoice(sfxDecoded.pcm, sfxDecoded.sampleRate, sfxDecoded.channels,
                /* loop */ false, /* vol */ 1.0f, /* name */ null);
        TinyAlsaMixer.addPcmVoice(sineCached, 44100, 2,
                /* loop */ false, /* vol */ 0.5f, /* name */ null);
        Toast.makeText(this, "已叠加 SFX + 880Hz 正弦波", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void stopAll() {
        TinyAlsaMixer.stop();
        Toast.makeText(this, "已停止全部（mixer 已关）", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    // ════════════════════════════════════════════════
    //   预解码
    // ════════════════════════════════════════════════
    private interface DecodeCallback {
        void onResult(Mp3DecodeHelper.Result r);
    }

    private void prefetchAssetsAsync() {
        // 重要：只有在还未解码并且当前没有正在解码时，才启动新解码线程。
        // 防止 Activity 重建（横竖屏切换 / 系统重启 Activity） + 用户连点
        // 同一资源在内存里同时存在 2~3 份 ByteArrayOutputStream，触发 OOM。
        if (bgmDecoded == null && decodingAssets.add(BGM_ASSET)) {
            Log.i(TAG, "prefetch: BGM decode start");
            decodeAsync(BGM_ASSET, r -> {
                bgmDecoded = r;
                decodingAssets.remove(BGM_ASSET);
                if (pendingBgmPlayback) {
                    pendingBgmPlayback = false;
                    Log.i(TAG, "prefetch: BGM decode done, pending → auto-play");
                    playBgm();
                }
            });
        } else {
            Log.i(TAG, "prefetch: BGM skip"
                    + " decoded=" + (bgmDecoded != null)
                    + " inflight=" + decodingAssets.contains(BGM_ASSET));
        }
        if (sfxDecoded == null && decodingAssets.add(SFX_ASSET)) {
            Log.i(TAG, "prefetch: SFX decode start");
            decodeAsync(SFX_ASSET, r -> {
                sfxDecoded = r;
                decodingAssets.remove(SFX_ASSET);
            });
        } else {
            Log.i(TAG, "prefetch: SFX skip"
                    + " decoded=" + (sfxDecoded != null)
                    + " inflight=" + decodingAssets.contains(SFX_ASSET));
        }
        if (sineCached == null) sineCached = synthSine(880.0, 1.0, 44100, 2, 0.4);
    }

    private void decodeAsync(String asset, DecodeCallback cb) {
        long t0 = System.currentTimeMillis();
        Log.i(TAG, "decode-" + asset + " thread spawn");
        new Thread(() -> {
            try {
                Mp3DecodeHelper.Result r = Mp3DecodeHelper.decodeAsset(this, asset);
                long dt = System.currentTimeMillis() - t0;
                Log.i(TAG, "decoded " + asset + " ok ("
                        + r.pcm.length + " bytes, " + r.sampleRate + "Hz/" + r.channels + "ch)"
                        + " in " + dt + "ms"
                        + " pcm@" + System.identityHashCode(r.pcm));
                mainHandler.post(() -> cb.onResult(r));
            } catch (Throwable e) {
                Log.e(TAG, "decode " + asset + " failed", e);
                // 关键：异常时也要释放 decodingAssets 互斥位，否则永久卡死。
                decodingAssets.remove(asset);
                mainHandler.post(() -> Toast.makeText(this,
                        asset + " 解码失败：" + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }, "decode-" + asset).start();
    }

    private void updateMasterVolumeLabel(int progress) {
        String hint = progress > 100 ? "  (boost，可能 clamp)"
                : progress == 0 ? "  (静音)" : "";
        masterVolumeLabel.setText("主音量 " + progress + "%" + hint);
    }

    // ════════════════════════════════════════════════
    //   工具
    // ════════════════════════════════════════════════
    private static byte[] synthSine(double freq, double seconds,
                                    int rate, int channels, double amp) {
        int totalFrames = (int) (rate * seconds);
        byte[] pcm = new byte[totalFrames * channels * 2];
        for (int i = 0; i < totalFrames; i++) {
            short s16 = (short) (Math.sin(2 * Math.PI * freq * i / rate) * amp * 32767);
            int base = i * channels * 2;
            for (int c = 0; c < channels; c++) {
                pcm[base + c * 2]     = (byte) (s16 & 0xFF);
                pcm[base + c * 2 + 1] = (byte) ((s16 >> 8) & 0xFF);
            }
        }
        return pcm;
    }

    private void refreshStatus() {
        refreshAlsaCards();

        StringBuilder sb = new StringBuilder();
        sb.append("【ALSA card】\n  ")
                .append(selectedCard == null ? "未选择" : selectedCard.label())
                .append("\n\n");

        sb.append("【Mixer】\n")
                .append("  状态：").append(TinyAlsaMixer.isRunning() ? "✓ 运行中" : "已停止").append('\n')
                .append("  活跃 voice：").append(TinyAlsaMixer.activeVoiceCount()).append("\n\n");

        sb.append("【素材】\n")
                .append("  BGM ").append(BGM_ASSET).append("：")
                .append(bgmDecoded != null ? "已解码" : "未就绪").append('\n')
                .append("  SFX ").append(SFX_ASSET).append("：")
                .append(sfxDecoded != null ? "已解码" : "未就绪").append("\n\n");

        sb.append("【/proc/asound/cards】\n");
        if (alsaCards == null || alsaCards.isEmpty()) {
            sb.append("  （读不到，或 native 库未加载）\n");
        } else {
            for (TinyAlsaPlayer.Card c : alsaCards) {
                sb.append("  ").append(c).append('\n');
            }
        }
        statusTextView.setText(sb.toString());
    }

    /** 当前选中的 ALSA card+device。 */
    private static final class SelectedCard {
        final int card;
        final int device;
        final String id;
        final String name;

        SelectedCard(int card, int device, String id, String name) {
            this.card = card;
            this.device = device;
            this.id = id;
            this.name = name;
        }

        String label() {
            return "card " + card + " dev " + device + "  [" + id + "]  " + name;
        }
    }
}

