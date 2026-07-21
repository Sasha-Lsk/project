package com.apkstudio.app;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import dalvik.system.DexClassLoader;

/**
 * Обёртка над apktool 2.7.0 через ЕГО БИБЛИОТЕЧНЫЙ API (не main()), чтобы
 * инструмент не звал System.exit и не убивал наш процесс.
 *
 * Decode:  brut.androlib.ApkDecoder -> setApkFile/setOutDir/setForceDelete/
 *          setFrameworkDir/decode()
 * Build:   new brut.androlib.Androlib(BuildOptions) -> build(ExtFile, File)
 *          где BuildOptions.useAapt2 / aaptPath / frameworkFolderLocation.
 */
public class Apktool {

    private final DexClassLoader cl;
    private final Toolchain.Log log;

    public Apktool(File apktoolJar, File dexOpt, Toolchain.Log log) {
        this.log = log;
        dexOpt.mkdirs();
        this.cl = new DexClassLoader(
                apktoolJar.getAbsolutePath(), dexOpt.getAbsolutePath(), null,
                Apktool.class.getClassLoader());
    }

    /**
     * Задаёт системные свойства, которые apktool ждёт от «настоящей» JVM,
     * но которых нет на Android/ART. Без "sun.arch.data.model" статический
     * блок brut.util.OSDetection бросает NPE и роняет сборку.
     */
    static void fixOsProperties() {
        if (System.getProperty("os.name") == null) {
            System.setProperty("os.name", "Linux");
        }
        String bit = System.getProperty("sun.arch.data.model");
        if (bit == null || bit.trim().isEmpty()) {
            String arch = System.getProperty("os.arch", "");
            boolean is64 = arch.contains("64") || arch.contains("aarch64")
                    || arch.contains("x86_64") || arch.contains("arm64");
            System.setProperty("sun.arch.data.model", is64 ? "64" : "32");
        }
    }

    /** APK -> smali/res проект. */
    public void decode(File apk, File outDir, File frameworkDir) throws Exception {
        fixOsProperties();
        Class<?> decoderC = cl.loadClass("brut.androlib.ApkDecoder");
        Object decoder = decoderC.getConstructor().newInstance();

        decoderC.getMethod("setApkFile", File.class).invoke(decoder, apk);
        decoderC.getMethod("setOutDir", File.class).invoke(decoder, outDir);
        decoderC.getMethod("setForceDelete", boolean.class).invoke(decoder, true);
        decoderC.getMethod("setFrameworkDir", String.class)
                .invoke(decoder, frameworkDir.getAbsolutePath());
        // s = 0x1 (SMALI) — исходники в smali, ресурсы декодируются по умолчанию
        log.line("apktool: декодирование ресурсов и smali…");
        // apktool.decode() — единый вызов без пошагового API, поэтому даём
        // «живую» индикацию тикером (0→~95%), а по факту завершения — 100%.
        boolean[] running = { true };
        Thread ticker = startTicker("apktool: декодирование", running);
        try {
            decoderC.getMethod("decode").invoke(decoder);
            try { decoderC.getMethod("close").invoke(decoder); } catch (Throwable ignore) {}
        } finally {
            running[0] = false; ticker.interrupt();
            log.progress("apktool: декодирование", 100, 100, null);
        }
        // Наш пропатченный (без ImageIO) декодер apktool копирует 9-patch как
        // есть — в «скомпилированном» виде без чёрной рамки. Восстанавливаем
        // рамку из npTc-чанка, иначе обратная сборка (aapt2 compile) падает:
        // "9-patch malformed / top-left corner pixel...".
        try {
            File res = new File(outDir, "res");
            if (res.isDirectory()) NinePatchRestore.restoreAll(res, log);
        } catch (Throwable t) {
            log.warn("9-patch: восстановление рамок пропущено: " + t);
        }
    }

