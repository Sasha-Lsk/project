package com.apktool.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;

public class MainActivity extends Activity implements Logger {

    // Базовые пути вывода
    static final String BASE = "/storage/emulated/0/Download/ApkTool";
    static final File DIR_DECOMPILER = new File(BASE, "decompiler");
    static final File DIR_COMPILER = new File(BASE, "compiler");

    private static final int REQ_APK = 1001;
    private static final int REQ_PERM = 1002;

    private TextView logView, lblApk, lblFolder, lblProgress, lblSdk;
    private ScrollView logScroll;
    private ProgressBar progress;
    private SeekBar seekSdk;

    private File pickedApk;
    private File pickedFolder;

    private final SpannableStringBuilder logBuf = new SpannableStringBuilder();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Engine engine;

    // Android версии для ползунка: 0=выкл, затем 8..14
    private final int[] SLIDER_VER = {0, 8, 9, 10, 11, 12, 13, 14};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        logView = (TextView) findViewById(R.id.logView);
        logScroll = (ScrollView) findViewById(R.id.logScroll);
        lblApk = (TextView) findViewById(R.id.lblApk);
        lblFolder = (TextView) findViewById(R.id.lblFolder);
        lblProgress = (TextView) findViewById(R.id.lblProgress);
        lblSdk = (TextView) findViewById(R.id.lblSdk);
        progress = (ProgressBar) findViewById(R.id.progress);
        seekSdk = (SeekBar) findViewById(R.id.seekSdk);

        engine = new Engine(getApplicationContext(), this);

