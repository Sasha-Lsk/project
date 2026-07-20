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
        decoderC.getMethod("decode").invoke(decoder);
        try { decoderC.getMethod("close").invoke(decoder); } catch (Throwable ignore) {}
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

        log.line("apktool: сборка ресурсов (aapt2) и dex из smali…");
        Method build = androlibC.getMethod("build", extFileC, File.class);
        try {
            build.invoke(androlib, extFile, outApk);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            throw new Exception("apktool build: " + (c != null ? c.toString() : e.toString()), c);
        }
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
