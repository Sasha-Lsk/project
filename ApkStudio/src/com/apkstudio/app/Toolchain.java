package com.apkstudio.app;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Распаковка встроенной toolchain (apktool/jadx/apksig — как dex-jar,
 * aapt/aapt2/zipalign — как нативные бинарники под ABI устройства) во
 * внутреннюю папку.
 *
 * jar-ы имеют внутри classes.dex, поэтому их классы грузятся через
 * DexClassLoader (см. Apktool.java / Jadx.java / Signer.java) и вызываются
 * их БИБЛИОТЕЧНЫЕ API прямо в процессе — без main()/System.exit.
 * Нативные бинарники (aapt2/zipalign) запускаются через runNative().
 */
public class Toolchain {

    public interface Log {
        void line(String s);        // обычная строка
        void ok(String s);
        void err(String s);
        void warn(String s);
        void cmd(String s);

        /**
         * Прогресс обработки файлов (панорама + уведомление с %).
         *
         * @param stage название этапа («Распаковка toolchain», «ecj», «d8»,
         *              «Упаковка APK», «apktool» …)
         * @param done  сколько файлов уже обработано
         * @param total всего файлов (0 или меньше — прогресс неопределённый)
         * @param file  имя текущего файла (может быть null)
         */
        void progress(String stage, int done, int total, String file);
    }

    private final Context ctx;
    private final Log log;
    private final File home;        // filesDir/toolchain

    public File apktoolJar, jadxJar, apksigJar, keyPk8, keyPem;
    // Toolchain для сборки APK ИЗ ПАПКИ ИСХОДНИКОВ (java/res/manifest) без
    // оригинального APK — как в AIDE: ecj (компилятор java→class),
    // d8 (class→dex), android.jar (SDK-заглушки для classpath).
    public File ecjJar, d8Jar, androidJar;
    public File aapt, aapt2, zipalign;
    public File frameworkDir;       // куда apktool кладёт framework

    public Toolchain(Context ctx, Log log) {
        this.ctx = ctx;
        this.log = log;
        this.home = new File(ctx.getFilesDir(), "toolchain");
    }