    /** проект -> unsigned APK, используя нативный aapt2 устройства. */
    public void build(File projectDir, File outApk, File frameworkDir, File aapt2, File tmpDir) throws Exception {
        // Проверка aapt2 — без него сборка ресурсов невозможна
        if (aapt2 == null || !aapt2.exists() || !aapt2.canRead()) {
            throw new Exception("aapt2 не найден или недоступен: " +
                    (aapt2 == null ? "null" : aapt2.getAbsolutePath()) +
                    ". Проверьте, что libs/<abi>/libaapt2.so попал в APK (nativeLibraryDir).");
        }
        aapt2.setExecutable(true, false);
        log.line("apktool: aapt2 = " + aapt2.getAbsolutePath() +
                " (executable=" + aapt2.canExecute() + ")");

        // ВАЖНО: apktool при getAaptBinaryFile() инициализирует класс
        // brut.util.OSDetection, чей static-блок делает:
        //   OS  = System.getProperty("os.name").toLowerCase();
        //   BIT = System.getProperty("sun.arch.data.model").toLowerCase();
        // На Android/ART свойства "sun.arch.data.model" НЕТ (=null) → NPE в
        // <clinit> → ExceptionInInitializerError. Задаём его вручную, иначе
        // сборка ресурсов падает ещё до использования нашего aapt2.
        fixOsProperties();

        // apktool всё равно попытается извлечь встроенный aapt2 во временную
        // папку и сделать его executable. Направляем java.io.tmpdir во
        // внутреннюю папку приложения, где chmod +x работает (иначе краш
        // "Can't set aapt binary as executable" в getAaptBinaryFile).
        if (tmpDir != null) {
            tmpDir.mkdirs();
            System.setProperty("java.io.tmpdir", tmpDir.getAbsolutePath());
        }

        Class<?> boC = cl.loadClass("brut.androlib.options.BuildOptions");
        Object bo = boC.getConstructor().newInstance();
        setField(boC, bo, "useAapt2", true);
        setField(boC, bo, "aaptPath", aapt2.getAbsolutePath());
        setField(boC, bo, "aaptVersion", 2);
        setField(boC, bo, "frameworkFolderLocation", frameworkDir.getAbsolutePath());
        setField(boC, bo, "forceBuildAll", true);
        setField(boC, bo, "verbose", true);

        Class<?> androlibC = cl.loadClass("brut.androlib.Androlib");
        Object androlib = androlibC.getConstructor(boC).newInstance(bo);

        Class<?> extFileC = cl.loadClass("brut.directory.ExtFile");
        Object extFile = extFileC.getConstructor(File.class).newInstance(projectDir);

        // Страховка для проектов, декомпилированных СТАРОЙ версией (без
        // восстановления рамок): чиним 9-patch прямо перед сборкой. Файлы,
        // уже имеющие корректную рамку (нет npTc), пропускаются.
        try {
            File res = new File(projectDir, "res");
            if (res.isDirectory()) NinePatchRestore.restoreAll(res, log);
        } catch (Throwable t) {
            log.warn("9-patch: восстановление рамок перед сборкой пропущено: " + t);
        }

        log.line("apktool: сборка ресурсов (aapt2) и dex из smali…");
        Method build = androlibC.getMethod("build", extFileC, File.class);
        boolean[] running = { true };
        Thread ticker = startTicker("apktool: сборка", running);
        try {
            build.invoke(androlib, extFile, outApk);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            running[0] = false; ticker.interrupt();
            // apktool через brut.util.OS.exec ПРОГЛАТЫВАЕТ stdout/stderr aapt2,
            // поэтому «exit code = 1» ничего не объясняет. Запускаем aapt2 compile
            // сами, с захватом вывода — так видно реальную причину (битый ресурс,
            // строка XML и т.п.), и часто это указывает на конкретный файл.
            String detail = diagnoseAapt2Compile(aapt2, projectDir);
            String base = c != null ? c.toString() : e.toString();
            throw new Exception("apktool build: " + base
                    + (detail != null ? "\n\n=== aapt2 compile вывод (реальная причина) ===\n" + detail : ""), c);
        } finally {
            running[0] = false; ticker.interrupt();
            log.progress("apktool: сборка", 100, 100, null);
        }
    }

    /**
     * Диагностика: сам вызывает `aapt2 compile --dir <res> -o <tmp.zip>` с
     * захватом stdout+stderr, чтобы показать реальную причину падения (apktool
     * её проглатывает). Возвращает собранный вывод aapt2 (или null).
     */
    private String diagnoseAapt2Compile(File aapt2, File projectDir) {
        try {
            File res = new File(projectDir, "res");
            if (!res.isDirectory()) return null;
            File tmpOut = new File(projectDir, "build/aapt2_diag_resources.zip");
            File parent = tmpOut.getParentFile();
            if (parent != null) parent.mkdirs();
            java.util.List<String> cmd = new java.util.ArrayList<String>();
            cmd.add(aapt2.getAbsolutePath());
            cmd.add("compile");
            cmd.add("--dir"); cmd.add(res.getAbsolutePath());
            cmd.add("--legacy");
            cmd.add("-o"); cmd.add(tmpOut.getAbsolutePath());
            cmd.add("-v");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);   // stderr → stdout, читаем один поток
            Process p = pb.start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String ln; int lines = 0;
            log.line("aapt2: диагностический прогон compile (захват вывода)…");
            while ((ln = r.readLine()) != null) {
                // aapt2 -v печатает уйму «note:» — оставляем error/warning + первые строки
                String low = ln.toLowerCase();
                boolean interesting = low.contains("error") || low.contains("fail")
                        || low.contains("warn") || low.contains("invalid")
                        || low.contains("not found") || low.contains("unexpected");
                if (interesting || lines < 40) {
                    sb.append(ln).append('\n');
                    if (interesting) log.err("  aapt2: " + ln);
                }
                lines++;
            }
            int code = p.waitFor();
            sb.append("aapt2 exit code = ").append(code).append('\n');
            log.line("aapt2: диагностика завершена (exit=" + code + ", строк вывода=" + lines + ")");
            return sb.toString();
        } catch (Throwable t) {
            log.warn("aapt2: не удалось выполнить диагностический прогон: " + t);
            return null;
        }
    }

    /**
     * «Живой» псевдо-прогресс для единичных долгих вызовов apktool, у которых
     * нет пошагового API: плавно тянет % к 95, пока running[0]==true.
     */
    private Thread startTicker(final String stage, final boolean[] running) {
        log.progress(stage, 0, 100, null);
        Thread t = new Thread(new Runnable() { public void run() {
            int shown = 0;
            while (running[0]) {
                if (shown < 95) shown += (shown < 60 ? 3 : 1);
                log.progress(stage, shown, 100, null);
                try { Thread.sleep(250); } catch (InterruptedException e) { break; }
            }
        }});
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void setField(Class<?> c, Object o, String name, Object val) {
        try {
            Field f = c.getField(name);
            f.setAccessible(true);
            f.set(o, val);
        } catch (Throwable t) {
            // поле могло исчезнуть в другой версии — не критично
        }
    }
}
