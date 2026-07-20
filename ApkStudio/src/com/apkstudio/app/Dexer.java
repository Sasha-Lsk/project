package com.apkstudio.app;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.DexClassLoader;

/**
 * Компиляция .class → classes.dex с помощью D8 (com.android.tools.r8.D8)
 * через ЕГО ПРОГРАММНЫЙ API (D8Command.builder()...build() + D8.run(cmd)),
 * а НЕ main() — чтобы не сработал System.exit и не убил процесс приложения.
 *
 * d8.jar — dex-jar, грузится DexClassLoader-ом.
 */
public class Dexer {

    private final DexClassLoader cl;
    private final Toolchain.Log log;

    public Dexer(File d8Jar, File dexOpt, Toolchain.Log log) {
        this.log = log;
        dexOpt.mkdirs();
        this.cl = new DexClassLoader(
                d8Jar.getAbsolutePath(), dexOpt.getAbsolutePath(), null,
                Dexer.class.getClassLoader());
    }

    /**
     * Собрать classes.dex из папки с .class (рекурсивно) + при необходимости
     * из доп. jar-ов, в выходную папку outDexDir (там появится classes.dex,
     * classes2.dex … при multidex).
     *
     * @param classesDir папка с .class
     * @param libJars    библиотеки (android.jar) для desugaring/линковки
     * @param outDexDir  папка для .dex
     * @param minApi     минимальный API (напр. 21)
     */
    public void dex(File classesDir, List<File> libJars, File outDexDir, int minApi) throws Exception {
        outDexDir.mkdirs();

        // Собираем список .class файлов и jar-ов как program inputs.
        List<File> programs = new ArrayList<File>();
        collectClasses(classesDir, programs);
        if (programs.isEmpty())
            throw new Exception("d8: нет .class для дексирования в " + classesDir);

        log.line("d8: дексирование " + programs.size() + " .class → classes.dex (minApi=" + minApi + ")");

        Class<?> pathC        = Class.forName("java.nio.file.Path");
        Class<?> d8C          = cl.loadClass("com.android.tools.r8.D8");
        Class<?> d8CmdC       = cl.loadClass("com.android.tools.r8.D8Command");
        Class<?> outputModeC  = cl.loadClass("com.android.tools.r8.OutputMode");
        Class<?> compModeC    = cl.loadClass("com.android.tools.r8.CompilationMode");

        // D8Command.Builder builder = D8Command.builder();
        Object builder = d8CmdC.getMethod("builder").invoke(null);
        Class<?> builderC = builder.getClass();

        // addProgramFiles(Collection<Path>)
        Method addProgram = findMethod(builderC, "addProgramFiles", java.util.Collection.class);
        addProgram.invoke(builder, toPaths(programs));

        // addLibraryFiles(Collection<Path>) — android.jar для desugaring
        if (libJars != null && !libJars.isEmpty()) {
            Method addLib = findMethod(builderC, "addLibraryFiles", java.util.Collection.class);
            addLib.invoke(builder, toPaths(libJars));
        }

        // setMinApiLevel(int)
        findMethod(builderC, "setMinApiLevel", int.class).invoke(builder, minApi);

        // setMode(CompilationMode.RELEASE) — меньше и быстрее
        Object release = compModeC.getField("RELEASE").get(null);
        findMethod(builderC, "setMode", compModeC).invoke(builder, release);

        // setOutput(Path, OutputMode.DexIndexed) — обычная раскладка (multidex ок)
        Object dexIndexed = outputModeC.getField("DexIndexed").get(null);
        Object outPath = toPath(outDexDir);
        findMethod(builderC, "setOutput", pathC, outputModeC).invoke(builder, outPath, dexIndexed);

        // D8Command cmd = builder.build();
        Object cmd = findMethod(builderC, "build").invoke(builder);

        // D8.run(cmd);
        try {
            d8C.getMethod("run", d8CmdC).invoke(null, cmd);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            throw new Exception("d8 run: " + (c != null ? c.toString() : e.toString()), c);
        }

        File dex = new File(outDexDir, "classes.dex");
        if (!dex.exists())
            throw new Exception("d8: classes.dex не создан");
        int n = 1;
        while (new File(outDexDir, "classes" + (n + 1) + ".dex").exists()) n++;
        log.ok("d8: OK, dex-файлов: " + n + " (classes.dex" + (n > 1 ? "…classes" + n + ".dex" : "") + ")");
    }

    // ---- helpers ----

    private void collectClasses(File dir, List<File> out) {
        File[] ch = dir.listFiles();
        if (ch == null) return;
        for (File f : ch) {
            if (f.isDirectory()) collectClasses(f, out);
            else if (f.getName().endsWith(".class")) out.add(f);
        }
    }

    private Object toPath(File f) throws Exception {
        // f.toPath()
        Method toPath = File.class.getMethod("toPath");
        return toPath.invoke(f);
    }

    private java.util.List<Object> toPaths(List<File> files) throws Exception {
        java.util.List<Object> paths = new ArrayList<Object>();
        for (File f : files) paths.add(toPath(f));
        return paths;
    }

    /** Ищет метод по имени и типам, включая унаследованные (D8 builder-цепочка). */
    private Method findMethod(Class<?> c, String name, Class<?>... types) throws Exception {
        Class<?> cur = c;
        while (cur != null) {
            for (Method m : cur.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != types.length) continue;
                boolean ok = true;
                for (int i = 0; i < p.length; i++) {
                    if (!p[i].isAssignableFrom(types[i]) && !types[i].isAssignableFrom(p[i])) { ok = false; break; }
                }
                if (ok) { m.setAccessible(true); return m; }
            }
            cur = cur.getSuperclass();
        }
        throw new NoSuchMethodException(name + " в " + c.getName());
    }
}
