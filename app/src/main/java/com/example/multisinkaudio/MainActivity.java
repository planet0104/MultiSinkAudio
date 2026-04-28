package com.example.multisinkaudio;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
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

import com.example.multisinkaudio.alsa.TinyAlsaPlayer;
import com.example.multisinkaudio.alsa.TinyAlsaTrackPlayer;

import java.io.IOException;
import java.util.List;

/**
 * 单一播放路径：MediaExtractor + MediaCodec + tinyalsa 直写 /dev/snd/pcmCxDxp。
 *
 * 完全绕开 AudioFlinger / AudioPolicy / AudioTrack，所以：
 *   - 不会被 audio_policy_configuration.xml 共用 mixPort 的设计卡住；
 *   - 两路播放可以分别落到不同 ALSA card（板载 / HDMI / USB 声卡）真同时出声；
 *   - 但需要进程能读写 /dev/snd/pcm* 和 /dev/snd/control*。
 *     RK3588 这台机器走 ROM 层方案：scripts/setup_device.ps1 把 ueventd.rc 改成 0666，
 *     重启即生效。其它机器同理。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQ_RECORD_AUDIO = 1001;

    private static final String MUSIC_ASSET_1 = "music_6.mp3";
    private static final String MUSIC_ASSET_2 = "music_2.mp3";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView statusTextView;

    private List<TinyAlsaPlayer.Card> alsaCards;

    private TinyAlsaTarget target1;
    private TinyAlsaTrackPlayer player1;
    private boolean opened1;

    private TinyAlsaTarget target2;
    private TinyAlsaTrackPlayer player2;
    private boolean opened2;

    private final Runnable periodicRefresh = new Runnable() {
        @Override
        public void run() {
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

        statusTextView = findViewById(R.id.audioDeviceListTextView);

        Button selectButton = findViewById(R.id.selectAudioDeviceButton);
        Button playButton = findViewById(R.id.playButton);
        Button stopButton = findViewById(R.id.stopButton);

        selectButton.setText("选择 ALSA card");
        selectButton.setOnClickListener(v -> showPlayerPicker());

        playButton.setText("同时播放");
        playButton.setOnClickListener(v -> playBothTinyAlsa());

        stopButton.setOnClickListener(v -> {
            releaseAllPlayers();
            refreshStatus();
            Toast.makeText(this, "已停止全部播放", Toast.LENGTH_SHORT).show();
        });

        refreshAlsaCards();
        refreshStatus();

        // tinyalsa 直写需要进程对 /dev/snd/pcm* 有读写权限。
        // 主路径是改 ueventd.rc 把节点放开 0666（见 scripts/setup_device.ps1）。
        // 这里再借 RECORD_AUDIO 兜个底——某些 ROM 的 platform.xml 把这条权限关联到 audio gid，
        // 授予后进程会带 audio gid，原生 0660 节点也能写。
        ensureRecordAudioGranted();

        mainHandler.postDelayed(periodicRefresh, 1000);
    }

    private void ensureRecordAudioGranted() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "RECORD_AUDIO already granted");
            return;
        }
        ActivityCompat.requestPermissions(this,
                new String[] { Manifest.permission.RECORD_AUDIO },
                REQ_RECORD_AUDIO);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Log.i(TAG, "RECORD_AUDIO grant result: " + granted);
            if (granted) {
                // audio gid 是在进程启动时附加的，授权后必须杀掉进程重启才生效
                new AlertDialog.Builder(this)
                        .setTitle("权限已授予")
                        .setMessage("RECORD_AUDIO 已授予。\n\n"
                                + "audio gid 是在进程启动时附加的，"
                                + "请关闭 app 再重新打开，写 /dev/snd/pcm* 才能生效。\n\n"
                                + "如果机器已经按 setup_device.ps1 改过 ueventd.rc，"
                                + "这一步其实可以忽略。")
                        .setPositiveButton("好", (d, w) -> {})
                        .show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(periodicRefresh);
        releaseAllPlayers();
        super.onDestroy();
    }

    // ════════════════════════════════════════════════
    //   状态
    // ════════════════════════════════════════════════

    private void refreshAlsaCards() {
        try {
            alsaCards = TinyAlsaPlayer.listCards();
        } catch (Throwable t) {
            alsaCards = null;
        }
    }

    private void refreshStatus() {
        refreshAlsaCards();

        StringBuilder sb = new StringBuilder();
        appendPlayerStatus(sb, 1, MUSIC_ASSET_1, target1, player1, opened1);
        sb.append('\n');
        appendPlayerStatus(sb, 2, MUSIC_ASSET_2, target2, player2, opened2);

        sb.append("\n──────────────────\n")
                .append("/proc/asound/cards：\n\n");
        if (alsaCards == null || alsaCards.isEmpty()) {
            sb.append("  （读不到，或 native 库未加载）\n");
        } else {
            for (TinyAlsaPlayer.Card c : alsaCards) {
                sb.append("  ").append(c).append('\n');
            }
        }

        statusTextView.setText(sb.toString());
    }

    private void appendPlayerStatus(StringBuilder sb,
                                    int index,
                                    String asset,
                                    TinyAlsaTarget target,
                                    TinyAlsaTrackPlayer player,
                                    boolean opened) {
        sb.append("【播放器 ").append(index).append("】\n")
                .append("  音频：").append(asset).append('\n')
                .append("  目标：")
                .append(target == null ? "未选择" : target.label())
                .append('\n');

        if (player != null && player.isPlaying()) {
            sb.append("  状态：").append(opened ? "✓ 写入 /dev/snd 中" : "✗ pcm 未打开").append('\n');
        } else {
            sb.append("  状态：已停止\n");
        }
    }

    // ════════════════════════════════════════════════
    //   选 ALSA card
    // ════════════════════════════════════════════════

    private void showPlayerPicker() {
        String[] options = {
                "为【播放器 1】选择 ALSA card  (" + MUSIC_ASSET_1 + ")",
                "为【播放器 2】选择 ALSA card  (" + MUSIC_ASSET_2 + ")"
        };
        new AlertDialog.Builder(this)
                .setTitle("请选择要配置的播放器")
                .setItems(options, (dialog, which) -> showAlsaCardPicker(which + 1))
                .show();
    }

    private void showAlsaCardPicker(int playerIndex) {
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
                .setTitle("为播放器 " + playerIndex + " 选择 ALSA card（device 默认 0）")
                .setItems(labels, (dialog, which) -> {
                    TinyAlsaPlayer.Card c = alsaCards.get(which);
                    TinyAlsaTarget t = new TinyAlsaTarget(c.index, /* device = */ 0, c.id, c.name);
                    if (playerIndex == 1) target1 = t;
                    else                  target2 = t;
                    refreshStatus();
                })
                .show();
    }

    // ════════════════════════════════════════════════
    //   播放 / 停止
    // ════════════════════════════════════════════════

    private void playBothTinyAlsa() {
        if (target1 == null && target2 == null) {
            Toast.makeText(this, "请至少为一个播放器选择 ALSA card", Toast.LENGTH_LONG).show();
            return;
        }
        releaseAllPlayers();
        boolean anyStarted = false;

        if (target1 != null) {
            try {
                player1 = startTinyPlayer(MUSIC_ASSET_1, target1, 1);
                anyStarted = true;
            } catch (IOException | RuntimeException e) {
                releasePlayer1();
                Toast.makeText(this, "播放器1 启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
        if (target2 != null) {
            try {
                player2 = startTinyPlayer(MUSIC_ASSET_2, target2, 2);
                anyStarted = true;
            } catch (IOException | RuntimeException e) {
                releasePlayer2();
                Toast.makeText(this, "播放器2 启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        if (anyStarted) {
            Toast.makeText(this, "已启动播放", Toast.LENGTH_SHORT).show();
            mainHandler.postDelayed(this::refreshStatus, 600);
        }
    }

    private TinyAlsaTrackPlayer startTinyPlayer(String asset,
                                                TinyAlsaTarget target,
                                                int playerIndex) throws IOException {
        TinyAlsaTrackPlayer player = new TinyAlsaTrackPlayer(
                this,
                asset,
                target.card,
                target.device,
                /* looping = */ true,
                playerIndex,
                mainHandler,
                new TinyAlsaTrackPlayer.Listener() {
                    @Override
                    public void onError(String message) {
                        Toast.makeText(MainActivity.this,
                                "播放器" + playerIndex + " " + message,
                                Toast.LENGTH_LONG).show();
                        if (playerIndex == 1) releasePlayer1();
                        else                  releasePlayer2();
                        refreshStatus();
                    }

                    @Override
                    public void onOpened(boolean success) {
                        if (playerIndex == 1) opened1 = success;
                        else                  opened2 = success;
                        if (!success) {
                            Toast.makeText(MainActivity.this,
                                    "播放器" + playerIndex + "：pcm_open 失败\n"
                                            + "检查 /dev/snd/pcmC*D*p 是否对当前 uid 可写",
                                    Toast.LENGTH_LONG).show();
                        }
                        refreshStatus();
                    }
                });
        player.start();
        return player;
    }

    private void releasePlayer1() {
        if (player1 != null) {
            player1.stop();
            player1 = null;
        }
        opened1 = false;
    }

    private void releasePlayer2() {
        if (player2 != null) {
            player2.stop();
            player2 = null;
        }
        opened2 = false;
    }

    private void releaseAllPlayers() {
        releasePlayer1();
        releasePlayer2();
    }

    /** 选中的 ALSA card+device 目标。 */
    private static final class TinyAlsaTarget {
        final int card;
        final int device;
        final String id;
        final String name;

        TinyAlsaTarget(int card, int device, String id, String name) {
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
