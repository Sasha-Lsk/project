package com.apkstudio.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Html;
import android.text.TextUtils;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private static final int REQ_PICK_APK = 101;
    private static final int REQ_PERM_STORAGE = 102;
    private static final int REQ_PICK_DIR = 103;
    private static final int REQ_PERM_NOTIF = 104;

    private TextView decPath, buildPath, logView;
    private ScrollView logScroll;
    private CheckBox cbBump;

    // Живой статус / прогресс
    private View statusCard;
    private TextView statusStage, statusPercent, statusFile;
    private ProgressBar statusBar;
    private static final int NOTIF_ID = 4201;
    private static final String NOTIF_CH = "apkstudio_progress";
    private long lastProgressUi = 0;   // троттлинг обновлений UI/уведомления
    // Последние 3 обработанных файла для окна статуса (кольцевой буфер).
    private final java.util.ArrayDeque<String> recentFiles = new java.util.ArrayDeque<String>(3);

    private File pickedApk;          // выбранный APK для декомпиляции
    private File pickedProject;      // выбранная папка проекта для сборки
    private int pendingAction = 0;   // какое действие ждёт выбора APK

    private final StringBuilder logBuf = new StringBuilder();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Toolchain toolchain;
    private Operations ops;
    private volatile boolean busy = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            setContentView(R.layout.activity_main);
            bind();
            initToolchain();
            requestStorageIfNeeded();
        } catch (Throwable e) {
            App.writeCrash(e);
            new AlertDialog.Builder(this).setTitle("Ошибка запуска")
                    .setMessage(App.stackToString(e)).setPositiveButton("OK", null).show();
        }
    }

    private void bind() {
        decPath = (TextView) findViewById(R.id.dec_path);
        buildPath = (TextView) findViewById(R.id.build_path);
        logView = (TextView) findViewById(R.id.log_view);
        logScroll = (ScrollView) findViewById(R.id.log_scroll);
        cbBump = (CheckBox) findViewById(R.id.cb_bump);

        statusCard    = findViewById(R.id.status_card);
        statusStage   = (TextView) findViewById(R.id.status_stage);
        statusPercent = (TextView) findViewById(R.id.status_percent);
        statusFile    = (TextView) findViewById(R.id.status_file);
        statusBar     = (ProgressBar) findViewById(R.id.status_bar);

        decPath.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pendingAction = 0; pickApk(); }
        });
        buildPath.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { chooseProjectDialog(); }
        });

        findViewById(R.id.btn_apk_smali).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runApkSmali(); }
        });
        findViewById(R.id.btn_apk_java).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runApkJava(); }
        });
        findViewById(R.id.btn_smali_apk).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runSmaliApk(); }
        });
        findViewById(R.id.btn_java_apk).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runJavaApk(); }
        });

        findViewById(R.id.btn_copy_log).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { copyLogs(); }
        });
        findViewById(R.id.btn_clear_log).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { clearAll(); }
        });
    }

    /**
     * Очистка журнала + сброс выбранных в picker'ах приложения/папки
     * (APK для декомпиляции и папки проекта для сборки).
     */
    private void clearAll() {
        logBuf.setLength(0);
        logView.setText("");
        // сброс picker'ов к состоянию «ничего не выбрано»
        pickedApk = null;
        pickedProject = null;
        pendingAction = 0;
        decPath.setText("Выбрать APK…");
        decPath.setTextColor(0xFF8B949E);        // text_secondary
        buildPath.setText("Выбрать папку проекта (decompiler_apk или любую)…");
        buildPath.setTextColor(0xFF8B949E);
        // скрыть панель статуса/прогресса
        hideStatus();
        toast("Журнал и выбор очищены");
    }

    // ---------------- Логирование (цветной HTML) ----------------
    private final Toolchain.Log LOG = new Toolchain.Log() {
        public void line(String s) { append(s, "#8B949E"); }
        public void ok(String s)   { append(s, "#3FB950"); }
        public void err(String s)  { append(s, "#F85149"); }
        public void warn(String s) { append(s, "#D29922"); }
        public void cmd(String s)  { append(s, "#58A6FF"); }
        public void progress(String stage, int done, int total, String file) {
            onProgress(stage, done, total, file);
        }
    };

    private void append(final String s, final String color) {
        ui.post(new Runnable() {
            public void run() {
                logBuf.append(s).append('\n');
                String esc = TextUtils.htmlEncode(s).replace(" ", "&#160;");
                logView.append(Html.fromHtml("<font color='" + color + "'>" + esc + "</font><br>"));
                logScroll.post(new Runnable() {
                    public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
                });
            }
        });
    }

    private void copyLogs() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("ApkStudio log", logBuf.toString()));
        Toast.makeText(this, "Логи скопированы (" + logBuf.length() + " симв.)", Toast.LENGTH_SHORT).show();
    }

    // ---------------- Панорама файлов + прогресс (%) ----------------
    /**
     * Обновляет панель статуса и всплывающее уведомление прогресса.
     * Вызывается из рабочих потоков — переносим на UI-поток. Обновление
     * троттлится (≈120 мс), финальные/стартовые кадры показываем всегда.
     */
    private void onProgress(final String stage, final int done, final int total, final String file) {
        final boolean edge = (total <= 0) || done <= 0 || done >= total;
        long now = System.currentTimeMillis();
        if (!edge && now - lastProgressUi < 150) return;   // троттлинг (и UI, и журнал)
        lastProgressUi = now;
        // Кольцевой буфер последних 3 файлов (для окна статуса — 3 строки).
        if (file != null && file.length() > 0) {
            synchronized (recentFiles) {
                recentFiles.addLast(file);
                while (recentFiles.size() > 3) recentFiles.removeFirst();
            }
            // Панораму файлов дублируем в журнал (лента файлов) — уже под троттлингом,
            // чтобы не забить журнал десятками тысяч строк при больших APK.
            append("  • " + stage + ": " + file
                    + (total > 0 ? "  (" + done + "/" + total + ")" : ""), "#6E7681");
        }
        ui.post(new Runnable() { public void run() {
            try {
                showStatus();
                int pct = (total > 0) ? (int) (done * 100L / total) : -1;
                statusStage.setText(stage);
                if (pct >= 0) {
                    statusPercent.setText(pct + "%");
                    statusBar.setIndeterminate(false);
                    statusBar.setProgress(pct);
                } else {
                    statusPercent.setText("…");
                    statusBar.setIndeterminate(true);
                }
                // Окно статуса: 3 строки — последние обработанные файлы.
                StringBuilder fb = new StringBuilder();
                synchronized (recentFiles) {
                    for (String f : recentFiles) {
                        if (fb.length() > 0) fb.append('\n');
                        fb.append(f);
                    }
                }
                statusFile.setText(fb.toString());
                notifyProgress(stage, done, total, pct);
            } catch (Throwable ignore) {}
        }});
    }

    private void showStatus() {
        if (statusCard != null && statusCard.getVisibility() != View.VISIBLE)
            statusCard.setVisibility(View.VISIBLE);
    }
    private void hideStatus() {
        if (statusCard != null) statusCard.setVisibility(View.GONE);
        cancelNotification();
    }

    private void notifyProgress(String stage, int done, int total, int pct) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        NOTIF_CH, "Сборка/распаковка ApkStudio",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, NOTIF_CH);
            else { b = new Notification.Builder(this); b.setPriority(Notification.PRIORITY_LOW); }
            b.setSmallIcon(android.R.drawable.stat_sys_download);
            b.setContentTitle("ApkStudio: " + stage);
            b.setOngoing(true);
            b.setOnlyAlertOnce(true);
            if (pct >= 0) {
                b.setContentText(pct + "%  (" + done + "/" + total + ")");
                b.setProgress(100, pct, false);
            } else {
                b.setContentText("выполняется…");
                b.setProgress(0, 0, true);
            }
            nm.notify(NOTIF_ID, b.build());
        } catch (Throwable ignore) {}
    }

    private void cancelNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ID);
        } catch (Throwable ignore) {}
    }

    // ---------------- Toolchain init ----------------
    private void initToolchain() {
        toolchain = new Toolchain(this, LOG);
        ops = new Operations(this, toolchain, LOG);
        new Thread(new Runnable() {
            public void run() {
                try {
                    LOG.line("Распаковка toolchain (apktool, jadx, apksig, aapt2, zipalign)…");
                    toolchain.prepare();
                    new File(Operations.DEC_DIR).mkdirs();
                    new File(Operations.OUT_DIR).mkdirs();
                    LOG.ok("Рабочая папка: " + Operations.ROOT);
                } catch (Throwable e) {
                    App.writeCrash(e);
                    LOG.err("Ошибка подготовки toolchain: " + e);
                    LOG.err(App.stackToString(e));
                }
            }
        }).start();
    }

    // ---------------- Права на хранилище ----------------
    private void requestStorageIfNeeded() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                LOG.warn("Нужен доступ ко всем файлам (MANAGE_EXTERNAL_STORAGE).");
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM_STORAGE);
        }
        // Android 13+: уведомления требуют явного разрешения — нужно для
        // всплывающего индикатора прогресса сборки/распаковки.
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_PERM_NOTIF);
                }
            } catch (Throwable ignore) {}
        }
    }

    // ---------------- Выбор APK ----------------
    private void pickApk() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/vnd.android.package-archive", "application/octet-stream", "*/*"});
        try { startActivityForResult(i, REQ_PICK_APK); }
        catch (Exception e) { toast("Не найден файловый менеджер"); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_DIR && res == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            File dir = treeUriToFile(tree);
            if (dir != null && dir.isDirectory()) {
                setPickedProject(dir);
            } else {
                LOG.err("Не удалось определить путь к папке: " + tree);
                toast("Выберите папку в основном хранилище (/storage/emulated/0/…)");
            }
            return;
        }
        if (req == REQ_PICK_APK && res == RESULT_OK && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            new Thread(new Runnable() { public void run() {
                try {
                    File apk = copyUriToCache(uri);
                    pickedApk = apk;
                    ui.post(new Runnable() { public void run() {
                        decPath.setText(pickedApk.getName());
                        decPath.setTextColor(0xFFE6EDF3);
                    }});
                    LOG.ok("Выбран APK: " + apk.getName() + " (" + apk.length() + " байт)");
                    if (pendingAction == 1) apkSmali();
                    else if (pendingAction == 2) apkJava();
                } catch (Exception e) {
                    LOG.err("Не удалось прочитать APK: " + e);
                }
            }}).start();
        }
    }

    private File copyUriToCache(Uri uri) throws Exception {
        String name = queryName(uri);
        if (name == null) name = "input.apk";
        File out = new File(getCacheDir(), name);
        InputStream in = getContentResolver().openInputStream(uri);
        OutputStream os = new FileOutputStream(out);
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        os.close(); in.close();
        return out;
    }

    private String queryName(Uri uri) {
        try {
            Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.moveToFirst()) { String n = c.getString(idx); c.close(); return n; }
                c.close();
            }
        } catch (Exception ignore) {}
        return uri.getLastPathSegment();
    }

    // ---------------- Выбор папки проекта для сборки ----------------
    private void chooseProjectDialog() {
        File dec = new File(Operations.DEC_DIR);
        File[] found = dec.listFiles(new java.io.FileFilter() {
            public boolean accept(File f) { return f.isDirectory(); }
        });
        final File[] dirs = (found != null) ? found : new File[0];

        // Пункты: [все проекты из decompiler_apk] + «Выбрать другую папку…»
        final String[] names = new String[dirs.length + 1];
        for (int i = 0; i < dirs.length; i++) {
            boolean smali = new File(dirs[i], "apktool.yml").exists();
            boolean java  = !smali && looksLikeJavaProject(dirs[i]);
            names[i] = dirs[i].getName() +
                    (smali ? "  (smali)" : java ? "  (java-проект)" : "");
        }
        names[dirs.length] = "📁  Выбрать другую папку…";

        new AlertDialog.Builder(this)
                .setTitle("Проект для сборки")
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        if (w == dirs.length) { pickProjectDir(); return; }
                        setPickedProject(dirs[w]);
                    }
                }).show();
    }

    private void setPickedProject(File dir) {
        pickedProject = dir;
        buildPath.setText(dir.getName());
        buildPath.setTextColor(0xFFE6EDF3);
        LOG.ok("Выбран проект: " + dir.getAbsolutePath());
    }

    private boolean looksLikeJavaProject(File d) {
        return new File(d, "AndroidManifest.xml").exists()
            || new File(d, "app/src/main/AndroidManifest.xml").exists()
            || new File(d, "src").isDirectory()
            || new File(d, "sources").isDirectory();
    }

    /** Выбор ЛЮБОЙ папки проекта (как в AIDE «Открыть проект»). */
    private void pickProjectDir() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(i, REQ_PICK_DIR);
        } catch (Exception e) { toast("Нет файлового менеджера для выбора папки"); }
    }

    // ---------------- Кнопки операций ----------------
    private void runApkSmali() {
        if (pickedApk == null) { pendingAction = 1; pickApk(); return; }
        apkSmali();
    }
    private void runApkJava() {
        if (pickedApk == null) { pendingAction = 2; pickApk(); return; }
        apkJava();
    }

    private void apkSmali() {
        runTask(new TaskRun() { public File go() throws Exception {
            return ops.apkToSmali(pickedApk);
        }}, "APK → Smali", true);
    }
    private void apkJava() {
        runTask(new TaskRun() { public File go() throws Exception {
            return ops.apkToJava(pickedApk);
        }}, "APK → Java", true);
    }
    private void runSmaliApk() {
        if (pickedProject == null) { toast("Сначала выберите папку проекта"); chooseProjectDialog(); return; }
        final boolean bump = cbBump.isChecked();
        runTask(new TaskRun() { public File go() throws Exception {
            return ops.smaliToApk(pickedProject, bump);
        }}, "Smali → APK", false);
    }
    private void runJavaApk() {
        if (pickedProject == null) { toast("Сначала выберите папку проекта"); chooseProjectDialog(); return; }
        final boolean bump = cbBump.isChecked();
        runTask(new TaskRun() { public File go() throws Exception {
            return ops.javaToApk(pickedProject, bump, pickedApk);
        }}, "Java → APK", false);
    }

    private interface TaskRun { File go() throws Exception; }

    private void runTask(final TaskRun t, final String title, final boolean isDecompile) {
        if (busy) { toast("Дождитесь завершения текущей операции"); return; }
        if (toolchain == null || toolchain.apktoolJar == null) { toast("Toolchain ещё готовится…"); return; }
        busy = true;
        synchronized (recentFiles) { recentFiles.clear(); }
        LOG.line("");
        LOG.cmd("▶ Старт: " + title);
        final long t0 = System.currentTimeMillis();
        new Thread(new Runnable() { public void run() {
            File result = null; Throwable error = null;
            try { result = t.go(); }
            catch (Throwable e) { error = e; App.writeCrash(e); }
            final File r = result; final Throwable err = error;
            final long ms = System.currentTimeMillis() - t0;
            busy = false;
            ui.post(new Runnable() { public void run() {
                hideStatus();     // убрать прогресс-панель и уведомление
                if (err != null) {
                    LOG.err("✖ ОШИБКА (" + title + ") за " + ms + " мс: " + err);
                    LOG.err(App.stackToString(err));
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Ошибка: " + title)
                        .setMessage("Операция не выполнена.\n\n" + err +
                            "\n\nПодробности в журнале. Нажмите «Копировать логи» вверху журнала.")
                        .setPositiveButton("OK", null).show();
                } else {
                    LOG.ok("■ Завершено: " + title + " за " + ms + " мс");
                    showDoneDialog(title, r, isDecompile);
                }
            }});
        }}).start();
    }

    // ---------------- Диалог «перейти в папку» ----------------
    private void showDoneDialog(String title, final File result, boolean isDecompile) {
        final File folder = isDecompile ? result :
                (result != null ? result.getParentFile() : new File(Operations.OUT_DIR));
        String msg = (isDecompile ? "Декомпиляция завершена.\n\nПапка:\n" : "Сборка завершена.\n\nФайл:\n")
                + (result != null ? result.getAbsolutePath() : "")
                + "\n\nОткрыть папку в системном приложении?";
        new AlertDialog.Builder(this)
            .setTitle(title + " — готово")
            .setMessage(msg)
            .setPositiveButton("Открыть папку", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) { openFolder(folder); }
            })
            .setNeutralButton("Скопировать путь", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("path",
                            result != null ? result.getAbsolutePath() : folder.getAbsolutePath()));
                    toast("Путь скопирован");
                }
            })
            .setNegativeButton("Закрыть", null)
            .show();
    }

    private void openFolder(File folder) {
        // 1) Пытаемся открыть папку через DocumentsUI (content-uri, безопасно на 7+)
        try {
            Uri docUri = folderToDocumentUri(folder);
            if (docUri != null) {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(docUri, "vnd.android.document/directory");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(i);
                return;
            }
        } catch (Exception ignore) {}
        // 2) Резерв: попросить систему выбрать файловый менеджер
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivity(Intent.createChooser(i, "Файловый менеджер"));
        } catch (Exception e2) {
            toast("Откройте вручную: " + folder.getAbsolutePath());
        }
    }

    /** Строит content-Uri для DocumentsUI из абсолютного пути в /storage/emulated/0. */
    private Uri folderToDocumentUri(File folder) {
        try {
            String path = folder.getAbsolutePath();
            String prefix = "/storage/emulated/0/";
            if (!path.startsWith(prefix)) return null;
            String rel = path.substring(prefix.length());
            String docId = "primary:" + rel;
            return android.provider.DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", docId);
        } catch (Throwable t) { return null; }
    }

    /**
     * Преобразует tree-Uri из ACTION_OPEN_DOCUMENT_TREE в реальный File-путь
     * в /storage/emulated/0. Работает для основного (primary) хранилища —
     * этого достаточно, т.к. проекты лежат там (AIDE/Download и т.п.).
     */
    private File treeUriToFile(Uri tree) {
        try {
            String docId = android.provider.DocumentsContract.getTreeDocumentId(tree);
            // docId вида "primary:AppProjects/ApkStudio"
            String[] split = docId.split(":", 2);
            String type = split[0];
            String rel = (split.length > 1) ? split[1] : "";
            if ("primary".equalsIgnoreCase(type)) {
                File base = Environment.getExternalStorageDirectory();
                return rel.isEmpty() ? base : new File(base, rel);
            }
            // не-primary (SD-карта) — пробуем /storage/<vol>/<rel>
            File alt = new File("/storage/" + type + "/" + rel);
            if (alt.exists()) return alt;
        } catch (Throwable t) {
            LOG.err("treeUriToFile: " + t);
        }
        return null;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
