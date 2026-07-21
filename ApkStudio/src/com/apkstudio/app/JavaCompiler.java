package com.apkstudio.app;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.DexClassLoader;

/**
 * Компиляция .java → .class с помощью ECJ (Eclipse Compiler for Java) через
 * его библиотечный API BatchCompiler.compile(String[], out, err, progress).
 *
 * Почему ECJ, а не javac: на Android/ART нет javax.tools.JavaCompiler (нет
 * javac). ECJ — полноценный автономный компилятор Java, работает целиком в
 * процессе, не зовёт System.exit. Именно его использует AIDE.
 *
 * ecj.jar — dex-jar, поэтому классы грузятся DexClassLoader-ом.
 */
public class JavaCompiler {

    private final DexClassLoader cl;
    private final Toolchain.Log log;

    public JavaCompiler(File ecjJar, File dexOpt, Toolchain.Log log) {
        this.log = log;
        dexOpt.mkdirs();
        this.cl = new DexClassLoader(
                ecjJar.getAbsolutePath(), dexOpt.getAbsolutePath(), null,
                JavaCompiler.class.getClassLoader());
    }

    /**
     * Скомпилировать все .java из srcDirs в classesOut.
     *
     * @param srcDirs     папки с исходниками (.java), включая папку с R.java
     * @param androidJar  SDK-заглушки для classpath
     * @param extraCp     доп. jar-ы/папки в classpath (может быть null)
     * @param classesOut  куда положить .class (папка)
     * @param sourceLevel уровень исходников, напр. "1.8"
     */
    public void compile(List<File> srcDirs, File androidJar, List<File> extraCp,
                        File classesOut, String sourceLevel) throws Exception {
        classesOut.mkdirs();

        List<String> args = new ArrayList<String>();
        // classpath: android.jar + доп.
        StringBuilder cp = new StringBuilder(androidJar.getAbsolutePath());
        if (extraCp != null) {
            for (File f : extraCp) {
                if (f != null && f.exists()) cp.append(File.pathSeparator).append(f.getAbsolutePath());
            }
        }
        args.add("-cp"); args.add(cp.toString());
        args.add("-d");  args.add(classesOut.getAbsolutePath());
        args.add("-source"); args.add(sourceLevel);
        args.add("-target"); args.add(sourceLevel);
        args.add("-encoding"); args.add("UTF-8");
        args.add("-nowarn");
        args.add("-warn:none");
        args.add("-noExit");                         // НЕ звать System.exit (убьёт приложение!)
        args.add("-proceedOnError:Fatal");           // собирать даже при фатальных ошибках
        // -proc:none — ОТКЛЮЧИТЬ обработку аннотаций (APT). На Android нет
        // пакетов javax.tools / javax.lang.model, поэтому ecj не должен
        // грузить свои apt/tool-классы (иначе NoClassDefFoundError → AbortCompilation).
        // Нам APT и не нужен: мы просто компилируем .java → .class как AIDE.
        args.add("-proc:none");
        // корни исходников — ECJ рекурсивно найдёт все .java
        int roots = 0;
        for (File d : srcDirs) {
            if (d != null && d.exists()) { args.add(d.getAbsolutePath()); roots++; }
        }
        if (roots == 0) throw new Exception("Нет папок с .java для компиляции");

        int jtot = 0;
        {   // сколько всего .java (для отображения общего числа в прогрессе)
            List<File> javaFiles = new ArrayList<File>();
            for (File d : srcDirs) if (d != null && d.exists()) collectJava(d, javaFiles);
            jtot = javaFiles.size();
        }
        log.line("ecj: компиляция " + jtot + " .java → .class (classpath = android.jar" +
                (extraCp != null && !extraCp.isEmpty() ? " + доп." : "") + ")");

        StringWriter outW = new StringWriter();
        StringWriter errW = new StringWriter();
        PrintWriter out = new PrintWriter(outW);
        PrintWriter err = new PrintWriter(errW);

        Class<?> bc = cl.loadClass("org.eclipse.jdt.core.compiler.batch.BatchCompiler");
        Class<?> progressC = cl.loadClass("org.eclipse.jdt.core.compiler.CompilationProgress");
        Method compile = bc.getMethod("compile",
                String[].class, PrintWriter.class, PrintWriter.class, progressC);

        // Прогресс компиляции. CompilationProgress у ECJ — абстрактный КЛАСС,
        // а не интерфейс, поэтому java.lang.reflect.Proxy к нему неприменим, а
        // dexmaker недоступен. Даём честную «живую» индикацию: фоновый тикер
        // плавно тянет прогресс от 0 до ~95% пока идёт compile(), а по факту
        // завершения показываем 100%. Никаких ложных мгновенных 100%.
        final int totalJava = jtot;
        final boolean[] compiling = { true };
        Thread ticker = new Thread(new Runnable() { public void run() {
            int shown = 0;
            // Плавный «псевдо-прогресс»: асимптотически к 95%, шаг замедляется.
            while (compiling[0]) {
                if (shown < 95) shown += (shown < 60 ? 3 : 1);
                log.progress("ecj: компиляция", shown, 100, null);
                try { Thread.sleep(250); } catch (InterruptedException e) { break; }
            }
        }});
        ticker.setDaemon(true);
        log.progress("ecj: компиляция", 0, 100, null);
        ticker.start();

        boolean success = false;
        Throwable invokeError = null;

        // 1) ecj читает свои ресурсы (messages.properties, parser*.rsc) через
        //    getResourceAsStream/ResourceBundle по contextClassLoader потока.
        //    Ставим наш DexClassLoader, иначе ресурсы из dex-jar не найдутся.
        // 2) Локаль устройства (напр. ru_DE) заставляет ResourceBundle искать
        //    messages_ru_DE / _ru — которых нет. Временно ставим ENGLISH, чтобы
        //    брался базовый messages.properties (иначе MissingResourceException).
        Thread th = Thread.currentThread();
        ClassLoader prevCl = th.getContextClassLoader();
        java.util.Locale prevLocale = java.util.Locale.getDefault();
        th.setContextClassLoader(cl);
        try {
            java.util.Locale.setDefault(java.util.Locale.ENGLISH);
        } catch (Throwable ignore) {}
        try {
            Object ok = compile.invoke(null,
                    args.toArray(new String[0]), out, err, null);
            success = Boolean.TRUE.equals(ok);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // ecj мог упасть на битом коде (например, jadx // decompilation error).
            // Не роняем всё: если часть .class всё же собралась — продолжаем.
            invokeError = e.getCause() != null ? e.getCause() : e;
        } catch (Throwable t) {
            invokeError = t;
        } finally {
            th.setContextClassLoader(prevCl);
            try { java.util.Locale.setDefault(prevLocale); } catch (Throwable ignore) {}
            // остановить тикер и показать 100%
            compiling[0] = false;
            ticker.interrupt();
            log.progress("ecj: компиляция", 100, 100, null);
        }
        out.flush(); err.flush();

        String errText = errW.toString().trim();
        String outText = outW.toString().trim();
        if (!outText.isEmpty()) for (String l : outText.split("\n")) log.line("  " + l);

        int classCount = countClasses(classesOut);

        if (!errText.isEmpty()) {
            // ECJ пишет и предупреждения, и ошибки в err. Показываем первые
            // строки; с -proceedOnError часть классов всё равно собрана.
            int shown = 0;
            for (String l : errText.split("\n")) {
                if (shown++ > 40) { log.warn("  … (лог обрезан)"); break; }
                if (l.toLowerCase().contains("error")) log.err("  " + l);
                else log.warn("  " + l);
            }
        }
        if (invokeError != null) {
            log.warn("ecj: компилятор прервался (" + invokeError + "), собрано .class: " + classCount);
        }

        if (classCount == 0) {
            String reason = (invokeError != null ? invokeError + "\n" : "") + errText;
            throw new Exception("ecj: не создано ни одного .class. " +
                    "Исходники не компилируются (частая причина — jadx-Java с " +
                    "// decompilation error). Подробности:\n" + reason);
        }
        if (success) log.ok("ecj: OK, .class файлов: " + classCount);
        else log.warn("ecj: собрано с ошибками (proceedOnError), .class файлов: " + classCount);
    }

    private void collectJava(File dir, List<File> out) {
        File[] ch = dir.listFiles();
        if (ch == null) return;
        for (File f : ch) {
            if (f.isDirectory()) collectJava(f, out);
            else if (f.getName().endsWith(".java")) out.add(f);
        }
    }

    private int countClasses(File dir) {
        int n = 0;
        File[] ch = dir.listFiles();
        if (ch == null) return 0;
        for (File f : ch) {
            if (f.isDirectory()) n += countClasses(f);
            else if (f.getName().endsWith(".class")) n++;
        }
        return n;
    }
}
