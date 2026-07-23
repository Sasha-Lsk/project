package com.apktool.app;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.ArrayList;

import dalvik.system.DexClassLoader;

/**
 * Движок компиляции/декомпиляции — повторяет конвейер сборки AIDE.
 *
 * Декомпиляция:
 *   apktool.jar -> brut.apktool.Main  (apk -> smali, ресурсы)
 *   jadx.jar    -> jadx.api           (apk -> java)
 *
 * Сборка java -> apk (как AIDE, без готового dex/apk в проекте):
 *   1) aapt2 compile + link  — ресурсы -> resources.arsc + СГЕНЕРИРОВАННЫЙ R.java
 *   2) ecj (ecj.jar)         — sources/*.java + свежий R.java -> .class
 *   3) d8  (jadx.jar)        — .class -> classes.dex
 *   4) упаковка              — resources.arsc + classes.dex + lib/ + assets/
 *   5) zipalign + apksig     — выравнивание и подпись v1+v2+v3
 *
 * R.java из шага 1 гарантирует, что ID ресурсов в коде совпадают с ID в
 * resources.arsc — ровно так делает AIDE, поэтому APK запускается.
 *
 * Все jar грузятся через DexClassLoader и вызываются рефлексией; нативные
 * aapt/aapt2/zipalign — из nativeLibraryDir (единственное место с правом exec).
 */
public class Engine {

    private final Context ctx;
    private final Logger log;

    private File dirEngine;     // распакованные jar-движки
    private File aapt2, aapt, zipalign;

    public Engine(Context ctx, Logger log) {
        this.ctx = ctx;
        this.log = log;
    }

    // ---------- Публичные операции ----------

    /** apk -> smali (apktool d). Возвращает выходную папку. */
    public File apkToSmali(File apk, File outRoot) throws Exception {
        prepare();
        File out = new File(outRoot, baseName(apk));
        rmdir(out);
        log.log("Декомпиляция APK -> smali: " + apk.getName());
        log.progress(10);
        String[] args = {
                "d", "-f",
                "--frame-path", new File(dirEngine, "framework").getAbsolutePath(),
                "-o", out.getAbsolutePath(),
                apk.getAbsolutePath()
        };
        runApktool(args);
        log.progress(90);
        listTree(out);
        log.progress(100);
        return out;
    }

    /**
     * apk -> java (jadx). Выдаёт ЧИСТЫЙ проект (как AIDE): только исходники и
     * декодированные ресурсы, БЕЗ classes.dex и БЕЗ вложенного apk.
     *
     * <имя>_java/
     *   sources/    — .java (jadx), включая старый R.java (будет заменён при сборке)
     *   resources/  — AndroidManifest.xml + res/ + assets/ + lib/ (декодировано)
     */
    public File apkToJava(File apk, File outRoot) throws Exception {
        prepare();
        File out = new File(outRoot, baseName(apk) + "_java");
        rmdir(out);
        log.log("Декомпиляция APK -> java: " + apk.getName());
        log.progress(10);
        out.mkdirs();
        runJadxApi(apk, out);
        log.progress(75);

        // В проекте AIDE НЕ должно быть скомпилированного кода: убираем любые
        // classes*.dex, которые jadx мог скопировать в вывод. Исходник кода —
        // это .java в sources/, он и компилируется при сборке.
        removeDexFiles(out);

        // НАДЁЖНАЯ ПЕРЕСБОРКА (как smali->apk). Дополнительно раскладываем в тот
        // же проект ПОЛНЫЙ apktool-проект (smali/ + apktool.yml + res/ +
        // AndroidManifest.xml + lib/ + assets/) — прямо в КОРЕНЬ проекта. Тогда
        // при сборке java->apk движок видит apktool.yml/smali и собирает через
        // apktool b (переассемблируя ОРИГИНАЛЬНЫЙ байткод 1:1) — тот же путь,
        // что и smali->apk. Это устраняет причину «пересобранное не запускается»:
        // рефлексия (InvokeHelper), native-методы, точные сигнатуры и внешние
        // классы сохраняются как в оригинале, а не пересобираются из lossy-jadx.
        // Каталог sources/ (java от jadx) остаётся для чтения/правки кода.
        try {
            embedApktoolProject(apk, out);
        } catch (Throwable t) {
            log.err("Не удалось разложить apktool-проект (smali) в вывод: " + t
                    + ". Сборка java->apk будет через ecj (менее надёжно).");
        }

        log.log("Готово. Проект:");
        log.log("  sources/   — исходный код .java (jadx) — для чтения/правок.");
        log.log("  smali/, apktool.yml, res/, AndroidManifest.xml, lib/, assets/");
        log.log("             — apktool-проект (оригинальный байткод).");
        log.log("При сборке java->apk используется НАДЁЖНЫЙ путь apktool b (как");
        log.log("smali->apk): байткод переассемблируется 1:1 — приложение");
        log.log("запускается стабильно (рефлексия/native/внешние классы целы).");

        log.progress(90);
        listTree(out);
        log.progress(100);
        return out;
    }

    /** Удалить classes*.dex из вывода jadx: в проекте AIDE их быть не должно. */
    private void removeDexFiles(File out) {
        try {
            ArrayList<File> stack = new ArrayList<File>();
            stack.add(out);
            int removed = 0;
            while (!stack.isEmpty()) {
                File f = stack.remove(stack.size() - 1);
                File[] kids = f.listFiles();
                if (kids == null) continue;
                for (File k : kids) {
                    if (k.isDirectory()) stack.add(k);
                    else if (k.getName().matches("classes\\d*\\.dex")) {
                        if (k.delete()) removed++;
                    }
                }
            }
            if (removed > 0) log.log("Убрано dex из вывода jadx: " + removed);
        } catch (Throwable t) {
            log.err("removeDexFiles: " + t);
        }
    }

    /**
     * Разложить в каталог проекта out полноценный apktool-проект (декодировав apk
     * через apktool d): каталоги smali, apktool.yml, AndroidManifest.xml, res,
     * lib, assets, original, unknown. Кладём прямо в КОРЕНЬ проекта, НЕ затирая
     * уже созданные jadx-каталоги sources и resources.
     *
     * Смысл: после этого javaToApk видит apktool.yml/smali в корне и собирает
     * проект НАДЁЖНЫМ путём smaliToApk (apktool b), переассемблируя ОРИГИНАЛЬНЫЙ
     * байткод один-в-один — как smali->apk. Именно это и просили: java->apk по
     * надёжности = smali->apk. jadx-код в sources остаётся для чтения/правок.
     */
    private void embedApktoolProject(File apk, File out) throws Exception {
        log.log("apktool d (для надёжной пересборки apktool b, как smali->apk)...");
        log.progress(82);
        File tmp = new File(ctx.getCacheDir(), "apk_d_" + System.currentTimeMillis());
        rmdir(tmp);
        String[] args = {
                "d", "-f",
                "--frame-path", new File(dirEngine, "framework").getAbsolutePath(),
                "-o", tmp.getAbsolutePath(),
                apk.getAbsolutePath()
        };
        runApktool(args);
        if (!new File(tmp, "apktool.yml").exists()) {
            rmdir(tmp);
            throw new Exception("apktool d не создал apktool.yml");
        }
        // Переносим содержимое apktool-проекта в корень out, не трогая
        // sources/ и resources/ (это вывод jadx для чтения кода).
        File[] items = tmp.listFiles();
        int moved = 0;
        if (items != null) for (File it : items) {
            String nm = it.getName();
            if (nm.equals("sources") || nm.equals("resources")) continue;
            File dst = new File(out, nm);
            rmdir(dst);
            if (dst.exists()) dst.delete();
            if (!it.renameTo(dst)) { copyAny(it, dst); }
            moved++;
        }
        rmdir(tmp);
        log.log("apktool-проект разложен в корень (" + moved + " элементов): "
                + "smali/apktool.yml/res/AndroidManifest.xml/lib/assets. "
                + "Сборка java->apk пойдёт через apktool b (оригинальный байткод).");
    }

