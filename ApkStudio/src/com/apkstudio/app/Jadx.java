package com.apkstudio.app;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.DexClassLoader;

/**
 * Обёртка над jadx через его библиотечный API (jadx.api.JadxDecompiler),
 * без main()/System.exit.
 *
 *   JadxArgs a = new JadxArgs();
 *   a.setInputFiles(list);  a.setOutDirSrc(out);  a.setShowInconsistentCode(true);
 *   JadxDecompiler j = new JadxDecompiler(a);  j.load();  j.saveSources();
 */
public class Jadx {

    private final DexClassLoader cl;
    private final Toolchain.Log log;

    public Jadx(File jadxJar, File dexOpt, Toolchain.Log log) {
        this.log = log;
        dexOpt.mkdirs();
        this.cl = new DexClassLoader(
                jadxJar.getAbsolutePath(), dexOpt.getAbsolutePath(), null,
                Jadx.class.getClassLoader());
    }

    public void decompile(File apk, File outSrcDir) throws Exception {
        decompile(apk, outSrcDir, null);
    }

    /**
     * Декомпиляция APK. Если outResDir != null — сохраняем ТАКЖЕ ресурсы и
     * декодированный AndroidManifest.xml (jadx setOutDirRes), чтобы получить
     * ПОЛНЫЙ проект (sources/ + res/ + AndroidManifest.xml), пригодный для
     * прямой обратной сборки Java → APK как в AIDE.
     */
    public void decompile(File apk, File outSrcDir, File outResDir) throws Exception {
        // jadx ищет input-плагины (dex/java/smali) через ServiceLoader по
        // context classloader текущего потока. Ставим наш DexClassLoader, иначе
        // плагин чтения dex не найдётся и разберутся ТОЛЬКО ресурсы (R.java).
        Thread th = Thread.currentThread();
        ClassLoader prevCl = th.getContextClassLoader();
        th.setContextClassLoader(cl);
        try {
            Class<?> argsC = cl.loadClass("jadx.api.JadxArgs");
            Object args = argsC.getConstructor().newInstance();

            List<File> inputs = new ArrayList<File>();
            inputs.add(apk);
            try {
                argsC.getMethod("setInputFiles", List.class).invoke(args, inputs);
            } catch (NoSuchMethodException e) {
                argsC.getMethod("setInputFile", File.class).invoke(args, apk);
            }
            argsC.getMethod("setOutDirSrc", File.class).invoke(args, outSrcDir);
            if (outResDir != null) {
                // сохраняем ресурсы (res/ + AndroidManifest.xml) для прямой сборки
                try { argsC.getMethod("setOutDirRes", File.class).invoke(args, outResDir); }
                catch (Throwable ignore) {}
                try { argsC.getMethod("setSkipResources", boolean.class).invoke(args, false); }
                catch (Throwable ignore) {}
            } else {
                // только код — быстрее (режим «для чтения»)
                try { argsC.getMethod("setSkipResources", boolean.class).invoke(args, true); }
                catch (Throwable ignore) {}
            }
            try { argsC.getMethod("setShowInconsistentCode", boolean.class).invoke(args, true); }
            catch (Throwable ignore) {}
            // разрешаем неточный/fallback-код, чтобы получить максимум исходников
            try { argsC.getMethod("setFallbackMode", boolean.class).invoke(args, false); }
            catch (Throwable ignore) {}

            Class<?> jadxC = cl.loadClass("jadx.api.JadxDecompiler");
            Object jadx = jadxC.getConstructor(argsC).newInstance(args);

            // Явно регистрируем input-плагины (dex/java/javaconvert/smali) через
            // JadxDecompiler.registerPlugin — надёжнее, чем полагаться на
            // ServiceLoader, который на dex-jar через DexClassLoader может не
            // найти плагины. Именно из-за отсутствия dex-плагина раньше
            // разбирались только ресурсы (R.java).
            registerPlugins(jadxC, jadx);

            log.line("jadx: загрузка dex и построение дерева классов…");
            jadxC.getMethod("load").invoke(jadx);

            // Проверяем, сколько классов загружено из dex
            int classes = -1;
            try {
                Object list = jadxC.getMethod("getClasses").invoke(jadx);
                if (list instanceof java.util.List) classes = ((java.util.List) list).size();
            } catch (Throwable ignore) {}
            if (classes == 0) {
                log.warn("jadx загрузил 0 классов из dex. Возможно APK защищён/зашифрован,");
                log.warn("либо dex отсутствует. Попробуйте путь APK → Smali.");
            } else if (classes > 0) {
                log.ok("jadx: загружено классов из байткода: " + classes);
            }

            log.line("jadx: декомпиляция в Java и сохранение…");
            jadxC.getMethod("saveSources").invoke(jadx);
            if (outResDir != null) {
                // сохраняем ресурсы + AndroidManifest.xml (для прямой сборки)
                try {
                    jadxC.getMethod("saveResources").invoke(jadx);
                    log.ok("jadx: ресурсы и AndroidManifest.xml сохранены (полный проект).");
                } catch (Throwable e) {
                    log.warn("jadx: не удалось сохранить ресурсы: " + e);
                }
            }
            try { jadxC.getMethod("close").invoke(jadx); } catch (Throwable ignore) {}
        } finally {
            th.setContextClassLoader(prevCl);
        }
    }

    private void registerPlugins(Class<?> jadxC, Object jadx) {
        String[] pluginClasses = new String[] {
            "jadx.plugins.input.dex.DexInputPlugin",
            "jadx.plugins.input.java.JavaInputPlugin",
            "jadx.plugins.input.javaconvert.JavaConvertPlugin",
            "jadx.plugins.input.smali.SmaliInputPlugin",
        };
        int ok = 0;
        try {
            Class<?> pluginItf = cl.loadClass("jadx.api.plugins.JadxPlugin");
            java.lang.reflect.Method reg = jadxC.getMethod("registerPlugin", pluginItf);
            for (String pc : pluginClasses) {
                try {
                    Object plugin = cl.loadClass(pc).getConstructor().newInstance();
                    reg.invoke(jadx, plugin);
                    ok++;
                } catch (Throwable t) {
                    log.warn("jadx: не удалось зарегистрировать " + pc + ": " + t);
                }
            }
        } catch (Throwable t) {
            log.warn("jadx: registerPlugin недоступен (" + t + "), полагаемся на ServiceLoader.");
        }
        log.line("jadx: зарегистрировано input-плагинов: " + ok + "/" + pluginClasses.length);
    }
}