        findViewById(R.id.btnPickApk).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickApk(); }
        });
        findViewById(R.id.btnPickFolder).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickFolder(); }
        });
        findViewById(R.id.btnApk2Java).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runApkToJava(); }
        });
        findViewById(R.id.btnApk2Smali).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runApkToSmali(); }
        });
        findViewById(R.id.btnJava2Apk).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runJavaToApk(); }
        });
        findViewById(R.id.btnSmali2Apk).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runSmaliToApk(); }
        });
        findViewById(R.id.btnCopy).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { copyLogs(); }
        });
        findViewById(R.id.btnClear).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { clearAll(); }
        });

        seekSdk.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { updateSdkLabel(); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        updateSdkLabel();

        ensureDirs();
        requestStorage();
        log("Apk Tool готов. Выход: " + BASE);
    }

    private void updateSdkLabel() {
        int v = SLIDER_VER[seekSdk.getProgress()];
        if (v == 0) lblSdk.setText("Адаптация под Android: выкл (оригинальный SDK)");
        else lblSdk.setText("Адаптация под Android " + v);
    }

    private int selectedAndroidVer() {
        return SLIDER_VER[seekSdk.getProgress()];
    }

    // ---------- Разрешения ----------

    private void requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Throwable t) {
                    try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
                    catch (Throwable ignore) {}
                }
            }
        } else {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
        }
    }

    private void ensureDirs() {
        DIR_COMPILER.mkdirs();
        DIR_DECOMPILER.mkdirs();
    }

    // ---------- Пикеры ----------

    private void pickApk() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Выберите APK"), REQ_APK);
        } catch (Throwable t) {
            err("Не удалось открыть выбор файла: " + t);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_APK && res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            File f = copyUriToCache(uri);
            if (f != null) {
                pickedApk = f;
                lblApk.setText(f.getName());
                log("Выбран APK: " + f.getName());
            } else {
                err("Не удалось прочитать выбранный файл.");
            }
        }
    }

    private File copyUriToCache(Uri uri) {
        try {
            String name = queryName(uri);
            if (name == null) name = "picked.apk";
            File out = new File(getCacheDir(), name);
            InputStream is = getContentResolver().openInputStream(uri);
            FileOutputStream os = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            os.close(); is.close();
            return out;
        } catch (Throwable t) {
            err("copyUriToCache: " + t);
            return null;
        }
    }

    private String queryName(Uri uri) {
        try {
            Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.moveToFirst()) {
                    String n = c.getString(idx);
                    c.close();
                    return n;
                }
                c.close();
            }
        } catch (Throwable ignore) {}
        return uri.getLastPathSegment();
    }

    /** Простой файловый навигатор по директориям — надёжен на всех версиях Android. */
    private void pickFolder() {
        File start = DIR_DECOMPILER.exists() ? DIR_DECOMPILER
                : Environment.getExternalStorageDirectory();
        browse(start);
    }

    private void browse(final File dir) {
        File[] raw = dir.listFiles();
        final ArrayList<File> dirs = new ArrayList<File>();
        if (raw != null) {
            Arrays.sort(raw);
            for (File f : raw) if (f.isDirectory()) dirs.add(f);
        }
        final ArrayList<String> items = new ArrayList<String>();
        items.add(".. (вверх)");
        items.add("[ ВЫБРАТЬ ЭТУ ПАПКУ ]");
        for (File d : dirs) items.add("\uD83D\uDCC1 " + d.getName());

        new AlertDialog.Builder(this)
                .setTitle(dir.getAbsolutePath())
                .setItems(items.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) {
                            File up = dir.getParentFile();
                            browse(up != null ? up : dir);
                        } else if (which == 1) {
                            pickedFolder = dir;
                            lblFolder.setText(dir.getName());
                            log("Выбрана папка: " + dir.getAbsolutePath());
                        } else {
                            browse(dirs.get(which - 2));
                        }
                    }
                }).show();
    }

    // ---------- Операции ----------

    private void runApkToSmali() {
        if (!checkApk()) return;
        background(new Runnable() {
            public void run() {
                try {
                    File out = engine.apkToSmali(pickedApk, DIR_DECOMPILER);
                    done(true, "Декомпиляция в smali завершена:\n" + out.getAbsolutePath());
                } catch (Throwable t) { fail(t); }
            }
        });
    }

    private void runApkToJava() {
        if (!checkApk()) return;
        background(new Runnable() {
            public void run() {
                try {
                    File out = engine.apkToJava(pickedApk, DIR_DECOMPILER);
                    done(true, "Декомпиляция в java завершена:\n" + out.getAbsolutePath());
                } catch (Throwable t) { fail(t); }
            }
        });
    }

    private void runSmaliToApk() {
        if (!checkFolder()) return;
        background(new Runnable() {
            public void run() {
                try {
                    File apk = engine.smaliToApk(pickedFolder, DIR_COMPILER, selectedAndroidVer());
                    done(true, "APK собран:\n" + apk.getAbsolutePath());
                } catch (Throwable t) { fail(t); }
            }
        });
    }

    private void runJavaToApk() {
        if (!checkFolder()) return;
        background(new Runnable() {
            public void run() {
                try {
                    File apk = engine.javaToApk(pickedFolder, DIR_COMPILER, selectedAndroidVer());
                    done(true, "APK собран:\n" + apk.getAbsolutePath());
                } catch (Throwable t) { fail(t); }
            }
        });
    }

    private boolean checkApk() {
        if (pickedApk == null || !pickedApk.exists()) {
            err("Сначала выберите APK.");
            toast("Выберите APK");
            return false;
        }
        return true;
    }

    private boolean checkFolder() {
        if (pickedFolder == null || !pickedFolder.exists()) {
            err("Сначала выберите папку с декомпилированным проектом.");
            toast("Выберите папку");
            return false;
        }
        return true;
    }

    private void background(final Runnable r) {
        setBar(0);
        new Thread(r).start();
    }

    private void done(final boolean ok, final String msg) {
        ui.post(new Runnable() {
            public void run() {
                progress(100);
                if (ok) { log(msg); toast("Успешно"); }
                showResultDialog(true, msg);
            }
        });
    }

    private void fail(final Throwable t) {
        ui.post(new Runnable() {
            public void run() {
                err("ОШИБКА: " + t);
                StackTraceElement[] st = t.getStackTrace();
                for (int i = 0; i < Math.min(st.length, 12); i++) err("    at " + st[i]);
                Throwable c = t.getCause();
                if (c != null) err("Причина: " + c);
                toast("Ошибка — см. логи");
                showResultDialog(false, String.valueOf(t));
            }
        });
    }

    private void showResultDialog(boolean ok, String msg) {
        new AlertDialog.Builder(this)
                .setTitle(ok ? "Готово" : "Ошибка")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show();
    }

    // ---------- Логи ----------

    private void copyLogs() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("logs", logBuf.toString()));
        toast("Логи скопированы");
    }

    private void clearAll() {
        logBuf.clear();
        logView.setText("");
        pickedApk = null;
        pickedFolder = null;
        lblApk.setText("APK не выбран");
        lblFolder.setText("Папка не выбрана");
        setBar(0);
        toast("Очищено");
    }

    private void appendLine(final String msg, final boolean isErr) {
        ui.post(new Runnable() {
            public void run() {
                // Автопрокрутка только если пользователь уже внизу — иначе
                // не мешаем ему листать логи вручную вверх.
                final boolean atBottom = isScrolledToBottom();
                int start = logBuf.length();
                logBuf.append(msg).append('\n');
                logBuf.setSpan(new android.text.style.ForegroundColorSpan(
                                isErr ? Color.parseColor("#FF5A5A") : Color.parseColor("#B7F5C8")),
                        start, logBuf.length(),
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                logView.setText(logBuf);
                if (atBottom) {
                    logScroll.post(new Runnable() {
                        public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
                    });
                }
            }
        });
    }

    /** Пользователь у нижнего края лога (в пределах небольшого допуска). */
    private boolean isScrolledToBottom() {
        View child = logScroll.getChildAt(0);
        if (child == null) return true;
        int diff = child.getBottom() - (logScroll.getHeight() + logScroll.getScrollY());
        return diff <= (int) (24 * getResources().getDisplayMetrics().density);
    }

    private void setBar(final int p) {
        ui.post(new Runnable() {
            public void run() {
                progress.setProgress(p);
                lblProgress.setText(p + "%");
            }
        });
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ---------- Logger ----------

    @Override public void log(String msg) { appendLine(msg, false); }
    @Override public void err(String msg) { appendLine(msg, true); }
    @Override public void progress(int percent) { setBar(percent); }
}