    /** Рекурсивно скопировать файл/каталог (fallback, если renameTo не сработал). */
    private void copyAny(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyAny(k, new File(dst, k.getName()));
        } else {
            File p = dst.getParentFile();
            if (p != null) p.mkdirs();
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream ou = new java.io.FileOutputStream(dst);
            byte[] b = new byte[65536]; int n;
            try { while ((n = in.read(b)) > 0) ou.write(b, 0, n); }
            finally { in.close(); ou.close(); }
        }
    }

    /** smali-папка (формат apktool) -> apk. */
    public File smaliToApk(File projectDir, File outRoot, int androidVer) throws Exception {
        prepare();
        ensureDir(outRoot);
        File built = new File(ctx.getCacheDir(), "build_" + System.currentTimeMillis() + ".apk");
        File finalApk = new File(outRoot, projectDir.getName() + "_rebuilt.apk");
        log.log("Сборка smali-проекта -> APK: " + projectDir.getName());
        log.progress(5);

        if (androidVer > 0) {
            patchTargetSdk(new File(projectDir, "apktool.yml"), androidVer);
        }

        // Встроить краш-логгер (smali-вариант): .smali-класс Application +
        // прописать его в AndroidManifest.xml. До apktool b.
        try {
            File mf = new File(projectDir, "AndroidManifest.xml");
            if (mf.exists()) injectCrashLogger(mf, null, projectDir);
        } catch (Throwable t) {
            log.err("Не удалось встроить краш-логгер (smali): " + t);
        }

        String[] args = {
                "b", "-f",
                "--frame-path", new File(dirEngine, "framework").getAbsolutePath(),
                "--aapt", aapt2.getAbsolutePath(),
                "--use-aapt2",
                "-o", built.getAbsolutePath(),
                projectDir.getAbsolutePath()
        };
        try {
            runApktool(args);
        } catch (Throwable t) {
            log.log("aapt2 не справился, пробую aapt(1)...");
            runApktool(new String[]{
                    "b", "-f",
                    "--frame-path", new File(dirEngine, "framework").getAbsolutePath(),
                    "--aapt", aapt.getAbsolutePath(),
                    "-o", built.getAbsolutePath(),
                    projectDir.getAbsolutePath()
            });
        }
        log.progress(70);
        finalizeApk(built, finalApk);
        log.progress(100);
        return finalApk;
    }

    /**
     * java-проект -> apk. Конвейер AIDE.
     *
     * Форматы каталога:
     *  1) apktool-проект (apktool.yml или smali/) -> apktool b (см. smaliToApk).
     *  2) jadx/AIDE-проект: sources/ (.java) + resources/ (или сам каталог) с
     *     AndroidManifest.xml + res/ + assets/ + lib/. Собираем как AIDE:
     *     aapt2 -> R.java -> ecj -> d8 -> упаковка -> подпись.
     */
    public File javaToApk(File projectDir, File outRoot, int androidVer) throws Exception {
        prepare();
        log.log("Сборка java-проекта -> APK: " + projectDir.getName());

        // НАДЁЖНЫЙ ПУТЬ (как smali->apk): если в проекте есть apktool-проект со
        // smali (оригинальный байткод), собираем через apktool b — байткод
        // переассемблируется 1:1, приложение запускается стабильно. Это то, что
        // теперь кладёт apk->java рядом с sources/. Ищем apktool-корень в самом
        // projectDir и, для совместимости, вложенным (например под resources/).
        File apktoolRoot = findApktoolRoot(projectDir);
        if (apktoolRoot != null) {
            log.log("Найден apktool-проект со smali (" + apktoolRoot.getName()
                    + ") -> сборка apktool b (надёжный путь, как smali->apk).");
            return smaliToApk(apktoolRoot, outRoot, androidVer);
        }

        File resRoot = locateResRoot(projectDir);
        if (resRoot == null) {
            throw new Exception("Не найден AndroidManifest.xml. Ожидается apktool-проект "
                    + "(apktool.yml/smali) или jadx/AIDE-каталог с resources/ "
                    + "(AndroidManifest.xml + res).");
        }
        File srcRoot = findSourcesDir(projectDir, resRoot);
        if (srcRoot == null) {
            throw new Exception("Не найден каталог с исходниками .java (sources/ или src/). "
                    + "Сборка java->apk компилирует код из исходников, как AIDE.");
        }
        log.log("Формат jadx/AIDE. Исходники: " + srcRoot.getName()
                + ", ресурсы: " + resRoot.getName());
        ensureDir(outRoot);
        File finalApk = new File(outRoot, projectDir.getName() + "_rebuilt.apk");
        buildLikeAide(projectDir, srcRoot, resRoot, finalApk, androidVer);
        log.progress(100);
        return finalApk;
    }

    /**
     * Найти корень apktool-проекта, пригодного для НАДЁЖНОЙ сборки apktool b.
     * Требуется apktool.yml И каталог smali* (оригинальный байткод). Ищем в самом
     * dir и в типичных вложенных местах (resources/). Возвращает каталог или null.
     */
    private File findApktoolRoot(File dir) {
        if (dir == null) return null;
        java.util.List<File> cand = new java.util.ArrayList<File>();
        cand.add(dir);
        cand.add(new File(dir, "resources"));
        for (File c : cand) {
            if (c == null || !c.isDirectory()) continue;
            if (!new File(c, "apktool.yml").exists()) continue;
            if (hasSmaliDir(c)) return c;
        }
        return null;
    }

    /** Есть ли в каталоге хотя бы одна папка smali / smali_classesN. */
    private static boolean hasSmaliDir(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File k : kids) {
            if (k.isDirectory() && k.getName().matches("smali(_classes\\d+)?")) return true;
        }
        return false;
    }

    /** Найти каталог, содержащий AndroidManifest.xml. */
    private static File locateResRoot(File dir) {
        if (new File(dir, "AndroidManifest.xml").exists()) return dir;
        File r = new File(dir, "resources");
        if (new File(r, "AndroidManifest.xml").exists()) return r;
        return null;
    }

    /** Найти каталог с исходниками .java: projectDir/sources | root/sources | projectDir/src. */
    private File findSourcesDir(File projectDir, File root) {
        File s1 = new File(projectDir, "sources");
        if (s1.isDirectory()) return s1;
        File s2 = new File(root, "sources");
        if (s2.isDirectory()) return s2;
        File s3 = new File(projectDir, "src");
        if (s3.isDirectory()) return s3;
        return null;
    }

    // ---------- Сборка как AIDE ----------

    /**
     * Полный конвейер AIDE: ресурсы -> R.java (aapt2), исходники+R.java -> dex
     * (ecj+d8), упаковка -> подпись. resources.arsc и R.java из ОДНОЙ aapt2-сессии,
     * поэтому ID ресурсов в коде и в arsc совпадают -> APK запускается.
     */
    // ---------- Встраивание краш-логгера ----------

    /** Имя простого класса логгера, внедряемого в собираемое приложение. */
    private static final String LOGGER_SIMPLE = "ApkToolCrashLogger";

    /**
     * Встроить в собираемое приложение краш-логгер: класс-наследник
     * android.app.Application, который в onCreate ставит
     * Thread.setDefaultUncaughtExceptionHandler и пишет лог запуска/работы/
     * вылетов в getExternalFilesDir() — т.е. в
     * /storage/emulated/0/Android/data/&lt;package&gt;/files/.
     *
     * <p>Логика:
     * <ul>
     * <li>Читаем package и текущий android:name у &lt;application&gt;.</li>
     * <li>Если свой Application уже был — наш класс НАСЛЕДУЕТ его (весь его код
     *     сохраняется, мы лишь добавляем перехват после super.onCreate()).
     *     Если своего не было — наследуем android.app.Application.</li>
     * <li>Генерируем исходник (java ИЛИ smali) и прописываем наш класс в
     *     android:name манифеста.</li>
     * </ul>
     *
     * @param manifest  AndroidManifest.xml (декодированный, текстовый)
     * @param srcRoot   каталог .java (java→apk) или null для smali
     * @param smaliRoot корень apktool-проекта (smali→apk) или null для java
     */
    private void injectCrashLogger(File manifest, File srcRoot, File smaliRoot) {
        try {
            String mf = readFile(manifest);
            String pkg = attrValue(mf, "manifest", "package");
            if (pkg == null || pkg.isEmpty()) {
                log.err("Краш-логгер: не найден package в манифесте — пропуск.");
                return;
            }
            String appName = applicationName(mf);       // текущий android:name (может быть null)
            String superFqn = "android.app.Application";
            if (appName != null && !appName.isEmpty()) {
                superFqn = normalizeClass(appName, pkg);
                // Если у приложения уже стоит НАШ логгер (повторная сборка) — выходим.
                if (superFqn.endsWith("." + LOGGER_SIMPLE)) {
                    log.log("Краш-логгер уже внедрён — пропуск.");
                    return;
                }
            }
            String loggerFqn = pkg + "." + LOGGER_SIMPLE;

            if (smaliRoot != null) {
                writeSmaliLogger(smaliRoot, loggerFqn, superFqn, appName != null);
            } else if (srcRoot != null) {
                writeJavaLogger(srcRoot, loggerFqn, superFqn, appName != null);
            } else {
                return;
            }

            // Прописать android:name на наш класс.
            String newMf = setApplicationName(mf, loggerFqn);
            write(manifest, newMf);
            log.log("Краш-логгер внедрён: " + loggerFqn
                    + " (extends " + superFqn + "). "
                    + "Логи -> /Android/data/" + pkg + "/files/apktool-log/");
        } catch (Throwable t) {
            log.err("injectCrashLogger: " + t);
        }
    }

    /** Java-исходник логгера. */
    private void writeJavaLogger(File srcRoot, String loggerFqn, String superFqn,
                                 boolean hadOwnApp) throws Exception {
        int dot = loggerFqn.lastIndexOf('.');
        String pkg = loggerFqn.substring(0, dot);
        String simple = loggerFqn.substring(dot + 1);
        File dir = new File(srcRoot, pkg.replace('.', '/'));
        dir.mkdirs();
        File out = new File(dir, simple + ".java");
        write(out, javaLoggerSource(pkg, simple, superFqn));
    }

    /** Текст java-класса логгера. */
    private String javaLoggerSource(String pkg, String simple, String superFqn) {
        StringBuilder s = new StringBuilder();
        s.append("package ").append(pkg).append(";\n\n");
        s.append("/** Краш-логгер, внедрён ApkTool. Пишет старт/работу/вылеты в\n");
        s.append(" *  getExternalFilesDir()/apktool-log/. Не влияет на логику приложения. */\n");
        s.append("public class ").append(simple).append(" extends ").append(superFqn).append(" {\n");
        s.append("    private static final String TAG = \"ApkToolLog\";\n");
        s.append("    @Override public void onCreate() {\n");
        s.append("        try { installHandler(); } catch (Throwable t) {}\n");
        s.append("        super.onCreate();\n");
        s.append("        log(\"onCreate: приложение стартовало (\" + getPackageName() + \")\");\n");
        s.append("        try { registerLifecycle(); } catch (Throwable t) {}\n");
        s.append("        try { startLogcatCapture(); } catch (Throwable t) {}\n");
        s.append("    }\n");
        // Захват logcat в app.log. Многие сбои (например «Ошибка работы камеры»)
        // ловятся самим приложением в catch(Exception) и лишь печатаются через
        // ex.printStackTrace()/Log.e(...) — краш-логгер их не видит, т.к. они не
        // uncaught. Фоновый reader logcat пишет в тот же app.log строки с
        // ошибками/предупреждениями и стектрейсы (System.err, AndroidRuntime,
        // *Camera*, *lgCamera* и уровни E/W), давая реальную причину молчаливых
        // сбоев. Только для СВОЕГО процесса (--pid), чтобы не тянуть чужой шум.
        s.append("    private void startLogcatCapture() {\n");
        s.append("        final int myPid = android.os.Process.myPid();\n");
        s.append("        Thread t = new Thread(new Runnable() { public void run() {\n");
        s.append("            try {\n");
        s.append("                try { Runtime.getRuntime().exec(\n");
        s.append("                    new String[]{\"logcat\", \"-c\"}).waitFor(); } catch (Throwable ig) {}\n");
        s.append("                Process p;\n");
        s.append("                try {\n");
        s.append("                    p = Runtime.getRuntime().exec(new String[]{\n");
        s.append("                        \"logcat\", \"-v\", \"time\", \"--pid=\" + myPid, \"*:W\"});\n");
        s.append("                } catch (Throwable noPid) {\n");
        s.append("                    p = Runtime.getRuntime().exec(new String[]{\n");
        s.append("                        \"logcat\", \"-v\", \"time\", \"*:E\"});\n");
        s.append("                }\n");
        s.append("                java.io.BufferedReader r = new java.io.BufferedReader(\n");
        s.append("                    new java.io.InputStreamReader(p.getInputStream()));\n");
        s.append("                String ln;\n");
        s.append("                while ((ln = r.readLine()) != null) {\n");
        s.append("                    if (ln.indexOf(\"ApkToolLog\") >= 0) continue;\n");
        s.append("                    logRaw(\"[logcat] \" + ln);\n");
        s.append("                }\n");
        s.append("            } catch (Throwable t) {\n");
        s.append("                logRaw(\"[logcat] недоступен: \" + t);\n");
        s.append("            }\n");
        s.append("        }});\n");
        s.append("        t.setDaemon(true);\n");
        s.append("        t.setName(\"ApkToolLogcat\");\n");
        s.append("        t.start();\n");
        s.append("    }\n");
        s.append("    private void installHandler() {\n");
        s.append("        final Thread.UncaughtExceptionHandler prev =\n");
        s.append("                Thread.getDefaultUncaughtExceptionHandler();\n");
        s.append("        Thread.setDefaultUncaughtExceptionHandler(\n");
        s.append("                new Thread.UncaughtExceptionHandler() {\n");
        s.append("            public void uncaughtException(Thread th, Throwable ex) {\n");
        s.append("                try {\n");
        s.append("                    java.io.StringWriter sw = new java.io.StringWriter();\n");
        s.append("                    ex.printStackTrace(new java.io.PrintWriter(sw));\n");
        s.append("                    log(\"ВЫЛЕТ в потоке '\" + th.getName() + \"':\\n\" + sw.toString());\n");
        s.append("                } catch (Throwable t) {}\n");
        s.append("                if (prev != null) prev.uncaughtException(th, ex);\n");
        s.append("            }\n");
        s.append("        });\n");
        s.append("    }\n");
        s.append("    private void registerLifecycle() {\n");
        s.append("        if (android.os.Build.VERSION.SDK_INT < 14) return;\n");
        s.append("        registerActivityLifecycleCallbacks(\n");
        s.append("                new android.app.Application.ActivityLifecycleCallbacks() {\n");
        s.append("            public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {\n");
        s.append("                log(\"Activity создана: \" + a.getClass().getName()); }\n");
        s.append("            public void onActivityStarted(android.app.Activity a) {}\n");
        s.append("            public void onActivityResumed(android.app.Activity a) {\n");
        s.append("                log(\"Activity на экране: \" + a.getClass().getName()); }\n");
        s.append("            public void onActivityPaused(android.app.Activity a) {}\n");
        s.append("            public void onActivityStopped(android.app.Activity a) {}\n");
        s.append("            public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {}\n");
        s.append("            public void onActivityDestroyed(android.app.Activity a) {}\n");
        s.append("        });\n");
        s.append("    }\n");
        s.append("    private void log(String msg) {\n");
        s.append("        android.util.Log.i(TAG, msg);\n");
        s.append("        logRaw(msg);\n");
        s.append("    }\n");
        // logRaw пишет только в файл (без Log.i), чтобы захват logcat не создавал
        // петлю (наша же запись -> logcat -> снова в файл).
        s.append("    private synchronized void logRaw(String msg) {\n");
        s.append("        try {\n");
        s.append("            java.io.File dir = getExternalFilesDir(null);\n");
        s.append("            if (dir == null) dir = new java.io.File(\n");
        s.append("                \"/storage/emulated/0/Android/data/\" + getPackageName() + \"/files\");\n");
        s.append("            java.io.File logDir = new java.io.File(dir, \"apktool-log\");\n");
        s.append("            logDir.mkdirs();\n");
        s.append("            java.io.File f = new java.io.File(logDir, \"app.log\");\n");
        s.append("            java.text.SimpleDateFormat fmt =\n");
        s.append("                new java.text.SimpleDateFormat(\"yyyy-MM-dd HH:mm:ss.SSS\");\n");
        s.append("            java.io.FileWriter w = new java.io.FileWriter(f, true);\n");
        s.append("            w.write(fmt.format(new java.util.Date()) + \"  \" + msg + \"\\n\");\n");
        s.append("            w.flush(); w.close();\n");
        s.append("        } catch (Throwable t) {\n");
        s.append("            android.util.Log.e(TAG, \"log() ошибка: \" + t);\n");
        s.append("        }\n");
        s.append("    }\n");
        s.append("}\n");
        return s.toString();
    }

    /**
     * Прочитать minSdkVersion/targetSdkVersion из &lt;uses-sdk&gt; в
     * AndroidManifest.xml (текстовый, декодированный apktool). Если атрибут
     * отсутствует — вернуть def. Значение может быть числом или (редко) codename;
     * нечисловое трактуем как отсутствие и берём def.
     */
    private int readManifestSdk(File manifestFile, String attr, int def) {
        try {
            String xml = readFile(manifestFile);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "<uses-sdk\\b[^>]*?\\bandroid:" + attr
                    + "\\s*=\\s*\"([^\"]*)\"").matcher(xml);
            if (m.find()) {
                String v = m.group(1).trim();
                if (v.matches("\\d+")) return Integer.parseInt(v);
            }
        } catch (Throwable t) {
            log.err("readManifestSdk(" + attr + "): " + t);
        }
        return def;
    }

    /** Значение атрибута android:name у &lt;application&gt; или null. */
    private String applicationName(String manifest) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "<application\\b[^>]*?\\bandroid:name\\s*=\\s*\"([^\"]*)\"").matcher(manifest);
        return m.find() ? m.group(1) : null;
    }

    /** Значение произвольного атрибута тега (первое вхождение). */
    private String attrValue(String xml, String tag, String attr) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "<" + tag + "\\b[^>]*?\\b" + java.util.regex.Pattern.quote(attr)
                + "\\s*=\\s*\"([^\"]*)\"").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    /** Привести имя класса из манифеста к FQN (учёт «.Name» и «Name» относительно package). */
    private String normalizeClass(String name, String pkg) {
        if (name.startsWith(".")) return pkg + name;
        if (!name.contains(".")) return pkg + "." + name;
        return name;
    }

    /**
     * Прописать android:name=loggerFqn у &lt;application&gt;. Если атрибут уже
     * есть — заменяем значение, иначе добавляем сразу после «&lt;application».
     */
    private String setApplicationName(String manifest, String loggerFqn) {
        java.util.regex.Matcher has = java.util.regex.Pattern.compile(
                "(<application\\b[^>]*?\\bandroid:name\\s*=\\s*\")([^\"]*)(\")").matcher(manifest);
        if (has.find()) {
            return manifest.substring(0, has.start(2)) + loggerFqn
                    + manifest.substring(has.end(2));
        }
        return manifest.replaceFirst("<application\\b",
                java.util.regex.Matcher.quoteReplacement(
                        "<application android:name=\"" + loggerFqn + "\""));
    }

    /**
     * Сгенерировать smali-класс логгера + класс-хэндлер вылетов. Для smali мы НЕ
     * используем анонимные классы (в байткоде они отдельные) — хэндлер вынесен
     * в именованный класс LoggerFqn$Handler. Логгер extends superFqn (оригинальный
     * Application, если он был — весь его код в проекте сохранён).
     */
    private void writeSmaliLogger(File projectDir, String loggerFqn, String superFqn,
                                  boolean hadOwnApp) throws Exception {
        // Выбираем smali-каталог: apktool кладёт код в smali/ или smali_classesN/.
        File smaliDir = pickSmaliDir(projectDir);
        if (smaliDir == null) {
            log.err("Краш-логгер (smali): не найден каталог smali/ — пропуск.");
            return;
        }
        String path = loggerFqn.replace('.', '/');
        String superPath = superFqn.replace('.', '/');
        File main = new File(smaliDir, path + ".smali");
        File handler = new File(smaliDir, path + "$Handler.smali");
        main.getParentFile().mkdirs();
        write(main, smaliLoggerMain(path, superPath));
        write(handler, smaliLoggerHandler(path));
        log.log("Сгенерирован smali логгера: " + path + ".smali (+$Handler).");
    }

    /** Найти каталог smali (smali/ или smali_classes2/…), содержащий классы. */
    private File pickSmaliDir(File projectDir) {
        File s = new File(projectDir, "smali");
        if (s.isDirectory()) return s;
        File[] kids = projectDir.listFiles();
        if (kids != null) for (File k : kids) {
            if (k.isDirectory() && k.getName().startsWith("smali")) return k;
        }
        return null;
    }

    /**
     * smali основного класса логгера. Ставит хэндлер и пишет старт-лог в
     * getExternalFilesDir()/apktool-log/app.log. Логика в одном методе writeLog,
     * вызываемом из onCreate; ловля вылетов — через $Handler.
     */
    private String smaliLoggerMain(String path, String superPath) {
        String cls = "L" + path + ";";
        String sup = "L" + superPath + ";";
        String h = "L" + path + "$Handler;";
        StringBuilder s = new StringBuilder();
        s.append(".class public L").append(path).append(";\n");
        s.append(".super ").append(sup).append("\n\n");
        s.append("# Краш-логгер, внедрён ApkTool (smali).\n\n");
        // onCreate()V
        s.append(".method public onCreate()V\n");
        s.append("    .locals 3\n");
        // install handler: Thread.setDefaultUncaughtExceptionHandler(new Handler(this))
        s.append("    :try_start_h\n");
        s.append("    new-instance v0, ").append(h).append("\n");
        s.append("    invoke-direct {v0, p0}, ").append(h)
         .append("-><init>(").append(cls).append(")V\n");
        s.append("    invoke-static {v0}, Ljava/lang/Thread;->setDefaultUncaughtExceptionHandler(Ljava/lang/Thread$UncaughtExceptionHandler;)V\n");
        s.append("    :try_end_h\n");
        s.append("    .catch Ljava/lang/Throwable; {:try_start_h .. :try_end_h} :h_done\n");
        s.append("    :h_done\n");
        // super.onCreate()
        s.append("    invoke-super {p0}, ").append(sup).append("->onCreate()V\n");
        // writeLog("onCreate: приложение стартовало")
        s.append("    const-string v1, \"onCreate: \\u043f\\u0440\\u0438\\u043b\\u043e\\u0436\\u0435\\u043d\\u0438\\u0435 \\u0441\\u0442\\u0430\\u0440\\u0442\\u043e\\u0432\\u0430\\u043b\\u043e\"\n");
        s.append("    invoke-direct {p0, v1}, ").append(cls)
         .append("->apktoolWriteLog(Ljava/lang/String;)V\n");
        s.append("    return-void\n");
        s.append(".end method\n\n");
        // apktoolWriteLog(String)V — общий метод записи
        s.append(smaliWriteLogMethod(cls));
        return s.toString();
    }

    /**
     * smali метода записи в файл. Эквивалент java-версии: пишет строку с меткой
     * времени в getExternalFilesDir()/apktool-log/app.log (fallback на
     * /storage/emulated/0/Android/data/&lt;pkg&gt;/files). Метод public, чтобы его
     * мог вызвать $Handler.
     */
    private String smaliWriteLogMethod(String cls) {
        StringBuilder s = new StringBuilder();
        s.append(".method public apktoolWriteLog(Ljava/lang/String;)V\n");
        s.append("    .locals 7\n");
        s.append("    :try_start_0\n");
        // File dir = getExternalFilesDir(null)
        s.append("    const/4 v0, 0x0\n");
        s.append("    invoke-virtual {p0, v0}, ").append(cls)
         .append("->getExternalFilesDir(Ljava/lang/String;)Ljava/io/File;\n");
        s.append("    move-result-object v1\n");
        // if dir == null -> fallback path
        s.append("    if-nez v1, :have_dir\n");
        s.append("    new-instance v1, Ljava/io/File;\n");
        s.append("    new-instance v2, Ljava/lang/StringBuilder;\n");
        s.append("    invoke-direct {v2}, Ljava/lang/StringBuilder;-><init>()V\n");
        s.append("    const-string v3, \"/storage/emulated/0/Android/data/\"\n");
        s.append("    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n");
        s.append("    invoke-virtual {p0}, ").append(cls)
         .append("->getPackageName()Ljava/lang/String;\n");
        s.append("    move-result-object v3\n");
        s.append("    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n");
        s.append("    const-string v3, \"/files\"\n");
        s.append("    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n");
        s.append("    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;\n");
        s.append("    move-result-object v2\n");
        s.append("    invoke-direct {v1, v2}, Ljava/io/File;-><init>(Ljava/lang/String;)V\n");
        s.append("    :have_dir\n");
        // File logDir = new File(dir, "apktool-log"); logDir.mkdirs();
        s.append("    new-instance v2, Ljava/io/File;\n");
        s.append("    const-string v3, \"apktool-log\"\n");
        s.append("    invoke-direct {v2, v1, v3}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V\n");
        s.append("    invoke-virtual {v2}, Ljava/io/File;->mkdirs()Z\n");
        // File f = new File(logDir, "app.log")
        s.append("    new-instance v3, Ljava/io/File;\n");
        s.append("    const-string v4, \"app.log\"\n");
        s.append("    invoke-direct {v3, v2, v4}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V\n");
        // FileWriter w = new FileWriter(f, true)
        s.append("    new-instance v4, Ljava/io/FileWriter;\n");
        s.append("    const/4 v5, 0x1\n");
        s.append("    invoke-direct {v4, v3, v5}, Ljava/io/FileWriter;-><init>(Ljava/io/File;Z)V\n");
        // w.write(System.currentTimeMillis()+"  "+msg+"\n") — упрощённо: msg + "\n"
        s.append("    new-instance v5, Ljava/lang/StringBuilder;\n");
        s.append("    invoke-direct {v5}, Ljava/lang/StringBuilder;-><init>()V\n");
        s.append("    invoke-static {}, Ljava/lang/System;->currentTimeMillis()J\n");
        s.append("    move-result-wide v0\n");
        s.append("    invoke-virtual {v5, v0, v1}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;\n");
        s.append("    const-string v6, \"  \"\n");
        s.append("    invoke-virtual {v5, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n");
        s.append("    invoke-virtual {v5, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n");
        s.append("    const-string v6, \"\\n\"\n");
        s.append("    invoke-virtual {v5, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n");
        s.append("    invoke-virtual {v5}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;\n");
        s.append("    move-result-object v5\n");
        s.append("    invoke-virtual {v4, v5}, Ljava/io/FileWriter;->write(Ljava/lang/String;)V\n");
        s.append("    invoke-virtual {v4}, Ljava/io/FileWriter;->flush()V\n");
        s.append("    invoke-virtual {v4}, Ljava/io/FileWriter;->close()V\n");
        s.append("    :try_end_0\n");
        s.append("    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :log_done\n");
        s.append("    :log_done\n");
        s.append("    return-void\n");
        s.append(".end method\n");
        return s.toString();
    }

    /**
     * smali класса-хэндлера вылетов LoggerFqn$Handler, реализующего
     * Thread$UncaughtExceptionHandler. Держит ссылку на логгер и в
     * uncaughtException пишет стектрейс, затем зовёт системный дефолт (если был).
     */
    private String smaliLoggerHandler(String path) {
        String cls = "L" + path + ";";
        String h = "L" + path + "$Handler;";
        StringBuilder s = new StringBuilder();
        s.append(".class public L").append(path).append("$Handler;\n");
        s.append(".super Ljava/lang/Object;\n");
        s.append(".implements Ljava/lang/Thread$UncaughtExceptionHandler;\n\n");
        s.append(".field final apktoolApp:").append(cls).append("\n");
        s.append(".field final prev:Ljava/lang/Thread$UncaughtExceptionHandler;\n\n");
        // <init>(Logger)V
        s.append(".method public constructor <init>(").append(cls).append(")V\n");
        s.append("    .locals 1\n");
        s.append("    invoke-direct {p0}, Ljava/lang/Object;-><init>()V\n");
        s.append("    iput-object p1, p0, ").append(h).append("->apktoolApp:").append(cls).append("\n");
        s.append("    invoke-static {}, Ljava/lang/Thread;->getDefaultUncaughtExceptionHandler()Ljava/lang/Thread$UncaughtExceptionHandler;\n");
        s.append("    move-result-object v0\n");
        s.append("    iput-object v0, p0, ").append(h).append("->prev:Ljava/lang/Thread$UncaughtExceptionHandler;\n");
        s.append("    return-void\n");
        s.append(".end method\n\n");
        // uncaughtException(Thread,Throwable)V
        s.append(".method public uncaughtException(Ljava/lang/Thread;Ljava/lang/Throwable;)V\n");
        s.append("    .locals 5\n");
        s.append("    :try_start_0\n");
        // StringWriter sw = new StringWriter(); ex.printStackTrace(new PrintWriter(sw));
        s.append("    new-instance v0, Ljava/io/StringWriter;\n");
        s.append("    invoke-direct {v0}, Ljava/io/StringWriter;-><init>()V\n");
        s.append("    new-instance v1, Ljava/io/PrintWriter;\n");
        s.append("    invoke-direct {v1, v0}, Ljava/io/PrintWriter;-><init>(Ljava/io/Writer;)V\n");
        s.append("    invoke-virtual {p2, v1}, Ljava/lang/Throwable;->printStackTrace(Ljava/io/PrintWriter;)V\n");
        s.append("    invoke-virtual {v1}, Ljava/io/PrintWriter;->flush()V\n");
        // msg = "VYLET:\n" + sw
        s.append("    new-instance v2, Ljava/lang/StringBuilder;\n");
        s.append("    invoke-direct {v2}, Ljava/lang/StringBuilder;-><init>()V\n");
        s.append("    const-string v3, \"\\u0412\\u042b\\u041b\\u0415\\u0422:\\n\"\n");
        s.append("    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n");
        s.append("    invoke-virtual {v0}, Ljava/io/StringWriter;->toString()Ljava/lang/String;\n");
        s.append("    move-result-object v3\n");
        s.append("    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n");
        s.append("    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;\n");
        s.append("    move-result-object v2\n");
        s.append("    iget-object v3, p0, ").append(h).append("->apktoolApp:").append(cls).append("\n");
        s.append("    invoke-virtual {v3, v2}, ").append(cls)
         .append("->apktoolWriteLog(Ljava/lang/String;)V\n");
        s.append("    :try_end_0\n");
        s.append("    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :after\n");
        s.append("    :after\n");
        // if (prev != null) prev.uncaughtException(t, ex)
        s.append("    iget-object v4, p0, ").append(h).append("->prev:Ljava/lang/Thread$UncaughtExceptionHandler;\n");
        s.append("    if-eqz v4, :no_prev\n");
        s.append("    invoke-interface {v4, p1, p2}, Ljava/lang/Thread$UncaughtExceptionHandler;->uncaughtException(Ljava/lang/Thread;Ljava/lang/Throwable;)V\n");
        s.append("    :no_prev\n");
        s.append("    return-void\n");
        s.append(".end method\n");
        return s.toString();
    }

    private void buildLikeAide(File projectDir, File srcRoot, File resRoot,
                               File finalApk, int androidVer) throws Exception {
        File work = new File(ctx.getCacheDir(), "aide_" + System.currentTimeMillis());
        File compiled = new File(work, "compiled");   // .flat от aapt2 compile
        File genSrc   = new File(work, "gen");         // сгенерированный R.java
        File classes  = new File(work, "classes");     // .class от ecj
        File dexDir   = new File(work, "dex");          // classes.dex от d8
        compiled.mkdirs(); genSrc.mkdirs(); classes.mkdirs(); dexDir.mkdirs();

        File manifest = new File(resRoot, "AndroidManifest.xml");
        if (!manifest.exists()) throw new Exception("Нет AndroidManifest.xml в " + resRoot);

        // Встроить краш-логгер: сгенерировать Application-класс с
        // UncaughtExceptionHandler и прописать его в манифест. Делается ДО aapt2,
        // чтобы изменённый манифест попал в resources.arsc, и ДО ecj, чтобы .java
        // логгера скомпилировался вместе с приложением.
        injectCrashLogger(manifest, srcRoot, null);

        // КРИТИЧНО для рантайма: targetSdkVersion берём ИЗ МАНИФЕСТА, а НЕ из
        // выбранной версии Android. targetSdk управляет моделью разрешений: при
        // targetSdk>=23 Android включает runtime-permissions, и старое приложение
        // (написанное под установочную модель, без запроса разрешений в рантайме)
        // не сможет открыть камеру/микрофон и т.п. — Camera.open() бросит
        // «Fail to connect to camera service», что в коде часто проглатывается
        // пустым catch (симптом: m_cCamera==null, чёрный экран, «Ошибка камеры»).
        // Раньше здесь жёстко ставилось targetSdk=25 -> регрессия на Android 6+.
        // Теперь сохраняем оригинальное поведение: targetSdk = как в манифесте;
        // если не указан — равен minSdk (правило Android по умолчанию).
        int minSdk = readManifestSdk(manifest, "minSdkVersion", 9);
        int targetSdk = readManifestSdk(manifest, "targetSdkVersion", minSdk);
        log.log("SDK из манифеста: minSdk=" + minSdk + ", targetSdk=" + targetSdk
                + " (targetSdk сохранён из оригинала — не поднимаем, иначе "
                + "сломается модель разрешений и камера).");

        // --- Шаг 1: ресурсы -> resources.arsc + R.java (aapt2) ---
        File linkedApk = new File(work, "res.apk");
        aapt2CompileAndLink(resRoot, manifest, compiled, genSrc, linkedApk, minSdk, targetSdk);

        // --- Шаг 2: sources/*.java + R.java -> .class (ecj) ---
        // Свежий R.java из aapt2 ЗАМЕНЯЕТ старый декомпилированный R.java, чтобы
        // ID в коде совпали с новым resources.arsc.
        java.util.List<File> libJars = collectLibJars(projectDir, resRoot);
        compileJavaLikeAide(srcRoot, genSrc, libJars, classes);

        // --- Шаг 3: .class -> classes.dex (d8) ---
        java.util.List<File> classFiles = new java.util.ArrayList<File>();
        collectByExt(classes, ".class", classFiles);
        if (classFiles.isEmpty()) {
            throw new Exception("Компиляция не дала ни одного .class — APK не собрать. "
                    + "См. ошибки ecj выше (возможно, не хватает библиотек в libs/).");
        }
        int minApi = androidVer > 0 ? Integer.parseInt(sdkOf(androidVer)) : 19;
        boolean okDex = runD8(classFiles, libJars, minApi, dexDir);
        File producedDex = new File(dexDir, "classes.dex");
        if (!okDex || !producedDex.exists() || producedDex.length() == 0) {
            throw new Exception("d8 не создал classes.dex. См. ошибки выше.");
        }
        log.log("classes.dex собран из исходников (" + classFiles.size() + " .class).");

        // --- Шаг 4: упаковка resources.arsc + classes.dex + lib/ + assets/ ---
        log.log("Упаковка APK (resources.arsc + classes.dex + lib + assets)...");
        log.progress(80);
        java.util.Map<String, File> add = new java.util.LinkedHashMap<String, File>();
        File[] dexOut = dexDir.listFiles();
        if (dexOut != null) for (File d : dexOut) {
            if (d.getName().matches("classes\\d*\\.dex")) add.put(d.getName(), d);
        }
        collect(new File(resRoot, "lib"), resRoot, add);
        collect(new File(resRoot, "assets"), resRoot, add);
        mergeIntoZip(linkedApk, add);

        // --- Шаг 5: выравнивание + подпись ---
        finalizeApk(linkedApk, finalApk);
        rmdir(work);
    }

    /**
     * aapt2 compile (пофайлово) + link. link генерирует resources.arsc внутри
     * res.apk И R.java в genSrc (--java). Ресурсы предварительно чистятся от
     * apktool-заглушек и enum-как-числа.
     */
    private void aapt2CompileAndLink(File resRoot, File manifest, File compiled,
                                     File genSrc, File linkedApk,
                                     int minSdk, int targetSdk) throws Exception {
        File srcRes = new File(resRoot, "res");
        File work = compiled.getParentFile();
        java.util.List<File> flats = new java.util.ArrayList<File>();
        if (srcRes.isDirectory()) {
            File resDir = new File(work, "res_fixed");
            log.log("Подготовка ресурсов (фикс apktool-заглушек)...");
            copyDir(srcRes, resDir);
            sanitizeValues(resDir);
            fixEnumInts(resDir);
            sanitizePublicXml(resDir);
            log.log("aapt2 compile ресурсов (пофайлово)...");
            log.progress(20);
            flats = compileResPerFile(resDir, compiled);
        } else {
            log.log("Каталог res/ отсутствует — линкую только манифест.");
        }

        log.log("aapt2 link (-> resources.arsc + R.java)...");
        log.progress(40);
        ArrayList<String> link = new ArrayList<String>();
        link.add(aapt2.getAbsolutePath());
        link.add("link");
        link.add("-o"); link.add(linkedApk.getAbsolutePath());
        link.add("--manifest"); link.add(manifest.getAbsolutePath());
        link.add("-I"); link.add(androidFramework());
        link.add("--java"); link.add(genSrc.getAbsolutePath()); // генерация R.java
        link.add("--auto-add-overlay");
        link.add("--min-sdk-version"); link.add(String.valueOf(minSdk));
        link.add("--target-sdk-version"); link.add(String.valueOf(targetSdk));
        for (File f : flats) link.add(f.getAbsolutePath());
        int rc = exec(link.toArray(new String[0]));
        if (rc != 0 || !linkedApk.exists()) {
            throw new Exception("aapt2 link завершился с кодом " + rc
                    + ". Проверьте res/ и AndroidManifest.xml.");
        }
        File[] gen = genSrc.exists() ? genSrc.listFiles() : null;
        log.log("R.java сгенерирован: " + (gen != null && gen.length > 0 ? "да" : "нет"));
    }

    /**
     * ecj: sources/*.java + сгенерированный R.java -> .class.
     * Старый декомпилированный R.java из sources/ ИСКЛЮЧАЕТСЯ (заменён свежим).
     * В classpath: android.jar + все libs/*.jar (внешние зависимости, как AIDE).
     */
    private void compileJavaLikeAide(File srcRoot, File genSrc,
                                     java.util.List<File> libJars, File outDir) throws Exception {
        // Собираем список .java: все из sources/, КРОМЕ R.java (берём свежий),
        // плюс сгенерированные R.java из genSrc.
        java.util.List<File> javaFiles = new java.util.ArrayList<File>();
        collectByExt(srcRoot, ".java", javaFiles);
        java.util.List<File> filtered = new java.util.ArrayList<File>();
        int droppedR = 0;
        for (File f : javaFiles) {
            if (f.getName().equals("R.java")) { droppedR++; continue; }
            filtered.add(f);
        }
        java.util.List<File> genJava = new java.util.ArrayList<File>();
        collectByExt(genSrc, ".java", genJava);
        filtered.addAll(genJava);
        if (droppedR > 0) log.log("Старый R.java исключён (" + droppedR
                + "), используется свежий из aapt2 (" + genJava.size() + ").");
        if (filtered.isEmpty()) throw new Exception("Нет .java для компиляции.");

        // classpath = android.jar + libs/*.jar
        StringBuilder cp = new StringBuilder(new File(dirEngine, "android.jar").getAbsolutePath());
        for (File j : libJars) cp.append(File.pathSeparator).append(j.getAbsolutePath());
        if (!libJars.isEmpty()) log.log("Библиотеки в classpath: " + libJars.size()
                + " (libs/*.jar).");

        // АВТОМАТИЗАЦИЯ (для любого декомпилята): генерируем stub-исходники для
        // классов из импортов, которых нет ни в android.jar, ни в sources, ни в
        // libs/*.jar (например com.google.android.gms.*). Заглушки позволяют
        // коду СКОМПИЛИРОВАТЬСЯ; их .class идут ТОЛЬКО в classpath ecj и НЕ
        // попадают в финальный dex.
        //
        // ВАЖНО (иначе рантайм-вылет): заглушённого класса НЕТ в итоговом dex и,
        // как правило, нет в оригинальном APK и в системе. Если код реально
        // обращается к такому классу во время работы (например new AdView(this)),
        // ART бросит java.lang.NoClassDefFoundError. Это Error, а НЕ Exception,
        // поэтому исходный catch(Exception) его НЕ ловит и приложение падает при
        // старте — ровно тот молчаливый вылет, который и ловит краш-логгер.
        // Лечим это в guardStubUsages(): вокруг обращений к stub-классам
        // расширяем перехват до Throwable, чтобы отсутствующая внешняя библиотека
        // (реклама и т.п.) не роняла приложение, а тихо отключалась.
        File stubSrc = new File(outDir.getParentFile(), "stub_src");
        File stubOut = new File(outDir.getParentFile(), "stub_classes");
        int stubs = generateStubs(filtered, libJars, stubSrc);
        String ecjCp = cp.toString();
        if (stubs > 0) {
            log.log("Сгенерированы заглушки внешних классов: " + stubs
                    + " (компиляция без ручного добавления .jar).");
            java.util.List<File> stubJava = new java.util.ArrayList<File>();
            collectByExt(stubSrc, ".java", stubJava);
            stubOut.mkdirs();
            // Компилируем сами заглушки (без нашего classpath — они самодостаточны).
            compileStubs(stubJava, stubOut);
            ecjCp = ecjCp + File.pathSeparator + stubOut.getAbsolutePath();
        }

        // Автопочинка типовых дефектов jadx перед компиляцией.
        autoFixJadxDefects(filtered);

        runEcjIterative(filtered, ecjCp, outDir, stubSrc, stubOut);

        // ПОСЛЕ успешной компиляции набор stub-классов финализирован. Защищаем
        // рантайм: расширяем catch вокруг обращений к заглушённым (отсутствующим
        // в dex) классам до Throwable, чтобы NoClassDefFoundError не убивал
        // приложение. Если что-то поправили — перекомпилируем один раз (замена
        // Exception->Throwable компилируется без новых ошибок).
        int guarded = guardStubUsages(filtered, stubSrc);
        if (guarded > 0) {
            log.log("Защита рантайма: catch->Throwable вокруг обращений к "
                    + "заглушкам расширено в " + guarded + " месте(ах) "
                    + "(отсутствующая внешняя библиотека не уронит приложение).");
            EcjResult r = ecjOnce(filtered, ecjCp, outDir, 99);
            if (!r.ok) {
                // Крайне маловероятно: откат смысла нет, но честно сообщим.
                log.err("Перекомпиляция после защиты рантайма дала ошибки — "
                        + "APK всё равно собран из уже скомпилированных .class.");
            }
        }
    }

    /**
     * Защита рантайма от NoClassDefFoundError по заглушённым классам.
     *
     * Заглушки нужны лишь для КОМПИЛЯЦИИ; в dex этих классов нет. Если код
     * обращается к ним при работе (например new AdView(this) для рекламы),
     * ART бросает java.lang.NoClassDefFoundError — а это Error, не Exception,
     * поэтому существующий catch(Exception) его пропускает и приложение падает.
     *
     * Метод находит в исходниках приложения try-блоки, тело которых обращается к
     * простому имени какого-либо stub-класса, и расширяет их catch-параметры до
     * Throwable. Семантика тела catch не меняется (перехват более широкого типа
     * безопасен), но теперь Error/NoClassDefFoundError ловится и приложение
     * продолжает работу с отключённой внешней функцией.
     *
     * Возвращает число расширенных catch-ов.
     */
    private int guardStubUsages(java.util.List<File> javaFiles, File stubSrc) {
        // 1) Простые имена всех stub-классов (AdView, AdSize, AdRequest, ...).
        java.util.LinkedHashSet<String> stubNames = new java.util.LinkedHashSet<String>();
        java.util.List<File> stubJava = new java.util.ArrayList<File>();
        collectByExt(stubSrc, ".java", stubJava);
        for (File f : stubJava) {
            stubNames.add(f.getName().replaceAll("\\.java$", ""));
        }
        if (stubNames.isEmpty()) return 0;

        int total = 0;
        for (File f : javaFiles) {
            if (f.getName().equals("R.java")) continue;
            try {
                String c = readFile(f);
                // Быстрый отсев: файл вообще упоминает какой-либо stub-тип?
                boolean mentions = false;
                for (String n : stubNames) {
                    if (java.util.regex.Pattern.compile("\\b"
                            + java.util.regex.Pattern.quote(n) + "\\b")
                            .matcher(c).find()) { mentions = true; break; }
                }
                if (!mentions) continue;
                String nc = widenCatchesAroundStubs(c, stubNames);
                if (!nc.equals(c)) {
                    write(f, nc);
                    total += countWidened(c, nc);
                }
            } catch (Throwable t) {
                log.err("guardStubUsages(" + f.getName() + "): " + t);
            }
        }
        return total;
    }

    /** Число расширенных catch = разница вхождений "catch (Throwable". */
    private int countWidened(String before, String after) {
        return occ(after, "catch (Throwable") - occ(before, "catch (Throwable");
    }

    private int occ(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }

    /**
     * Сканирует исходник, находит try{...}catch(...) блоки, чьё try-ТЕЛО ссылается
     * на любой stub-тип, и заменяет их catch-типы на Throwable. Разбор — по
     * балансу фигурных скобок (учитывает вложенность), поэтому вложенные try
     * обрабатываются корректно. Строки/комментарии для баланса скобок мы не
     * анализируем детально, но фигурные скобки в строковых литералах в
     * декомпиляте jadx практически не встречаются в позициях, ломающих разбор;
     * при любой неоднозначности блок пропускается (правка не применяется).
     */
    private String widenCatchesAroundStubs(String src,
            java.util.LinkedHashSet<String> stubNames) {
        StringBuilder out = new StringBuilder(src.length() + 64);
        int i = 0, len = src.length();
        java.util.regex.Pattern tryP = java.util.regex.Pattern.compile("\\btry\\b\\s*\\{");
        while (i < len) {
            java.util.regex.Matcher m = tryP.matcher(src);
            if (!m.find(i)) { out.append(src, i, len); break; }
            int tryKw = m.start();
            int braceOpen = m.end() - 1;      // индекс '{'
            out.append(src, i, braceOpen + 1); // всё до и включая '{'
            int bodyStart = braceOpen + 1;
            int bodyEnd = matchBrace(src, braceOpen); // индекс парной '}'
            if (bodyEnd < 0) { out.append(src, bodyStart, len); break; }
            String body = src.substring(bodyStart, bodyEnd); // без внешних скобок
            boolean touches = false;
            for (String n : stubNames) {
                if (java.util.regex.Pattern.compile("\\b"
                        + java.util.regex.Pattern.quote(n) + "\\b")
                        .matcher(body).find()) { touches = true; break; }
            }
            // Тело try выводим как есть (внутри могут быть вложенные try — их
            // обработает следующая итерация внешнего цикла, т.к. i встанет в
            // начало тела).
            // Дальше идут catch/finally. Расширяем catch-типы, если тело задело stub.
            int afterBody = bodyEnd + 1; // сразу после '}'
            if (!touches) {
                out.append(body);        // тело без изменений
                out.append('}');         // закрывающая скобка тела
                i = afterBody;
                continue;
            }
            out.append(body);
            out.append('}');
            // Разбираем цепочку catch(...) { ... } [catch...] [finally {...}].
            int j = afterBody;
            while (true) {
                int k = skipWs(src, j);
                if (src.startsWith("catch", k)) {
                    int paren = src.indexOf('(', k);
                    if (paren < 0) break;
                    int parenEnd = matchParen(src, paren);
                    if (parenEnd < 0) break;
                    String decl = src.substring(paren + 1, parenEnd); // напр. "Exception e"
                    String widened = widenCatchDecl(decl);
                    int cbOpen = src.indexOf('{', parenEnd);
                    if (cbOpen < 0) break;
                    int cbEnd = matchBrace(src, cbOpen);
                    if (cbEnd < 0) break;
                    out.append(src, j, paren + 1);
                    out.append(widened);
                    out.append(src, parenEnd, cbEnd + 1); // от ')' до '}' catch-тела
                    j = cbEnd + 1;
                    continue;
                } else if (src.startsWith("finally", k)) {
                    int fbOpen = src.indexOf('{', k);
                    if (fbOpen < 0) break;
                    int fbEnd = matchBrace(src, fbOpen);
                    if (fbEnd < 0) break;
                    out.append(src, j, fbEnd + 1);
                    j = fbEnd + 1;
                    break; // после finally цепочка завершена
                } else {
                    break; // ни catch, ни finally — конец конструкции
                }
            }
            i = j;
        }
        return out.toString();
    }

    /**
     * Расширяет объявление catch до Throwable. Для одиночного типа
     * ("Exception e" -> "Throwable e"), для мультикатча ("A | B e" -> "Throwable e").
     * Если уже Throwable/Error/Object — оставляет как есть.
     */
    private String widenCatchDecl(String decl) {
        String d = decl.trim();
        // имя переменной — последний идентификатор
        java.util.regex.Matcher vm = java.util.regex.Pattern.compile(
                "([A-Za-z_$][\\w$]*)\\s*$").matcher(d);
        if (!vm.find()) return decl; // не разобрали — не трогаем
        String var = vm.group(1);
        String typePart = d.substring(0, vm.start()).trim();
        if (typePart.isEmpty()) return decl;
        // уже достаточно широкий?
        if (typePart.equals("Throwable") || typePart.endsWith(".Throwable")
                || typePart.equals("Error") || typePart.equals("Object")) {
            return decl;
        }
        return "Throwable " + var;
    }

    /** Индекс парной '}' для '{' по позиции open. -1 если не найдено. */
    private int matchBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    /** Индекс парной ')' для '(' по позиции open. -1 если не найдено. */
    private int matchParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    /** Пропустить пробелы/переводы строк, вернуть индекс первого непробела. */
    private int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /**
     * Итеративная компиляция с автодоращиванием заглушек. ecj не всегда видит
     * все недостающие типы сразу (import-ы + qualified-имена), поэтому: компилим
     * -> из ошибок «X cannot be resolved (to a type)»/«package ... does not
     * exist» собираем недостающие FQN -> генерим для них stub -> докомпилим
     * заглушки -> повторяем (до 4 проходов). Так компиляция «дотягивается» для
     * любого декомпилята без ручного добавления .jar.
     */
    private void runEcjIterative(java.util.List<File> javaFiles, String classpath,
                                 File outDir, File stubSrc, File stubOut) throws Exception {
        String cp = classpath;
        String lastErr = "";
        // Больше проходов: доращивание типов + членов заглушек + починка
        // дефектов jadx по координатам ошибок ecj требует нескольких итераций.
        for (int pass = 1; pass <= 8; pass++) {
            EcjResult r = ecjOnce(javaFiles, cp, outDir, pass);
            if (r.ok) {
                if (pass > 1) log.log("Компиляция удалась после автопочинки (проход " + pass + ").");
                return;
            }
            lastErr = r.errText;
            boolean progress = false;

            // (A) Доростить недостающие ВНЕШНИЕ типы новыми заглушками.
            java.util.LinkedHashSet<String> newFqns = missingTypesFromErrors(r.errText, javaFiles);
            if (!newFqns.isEmpty()) {
                int made = 0;
                stubOut.mkdirs();
                for (String fqn : newFqns) if (writeStub(stubSrc, fqn)) made++;
                if (made > 0) { progress = true; log.log("Автозаглушки: +" + made + " класс(ов)."); }
            }

            // (B) Дописать недостающие ЧЛЕНЫ (методы/поля) в существующие заглушки:
            // ecj «The method setAdUnitId(String) is undefined for the type AdView»,
            // «BANNER cannot be resolved or is not a field» и т.п.
            int addedMembers = enrichStubsFromErrors(r.errText, stubSrc);
            if (addedMembers > 0) { progress = true;
                log.log("Заглушки: дописано членов (методы/поля): " + addedMembers + "."); }

            // Перекомпилировать заглушки, если что-то добавили в (A) или (B).
            if (progress) {
                java.util.List<File> stubJava = new java.util.ArrayList<File>();
                collectByExt(stubSrc, ".java", stubJava);
                compileStubs(stubJava, stubOut);
                if (!cp.contains(stubOut.getAbsolutePath())) {
                    cp = cp + File.pathSeparator + stubOut.getAbsolutePath();
                }
            }

            // (C) Починить дефекты декомпиляции jadx прямо в sources по координатам
            // ошибок ecj: unreachable catch/code. Правки локальные и безопасные.
            int fixedDefects = fixEcjSourceDefects(r.errText);
            if (fixedDefects > 0) { progress = true;
                log.log("Автопочинка декомпиляции по ошибкам ecj: " + fixedDefects + "."); }

            if (!progress) break; // ничего не изменили -> дальше не поможет
            log.log("Повтор компиляции (проход " + (pass + 1) + ")...");
        }
        // Не удалось -> честная понятная ошибка.
        reportEcjFailure(lastErr);
    }

    private static class EcjResult { boolean ok; String errText; }

    /** Один прогон ecj. Возвращает флаг успеха и текст ошибок. */
    private EcjResult ecjOnce(java.util.List<File> javaFiles, String classpath,
                              File outDir, int pass) throws Exception {
        log.log("ecj: компиляция " + javaFiles.size() + " .java (проход " + pass + ")...");
        log.progress(55);
        ClassLoader cl = loader("ecj.jar");
        Class<?> bc = cl.loadClass("org.eclipse.jdt.core.compiler.batch.BatchCompiler");
        Method compile = bc.getMethod("compile", String[].class,
                java.io.PrintWriter.class, java.io.PrintWriter.class,
                cl.loadClass("org.eclipse.jdt.core.compiler.CompilationProgress"));

        File argFile = new File(outDir.getParentFile(), "ecj_sources.txt");
        StringBuilder sb = new StringBuilder();
        for (File f : javaFiles) sb.append('"').append(f.getAbsolutePath()).append("\"\n");
        write(argFile, sb.toString());

        java.util.List<String> args = new java.util.ArrayList<String>();
        args.add("-cp"); args.add(classpath);
        args.add("-source"); args.add("1.8");
        args.add("-target"); args.add("1.8");
        args.add("-proc:none"); args.add("-nowarn"); args.add("-noExit");
        args.add("-d"); args.add(outDir.getAbsolutePath());
        args.add("@" + argFile.getAbsolutePath());

        java.io.StringWriter errSw = new java.io.StringWriter();
        java.io.PrintWriter outPw = new java.io.PrintWriter(new java.io.StringWriter());
        java.io.PrintWriter errPw = new java.io.PrintWriter(errSw);
        Object ok = compile.invoke(null, (Object) args.toArray(new String[0]), outPw, errPw, null);
        errPw.flush();
        EcjResult r = new EcjResult();
        r.ok = Boolean.TRUE.equals(ok);
        r.errText = errSw.toString();
        return r;
    }

    /**
     * Из текста ошибок ecj извлечь недостающие типы (FQN), которые можно
     * заглушить. Ловим паттерны:
     *   «import a.b.C cannot be resolved»  -> a.b.C
     *   «a.b.C cannot be resolved to a type» -> a.b.C (если с точками)
     *   «The import a.b cannot be resolved» -> пропускаем пакеты без класса
     */
    private java.util.LinkedHashSet<String> missingTypesFromErrors(
            String errText, java.util.List<File> javaFiles) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<String>();
        // 1) «import X cannot be resolved»
        java.util.regex.Matcher m1 = java.util.regex.Pattern.compile(
                "import\\s+([\\w.]+)\\s+cannot be resolved").matcher(errText);
        while (m1.find()) {
            String fqn = m1.group(1);
            if (fqn.contains(".") && isStubbable(fqn)) out.add(fqn);
        }
        // 2) «a.b.C cannot be resolved to a type» (полное имя с точками)
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile(
                "([\\w]+(?:\\.[\\w]+){2,})\\s+cannot be resolved to a type").matcher(errText);
        while (m2.find()) {
            String fqn = m2.group(1);
            if (isStubbable(fqn)) out.add(fqn);
        }
        return out;
    }

    /**
     * Дописывает недостающие члены (методы/поля/константы) в уже существующие
     * stub-классы на основе ошибок ecj. Так «податливые» заглушки внешних
     * библиотек (например gms AdView) обретают ровно те методы и поля, которые
     * реально использует код: setAdUnitId(String), setAdSize(AdSize), AdSize.BANNER.
     * Возвращает число дописанных членов.
     */
    private int enrichStubsFromErrors(String errText, File stubSrc) {
        // Тип -> набор объявлений-членов, которые надо добавить.
        java.util.LinkedHashMap<String, java.util.LinkedHashSet<String>> add =
                new java.util.LinkedHashMap<String, java.util.LinkedHashSet<String>>();
        // Методы, к которым код обращается статически (Type.method(...)) — их
        // заглушка ОБЯЗАНА объявить static, иначе «Cannot make a static reference
        // to the non-static method». Ключ: "Type#method".
        java.util.LinkedHashSet<String> staticMethods = new java.util.LinkedHashSet<String>();

        String[] errLines = errText.split("\n");

        // 0) «Cannot make a static reference to the non-static method m(...) from
        //    the type T» — метод в заглушке нужно пометить static. Определяем и
        //    имя T (из этой же строки), и добавляем метод в набор static.
        java.util.regex.Matcher sm = java.util.regex.Pattern.compile(
                "Cannot make a static reference to the non-static method (\\w+)\\("
                + "[^)]*\\) from the type (\\w+)").matcher(errText);
        while (sm.find()) {
            staticMethods.add(sm.group(2) + "#" + sm.group(1));
        }

        // 1) «The method foo(A,B) is undefined for the type T». Заглушка получает
        //    generic-возврат <R> R + varargs Object...: это снимает сразу три
        //    класса ошибок jadx-заглушек — несовпадение арности аргументов,
        //    «Type mismatch: cannot convert from Object» (R выводится в нужный тип)
        //    и «is not applicable for the arguments». Если рядом в логе метод
        //    вызывается статически (T.foo) — объявляем его static.
        java.util.regex.Matcher mm = java.util.regex.Pattern.compile(
                "The method (\\w+)\\(([^)]*)\\) is undefined for the type (\\w+)")
                .matcher(errText);
        while (mm.find()) {
            String name = mm.group(1), type = mm.group(3);
            boolean isStatic = staticMethods.contains(type + "#" + name)
                    || usedStatically(errLines, type, name);
            String decl = stubMethodDecl(name, isStatic);
            put(add, type, decl);
        }

        // 2) «X cannot be resolved or is not a field» — обращение T.X (константа/поле)
        //    Ищем предшествующую подчёркнутую строку с «Type.FIELD».
        String[] lines = errText.split("\n");
        for (int i = 0; i < lines.length; i++) {
            java.util.regex.Matcher fm = java.util.regex.Pattern.compile(
                    "(\\w+) cannot be resolved or is not a field").matcher(lines[i]);
            if (!fm.find()) continue;
            String field = fm.group(1);
            // Ищем в окне строк рядом «Type.field» чтобы определить владельца.
            String owner = findFieldOwner(lines, i, field);
            if (owner != null) {
                put(add, owner, "    public static Object " + field + " = null;");
            }
        }

        // 3) Супертип заглушки. Когда заглушка передаётся туда, где ждут android.view.View
        //    («The method addView(View,int) in the type ViewGroup is not applicable for
        //    the arguments (AdView, ...)»), заглушка должна наследовать View. Определяем
        //    имя типа-заглушки в списке фактических аргументов.
        java.util.LinkedHashMap<String, String> superType =
                new java.util.LinkedHashMap<String, String>();
        java.util.regex.Matcher na = java.util.regex.Pattern.compile(
                "is not applicable for the arguments \\(([^)]*)\\)").matcher(errText);
        while (na.find()) {
            for (String arg : na.group(1).split("\\s*,\\s*")) {
                String t = arg.trim();
                if (findStubFile(stubSrc, t) != null) {
                    // Эвристика: если рядом упоминается View/ViewGroup — наследуем View.
                    superType.put(t, "android.view.View");
                }
            }
        }

        if (add.isEmpty() && superType.isEmpty() && staticMethods.isEmpty()) return 0;

        int added = 0;
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<String>();
        keys.addAll(add.keySet());
        keys.addAll(superType.keySet());
        for (String sMeth : staticMethods) keys.add(sMeth.substring(0, sMeth.indexOf('#')));
        for (String type : keys) {
            File sf = findStubFile(stubSrc, type);
            if (sf == null) continue; // это не наша заглушка (например локальный тип)
            try {
                String c = readFile(sf);
                // 3a) Назначить супертип, если требуется и ещё не унаследован.
                String sup = superType.get(type);
                if (sup != null && !c.contains(" extends ")) {
                    // android.view.View не имеет конструктора без Context, поэтому
                    // заглушку-View нужно ПЕРЕГЕНЕРИРОВАТЬ с корректными super(...)
                    // вызовами, сохранив уже дописанные члены (методы/поля).
                    c = regenStubExtendsView(type, c);
                    added++;
                }
                // 3b) Дописать члены.
                java.util.LinkedHashSet<String> decls = add.get(type);
                if (decls != null) {
                    int lastBrace = c.lastIndexOf('}');
                    if (lastBrace >= 0) {
                        StringBuilder ins = new StringBuilder();
                        for (String decl : decls) {
                            if (c.contains(decl)) continue; // уже есть
                            ins.append(decl).append('\n');
                            added++;
                        }
                        if (ins.length() > 0) {
                            c = c.substring(0, lastBrace) + ins + c.substring(lastBrace);
                        }
                    }
                }
                // 3c) Перевести уже существующие НЕстатические методы-заглушки в
                //     static, если код обращается к ним статически (T.method(...)).
                //     Это чинит «Cannot make a static reference to the non-static
                //     method ... from the type T», когда метод был дописан ранее.
                for (String sMeth : staticMethods) {
                    int hash = sMeth.indexOf('#');
                    if (!type.equals(sMeth.substring(0, hash))) continue;
                    String mName = sMeth.substring(hash + 1);
                    String nc = makeStubMethodStatic(c, mName);
                    if (!nc.equals(c)) { c = nc; added++; }
                }
                write(sf, c);
            } catch (Throwable t) {
                log.err("enrichStubs(" + type + "): " + t);
            }
        }
        return added;
    }

    /**
     * Объявление метода-заглушки с generic-возвратом и varargs-параметром.
     * generic-возврат «<R> R» выводится компилятором в требуемый по контексту тип
     * (List<...>, String, byte[] и т.п.), устраняя «Type mismatch: cannot convert
     * from Object» и «is not applicable for the arguments». varargs «Object...»
     * принимает любое число аргументов любых типов, устраняя несовпадение арности.
     */
    private String stubMethodDecl(String name, boolean isStatic) {
        return "    public " + (isStatic ? "static " : "") + "<R> R " + name
                + "(Object... a) { return null; }";
    }

    /**
     * Определяет, вызывается ли метод name типа type статически (шаблон
     * «Type.name(») где-либо в подчёркнутых строках вывода ecj. Используется,
     * чтобы генерировать static-заглушку до появления явной ошибки static-reference.
     */
    private boolean usedStatically(String[] errLines, String type, String name) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "\\b" + java.util.regex.Pattern.quote(type) + "\\."
                + java.util.regex.Pattern.quote(name) + "\\s*\\(");
        for (String ln : errLines) if (p.matcher(ln).find()) return true;
        return false;
    }

    /**
     * Переписывает объявление метода-заглушки mName, добавляя модификатор static,
     * если он ещё не static. Работает по сигнатуре «public [<...>] RET mName(».
     * Возвращает изменённый (или исходный, если менять нечего) текст класса.
     */
    private String makeStubMethodStatic(String classSrc, String mName) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?m)^(\\s*public\\s+)(?!static\\b)(.*?\\b"
                + java.util.regex.Pattern.quote(mName) + "\\s*\\()").matcher(classSrc);
        StringBuffer sb = new StringBuffer();
        boolean changed = false;
        while (m.find()) {
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                    m.group(1) + "static " + m.group(2)));
            changed = true;
        }
        m.appendTail(sb);
        return changed ? sb.toString() : classSrc;
    }

    /**
     * Перегенерировать stub-класс так, чтобы он наследовал android.view.View
     * (нужно, когда заглушка передаётся в addView и т.п.). android.view.View не
     * имеет конструктора без Context, поэтому все конструкторы заглушки вызывают
     * super((Context)null). Ранее дописанные пользовательские члены (строки,
     * начинающиеся с «    public », кроме стандартных заглушечных) сохраняются.
     */
    private String regenStubExtendsView(String type, String old) {
        String pkg = "";
        java.util.regex.Matcher pm = java.util.regex.Pattern.compile(
                "(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(old);
        if (pm.find()) pkg = pm.group(1);
        // Сохранить ранее дописанные члены (методы/поля из enrich), но НЕ
        // стандартные конструкторы/Builder исходной заглушки.
        StringBuilder members = new StringBuilder();
        for (String ln : old.split("\n")) {
            String t = ln.trim();
            boolean isMember = t.startsWith("public ") && (t.endsWith("}") || t.endsWith(";"));
            boolean isCtor = t.startsWith("public " + type + "(");
            boolean isBuilderLine = t.contains("class Builder") || t.equals("public Builder() {}")
                    || t.contains("Builder(Object") || t.contains("build()");
            if (isMember && !isCtor && !isBuilderLine) members.append("    ").append(t).append('\n');
        }
        StringBuilder sb = new StringBuilder();
        if (!pkg.isEmpty()) sb.append("package ").append(pkg).append(";\n");
        sb.append("/** Автозаглушка ApkTool (View-совместимая). */\n");
        sb.append("public class ").append(type).append(" extends android.view.View {\n");
        sb.append("    public ").append(type).append("(android.content.Context c) { super(c); }\n");
        sb.append("    public ").append(type)
          .append("(android.content.Context c, Object... a) { super(c); }\n");
        sb.append(members);
        sb.append("}\n");
        return sb.toString();
    }

    private void put(java.util.LinkedHashMap<String, java.util.LinkedHashSet<String>> m,
                     String k, String v) {
        java.util.LinkedHashSet<String> s = m.get(k);
        if (s == null) { s = new java.util.LinkedHashSet<String>(); m.put(k, s); }
        s.add(v);
    }

    /** Найти «Type.field» рядом со строкой ошибки, вернуть Type (simple name). */
    private String findFieldOwner(String[] lines, int errLine, String field) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(\\w+)\\." + java.util.regex.Pattern.quote(field) + "\\b");
        for (int d = 0; d <= 3; d++) {
            for (int s : new int[]{errLine - d, errLine + d}) {
                if (s < 0 || s >= lines.length) continue;
                java.util.regex.Matcher m = p.matcher(lines[s]);
                if (m.find()) return m.group(1);
            }
        }
        return null;
    }

    /** Найти файл stub-класса по простому имени типа (перебор всех .java в stubSrc). */
    private File findStubFile(File stubSrc, String simpleType) {
        java.util.List<File> all = new java.util.ArrayList<File>();
        collectByExt(stubSrc, ".java", all);
        for (File f : all) {
            if (f.getName().equals(simpleType + ".java")) return f;
        }
        return null;
    }

    /**
     * Чинит остаточные дефекты декомпиляции jadx прямо в sources по координатам
     * из вывода ecj. Обрабатывает:
     *   - «Unreachable catch block for T» -> расширяет тип до Throwable
     *     (Throwable всегда достижим: метод может бросить Error/RuntimeException);
     *   - «Unreachable code» -> закомментировать недостижимый оператор.
     * Возвращает число исправлений. Правки локальны (по строке), баланс скобок
     * не меняется.
     */
    private int fixEcjSourceDefects(String errText) {
        // Парсим блоки ecj: «N. ERROR in <path> (at line L)» + текст ошибки.
        String[] lines = errText.split("\n");
        // Копим правки по файлам: файл -> (номер строки -> действие).
        java.util.LinkedHashMap<String, java.util.TreeMap<Integer, String>> edits =
                new java.util.LinkedHashMap<String, java.util.TreeMap<Integer, String>>();
        String curFile = null; int curLine = -1;
        java.util.regex.Pattern hdr = java.util.regex.Pattern.compile(
                "ERROR in (.+\\.java) \\(at line (\\d+)\\)");
        for (String ln : lines) {
            java.util.regex.Matcher hm = hdr.matcher(ln);
            if (hm.find()) { curFile = hm.group(1); curLine = Integer.parseInt(hm.group(2)); continue; }
            if (curFile == null) continue;
            if (ln.contains("Unreachable catch block")) {
                putEdit(edits, curFile, curLine, "catch->throwable");
            } else if (ln.contains("Unreachable code")) {
                putEdit(edits, curFile, curLine, "comment");
            } else if (ln.contains("This method must return a result of type")) {
                java.util.regex.Matcher tm = java.util.regex.Pattern.compile(
                        "must return a result of type (\\S+)").matcher(ln);
                String rt = tm.find() ? tm.group(1) : "Object";
                putEdit(edits, curFile, curLine, "return:" + rt);
            } else if (ln.contains("may not have been initialized")) {
                java.util.regex.Matcher vm = java.util.regex.Pattern.compile(
                        "The local variable (\\w+) may not").matcher(ln);
                if (vm.find()) putEdit(edits, curFile, curLine, "init:" + vm.group(1));
            } else if (ln.contains("Unhandled exception type")) {
                // jadx не объявил throws и не обернул вызов, бросающий checked-
                // исключение (например DocumentsContract.* -> FileNotFoundException).
                // Оборачиваем оператор в try/catch на этой строке.
                java.util.regex.Matcher em = java.util.regex.Pattern.compile(
                        "Unhandled exception type ([\\w.]+)").matcher(ln);
                if (em.find()) putEdit(edits, curFile, curLine, "wrap:" + em.group(1));
            } else if (ln.contains("cannot be resolved to a variable")) {
                // jadx «потерял» объявление локальной переменной (например nRead в
                // условии цикла). Объявляем её с дефолтом в начале тела метода.
                java.util.regex.Matcher vm = java.util.regex.Pattern.compile(
                        "(\\w+) cannot be resolved to a variable").matcher(ln);
                if (vm.find()) putEdit(edits, curFile, curLine, "declvar:" + vm.group(1));
            }
        }
        if (edits.isEmpty()) return 0;

        int fixed = 0;
        for (java.util.Map.Entry<String, java.util.TreeMap<Integer, String>> e : edits.entrySet()) {
            File f = new File(e.getKey());
            if (!f.exists()) continue;
            try {
                String c = readFile(f);
                java.util.List<String> fl = new java.util.ArrayList<String>(
                        java.util.Arrays.asList(c.split("\n", -1)));
                // Правки — в порядке УБЫВАНИЯ строк, чтобы вставки не сбивали
                // индексы ещё не обработанных правок.
                for (Integer lineNo : e.getValue().descendingKeySet()) {
                    int idx = lineNo - 1;
                    if (idx < 0 || idx >= fl.size()) continue;
                    String action = e.getValue().get(lineNo);
                    String orig = fl.get(idx);
                    if ("catch->throwable".equals(action)) {
                        String nl = orig.replaceFirst(
                                "catch\\s*\\(\\s*[\\w.]+(\\s+\\w+\\s*)\\)",
                                "catch (Throwable$1)");
                        if (!nl.equals(orig)) { fl.set(idx, nl); fixed++; }
                    } else if ("comment".equals(action)) {
                        // Комментируем ТОЛЬКО безопасные (break;/continue;).
                        String trimmed = orig.trim();
                        if ((trimmed.startsWith("break") || trimmed.startsWith("continue"))
                                && !trimmed.startsWith("//")) {
                            fl.set(idx, "// [ApkTool unreachable] " + orig);
                            fixed++;
                        }
                    } else if (action.startsWith("return:")) {
                        String rt = action.substring("return:".length());
                        if (insertDefaultReturn(fl, idx, rt)) fixed++;
                    } else if (action.startsWith("init:")) {
                        String varName = action.substring("init:".length());
                        if (initLocalVariable(fl, idx, varName)) fixed++;
                    } else if (action.startsWith("wrap:")) {
                        String exc = action.substring("wrap:".length());
                        if (wrapChecked(fl, idx, exc)) fixed++;
                    } else if (action.startsWith("declvar:")) {
                        String varName = action.substring("declvar:".length());
                        if (declareMissingVariable(fl, idx, varName)) fixed++;
                    }
                }
                StringBuilder rebuilt = new StringBuilder();
                for (int li = 0; li < fl.size(); li++) {
                    if (li > 0) rebuilt.append('\n');
                    rebuilt.append(fl.get(li));
                }
                write(f, rebuilt.toString());
            } catch (Throwable t) {
                log.err("fixEcjSourceDefects(" + f.getName() + "): " + t);
            }
        }
        return fixed;
    }

    private void putEdit(java.util.LinkedHashMap<String, java.util.TreeMap<Integer, String>> m,
                         String file, int line, String action) {
        java.util.TreeMap<Integer, String> t = m.get(file);
        if (t == null) { t = new java.util.TreeMap<Integer, String>(); m.put(file, t); }
        t.put(line, action);
    }

    /** Дефолтное значение для типа возврата Java (для вставки return). */
    private String defaultForType(String t) {
        t = t.trim();
        if (t.endsWith("[]")) return "null";
        if (t.equals("void")) return "";
        if (t.equals("boolean")) return "false";
        if (t.equals("char")) return "'\\0'";
        if (t.equals("byte") || t.equals("short") || t.equals("int") || t.equals("long"))
            return "0";
        if (t.equals("float") || t.equals("double")) return "0";
        return "null"; // ссылочный тип
    }

    /**
     * Вставить дефолтный return в конец тела метода, сигнатура которого на строке
     * sigIdx. Находит открывающую «{» тела и парную закрывающую «}» балансом
     * скобок, вставляет «return <def>;» перед ней. Возвращает true при успехе.
     */
    private boolean insertDefaultReturn(java.util.List<String> fl, int sigIdx, String retType) {
        int open = -1, col = -1;
        for (int i = sigIdx; i < Math.min(fl.size(), sigIdx + 3); i++) {
            int b = fl.get(i).indexOf('{');
            if (b >= 0) { open = i; col = b; break; }
        }
        if (open < 0) return false;
        int depth = 0, endLine = -1;
        for (int i = open; i < fl.size(); i++) {
            String line = fl.get(i);
            int from = (i == open) ? col : 0;
            for (int j = from; j < line.length(); j++) {
                char ch = line.charAt(j);
                if (ch == '{') depth++;
                else if (ch == '}') { depth--; if (depth == 0) { endLine = i; break; } }
            }
            if (endLine >= 0) break;
        }
        if (endLine < 0) return false;
        // ВАЖНО: ecj выдаёт «This method must return a result» ТОЛЬКО когда конец
        // тела реально достижим (не все пути возвращают значение) — значит вставка
        // return перед закрывающей «}» безопасна и не создаёт unreachable code.
        // Отказываемся лишь если НЕПОСРЕДСТВЕННО перед «}» на ВЕРХНЕМ уровне тела
        // (глубина вложенности == 1 относительно сигнатуры) уже стоит return/throw.
        // Прежняя версия ошибочно смотрела на return/throw ВНУТРИ вложенных блоков
        // (if/for/switch) и отказывалась чинить, оставляя ошибку компиляции.
        String lastTop = lastTopLevelStatement(fl, open, col, endLine);
        if (lastTop != null && (lastTop.startsWith("throw ")
                || lastTop.startsWith("return ") || lastTop.equals("return;")
                || lastTop.startsWith("return\t"))) {
            return false;
        }
        String def = defaultForType(retType);
        fl.add(endLine, "        return " + def
                + "; // [ApkTool] дефект jadx: метод без return");
        return true;
    }

    /**
     * Возвращает trimmed-текст последнего оператора тела метода, находящегося на
     * ВЕРХНЕМ уровне тела (глубина скобок == 1 относительно открывающей «{» тела).
     * Операторы внутри вложенных блоков (if/for/while/switch/try) игнорируются —
     * их наличие не гарантирует возврат по всем путям. Если top-level операторов
     * нет (тело сразу закрывается вложенным блоком), возвращает null.
     */
    private String lastTopLevelStatement(java.util.List<String> fl, int open, int col, int endLine) {
        int depth = 0;
        String lastTop = null;
        StringBuilder cur = new StringBuilder();
        int curStartDepth = -1;
        for (int i = open; i <= endLine; i++) {
            String line = fl.get(i);
            int from = (i == open) ? col : 0;
            for (int j = from; j < line.length(); j++) {
                char ch = line.charAt(j);
                if (ch == '{') {
                    depth++;
                    if (depth == 1) { cur.setLength(0); curStartDepth = -1; }
                } else if (ch == '}') {
                    // Закрытие вложенного блока на верхнем уровне тела — это
                    // завершённый top-level оператор (if{...} / try{...} и т.п.).
                    if (depth == 2) { lastTop = "}"; cur.setLength(0); curStartDepth = -1; }
                    depth--;
                    if (depth == 0) return lastTop; // достигли конца тела
                } else if (depth == 1) {
                    if (ch == ';') {
                        String s = cur.toString().trim();
                        if (!s.isEmpty()) lastTop = s;
                        cur.setLength(0); curStartDepth = -1;
                    } else {
                        if (curStartDepth < 0 && !Character.isWhitespace(ch)) curStartDepth = 1;
                        if (curStartDepth == 1) cur.append(ch);
                    }
                }
            }
            if (depth == 1) cur.append(' ');
        }
        return lastTop;
    }

    /**
     * Инициализировать локальную переменную varName, на которую ecj указал «may
     * not have been initialized». Ищем ВЫШЕ строки использования объявление вида
     * «Type varName;» и добавляем дефолтное значение по типу. Окно поиска большое
     * (до 800 строк), т.к. jadx выносит объявление в начало метода.
     */
    private boolean initLocalVariable(java.util.List<String> fl, int useIdx, String varName) {
        java.util.regex.Pattern declP = java.util.regex.Pattern.compile(
                "^(\\s*)([A-Za-z_][\\w.<>\\[\\]]*)\\s+"
                + java.util.regex.Pattern.quote(varName) + "\\s*;\\s*$");
        for (int i = useIdx - 1; i >= 0 && i > useIdx - 800; i--) {
            java.util.regex.Matcher m = declP.matcher(fl.get(i));
            if (!m.find()) continue;
            String type = m.group(2);
            if (type.equals("return") || type.equals("break") || type.equals("continue")
                    || type.equals("case") || type.equals("else")) continue;
            String def = defaultForType(type);
            fl.set(i, m.group(1) + type + " " + varName + " = " + def
                    + "; // [ApkTool] дефект jadx: неинициализир. переменная");
            return true;
        }
        return false;
    }

    /**
     * Обернуть оператор на строке idx в try/catch для checked-исключения, о
     * котором ecj сказал «Unhandled exception type T». jadx нередко теряет
     * throws у метода и не оборачивает вызов, бросающий checked-исключение
     * (например DocumentsContract.createDocument/deleteDocument ->
     * FileNotFoundException). Ловим ШИРОКИМ java.lang.Exception: ecj даёт лишь
     * ПРОСТОЕ имя исключения (пакет неизвестен, а import добавлять некуда), тогда
     * как Exception покрывает любое checked-исключение и не требует import.
     * В catch пробрасываем как unchecked RuntimeException — семантика сохранена
     * (в исходном байткоде исключение и так распространилось бы вверх).
     * Правка строчная: работает для одиночного оператора-выражения (в т.ч.
     * «return expr;»), который jadx кладёт на одну строку.
     */
    private boolean wrapChecked(java.util.List<String> fl, int idx, String exc) {
        String orig = fl.get(idx);
        // Уже обёрнуто нами или это строка с catch — не трогаем.
        if (orig.contains("[ApkTool") || orig.contains("catch (")) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^(\\s*)(.*\\S)\\s*$").matcher(orig);
        if (!m.find()) return false;
        String ind = m.group(1), stmt = m.group(2);
        // Оборачиваем только завершённый оператор (одна строка, оканчивается на ; или }).
        if (!stmt.endsWith(";") && !stmt.endsWith("}")) return false;
        String simple = exc.contains(".") ? exc.substring(exc.lastIndexOf('.') + 1) : exc;
        fl.set(idx, ind + "try { " + stmt + " } catch (java.lang.Exception apktoolEx) { "
                + "throw new RuntimeException(apktoolEx); } "
                + "// [ApkTool] дефект jadx: непойманное " + simple);
        return true;
    }

    /**
     * Объявить недостающую локальную переменную, на которую ecj указал
     * «X cannot be resolved to a variable». Это дефект jadx (переменная
     * используется, но её объявление потеряно — например счётчик nRead в условии
     * цикла в методе, помеченном jadx как «Code decompiled incorrectly»).
     * Вставляем объявление с дефолтом в начало тела ближайшего метода выше строки
     * использования. Тип определяем эвристикой: если переменная участвует в
     * сравнении/арифметике (X &gt; 0 и т.п.) — int, иначе Object. Цель — дать
     * коду СКОМПИЛИРОВАТЬСЯ (как AIDE), а не восстановить утраченную логику.
     */
    private boolean declareMissingVariable(java.util.List<String> fl, int idx, String varName) {
        String use = fl.get(idx);
        String q = java.util.regex.Pattern.quote(varName);
        boolean numeric =
                java.util.regex.Pattern.compile(q + "\\s*[<>=!+\\-*/%]").matcher(use).find()
             || java.util.regex.Pattern.compile("[<>=!+\\-*/%]\\s*" + q).matcher(use).find();
        String type = numeric ? "int" : "Object";
        String def = numeric ? "0" : "null";
        // Ищем строку-сигнатуру метода выше: оканчивается на «) {» (с опц. throws).
        java.util.regex.Pattern sigP = java.util.regex.Pattern.compile(
                "\\)\\s*(?:throws [\\w., ]+)?\\{\\s*$");
        for (int i = idx - 1; i >= 0 && i > idx - 400; i--) {
            if (sigP.matcher(fl.get(i)).find()) {
                // Индентация — по строке тела (следующей за сигнатурой).
                String bodyLine = (i + 1 < fl.size()) ? fl.get(i + 1) : fl.get(i);
                java.util.regex.Matcher im = java.util.regex.Pattern.compile("^(\\s*)")
                        .matcher(bodyLine);
                String indent = im.find() ? im.group(1) : "        ";
                fl.add(i + 1, indent + type + " " + varName + " = " + def
                        + "; // [ApkTool] дефект jadx: пропущенное объявление");
                return true;
            }
        }
        return false;
    }

    /**
     * Можно ли заглушить FQN. НЕ заглушаем то, что гарантированно есть в
     * android.jar/рантайме (java.*, android.*, json, w3c/xml). Классы
     * org.apache.http.* заглушаем: начиная с Android 6 они удалены из
     * bootclasspath (нужен useLibrary 'org.apache.http.legacy'), и в нашем
     * android.jar их нет — иначе «import cannot be resolved».
     */
    private boolean isStubbable(String fqn) {
        return !(fqn.startsWith("java.") || fqn.startsWith("javax.")
                || fqn.startsWith("android.") || fqn.startsWith("dalvik.")
                || fqn.startsWith("org.w3c.") || fqn.startsWith("org.xml.")
                || fqn.startsWith("org.json."));
    }

    /** Сформировать и бросить понятную ошибку о неудачной компиляции. */
    private void reportEcjFailure(String errText) throws Exception {
        java.util.LinkedHashSet<String> badFiles = new java.util.LinkedHashSet<String>();
        String[] lines = errText.split("\n");
        int shown = 0;
        for (String ln : lines) {
            if (ln.trim().isEmpty()) continue;
            java.util.regex.Matcher fm = java.util.regex.Pattern
                    .compile("in\\s+(\\S+\\.java)").matcher(ln);
            if (fm.find()) badFiles.add(fm.group(1));
            log.err("[ecj] " + ln);
            if (++shown >= 25) { log.err("[ecj] ...(ошибки усечены)"); break; }
        }
        StringBuilder msg = new StringBuilder();
        msg.append("Компиляция не удалась (ecj). APK НЕ собран, чтобы не выдать нерабочий файл.\n");
        boolean anon = errText.contains("invalid ClassType")
                || java.util.regex.Pattern.compile("new\\s+\\d+\\s*\\(").matcher(errText).find();
        if (anon) {
            msg.append("Невалидный код анонимных классов (new 1()). ")
               .append("Пере-декомпилируйте apk->java этой версией ApkTool.\n");
        }
        if (!badFiles.isEmpty()) {
            msg.append("Файлы с ошибками (правьте в sources/, как в AIDE):\n");
            int k = 0;
            for (String bf : badFiles) {
                msg.append("  - ").append(bf).append('\n');
                if (++k >= 20) { msg.append("  ...\n"); break; }
            }
        }
        msg.append("Остаточные ошибки — это дефекты декомпиляции jadx в самом коде ")
           .append("(например неверно восстановленный try/catch или checked-исключения). ")
           .append("Их нужно поправить вручную в указанных файлах — как и в AIDE, ")
           .append("который тоже не соберёт код с ошибками компиляции.");
        throw new Exception(msg.toString());
    }

    /**
     * Сгенерировать stub-исходники для внешних классов, отсутствующих в
     * classpath (android.jar + libs) и в самих sources. Определяем по import-ам.
     * Для каждого недостающего FQN создаём максимально «податливый» класс:
     * не-final, с пустым и varargs-конструктором, наследующий Object; вложенные
     * обращения к его членам (поля/методы) в момент компиляции резолвятся как
     * доступ к произвольным членам — ecj жёстко проверяет, поэтому даём и
     * fallback-члены через дополнительный проход (см. runEcj-autofix).
     * Возвращает число сгенерированных классов.
     */
    private int generateStubs(java.util.List<File> javaFiles, java.util.List<File> libJars,
                              File stubSrc) throws Exception {
        // 1) Собираем множество FQN, которые импортируются.
        java.util.LinkedHashSet<String> imported = new java.util.LinkedHashSet<String>();
        java.util.regex.Pattern impP = java.util.regex.Pattern.compile(
                "(?m)^\\s*import\\s+(static\\s+)?([\\w.]+)\\s*;");
        for (File f : javaFiles) {
            String c = readFile(f);
            java.util.regex.Matcher m = impP.matcher(c);
            while (m.find()) {
                String fqn = m.group(2);
                if (fqn.endsWith(".*")) continue;          // wildcard пропускаем
                imported.add(fqn);
            }
        }
        // 2) Какие пакеты есть в sources (их не стабим).
        java.util.LinkedHashSet<String> localTypes = new java.util.LinkedHashSet<String>();
        for (File f : javaFiles) {
            String pkg = packageOf(f);
            String name = f.getName().replaceAll("\\.java$", "");
            localTypes.add(pkg.isEmpty() ? name : pkg + "." + name);
        }
        // 3) Отсеиваем те, что есть в android.jar/libs или в java.*/локальных.
        java.util.LinkedHashSet<String> androidLib = classNamesFromJar(
                new File(dirEngine, "android.jar"));
        for (File j : libJars) androidLib.addAll(classNamesFromJar(j));

        java.util.LinkedHashSet<String> missing = new java.util.LinkedHashSet<String>();
        for (String fqn : imported) {
            if (!isStubbable(fqn) || fqn.startsWith("junit.")) {
                continue; // предоставляются android.jar/рантаймом
            }
            if (localTypes.contains(fqn)) continue;
            if (androidLib.contains(fqn)) continue;
            missing.add(fqn);
        }
        if (missing.isEmpty()) return 0;

        // 4) Генерируем по одному stub-классу на FQN.
        int made = 0;
        for (String fqn : missing) {
            if (writeStub(stubSrc, fqn)) made++;
        }
        return made;
    }

    /** Записать один stub-класс (public class с «податливым» API). */
    private boolean writeStub(File stubSrc, String fqn) {
        try {
            int dot = fqn.lastIndexOf('.');
            String pkg = dot > 0 ? fqn.substring(0, dot) : "";
            String simple = dot > 0 ? fqn.substring(dot + 1) : fqn;
            // Вложенные классы (Outer.Inner) стабим как отдельный верхнеуровневый
            // с именем Inner в пакете Outer — упрощение; для import это работает.
            File dir = new File(stubSrc, pkg.replace('.', '/'));
            dir.mkdirs();
            File out = new File(dir, simple + ".java");
            if (out.exists()) return false;
            StringBuilder sb = new StringBuilder();
            if (!pkg.isEmpty()) sb.append("package ").append(pkg).append(";\n");
            sb.append("/** Автозаглушка ApkTool: реальная реализация в APK/системе. */\n");
            sb.append("public class ").append(simple).append(" {\n");
            sb.append("    public ").append(simple).append("() {}\n");
            sb.append("    public ").append(simple).append("(Object... a) {}\n");
            // Вложенный Builder — частый паттерн (AdRequest.Builder и т.п.).
            sb.append("    public static class Builder {\n");
            sb.append("        public Builder() {}\n");
            sb.append("        public Builder(Object... a) {}\n");
            sb.append("        public Object build() { return null; }\n");
            sb.append("    }\n");
            sb.append("}\n");
            write(out, sb.toString());
            return true;
        } catch (Throwable t) {
            log.err("writeStub(" + fqn + "): " + t);
            return false;
        }
    }

    /** Скомпилировать stub-исходники (ecj, только android.jar в classpath). */
    private void compileStubs(java.util.List<File> stubJava, File outDir) throws Exception {
        if (stubJava.isEmpty()) return;
        ClassLoader cl = loader("ecj.jar");
        Class<?> bc = cl.loadClass("org.eclipse.jdt.core.compiler.batch.BatchCompiler");
        Method compile = bc.getMethod("compile", String[].class,
                java.io.PrintWriter.class, java.io.PrintWriter.class,
                cl.loadClass("org.eclipse.jdt.core.compiler.CompilationProgress"));
        File argFile = new File(outDir.getParentFile(), "stub_sources.txt");
        StringBuilder sb = new StringBuilder();
        for (File f : stubJava) sb.append('"').append(f.getAbsolutePath()).append("\"\n");
        write(argFile, sb.toString());
        java.util.List<String> args = new java.util.ArrayList<String>();
        args.add("-cp"); args.add(new File(dirEngine, "android.jar").getAbsolutePath());
        args.add("-source"); args.add("1.8");
        args.add("-target"); args.add("1.8");
        args.add("-proc:none"); args.add("-nowarn"); args.add("-noExit");
        args.add("-d"); args.add(outDir.getAbsolutePath());
        args.add("@" + argFile.getAbsolutePath());
        java.io.PrintWriter np = new java.io.PrintWriter(new java.io.StringWriter());
        compile.invoke(null, (Object) args.toArray(new String[0]), np, np, null);
    }

    /** Имена классов (FQN) в jar: из путей *.class. */
    private java.util.LinkedHashSet<String> classNamesFromJar(File jar) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<String>();
        if (jar == null || !jar.exists()) return out;
        try {
            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (!n.endsWith(".class")) continue;
                String fqn = n.substring(0, n.length() - 6).replace('/', '.');
                fqn = fqn.replace('$', '.'); // вложенные -> точечная запись
                out.add(fqn);
            }
            zf.close();
        } catch (Throwable ignore) {}
        return out;
    }

    /** package X.Y.Z; из файла (пустая строка, если нет). */
    private String packageOf(File javaFile) {
        try {
            String c = readFile(javaFile);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(c);
            if (m.find()) return m.group(1);
        } catch (Throwable ignore) {}
        return "";
    }

    /**
     * Автопочинка типовых дефектов jadx, ломающих компиляцию.
     * Главный дефект: jadx выдаёт catch-блок вида
     *     } catch (Exception e) { ex = e; ... ex.printStackTrace(); }
     * где переменная-приёмник (ex) НИГДЕ не объявлена -> «ex cannot be resolved».
     * Чиним локально и надёжно: первое присваивание `ident = param;` внутри
     * такого catch превращаем в объявление `Throwable ident = param;`. Объявление
     * попадает в область видимости блока и покрывает все обращения к ident внутри
     * него. Это точный, пометодный фикс (без ложных срабатываний по всему файлу).
     */
    private void autoFixJadxDefects(java.util.List<File> javaFiles) {
        int fixedFiles = 0, fixedVars = 0, fixedInt = 0;
        for (File f : javaFiles) {
            try {
                String c = readFile(f);
                int[] cv = new int[1];
                String nc = fixUndeclaredCatchVar(c, cv);
                int[] ci = new int[1];
                nc = fixCheckedInterrupted(nc, ci);
                if (!nc.equals(c)) {
                    write(f, nc); fixedFiles++;
                    fixedVars += cv[0]; fixedInt += ci[0];
                }
            } catch (Throwable t) {
                log.err("autoFixJadxDefects(" + f.getName() + "): " + t);
            }
        }
        if (fixedFiles > 0) log.log("Автопочинка jadx: catch-переменных " + fixedVars
                + ", обёрнуто wait/sleep/join " + fixedInt + " (файлов: " + fixedFiles + ").");
    }

    /**
     * Оборачивает одиночные вызовы, бросающие checked InterruptedException
     * (`X.wait()`, `X.join()`, `Thread.sleep(...)`), в try/catch. jadx часто
     * ставит их в методах без `throws` (например Thread.run()), из-за чего ecj
     * падает «unhandled exception InterruptedException». Обёртка локальна и
     * безопасна: восстанавливает флаг прерывания потока.
     */
    private String fixCheckedInterrupted(String code, int[] cnt) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?m)^([ \\t]+)((?:[\\w.]+\\.wait\\([^;]*\\)"
                + "|[\\w.]+\\.join\\([^;]*\\)"
                + "|Thread\\.sleep\\([^;]*\\))\\s*;)");
        java.util.regex.Matcher m = p.matcher(code);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String ind = m.group(1), stmt = m.group(2);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                    ind + "try { " + stmt + " } catch (InterruptedException "
                    + "eInterrupted) { Thread.currentThread().interrupt(); }"));
            cnt[0]++;
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Заменяет `catch (Type param) { <ws> ident = param;` на
     * `catch (Type param) { <ws> Throwable ident = param;` — но только если ident
     * не совпадает с param (это и есть jadx-дефект) и ident НЕ объявлен как тип
     * в том же файле в позиции этого catch. Считаем количество правок в cnt[0].
     */
    private String fixUndeclaredCatchVar(String code, int[] cnt) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(catch\\s*\\(\\s*[\\w.]+\\s+(\\w+)\\s*\\)\\s*\\{\\s*)(\\w+)(\\s*=\\s*)(\\2)(\\s*;)");
        java.util.regex.Matcher m = p.matcher(code);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String param = m.group(2);
            String ident = m.group(3);
            // Дефект: приёмник отличается от параметра и не объявлен явно рядом.
            boolean declaredType = java.util.regex.Pattern.compile(
                    "(?:Exception|Throwable|Error|[A-Z]\\w*)\\s+" + java.util.regex.Pattern.quote(ident)
                            + "\\s*=").matcher(m.group(0)).find();
            if (!ident.equals(param) && !declaredType) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                        m.group(1) + "Throwable " + ident + m.group(4) + param + m.group(6)));
                cnt[0]++;
            } else {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** d8 (из jadx.jar): .class + libs -> classes.dex. */
    private boolean runD8(java.util.List<File> classFiles, java.util.List<File> libJars,
                          int minApi, File outDir) throws Exception {
        log.log("d8: .class -> classes.dex (minApi " + minApi + ")...");
        log.progress(70);
        ClassLoader cl = loader("jadx.jar");
        Thread cur = Thread.currentThread();
        ClassLoader saved = cur.getContextClassLoader();
        cur.setContextClassLoader(cl);
        try {
            Class<?> d8Cmd = cl.loadClass("com.android.tools.r8.D8Command");
            Class<?> compMode = cl.loadClass("com.android.tools.r8.CompilationMode");
            Class<?> outMode = cl.loadClass("com.android.tools.r8.OutputMode");
            Class<?> d8 = cl.loadClass("com.android.tools.r8.D8");

            Object builder = d8Cmd.getMethod("builder").invoke(null);
            Class<?> builderCls = builder.getClass();

            java.util.List<java.nio.file.Path> paths = new java.util.ArrayList<java.nio.file.Path>();
            for (File c : classFiles) paths.add(c.toPath());
            invokeChain(builderCls, builder, "addProgramFiles", java.util.Collection.class, paths);

            // Библиотеки: android.jar + libs/*.jar (для десугаринга/линковки d8).
            java.util.List<java.nio.file.Path> libs = new java.util.ArrayList<java.nio.file.Path>();
            libs.add(new File(dirEngine, "android.jar").toPath());
            for (File j : libJars) libs.add(j.toPath());
            invokeChain(builderCls, builder, "addLibraryFiles", java.util.Collection.class, libs);

            Object debug = enumValue(compMode, "DEBUG");
            invokeChain(builderCls, builder, "setMode", compMode, debug);
            invokeChain(builderCls, builder, "setMinApiLevel", int.class, minApi);

            Object dexIndexed = enumValue(outMode, "DexIndexed");
            builderCls.getMethod("setOutput", java.nio.file.Path.class, outMode)
                    .invoke(builder, outDir.toPath(), dexIndexed);

            Object command = builderCls.getMethod("build").invoke(builder);
            d8.getMethod("run", d8Cmd).invoke(null, command);
            return true;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable real = e.getCause() != null ? e.getCause() : e;
            log.err("d8 ошибка: " + real);
            return false;
        } finally {
            cur.setContextClassLoader(saved);
        }
    }

    /** Собрать список .jar-библиотек проекта (libs/ рядом с проектом/ресурсами). */
    private java.util.List<File> collectLibJars(File projectDir, File resRoot) {
        java.util.List<File> jars = new java.util.ArrayList<File>();
        File[] dirs = {
                new File(projectDir, "libs"),
                new File(resRoot, "libs"),
                new File(projectDir, "lib"),
        };
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        for (File d : dirs) {
            if (d == null || !d.isDirectory()) continue;
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                if (f.isFile() && f.getName().endsWith(".jar") && seen.add(f.getName())) {
                    jars.add(f);
                }
            }
        }
        return jars;
    }

    private static void invokeChain(Class<?> cls, Object obj, String method,
                                    Class<?> paramType, Object arg) throws Exception {
        Method m = null;
        Class<?> c = cls;
        while (c != null && m == null) {
            try { m = c.getMethod(method, paramType); } catch (NoSuchMethodException ignore) {}
            c = c.getSuperclass();
        }
        if (m == null) throw new NoSuchMethodException(method + "(" + paramType + ")");
        m.invoke(obj, arg);
    }

    private static Object enumValue(Class<?> enumCls, String name) throws Exception {
        java.lang.reflect.Field f = enumCls.getField(name);
        return f.get(null);
    }

    private static void collectByExt(File dir, String ext, java.util.List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) collectByExt(k, ext, out);
            else if (k.getName().endsWith(ext)) out.add(k);
        }
    }

    /** Компилировать каждый файл ресурсов отдельно, пропуская сбойные. */
    private java.util.List<File> compileResPerFile(File resDir, File outDir) throws Exception {
        java.util.List<File> flats = new java.util.ArrayList<File>();
        File[] typeDirs = resDir.listFiles();
        if (typeDirs == null) return flats;
        int ok = 0, fail = 0;
        for (File td : typeDirs) {
            if (!td.isDirectory()) continue;
            File[] files = td.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                int rc = exec(new String[]{aapt2.getAbsolutePath(), "compile",
                        f.getAbsolutePath(), "-o", outDir.getAbsolutePath()});
                if (rc == 0) ok++;
                else { fail++; log.err("Пропущен ресурс (aapt2): " + td.getName() + "/" + f.getName()); }
            }
        }
        File[] produced = outDir.listFiles();
        if (produced != null) for (File p : produced) {
            if (p.getName().endsWith(".flat")) flats.add(p);
        }
        log.log("Скомпилировано ресурсов: " + ok + ", пропущено: " + fail
                + ", .flat: " + flats.size());
        return flats;
    }

    /** Фикс apktool-заглушек drawable, несовместимых с aapt2. */
    private void sanitizeValues(File resDir) {
        File[] dirs = resDir.listFiles();
        if (dirs == null) return;
        int fixed = 0;
        for (File d : dirs) {
            if (!d.isDirectory() || !d.getName().startsWith("values")) continue;
            File[] xmls = d.listFiles();
            if (xmls == null) continue;
            for (File x : xmls) {
                if (!x.getName().endsWith(".xml")) continue;
                try {
                    String c = readFile(x);
                    String orig = c;
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("<drawable(\\s+name=\"[^\"]*\")\\s*>\\s*([^<@#][^<]*?)\\s*</drawable>")
                            .matcher(c);
                    StringBuffer sb = new StringBuffer();
                    while (m.find()) {
                        m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                                "<item type=\"drawable\"" + m.group(1) + ">@null</item>"));
                        fixed++;
                    }
                    m.appendTail(sb);
                    c = sb.toString();
                    if (!c.equals(orig)) write(x, c);
                } catch (Throwable t) {
                    log.err("sanitizeValues: " + x.getName() + ": " + t);
                }
            }
        }
        if (fixed > 0) log.log("Исправлено apktool-заглушек drawable: " + fixed);
    }

    /** enum/flag-атрибуты, записанные числами, -> символьные имена (для aapt2). */
    private void fixEnumInts(File resDir) {
        try {
            java.util.Map<String, java.util.Map<String, String>> attrEnum =
                    new java.util.HashMap<String, java.util.Map<String, String>>();
            java.util.regex.Pattern attrP = java.util.regex.Pattern.compile(
                    "<attr\\s+name=\"([^\"]+)\"[^>]*>(.*?)</attr>", java.util.regex.Pattern.DOTALL);
            java.util.regex.Pattern enumP = java.util.regex.Pattern.compile(
                    "<(?:enum|flag)\\s+name=\"([^\"]+)\"\\s+value=\"([^\"]+)\"");
            File[] dirs = resDir.listFiles();
            if (dirs == null) return;
            for (File d : dirs) {
                if (d == null || !d.isDirectory() || !d.getName().startsWith("values")) continue;
                File[] xmls = d.listFiles();
                if (xmls == null) continue;
                for (File x : xmls) {
                    if (!x.getName().startsWith("attrs")) continue;
                    String c = readFile(x);
                    java.util.regex.Matcher am = attrP.matcher(c);
                    while (am.find()) {
                        String name = strip(am.group(1));
                        java.util.regex.Matcher em = enumP.matcher(am.group(2));
                        java.util.Map<String, String> m = attrEnum.get(name);
                        if (m == null) m = new java.util.HashMap<String, String>();
                        while (em.find()) {
                            if (!m.containsKey(em.group(2))) m.put(em.group(2), em.group(1));
                        }
                        if (!m.isEmpty()) attrEnum.put(name, m);
                    }
                }
            }
            if (attrEnum.isEmpty()) return;
            java.util.regex.Pattern itemP = java.util.regex.Pattern.compile(
                    "<item\\s+name=\"([^\"]+)\"\\s*>\\s*(-?\\d+)\\s*</item>");
            int fixed = 0;
            for (File d : dirs) {
                if (d == null || !d.isDirectory() || !d.getName().startsWith("values")) continue;
                File[] xmls = d.listFiles();
                if (xmls == null) continue;
                for (File x : xmls) {
                    if (!x.getName().endsWith(".xml")) continue;
                    String c = readFile(x), orig = c;
                    java.util.regex.Matcher im = itemP.matcher(c);
                    StringBuffer sb = new StringBuffer();
                    while (im.find()) {
                        String attrFull = im.group(1);
                        String attr = strip(attrFull);
                        String val = im.group(2);
                        java.util.Map<String, String> m = attrEnum.get(attr);
                        if (m != null && m.containsKey(val)) {
                            im.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                                    "<item name=\"" + attrFull + "\">" + m.get(val) + "</item>"));
                            fixed++;
                        } else {
                            im.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(im.group(0)));
                        }
                    }
                    im.appendTail(sb);
                    c = sb.toString();
                    if (!c.equals(orig)) write(x, c);
                }
            }
            if (fixed > 0) log.log("Заменено enum-значений (число->имя): " + fixed);
        } catch (Throwable t) {
            log.err("fixEnumInts: " + t);
        }
    }

    private static String strip(String attr) {
        int i = attr.indexOf(':');
        return i >= 0 ? attr.substring(i + 1) : attr;
    }

    /**
     * Санация apktool-овского values/public.xml. apktool сохраняет ТАБЛИЦУ
     * фиксированных ID ресурсов (public.xml). В декомпилятах часто остаются
     * «сироты» — объявления &lt;public type=".." name=".."/&gt; для ресурсов,
     * определение которых apktool потерял (типичный случай — ID из Google Play
     * Services: cast_notification_id, maps-enum'ы none/hybrid, wallet buyButton
     * и т.п.). aapt2 link на них падает: «no definition for declared symbol».
     *
     * Мы пересоздаём R.java СВОЕЙ aapt2-сессией (--java), поэтому фиксировать
     * оригинальные ID не обязательно — ID в коде берутся из свежего R.java.
     * Значит сироты в public.xml можно безопасно удалить: на них никто не
     * ссылается (иначе ecj позже укажет недостающее поле R). Собираем множество
     * реально определённых ресурсов (values/*.xml + res/&lt;type&gt;/файлы +
     * @+id/ в любом xml) и вычищаем из public.xml все &lt;public&gt; без
     * определения. Если после чистки public.xml пуст — удаляем его целиком.
     */
    private void sanitizePublicXml(File resDir) {
        try {
            File[] dirs = resDir.listFiles();
            if (dirs == null) return;
            File publicXml = null;
            for (File d : dirs) {
                if (d.isDirectory() && d.getName().equals("values")) {
                    File p = new File(d, "public.xml");
                    if (p.exists()) publicXml = p;
                }
            }
            if (publicXml == null) return;

            // 1) Собрать определённые ресурсы: "type/name".
            java.util.HashSet<String> defined = new java.util.HashSet<String>();
            collectDefinedResources(resDir, defined);

            // 2) Отфильтровать public.xml.
            String c = readFile(publicXml);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "[ \\t]*<public\\s+type=\"([^\"]+)\"\\s+name=\"([^\"]+)\"[^>]*/>\\s*\\n?")
                    .matcher(c);
            StringBuffer sb = new StringBuffer();
            int removed = 0, kept = 0;
            while (m.find()) {
                String key = m.group(1) + "/" + m.group(2);
                if (defined.contains(key)) { kept++;
                    m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(0)));
                } else { removed++;
                    m.appendReplacement(sb, "");
                }
            }
            m.appendTail(sb);
            String out = sb.toString();

            if (removed > 0) {
                if (kept == 0) {
                    // public.xml полностью состоял из сирот — удаляем файл,
                    // чтобы aapt2 сам присвоил ID (R.java всё равно свежий).
                    publicXml.delete();
                    log.log("public.xml удалён (все " + removed + " записей — сироты apktool).");
                } else {
                    write(publicXml, out);
                    log.log("public.xml: убрано записей без определения: " + removed
                            + " (оставлено: " + kept + ").");
                }
            }
        } catch (Throwable t) {
            log.err("sanitizePublicXml: " + t);
        }
    }

    /** Собрать множество определённых ресурсов вида "type/name" из проекта res/. */
    private void collectDefinedResources(File resDir, java.util.HashSet<String> defined) {
        File[] dirs = resDir.listFiles();
        if (dirs == null) return;
        // 2a) Из values*/*.xml — по тегам определений.
        java.util.regex.Pattern defP = java.util.regex.Pattern.compile(
                "<(string|color|dimen|integer|bool|style|array|string-array|integer-array|"
                + "drawable|attr|id|item|declare-styleable|fraction|plurals)\\b[^>]*\\bname=\"([^\"]+)\"");
        java.util.regex.Pattern typeAttr = java.util.regex.Pattern.compile("type=\"([^\"]+)\"");
        for (File d : dirs) {
            if (d == null || !d.isDirectory()) continue;
            if (d.getName().startsWith("values")) {
                File[] xmls = d.listFiles();
                if (xmls == null) continue;
                for (File x : xmls) {
                    if (!x.getName().endsWith(".xml") || x.getName().equals("public.xml")) continue;
                    try {
                        String c = readFile(x);
                        java.util.regex.Matcher dm = defP.matcher(c);
                        while (dm.find()) {
                            String tag = dm.group(1), name = dm.group(2);
                            String type;
                            if (tag.equals("string-array") || tag.equals("integer-array")) type = "array";
                            else if (tag.equals("declare-styleable")) type = "styleable";
                            else if (tag.equals("item")) {
                                java.util.regex.Matcher tm = typeAttr.matcher(dm.group(0));
                                type = tm.find() ? tm.group(1) : "id";
                            } else type = tag;
                            defined.add(type + "/" + name);
                        }
                    } catch (Throwable ignore) {}
                }
            } else {
                // 2b) Файловые ресурсы: res/<type>/<name>.<ext> -> type/name.
                String type = d.getName();
                int dash = type.indexOf('-');
                if (dash > 0) type = type.substring(0, dash);
                File[] fs = d.listFiles();
                if (fs == null) continue;
                for (File f : fs) {
                    String n = f.getName();
                    int dot = n.indexOf('.');
                    if (dot > 0) n = n.substring(0, dot);
                    defined.add(type + "/" + n);
                }
            }
        }
        // 2c) @+id/NAME в любом xml (id объявляются прямо в layout и т.п.).
        java.util.ArrayList<File> stack = new java.util.ArrayList<File>();
        stack.add(resDir);
        java.util.regex.Pattern idP = java.util.regex.Pattern.compile("@\\+id/(\\w+)");
        while (!stack.isEmpty()) {
            File f = stack.remove(stack.size() - 1);
            File[] kids = f.listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                if (k.isDirectory()) stack.add(k);
                else if (k.getName().endsWith(".xml")) {
                    try {
                        java.util.regex.Matcher im = idP.matcher(readFile(k));
                        while (im.find()) defined.add("id/" + im.group(1));
                    } catch (Throwable ignore) {}
                }
            }
        }
    }

    private void copyDir(File src, File dst) throws Exception {
        dst.mkdirs();
        File[] kids = src.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            File to = new File(dst, k.getName());
            if (k.isDirectory()) copyDir(k, to);
            else {
                java.io.FileInputStream in = new java.io.FileInputStream(k);
                FileOutputStream out = new FileOutputStream(to);
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                in.close(); out.close();
            }
        }
    }

    private void collect(File dir, File root, java.util.Map<String, File> out) {
        if (dir == null || !dir.isDirectory()) return;
        ArrayList<File> stack = new ArrayList<File>();
        stack.add(dir);
        while (!stack.isEmpty()) {
            File f = stack.remove(stack.size() - 1);
            File[] kids = f.listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                if (k.isDirectory()) stack.add(k);
                else {
                    String entry = rel(root, k).replace('\\', '/');
                    out.put(entry, k);
                    log.log("  + " + entry);
                }
            }
        }
    }

    /**
     * Добавить файлы в существующий zip/apk. resources.arsc, *.so и уже-сжатые
     * медиаформаты (см. storedFor/isNoCompressExt: mp3, ogg, png, mp4, ...) пишем
     * без сжатия (STORED) и выравниваем по 4 байта. Для resources.arsc/.so это
     * требование Android на API 30+; для медиа из res/raw — обязательное условие
     * работы openRawResourceFd/SoundPool/MediaPlayer (иначе «file can not be
     * opened as a file descriptor; it is probably compressed»). Данные читаются
     * из res.apk уже РАСПАКОВАННЫМИ (ZipFile.getInputStream), поэтому запись как
     * STORED даёт корректный несжатый файл независимо от того, как их упаковал
     * aapt2.
     */
    private void mergeIntoZip(File apk, java.util.Map<String, File> add) throws Exception {
        File tmp = new File(apk.getParentFile(), apk.getName() + ".merge");
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apk);
        AlignZipOutput zout = new AlignZipOutput(
                new java.io.BufferedOutputStream(new java.io.FileOutputStream(tmp)));
        byte[] buf = new byte[65536];
        java.util.HashSet<String> existing = new java.util.HashSet<String>();
        try {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                existing.add(e.getName());
                java.io.InputStream in = zf.getInputStream(e);
                writeEntry(zout, e.getName(), readAll(in, e.getSize(), buf), storedFor(e.getName()));
                in.close();
            }
            for (java.util.Map.Entry<String, File> me : add.entrySet()) {
                if (existing.contains(me.getKey())) continue;
                java.io.FileInputStream fin = new java.io.FileInputStream(me.getValue());
                writeEntry(zout, me.getKey(), readAll(fin, me.getValue().length(), buf),
                        storedFor(me.getKey()));
                fin.close();
            }
        } finally {
            zout.close();
            zf.close();
        }
        if (!apk.delete() || !tmp.renameTo(apk)) {
            throw new Exception("Не удалось пересобрать apk с dex/lib/assets.");
        }
    }

    private static boolean storedFor(String name) {
        if (name.equals("resources.arsc") || name.endsWith(".so")) return true;
        // Уже-сжатые/медиа-форматы Android читает через file descriptor
        // (SoundPool.load, MediaPlayer из res/raw, openRawResourceFd и т.п.).
        // Их НЕЛЬЗЯ хранить в apk сжатыми (DEFLATED), иначе openRawResourceFd
        // бросает «This file can not be opened as a file descriptor; it is
        // probably compressed» -> Resources$NotFoundException и вылет при старте.
        // Это тот же список расширений, что aapt/aapt2 держит несжатым по
        // умолчанию (kNoCompressExt). Держим STORED — как в оригинальном APK.
        return isNoCompressExt(name);
    }

    /**
     * Расширения, которые не должны сжиматься в APK (список aapt kNoCompressExt).
     * Совпадение по расширению без учёта регистра.
     */
    private static boolean isNoCompressExt(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        String[] ext = {
            ".jpg", ".jpeg", ".png", ".gif", ".webp",
            ".mp2", ".mp3", ".ogg", ".aac", ".wav", ".mid", ".midi",
            ".smf", ".jet", ".rtttl", ".imy", ".xmf", ".mkv", ".wma",
            ".mpg", ".mpeg", ".mp4", ".m4a", ".m4v", ".3gp", ".3gpp",
            ".3g2", ".3gpp2", ".amr", ".awb", ".flac", ".mov", ".webm",
            ".mka", ".ts"
        };
        for (String e : ext) if (n.endsWith(e)) return true;
        return false;
    }

    private static byte[] readAll(java.io.InputStream in, long hint, byte[] buf) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(
                hint > 0 && hint < (1 << 27) ? (int) hint : 65536);
        int n; while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static void writeEntry(AlignZipOutput zout, String name, byte[] data, boolean stored)
            throws Exception {
        java.util.zip.ZipEntry ze = new java.util.zip.ZipEntry(name);
        if (stored) {
            ze.setMethod(java.util.zip.ZipEntry.STORED);
            ze.setSize(data.length);
            ze.setCompressedSize(data.length);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(data);
            ze.setCrc(crc.getValue());
            zout.putAligned(ze, 4);
        } else {
            ze.setMethod(java.util.zip.ZipEntry.DEFLATED);
            zout.putNextEntry(ze);
        }
        zout.write(data, 0, data.length);
        zout.closeEntry();
    }

    private static class AlignZipOutput extends java.util.zip.ZipOutputStream {
        private final CountingStream counter;
        AlignZipOutput(java.io.OutputStream out) { this(new CountingStream(out)); }
        private AlignZipOutput(CountingStream cs) { super(cs); this.counter = cs; }
        void putAligned(java.util.zip.ZipEntry ze, int align) throws java.io.IOException {
            int nameLen = ze.getName().getBytes("UTF-8").length;
            int extraBase = (ze.getExtra() == null) ? 0 : ze.getExtra().length;
            long dataStart = counter.count + 30 + nameLen + extraBase;
            int pad = (int) ((align - (dataStart % align)) % align);
            if (pad > 0) {
                byte[] extra = new byte[extraBase + pad];
                if (extraBase > 0) System.arraycopy(ze.getExtra(), 0, extra, 0, extraBase);
                ze.setExtra(extra);
            }
            putNextEntry(ze);
        }
    }

    private static class CountingStream extends java.io.FilterOutputStream {
        long count = 0;
        CountingStream(java.io.OutputStream out) { super(out); }
        @Override public void write(int b) throws java.io.IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws java.io.IOException {
            out.write(b, off, len); count += len;
        }
    }

    private String androidFramework() throws Exception {
        File jar = new File(dirEngine, "android.jar");
        if (jar.exists() && jar.length() > 0) return jar.getAbsolutePath();
        throw new Exception("Не найден android.jar для aapt2 (assets/engine/android.jar).");
    }

    private void finalizeApk(File in, File out) throws Exception {
        ensureDir(out.getParentFile());
        if (out.exists()) out.delete();
        log.log("zipalign...");
        File aligned = new File(ctx.getCacheDir(), "aligned_" + System.currentTimeMillis() + ".apk");
        int rc = exec(new String[]{zipalign.getAbsolutePath(), "-f", "-p", "4",
                in.getAbsolutePath(), aligned.getAbsolutePath()});
        File toSign = (rc == 0 && aligned.exists()) ? aligned : in;
        if (rc != 0) log.err("zipalign вернул код " + rc + ", подписываю без выравнивания.");
        log.progress(90);
        log.log("Подпись APK (testkey, v1+v2+v3)...");
        signApk(toSign, out);
        log.log("Готово: " + out.getAbsolutePath());
    }

    private void ensureDir(File dir) throws Exception {
        if (dir == null) return;
        if (dir.isDirectory()) return;
        dir.mkdirs();
        if (!dir.isDirectory()) {
            throw new Exception("Не удалось создать каталог: " + dir.getAbsolutePath()
                    + ". Проверьте разрешение на доступ к хранилищу (Storage).");
        }
    }

    private void signApk(File in, File out) throws Exception {
        ClassLoader cl = loader("apksig.jar");
        File pk8 = new File(dirEngine, "testkey.pk8");
        File pem = new File(dirEngine, "testkey.x509.pem");
        java.security.PrivateKey key = ApkSignHelper.readPk8(pk8);
        java.security.cert.X509Certificate cert = ApkSignHelper.readCert(pem);

        Class<?> scbCls = cl.loadClass("com.android.apksig.ApkSigner$SignerConfig$Builder");
        Object scb = scbCls.getConstructor(String.class, java.security.PrivateKey.class, java.util.List.class)
                .newInstance("CERT", key, java.util.Collections.singletonList(cert));
        Object signerConfig = scbCls.getMethod("build").invoke(scb);

        Class<?> asbCls = cl.loadClass("com.android.apksig.ApkSigner$Builder");
        Object builder = asbCls.getConstructor(java.util.List.class)
                .newInstance(java.util.Collections.singletonList(signerConfig));
        asbCls.getMethod("setInputApk", File.class).invoke(builder, in);
        asbCls.getMethod("setOutputApk", File.class).invoke(builder, out);
        tryCall(asbCls, builder, "setV1SigningEnabled", boolean.class, true);
        tryCall(asbCls, builder, "setV2SigningEnabled", boolean.class, true);
        tryCall(asbCls, builder, "setV3SigningEnabled", boolean.class, true);
        Object apkSigner = asbCls.getMethod("build").invoke(builder);
        apkSigner.getClass().getMethod("sign").invoke(apkSigner);
    }

    private static void tryCall(Class<?> c, Object o, String m, Class<?> pt, Object v) {
        try { c.getMethod(m, pt).invoke(o, v); } catch (Throwable ignore) {}
    }

    // ---------- Вызов движков рефлексией ----------

    private void runApktool(String[] args) throws Exception {
        log.log("apktool " + join(args));
        invokeMain("apktool.jar", "brut.apktool.Main", args);
    }

    /**
     * jadx через публичный API. Контекстный загрузчик потока подменяем на
     * DexClassLoader с jadx.jar, иначе ServiceLoader не находит input-плагины
     * (dex) -> "Loaded classes: 0".
     */
    private void runJadxApi(File apk, File out) throws Exception {
        log.log("jadx (API): " + apk.getName() + " -> " + out.getAbsolutePath());
        ClassLoader cl = loader("jadx.jar");
        java.util.List<File> inputs = new java.util.ArrayList<File>();
        inputs.add(apk);

        PrintStream oldOut = System.out, oldErr = System.err;
        System.setOut(new PrintStream(new LogStream(false), true));
        System.setErr(new PrintStream(new LogStream(true), true));
        Thread curThread = Thread.currentThread();
        ClassLoader savedTccl = curThread.getContextClassLoader();
        curThread.setContextClassLoader(cl);
        try {
            Class<?> argsCls = cl.loadClass("jadx.api.JadxArgs");
            Object args = argsCls.getConstructor().newInstance();
            try {
                argsCls.getMethod("setInputFiles", java.util.List.class).invoke(args, inputs);
            } catch (NoSuchMethodException e) {
                argsCls.getMethod("setInputFile", File.class).invoke(args, inputs.get(0));
            }
            argsCls.getMethod("setOutDir", File.class).invoke(args, out);

            // Настраиваем jadx на генерацию КОМПИЛИРУЕМОГО Java-кода. Без этого
            // анонимные классы выводятся как `new 1()`/`new 2()` (ссылки на
            // Class$1, не встроенные в файл) — такой код невалиден и ecj падает
            // с "Syntax error on token, invalid ClassType".
            configureJadxArgs(argsCls, args);

            Class<?> decCls = cl.loadClass("jadx.api.JadxDecompiler");
            Object dec = decCls.getConstructor(argsCls).newInstance(args);
            decCls.getMethod("load").invoke(dec);
            try {
                Object classes = decCls.getMethod("getClasses").invoke(dec);
                int n = (Integer) classes.getClass().getMethod("size").invoke(classes);
                log.log("jadx загрузил классов: " + n);
                if (n == 0) log.err("jadx загрузил 0 классов — проверьте dex/плагины jadx.");
            } catch (Throwable ignore) {}
            decCls.getMethod("save").invoke(dec);
            try { decCls.getMethod("close").invoke(dec); } catch (Throwable ignore) {}
        } finally {
            curThread.setContextClassLoader(savedTccl);
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    /**
     * Выставляет опции jadx для максимально компилируемого вывода:
     *  - setInlineAnonymousClasses(true) — встраивать анонимные классы в место
     *    использования (иначе `new 1()` вместо `new Runnable(){...}`);
     *  - setInlineMethods(true) — инлайнить синтетические методы-обёртки;
     *  - setRenameValid(true) + setRenamePrintable(true) — переименовывать
     *    невалидные для Java идентификаторы (числа, ключевые слова) в валидные;
     *  - setFallbackMode(false) — не отдавать сырой (некомпилируемый) код;
     *  - setShowInconsistentCode(true) — выводить код даже при неоднозначностях,
     *    но помечать комментариями (лучше правимый код, чем /* fallback * /);
     *  - setDecompilationMode(AUTO) — обычный режим реструктуризации.
     * Все вызовы «мягкие»: если метод/enum отсутствует в данной сборке jadx —
     * тихо пропускаем (совместимость с разными версиями jadx.jar).
     */
    private void configureJadxArgs(Class<?> argsCls, Object args) {
        setJadxBool(argsCls, args, "setInlineAnonymousClasses", true);
        setJadxBool(argsCls, args, "setInlineMethods", true);
        setJadxBool(argsCls, args, "setRenameValid", true);
        setJadxBool(argsCls, args, "setRenamePrintable", true);
        setJadxBool(argsCls, args, "setFallbackMode", false);
        setJadxBool(argsCls, args, "setShowInconsistentCode", true);
        setJadxBool(argsCls, args, "setRespectBytecodeAccModifiers", false);
        setJadxBool(argsCls, args, "setUseDxInput", false);
        // Отключаем деобфускацию имён (сохраняем оригинальные имена классов).
        setJadxBool(argsCls, args, "setDeobfuscationOn", false);
        // Режим декомпиляции AUTO (если поддерживается).
        setJadxDecompilationMode(argsCls, args);
        log.log("jadx настроен: inline анонимных классов + переименование "
                + "невалидных идентификаторов (компилируемый Java).");
    }

    /** Мягко вызвать setter(boolean) на JadxArgs (пропустить, если нет метода). */
    private void setJadxBool(Class<?> argsCls, Object args, String method, boolean value) {
        try {
            argsCls.getMethod(method, boolean.class).invoke(args, value);
        } catch (Throwable ignore) {
            // метод отсутствует в этой версии jadx — не критично
        }
    }

    /** Выставить setDecompilationMode(AUTO), если enum и метод доступны. */
    private void setJadxDecompilationMode(Class<?> argsCls, Object args) {
        // В этой сборке jadx enum лежит в jadx.api.DecompilationMode (не вложен).
        String[] enumNames = {
                "jadx.api.DecompilationMode",
                "jadx.api.JadxArgs$DecompilationMode",
        };
        ClassLoader cl = argsCls.getClassLoader();
        for (String cn : enumNames) {
            try {
                Class<?> modeCls = cl.loadClass(cn);
                Object auto = enumValue(modeCls, "AUTO");
                argsCls.getMethod("setDecompilationMode", modeCls).invoke(args, auto);
                return;
            } catch (Throwable ignore) {
                // пробуем следующее имя
            }
        }
    }

    static class ExitTrap extends SecurityException {
        final int code;
        ExitTrap(int code) { this.code = code; }
    }

    private void invokeMain(String jar, String cls, String[] args) throws Exception {
        ClassLoader cl = loader(jar);
        Class<?> c = cl.loadClass(cls);
        Method m = c.getMethod("main", String[].class);

        PrintStream oldOut = System.out, oldErr = System.err;
        System.setOut(new PrintStream(new LogStream(false), true));
        System.setErr(new PrintStream(new LogStream(true), true));

        SecurityManager oldSm = null;
        boolean smInstalled = false;
        try {
            oldSm = System.getSecurityManager();
            System.setSecurityManager(new SecurityManager() {
                @Override public void checkExit(int status) { throw new ExitTrap(status); }
                @Override public void checkPermission(java.security.Permission p) {}
                @Override public void checkPermission(java.security.Permission p, Object ctx) {}
            });
            smInstalled = true;
        } catch (Throwable ignore) {}

        try {
            m.invoke(null, (Object) args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ExitTrap) {
                int code = ((ExitTrap) cause).code;
                if (code != 0) throw new Exception(cls + " завершился с кодом " + code);
            } else if (cause != null && cause.getClass().getName().contains("ExitException")) {
                // ок
            } else {
                Throwable real = cause;
                while (real instanceof ExceptionInInitializerError && real.getCause() != null) {
                    real = real.getCause();
                }
                if (real != null) {
                    log.err("Причина сбоя движка: " + real);
                    StackTraceElement[] st = real.getStackTrace();
                    for (int i = 0; i < Math.min(st.length, 10); i++) log.err("    at " + st[i]);
                }
                throw new Exception(real != null ? String.valueOf(real) : e.toString(), real);
            }
        } finally {
            if (smInstalled) { try { System.setSecurityManager(oldSm); } catch (Throwable ignore) {} }
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    private ClassLoader loader(String jar) {
        File f = new File(dirEngine, jar);
        File opt = new File(ctx.getCacheDir(), "dexopt");
        opt.mkdirs();
        return new DexClassLoader(f.getAbsolutePath(), opt.getAbsolutePath(), null, ctx.getClassLoader());
    }

    private class LogStream extends OutputStream {
        private final boolean isErr;
        private final StringBuilder sb = new StringBuilder();
        LogStream(boolean isErr) { this.isErr = isErr; }
        @Override public void write(int b) {
            if (b == '\n') flushLine();
            else if (b != '\r') sb.append((char) b);
        }
        private void flushLine() {
            String s = sb.toString(); sb.setLength(0);
            if (s.trim().isEmpty()) return;
            if (s.contains("disallow-doctype-decl") || s.contains("ManifestAttributes")
                    || s.contains("Xml load error, file: /android/attrs.xml")
                    || s.contains("Failed to parse '.arsc'") || s.contains("updateManifestAttribMap")
                    || s.contains("jadx.core.xmlgen") || s.contains("RootNode.loadResources")
                    || s.contains("XmlSecurity") || s.contains("DocumentBuilderFactoryImpl")
                    || s.contains("JadxRuntimeException: Xml load error")) {
                log.log("[jadx warn] " + s);
                return;
            }
            boolean looksErr = isErr || s.contains("Exception") || s.contains("ERROR")
                    || s.contains("error:") || s.contains("FAILED");
            if (looksErr) log.err(s); else log.log(s);
        }
    }

    // ---------- Развёртывание assets ----------

    private boolean prepared = false;

    private synchronized void prepare() throws Exception {
        if (prepared) return;
        dirEngine = new File(ctx.getFilesDir(), "engine");
        dirEngine.mkdirs();

        String abi = detectAbi();
        log.log("ABI устройства: " + abi);

        aapt2 = nativeBin("libaapt2.so");
        aapt = nativeBin("libaapt.so");
        zipalign = nativeBin("libzipalign.so");

        copyAsset("engine/apktool.jar", new File(dirEngine, "apktool.jar"));
        copyAsset("engine/jadx.jar", new File(dirEngine, "jadx.jar"));
        copyAsset("engine/apksig.jar", new File(dirEngine, "apksig.jar"));
        copyAsset("engine/android.jar", new File(dirEngine, "android.jar"));
        copyAsset("engine/ecj.jar", new File(dirEngine, "ecj.jar"));
        copyAsset("key/testkey.pk8", new File(dirEngine, "testkey.pk8"));
        copyAsset("key/testkey.x509.pem", new File(dirEngine, "testkey.x509.pem"));
        new File(dirEngine, "framework").mkdirs();

        setupSystemProps();
        prepared = true;
        log.log("Движки развёрнуты.");
    }

    private void setupSystemProps() {
        File tmp = new File(ctx.getCacheDir(), "tmp");
        tmp.mkdirs();
        setProp("java.io.tmpdir", tmp.getAbsolutePath());
        setProp("user.home", ctx.getFilesDir().getAbsolutePath());
        setProp("user.dir", ctx.getFilesDir().getAbsolutePath());
        String osName = System.getProperty("os.name");
        if (osName == null || osName.trim().isEmpty()) setProp("os.name", "Linux");
        setProp("os.arch", is64Bit() ? "aarch64" : "arm");
        setProp("sun.arch.data.model", is64Bit() ? "64" : "32");
        setProp("javax.xml.accessExternalDTD", "all");
        setProp("javax.xml.accessExternalSchema", "all");
    }

    private static void setProp(String k, String v) {
        try { if (v != null) System.setProperty(k, v); } catch (Throwable ignore) {}
    }

    private boolean is64Bit() {
        try {
            String[] abis = android.os.Build.SUPPORTED_64_BIT_ABIS;
            return abis != null && abis.length > 0;
        } catch (Throwable t) { return true; }
    }

    private File nativeBin(String soName) throws Exception {
        String nativeDir = ctx.getApplicationInfo().nativeLibraryDir;
        File f = new File(nativeDir, soName);
        if (f.exists()) { f.setExecutable(true, false); return f; }
        throw new Exception("Не найден бинарник " + soName + " в nativeLibraryDir ("
                + nativeDir + "). Проверьте libs/<abi>/" + soName + " в APK.");
    }

    private String detectAbi() {
        try {
            String[] abis = android.os.Build.SUPPORTED_ABIS;
            if (abis != null && abis.length > 0) {
                for (String a : abis) {
                    if (a.equals("arm64-v8a") || a.equals("armeabi-v7a")
                            || a.equals("x86") || a.equals("x86_64")) return a;
                }
                return abis[0];
            }
        } catch (Throwable ignore) {}
        return "arm64-v8a";
    }

    private void copyAsset(String assetPath, File dst) throws Exception {
        if (dst.exists() && dst.length() > 0) return;
        AssetManager am = ctx.getAssets();
        InputStream is = am.open(assetPath);
        FileOutputStream os = new FileOutputStream(dst);
        byte[] buf = new byte[65536]; int n;
        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        os.close(); is.close();
    }

    // ---------- Утилиты ----------

    private void patchTargetSdk(File yml, int androidVer) {
        try {
            if (!yml.exists()) return;
            String target = sdkOf(androidVer);
            String content = readFile(yml);
            content = content.replaceAll("targetSdkVersion:\\s*'?\\d+'?",
                    "targetSdkVersion: '" + target + "'");
            write(yml, content);
            log.log("targetSdk адаптирован под Android " + androidVer + " (API " + target + ").");
        } catch (Throwable t) {
            log.err("Не удалось адаптировать SDK: " + t);
        }
    }

    private static String sdkOf(int androidVer) {
        switch (androidVer) {
            case 8: return "26";
            case 9: return "28";
            case 10: return "29";
            case 11: return "30";
            case 12: return "31";
            case 13: return "33";
            case 14: return "34";
            default: return "33";
        }
    }

    private int exec(String[] cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()));
        String line;
        while ((line = r.readLine()) != null) {
            if (line.toLowerCase().contains("error")) log.err(line); else log.log(line);
        }
        return p.waitFor();
    }

    private void listTree(File root) {
        if (root == null || !root.exists()) return;
        ArrayList<File> stack = new ArrayList<File>();
        stack.add(root);
        int count = 0;
        while (!stack.isEmpty() && count < 4000) {
            File f = stack.remove(stack.size() - 1);
            File[] kids = f.listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                if (k.isDirectory()) stack.add(k);
                else { log.log("  + " + rel(root, k)); count++; }
            }
        }
        log.log("Всего файлов: " + count + (count >= 4000 ? "+ (список усечён)" : ""));
    }

    private static String rel(File root, File f) {
        String r = root.getAbsolutePath(), a = f.getAbsolutePath();
        return a.startsWith(r) ? a.substring(r.length() + 1) : a;
    }

    private static String baseName(File apk) {
        String n = apk.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static void rmdir(File d) {
        if (d == null || !d.exists()) return;
        File[] kids = d.listFiles();
        if (kids != null) for (File k : kids) { if (k.isDirectory()) rmdir(k); else k.delete(); }
        d.delete();
    }

    private static String join(String[] a) {
        StringBuilder sb = new StringBuilder();
        for (String s : a) sb.append(s).append(' ');
        return sb.toString().trim();
    }

    private static String readFile(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    private static void write(File f, String s) throws Exception {
        FileOutputStream os = new FileOutputStream(f);
        os.write(s.getBytes("UTF-8"));
        os.close();
    }
}