    private static String primaryAbi() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis != null && abis.length > 0) return abis[0];
        return "arm64-v8a";
    }

    private static String binDirFor(String abi) {
        // сопоставление ABI устройства с папкой встроенных бинарников
        if (abi.startsWith("arm64")) return "arm64-v8a";
        if (abi.startsWith("armeabi")) return "armeabi-v7a";
        if (abi.equals("x86_64")) return "x86_64";
        if (abi.startsWith("x86")) return "x86";
        return "arm64-v8a";
    }

    /** Распаковать всё что нужно. Идемпотентно — распаковывает один раз. */
    public void prepare() throws Exception {
        home.mkdirs();
        frameworkDir = new File(home, "framework");
        frameworkDir.mkdirs();

        // apktool/aapt2 создают временные файлы через java.io.tmpdir. На Android
        // это /tmp (недоступно) — перенаправляем во внутреннюю папку приложения.
        File tmp = new File(home, "tmp");
        tmp.mkdirs();
        System.setProperty("java.io.tmpdir", tmp.getAbsolutePath());

        String abi = primaryAbi();
        String bin = binDirFor(abi);
        log.line("Устройство ABI: " + abi + "  →  бинарники: " + bin);

        // Список ассетов toolchain — для панорамы распаковки с прогрессом в %.
        String[][] assets = new String[][] {
            {"engine/apktool.jar",        "apktool.jar"},
            {"engine/jadx.jar",           "jadx.jar"},
            {"engine/apksig.jar",         "apksig.jar"},
            {"engine/testkey.pk8",        "testkey.pk8"},
            {"engine/testkey.x509.pem",   "testkey.x509.pem"},
            {"engine/ecj.jar",            "ecj.jar"},
            {"engine/d8.jar",             "d8.jar"},
            {"engine/android.jar",        "android.jar"},
        };
        int total = assets.length;
        java.util.Map<String, File> out = new java.util.HashMap<String, File>();
        log.progress("Распаковка toolchain", 0, total, null);
        for (int i = 0; i < total; i++) {
            log.progress("Распаковка toolchain", i, total, assets[i][1]);
            out.put(assets[i][1], copyAsset(assets[i][0], assets[i][1], false));
        }
        log.progress("Распаковка toolchain", total, total, null);

        apktoolJar = out.get("apktool.jar");
        jadxJar    = out.get("jadx.jar");
        apksigJar  = out.get("apksig.jar");
        keyPk8     = out.get("testkey.pk8");
        keyPem     = out.get("testkey.x509.pem");
        // Компоненты сборки из исходников (Java → APK без оригинала).
        // ecj.jar и d8.jar — dex-jar-ы (грузятся DexClassLoader-ом),
        // android.jar — обычный jar с .class-заглушками SDK ТОЛЬКО как
        // classpath для ecj/d8 (в ART не загружается).
        ecjJar     = out.get("ecj.jar");
        d8Jar      = out.get("d8.jar");
        androidJar = out.get("android.jar");

        // Нативные бинарники: на Android 10+ (W^X) их МОЖНО запускать только из
        // nativeLibraryDir. Поэтому они упакованы в APK как libaapt.so/libaapt2.so/
        // libzipalign.so (папка libs/<abi>) и лежат в nativeLibraryDir. Fallback на
        // распаковку из assets — только для Android ≤ 9, где exec из filesDir разрешён.
        aapt     = resolveBinary("libaapt.so",     bin, "aapt");
        aapt2    = resolveBinary("libaapt2.so",    bin, "aapt2");
        zipalign = resolveBinary("libzipalign.so", bin, "zipalign");

        log.ok("Toolchain готова: " + home.getAbsolutePath());
        log.line("aapt2: " + (aapt2 != null ? aapt2.getAbsolutePath() : "НЕ НАЙДЕН"));
    }

    /**
     * Ищет нативный бинарник в nativeLibraryDir (lib*.so). Это единственный
     * путь, откуда Android (в т.ч. 10+) разрешает exec. Бинарники попадают туда
     * из папки libs/<abi>/ проекта при сборке APK в AIDE.
     */
    private File resolveBinary(String soName, String abiDir, String plainName) {
        try {
            String nld = ctx.getApplicationInfo().nativeLibraryDir;
            if (nld != null) {
                File so = new File(nld, soName);
                if (so.exists() && so.canRead()) {
                    so.setExecutable(true, false);
                    return so;
                }
                log.err(soName + " не найден в nativeLibraryDir (" + nld + ").");
                log.err("Проверьте, что папка libs/" + abiDir + "/" + soName +
                        " попала в APK при сборке в AIDE.");
            }
        } catch (Throwable e) {
            log.err("Не удалось найти " + plainName + ": " + e);
        }
        return null;
    }

    private File copyAsset(String assetPath, String outName, boolean executable) throws Exception {
        File out = new File(home, outName);
        // Перезаписываем, если файла нет ИЛИ его размер отличается от asset —
        // иначе после обновления приложения на диске останется старая (битая)
        // версия jar/бинарника (например, ecj.jar без ресурсов).
        long assetLen = assetLength(assetPath);
        boolean needCopy = !out.exists() || out.length() == 0
                || (assetLen > 0 && out.length() != assetLen);
        if (needCopy) {
            InputStream in = ctx.getAssets().open(assetPath);
            OutputStream os = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.close();
            in.close();
            log.line("Распакован: " + outName + " (" + out.length() + " байт)");
        }
        if (executable) {
            out.setReadable(true, false);
            out.setExecutable(true, false);
        }
        return out;
    }

    /** Размер asset в байтах (для распознавания обновления). 0 — не удалось. */
    private long assetLength(String assetPath) {
        java.io.InputStream in = null;
        try {
            in = ctx.getAssets().open(assetPath);
            long total = 0; byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) total += n;
            return total;
        } catch (Throwable t) {
            return 0;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignore) {}
        }
    }

    // ---------- Запуск нативных бинарников (aapt2/zipalign) ----------

    /**
     * Колбэк на каждую строку живого вывода нативного бинарника (aapt2 -v и т.п.).
     * Позволяет вызывающей стороне показывать ПОФАЙЛОВЫЙ прогресс в реальном
     * времени (какой ресурс сейчас компилируется/линкуется в resources.arsc).
     */
    public interface LineCB { void onLine(String line); }

    public int runNative(File tool, String[] args, StringBuilder outCollector) throws Exception {
        return runNative(tool, args, outCollector, null);
    }

    /**
     * То же, что runNative(tool,args,out), но с колбэком на КАЖДУЮ строку вывода.
     * Строки по-прежнему пишутся в журнал; колбэк вызывается дополнительно (может
     * быть null). Ошибка внутри колбэка не прерывает чтение вывода процесса.
     */
    public int runNative(File tool, String[] args, StringBuilder outCollector, LineCB cb) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<String>();
        cmd.add(tool.getAbsolutePath());
        for (String a : args) cmd.add(a);
        log.cmd("$ " + tool.getName() + " " + join(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        String nld = "";
        try { nld = ctx.getApplicationInfo().nativeLibraryDir; } catch (Throwable ignore) {}
        pb.environment().put("LD_LIBRARY_PATH", home.getAbsolutePath() + ":" + nld);
        pb.environment().put("TMPDIR", home.getAbsolutePath());
        pb.directory(home);
        Process p = pb.start();

        InputStream is = p.getInputStream();
        byte[] buf = new byte[8192];
        int n;
        StringBuilder lineBuf = new StringBuilder();
        while ((n = is.read(buf)) > 0) {
            for (int i = 0; i < n; i++) {
                char ch = (char) buf[i];
                if (ch == '\n') {
                    String s = lineBuf.toString(); lineBuf.setLength(0);
                    if (outCollector != null) outCollector.append(s).append('\n');
                    if (cb != null) { try { cb.onLine(s); } catch (Throwable ignore) {} }
                    if (s.toLowerCase().contains("error")) log.err(s); else log.line(s);
                } else if (ch != '\r') lineBuf.append(ch);
            }
        }
        if (lineBuf.length() > 0) {
            String s = lineBuf.toString();
            if (cb != null) { try { cb.onLine(s); } catch (Throwable ignore) {} }
            log.line(s);
        }
        int code = p.waitFor();
        return code;
    }

    private static String join(String[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(' '); sb.append(a[i]); }
        return sb.toString();
    }
}
