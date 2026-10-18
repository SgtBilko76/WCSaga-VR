package com.bilko.wcsagavr;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "One-Tap Installer": 2D panel shown instead of the game while the Wing Commander Saga data is missing:
 * asks for all-files access, then downloads and unpacks the data (DataInstaller) into
 * /sdcard/WCSaga/Wing_Commander_Saga and starts the game when it is done.
 */
public class InstallActivity extends Activity {
    private static final String TAG = "WCSagaInstall";

    // The download keeps running while the panel is closed or recreated.
    private static DataInstaller sInstaller;
    private static PowerManager.WakeLock sWakeLock;
    private static WifiManager.WifiLock sWifiLock;

    private TextView mBody, mDetail;
    private ProgressBar mBar;
    private Button mButton;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private long mLastNet = -1, mLastTime;
    private double mSpeed;
    private boolean mStarted;

    static File gameRoot() { return new File(WCSagaActivity.GAME_DATA_DIR); }
    static File modDir() { return new File(gameRoot(), WCSagaActivity.MOD); }
    static File stateDir() { return new File(gameRoot(), ".install"); }

    static boolean hasFileAccess() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
    }

    /** True while the data is missing or an install was started and not finished. */
    static boolean needsInstall() {
        if (!hasFileAccess()) return true;
        if (sInstaller != null && sInstaller.isRunning()) return true;
        return stateDir().exists() || !new File(modDir(), "data").isDirectory();
    }

    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            refresh();
            mHandler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (32 * dp);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("One-Tap Installer");
        subtitle.setTextSize(20);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle);

        mBody = new TextView(this);
        mBody.setTextSize(18);
        mBody.setPadding(0, (int) (16 * dp), 0, (int) (16 * dp));
        root.addView(mBody, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mBar.setMax(1000);
        root.addView(mBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (24 * dp)));

        mDetail = new TextView(this);
        mDetail.setTextSize(16);
        mDetail.setPadding(0, (int) (12 * dp), 0, (int) (24 * dp));
        root.addView(mDetail, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mButton = new Button(this);
        mButton.setTextSize(20);
        int bp = (int) (16 * dp);
        mButton.setPadding(bp * 2, bp, bp * 2, bp);
        root.addView(mButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.post(mTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTick);
    }

    private List<DataInstaller.Archive> loadArchives() throws Exception {
        try (InputStream in = getAssets().open("wcs_data.json")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            JSONArray arr = new JSONObject(bo.toString("UTF-8")).getJSONArray("archives");
            List<DataInstaller.Archive> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                DataInstaller.Archive a = new DataInstaller.Archive();
                a.name = o.getString("name");
                a.size = o.getLong("size");
                a.sha256 = o.getString("sha256");
                a.dest = o.optString("dest", "");
                a.unpacked = o.getLong("unpacked");
                a.urls = new ArrayList<>();
                JSONArray u = o.getJSONArray("urls");
                for (int j = 0; j < u.length(); j++) a.urls.add(u.getString(j));
                list.add(a);
            }
            return list;
        }
    }

    private static String gb(long bytes) {
        return String.format(Locale.US, "%.1f GB", bytes / 1e9);
    }

    private void refresh() {
        if (mStarted) return;
        DataInstaller inst = sInstaller;
        if (inst != null && inst.isRunning()) {
            showProgress(inst);
            return;
        }
        if (!hasFileAccess()) {
            mBody.setText("To store the game data in /sdcard/WCSaga, this app needs access to all files.\n\n"
                    + "Press the button, switch on \"Allow access\", then press Done.");
            mBar.setVisibility(ProgressBar.INVISIBLE);
            mDetail.setText("");
            setButton("Allow file access", new Runnable() {
                @Override public void run() {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    }
                }
            });
            return;
        }
        if ((inst != null && inst.phase == DataInstaller.DONE) || !needsInstall()) {
            startGame();
            return;
        }

        List<DataInstaller.Archive> archives;
        try {
            archives = loadArchives();
        } catch (Exception e) {
            mBody.setText("Internal error: " + e);
            return;
        }
        long total = 0, unpacked = 0;
        for (DataInstaller.Archive a : archives) { total += a.size; unpacked += a.unpacked; }
        long need = DataInstaller.spaceNeeded(archives, stateDir());
        long free = new StatFs(Environment.getExternalStorageDirectory().getPath()).getAvailableBytes();
        boolean resume = stateDir().exists();

        StringBuilder sb = new StringBuilder();
        if (inst != null && inst.phase == DataInstaller.FAILED) {
            sb.append("The installation stopped: ").append(inst.error).append("\n\n")
              .append("Check the Wi-Fi connection and press Retry. It continues where it stopped.\n\n");
        } else if (resume) {
            sb.append("The game data installation was interrupted. Press Continue to finish it.\n\n");
        } else {
            sb.append("Wing Commander Saga is a free fan game. Its game data is not on this headset yet.\n\n")
              .append("It is downloaded from the official FreeSpace mod repository (FSNebula): ")
              .append(gb(total)).append(" to download, ").append(gb(unpacked))
              .append(" on the headset when unpacked.\n\n")
              .append("Keep the headset on and connected to Wi-Fi until it is finished. ")
              .append("If it gets interrupted, start the app again and it continues where it stopped.\n\n");
        }
        sb.append("Free space: ").append(gb(free)).append(", needed: ").append(gb(need)).append('.');
        mBody.setText(sb.toString());
        mBar.setVisibility(ProgressBar.INVISIBLE);
        mDetail.setText("");
        if (free < need + 200_000_000L) {
            mDetail.setText("Not enough free space. Delete some apps or videos, then start this app again.");
            mButton.setVisibility(Button.GONE);
            return;
        }
        String label = (inst != null && inst.phase == DataInstaller.FAILED) ? "Retry"
                : resume ? "Continue installation" : "Download and install (" + gb(total) + ")";
        final List<DataInstaller.Archive> list = archives;
        setButton(label, new Runnable() {
            @Override public void run() { startInstall(list); }
        });
    }

    private void setButton(String label, final Runnable action) {
        mButton.setVisibility(Button.VISIBLE);
        mButton.setEnabled(true);
        mButton.setText(label);
        mButton.setOnClickListener(v -> action.run());
    }

    private void showProgress(DataInstaller inst) {
        mButton.setVisibility(Button.GONE);
        mBar.setVisibility(ProgressBar.VISIBLE);
        long total = Math.max(inst.totalBytes, 1);
        mBar.setProgress((int) (1000 * inst.doneBytes / total));

        long now = System.currentTimeMillis();
        long net = inst.downloaded;
        if (mLastNet >= 0 && now > mLastTime) {
            double s = (net - mLastNet) * 1000.0 / (now - mLastTime);
            mSpeed = mSpeed == 0 ? s : mSpeed * 0.8 + s * 0.2;
        }
        mLastNet = net;
        mLastTime = now;

        String what;
        switch (inst.phase) {
            case DataInstaller.DOWNLOADING:
                what = String.format(Locale.US, "Downloading %s: %d of %d MB (%.1f MB/s)", inst.current,
                        inst.stepDone >> 20, inst.stepTotal >> 20, mSpeed / (1 << 20));
                break;
            case DataInstaller.VERIFYING:
                what = String.format(Locale.US, "Checking %s: %d%%", inst.current,
                        inst.stepTotal > 0 ? 100 * inst.stepDone / inst.stepTotal : 0);
                break;
            default:
                what = String.format(Locale.US, "Unpacking %s: %d%%", inst.current,
                        inst.stepTotal > 0 ? 100 * inst.stepDone / inst.stepTotal : 0);
                break;
        }
        mBody.setText(String.format(Locale.US, "Installing the game data: %d%%\n\nPart %d of %d. Keep the headset on and connected to Wi-Fi.",
                100 * inst.doneBytes / total, inst.index, inst.count));
        mDetail.setText(what);
    }

    private void startInstall(List<DataInstaller.Archive> archives) {
        if (sInstaller != null && sInstaller.isRunning()) return;
        Log.i(TAG, "starting the game data installation");
        final DataInstaller inst = new DataInstaller(archives, modDir(), stateDir());
        inst.phase = DataInstaller.DOWNLOADING;
        sInstaller = inst;
        mLastNet = -1;
        mSpeed = 0;
        acquireLocks(getApplicationContext());
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    inst.run();
                } finally {
                    Log.i(TAG, "installation ended: phase " + inst.phase + (inst.error != null ? ", " + inst.error : ""));
                    releaseLocks();
                }
            }
        }, "WCSagaInstall");
        t.start();
        refresh();
    }

    private static synchronized void acquireLocks(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            sWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wcsagavr:install");
            sWakeLock.acquire();
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            sWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "wcsagavr:install");
            sWifiLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "could not take the wake/wifi lock", e);
        }
    }

    private static synchronized void releaseLocks() {
        try {
            if (sWakeLock != null && sWakeLock.isHeld()) sWakeLock.release();
            if (sWifiLock != null && sWifiLock.isHeld()) sWifiLock.release();
        } catch (Exception ignored) {
        }
        sWakeLock = null;
        sWifiLock = null;
    }

    private void startGame() {
        if (mStarted) return;
        mStarted = true;
        Log.i(TAG, "game data ready; starting the game");
        Intent i = new Intent(this, WCSagaActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finish();
    }
}
