package com.apkstudio.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Сборка APK ИЗ ПАПКИ ИСХОДНИКОВ (java + res + AndroidManifest + assets/lib),
 * БЕЗ оригинального APK — так же, как это делает AIDE на телефоне.
 *
 * Полный конвейер (никакого apktool/оригинального APK не требуется):
 *   1. aapt2 compile  — компилирует res/ в промежуточный .flat/.zip
 *   2. aapt2 link     — линкует ресурсы + AndroidManifest в base.apk и
 *                       генерирует R.java (используя android.jar как -I)
 *   3. ecj            — компилирует все .java (проект + R.java) → .class
 *   4. d8             — .class → classes.dex
 *   5. package        — кладём classes.dex, assets/, lib/ в base.apk (zip)
 *   6. zipalign + sign (делает Operations.alignAndSign)
 *
 * Поддерживаемые раскладки папки проекта (авто-определение):
 *   • AIDE / Eclipse:   src/<pkg>/*.java, res/, AndroidManifest.xml, assets/, libs/
 *   • Gradle:           app/src/main/java|res|AndroidManifest.xml|assets|jniLibs
 *   • jadx-вывод:       sources/ (java), resources/ (res+manifest+assets)
 */
public class ProjectBuilder {

    private final Toolchain tc;
    private final Toolchain.Log log;

    // Счётчики прогресса упаковки APK (панорама файлов + % в уведомлении).
    private int pkgTotal = 0;
    private int pkgDone = 0;

    public ProjectBuilder(Toolchain tc, Toolchain.Log log) {
        this.tc = tc;
        this.log = log;
    }

    /** Найденная раскладка проекта. */
    public static class Layout {
        public File manifest;          // AndroidManifest.xml
        public List<File> srcRoots = new ArrayList<File>();  // корни .java
        public File resDir;            // res/ (может быть null)
        public File assetsDir;         // assets/ (может быть null)
        public File libDir;            // lib/ или libs/ (может быть null)
        public boolean isValid() { return manifest != null && !srcRoots.isEmpty(); }
    }

    /** Автоопределение раскладки проекта. */
    public Layout detect(File proj) {
        Layout L = new Layout();

        // manifest
        File[] mCandidates = new File[] {
            new File(proj, "AndroidManifest.xml"),
            new File(proj, "app/src/main/AndroidManifest.xml"),
            new File(proj, "src/main/AndroidManifest.xml"),
            new File(proj, "resources/AndroidManifest.xml"),
        };
        for (File m : mCandidates) if (m.exists()) { L.manifest = m; break; }
        // текстовый (не бинарный) AndroidManifest.xml где угодно в проекте
        if (L.manifest == null) L.manifest = findTextManifest(proj, 6);

        // src roots (папки с .java)
        addIfHasJava(L, new File(proj, "src"));
        addIfHasJava(L, new File(proj, "app/src/main/java"));
        addIfHasJava(L, new File(proj, "src/main/java"));
        addIfHasJava(L, new File(proj, "java"));
        addIfHasJava(L, new File(proj, "sources"));   // jadx
        // вложенный проект: decompiler_apk/<имя>/<реальный проект>/…
        if (L.srcRoots.isEmpty() || L.manifest == null) {
            File nested = findNestedRoot(proj, 3);
            if (nested != null && !nested.equals(proj)) {
                if (L.manifest == null) {
                    File nm = new File(nested, "AndroidManifest.xml");
                    if (nm.exists()) L.manifest = nm;
                }
                addIfHasJava(L, new File(nested, "src"));
                addIfHasJava(L, new File(nested, "sources"));
                addIfHasJava(L, new File(nested, "java"));
            }
        }
        if (L.srcRoots.isEmpty()) addIfHasJava(L, proj); // fallback: искать по всему проекту

        // res
        File[] resC = new File[] {
            new File(proj, "res"),
            new File(proj, "app/src/main/res"),
            new File(proj, "src/main/res"),
            new File(proj, "resources/res"),
        };
        for (File r : resC) if (r.isDirectory()) { L.resDir = r; break; }

        // assets
        File[] asC = new File[] {
            new File(proj, "assets"),
            new File(proj, "app/src/main/assets"),
            new File(proj, "src/main/assets"),
            new File(proj, "resources/assets"),
        };
        for (File a : asC) if (a.isDirectory()) { L.assetsDir = a; break; }

        // native libs
        File[] libC = new File[] {
            new File(proj, "lib"),
            new File(proj, "libs"),
            new File(proj, "app/src/main/jniLibs"),
            new File(proj, "src/main/jniLibs"),
        };
        for (File l : libC) if (l.isDirectory() && hasAbiSo(l)) { L.libDir = l; break; }

        return L;
    }

    /**
     * Полная сборка. Возвращает НЕВЫРОВНЕННЫЙ, НЕПОДПИСАННЫЙ APK
     * (align+sign делает вызывающая сторона).
     */
    public File buildUnsigned(File proj, File workDir, File unsignedOut, boolean bumpTarget) throws Exception {
        Layout L = detect(proj);
        if (L.manifest == null)
            throw new Exception("Не найден AndroidManifest.xml в проекте");
        if (L.srcRoots.isEmpty())
            throw new Exception("Не найдены .java исходники (искал src/, java/, sources/…)");

        log.line("Раскладка проекта:");
        log.line("  manifest: " + rel(proj, L.manifest));
        for (File s : L.srcRoots) log.line("  src:      " + rel(proj, s));
        log.line("  res:      " + (L.resDir != null ? rel(proj, L.resDir) : "нет"));
        log.line("  assets:   " + (L.assetsDir != null ? rel(proj, L.assetsDir) : "нет"));
        log.line("  lib:      " + (L.libDir != null ? rel(proj, L.libDir) : "нет"));

        deleteRec(workDir); workDir.mkdirs();
        File genDir      = new File(workDir, "gen");     genDir.mkdirs();     // R.java
        File compiledDir = new File(workDir, "compiled"); compiledDir.mkdirs(); // aapt2 .flat
        File classesDir  = new File(workDir, "classes"); classesDir.mkdirs();  // .class
        File dexDir      = new File(workDir, "dex");     dexDir.mkdirs();       // classes.dex
        File baseApk     = new File(workDir, "base.apk");

        File manifestForLink = L.manifest;
        if (bumpTarget) {
            manifestForLink = new File(workDir, "AndroidManifest.xml");
            copy(L.manifest, manifestForLink);
            bumpManifest(manifestForLink);
        }

        // ---- 1+2: aapt2 compile + link ----
        aapt2CompileLink(L, manifestForLink, compiledDir, genDir, baseApk);

        // ---- 3: ecj (java + R.java → class) ----
        List<File> srcRoots = new ArrayList<File>(L.srcRoots);
        srcRoots.add(genDir); // R.java
        String srcLevel = "1.8";
        JavaCompiler jc = new JavaCompiler(tc.ecjJar, new File(workDir, "dexopt_ecj"), log);
        jc.compile(srcRoots, tc.androidJar, null, classesDir, srcLevel);

        // ---- 4: d8 (class → dex) ----
        int minApi = readMinSdk(manifestForLink, 21);
        List<File> libs = new ArrayList<File>();
        libs.add(tc.androidJar);
        Dexer dexer = new Dexer(tc.d8Jar, new File(workDir, "dexopt_d8"), log);
        dexer.dex(classesDir, libs, dexDir, minApi);

        // ---- 5: package (base.apk + dex + assets + lib) ----
        packageApk(baseApk, dexDir, L.assetsDir, L.libDir, unsignedOut);
        log.ok("Пакет собран: " + unsignedOut.getName() + " (" + unsignedOut.length() + " байт)");
        return unsignedOut;
    }

    // =========================================================
    //  aapt2 compile + link
    // =========================================================
    private void aapt2CompileLink(Layout L, File manifest, File compiledDir,
                                  File genDir, File baseApk) throws Exception {
        if (tc.aapt2 == null || !tc.aapt2.exists())
            throw new Exception("aapt2 не найден (libs/<abi>/libaapt2.so). Без него ресурсы не собрать.");

        List<String> linkInputs = new ArrayList<String>();

        if (L.resDir != null && L.resDir.isDirectory()) {
            // aapt2 compile --dir res -o compiled.zip
            File compiledZip = new File(compiledDir, "resources.zip");
            StringBuilder o = new StringBuilder();
            int c = tc.runNative(tc.aapt2, new String[] {
                    "compile", "--dir", L.resDir.getAbsolutePath(),
                    "-o", compiledZip.getAbsolutePath()
            }, o);
            if (c != 0 || !compiledZip.exists())
                throw new Exception("aapt2 compile завершился с кодом " + c + ":\n" + o);
            log.ok("aapt2 compile: OK");
            linkInputs.add(compiledZip.getAbsolutePath());
        } else {
            log.warn("res/ не найдена — линкую APK без пользовательских ресурсов.");
        }

        // aapt2 link -o base.apk -I android.jar --manifest M --java gen [compiled...]
        List<String> args = new ArrayList<String>();
        args.add("link");
        args.add("-o"); args.add(baseApk.getAbsolutePath());
        args.add("-I"); args.add(tc.androidJar.getAbsolutePath());
        args.add("--manifest"); args.add(manifest.getAbsolutePath());
        args.add("--java"); args.add(genDir.getAbsolutePath());
        args.add("--auto-add-overlay");
        // разрешаем без версии/имени — берём из манифеста
        for (String in : linkInputs) args.add(in);

        StringBuilder lo = new StringBuilder();
        int lc = tc.runNative(tc.aapt2, args.toArray(new String[0]), lo);
        if (lc != 0 || !baseApk.exists())
            throw new Exception("aapt2 link завершился с кодом " + lc + ":\n" + lo);
        log.ok("aapt2 link: OK — base.apk + R.java созданы");
    }

    // =========================================================
    //  Упаковка: base.apk (ресурсы+manifest) + dex + assets + lib
    // =========================================================
    private void packageApk(File baseApk, File dexDir, File assetsDir,
                            File libDir, File out) throws Exception {
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out));
        java.util.Set<String> added = new java.util.HashSet<String>();

        // Оценка общего числа файлов для прогресса упаковки (панорама + %).
        pkgTotal = countZipEntries(baseApk) + countDex(dexDir)
                + countTree(assetsDir) + countTree(libDir);
        pkgDone = 0;
        log.progress("Упаковка APK", 0, pkgTotal, null);

        // 1) всё из base.apk (ресурсы, resources.arsc, AndroidManifest.xml бинарный)
        ZipInputStream zis = new ZipInputStream(new FileInputStream(baseApk));
        ZipEntry e;
        byte[] buf = new byte[65536];
        while ((e = zis.getNextEntry()) != null) {
            if (added.contains(e.getName())) { zis.closeEntry(); continue; }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            int n; while ((n = zis.read(buf)) > 0) bo.write(buf, 0, n);
            writeEntry(zos, e.getName(), bo.toByteArray(), added);
            pkgProgress(e.getName());
            zis.closeEntry();
        }
        zis.close();

        // 2) classes.dex, classes2.dex …
        File[] dexes = dexDir.listFiles();
        if (dexes != null) {
            java.util.Arrays.sort(dexes);
            for (File d : dexes) {
                if (d.getName().endsWith(".dex")) {
                    writeEntry(zos, d.getName(), readAll(d), added);
                    pkgProgress(d.getName());
                }
            }
        }

        // 3) assets/**
        if (assetsDir != null && assetsDir.isDirectory())
            addTree(zos, assetsDir, "assets/", added);

        // 4) lib/** (нативные .so) — если папка называется libs, кладём под lib/
        if (libDir != null && libDir.isDirectory())
            addTree(zos, libDir, "lib/", added);

        log.progress("Упаковка APK", pkgTotal, pkgTotal, null);
        zos.close();
    }

    // =========================================================
    //  Утилиты
    // =========================================================
    private void addIfHasJava(Layout L, File dir) {
        if (dir != null && dir.isDirectory() && hasJava(dir)) L.srcRoots.add(dir);
    }
    private boolean hasJava(File dir) {
        File[] ch = dir.listFiles();
        if (ch == null) return false;
        for (File f : ch) {
            if (f.isFile() && f.getName().endsWith(".java")) return true;
            if (f.isDirectory() && !f.getName().equals("res") && hasJava(f)) return true;
        }
        return false;
    }
    private boolean hasAbiSo(File dir) {
        File[] ch = dir.listFiles();
        if (ch == null) return false;
        for (File f : ch) {
            if (f.isDirectory()) {
                String n = f.getName();
                if (n.contains("arm") || n.contains("x86")) {
                    File[] so = f.listFiles();
                    if (so != null) for (File s : so) if (s.getName().endsWith(".so")) return true;
                }
            }
        }
        return false;
    }
    private File findFile(File dir, String name, int depth) {
        if (depth < 0) return null;
        File[] ch = dir.listFiles();
        if (ch == null) return null;
        for (File f : ch) if (f.isFile() && f.getName().equals(name)) return f;
        for (File f : ch) if (f.isDirectory()) {
            File r = findFile(f, name, depth - 1);
            if (r != null) return r;
        }
        return null;
    }

    /** AndroidManifest.xml в текстовом (не бинарном) виде — годный для aapt2 link. */
    private File findTextManifest(File dir, int depth) {
        if (depth < 0 || dir == null) return null;
        File[] ch = dir.listFiles();
        if (ch == null) return null;
        for (File f : ch)
            if (f.isFile() && f.getName().equals("AndroidManifest.xml") && isTextManifest(f))
                return f;
        for (File f : ch) if (f.isDirectory()) {
            File r = findTextManifest(f, depth - 1);
            if (r != null) return r;
        }
        return null;
    }
    private boolean isTextManifest(File f) {
        try {
            byte[] b = new byte[4];
            FileInputStream in = new FileInputStream(f);
            int n = in.read(b); in.close();
            // бинарный AXML начинается с 0x03000800; текстовый — с '<' или BOM
            if (n >= 2 && b[0] == 0x03 && b[1] == 0x00) return false;
            return true;
        } catch (Exception e) { return true; }
    }

    /** Ищет вложенную папку с реальным проектом (манифест или src/sources с .java). */
    private File findNestedRoot(File dir, int depth) {
        if (depth < 0 || dir == null) return null;
        File[] ch = dir.listFiles();
        if (ch == null) return null;
        for (File f : ch) {
            if (!f.isDirectory()) continue;
            boolean hasM = new File(f, "AndroidManifest.xml").exists();
            boolean hasS = hasJava(new File(f, "src")) || hasJava(new File(f, "sources"))
                        || hasJava(new File(f, "java"));
            if (hasM || hasS) return f;
        }
        for (File f : ch) if (f.isDirectory()) {
            File r = findNestedRoot(f, depth - 1);
            if (r != null) return r;
        }
        return null;
    }

    /**
     * Генерирует минимальный AndroidManifest.xml из package, найденного в .java,
     * — чтобы sources-only jadx-вывод тоже можно было собрать напрямую.
     * Возвращает файл манифеста или null.
     */
    public File synthManifest(File proj, List<File> srcRoots) {
        String pkg = detectPackage(srcRoots);
        if (pkg == null) return null;
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
            "    package=\"" + pkg + "\"\n" +
            "    android:versionCode=\"1\" android:versionName=\"1.0\">\n" +
            "  <uses-sdk android:minSdkVersion=\"21\" android:targetSdkVersion=\"33\" />\n" +
            "  <application android:label=\"" + pkg + "\" android:allowBackup=\"true\" />\n" +
            "</manifest>\n";
        try {
            File out = new File(proj, "AndroidManifest.xml");
            FileOutputStream fo = new FileOutputStream(out);
            fo.write(xml.getBytes("UTF-8")); fo.close();
            log.warn("Манифест отсутствовал — сгенерирован минимальный (package=" + pkg + ").");
            return out;
        } catch (Exception e) { return null; }
    }
    private String detectPackage(List<File> srcRoots) {
        for (File root : srcRoots) {
            String p = scanPackage(root, 6);
            if (p != null) return p;
        }
        return null;
    }
    private String scanPackage(File dir, int depth) {
        if (depth < 0 || dir == null) return null;
        File[] ch = dir.listFiles();
        if (ch == null) return null;
        for (File f : ch) if (f.isFile() && f.getName().endsWith(".java")) {
            try {
                String t = new String(readAll(f), "UTF-8");
                java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?m)^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;").matcher(t);
                if (m.find()) return m.group(1);
            } catch (Exception ignore) {}
        }
        for (File f : ch) if (f.isDirectory()) {
            String r = scanPackage(f, depth - 1);
            if (r != null) return r;
        }
        return null;
    }

    private void addTree(ZipOutputStream zos, File root, String prefix,
                         java.util.Set<String> added) throws Exception {
        File[] ch = root.listFiles();
        if (ch == null) return;
        for (File f : ch) {
            String entry = prefix + f.getName();
            if (f.isDirectory()) addTree(zos, f, entry + "/", added);
            else { writeEntry(zos, entry, readAll(f), added); pkgProgress(entry); }
        }
    }

    // ---- прогресс упаковки ----
    private void pkgProgress(String name) {
        pkgDone++;
        log.progress("Упаковка APK", pkgDone, pkgTotal, name);
    }
    private int countZipEntries(File zip) {
        int n = 0;
        try {
            ZipInputStream zis = new ZipInputStream(new FileInputStream(zip));
            while (zis.getNextEntry() != null) { n++; zis.closeEntry(); }
            zis.close();
        } catch (Exception ignore) {}
        return n;
    }
    private int countDex(File dir) {
        int n = 0;
        File[] ch = (dir != null) ? dir.listFiles() : null;
        if (ch != null) for (File f : ch) if (f.getName().endsWith(".dex")) n++;
        return n;
    }
    private int countTree(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        int n = 0;
        File[] ch = dir.listFiles();
        if (ch != null) for (File f : ch) n += f.isDirectory() ? countTree(f) : 1;
        return n;
    }

    private void writeEntry(ZipOutputStream zos, String name, byte[] data,
                            java.util.Set<String> added) throws Exception {
        if (added.contains(name)) return;
        ZipEntry ze = new ZipEntry(name);
        // .so и resources.arsc храним без сжатия для корректной работы на 6.0+
        boolean store = name.endsWith(".so") || name.equals("resources.arsc");
        if (store) {
            ze.setMethod(ZipEntry.STORED);
            ze.setSize(data.length);
            ze.setCompressedSize(data.length);
            CRC32 crc = new CRC32(); crc.update(data);
            ze.setCrc(crc.getValue());
        } else {
            ze.setMethod(ZipEntry.DEFLATED);
        }
        zos.putNextEntry(ze);
        zos.write(data);
        zos.closeEntry();
        added.add(name);
    }

    private int readMinSdk(File manifest, int def) {
        try {
            String t = new String(readAll(manifest), "UTF-8");
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("minSdkVersion=\"(\\d+)\"").matcher(t);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Exception ignore) {}
        return def;
    }

    private void bumpManifest(File manifest) {
        try {
            String txt = new String(readAll(manifest), "UTF-8");
            String o = txt;
            txt = txt.replaceAll("android:targetSdkVersion=\"[0-9]+\"",
                    "android:targetSdkVersion=\"33\"");
            if (!txt.equals(o)) {
                FileOutputStream fo = new FileOutputStream(manifest);
                fo.write(txt.getBytes("UTF-8")); fo.close();
                log.line("Манифест: targetSdkVersion → 33");
            }
        } catch (Exception e) { log.warn("bumpManifest: " + e.getMessage()); }
    }

    private String rel(File base, File f) {
        String b = base.getAbsolutePath();
        String p = f.getAbsolutePath();
        return p.startsWith(b) ? p.substring(b.length()).replaceFirst("^/", "") : p;
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }
    private static void copy(File a, File b) throws Exception {
        InputStream in = new FileInputStream(a);
        OutputStream out = new FileOutputStream(b);
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close(); out.close();
    }
    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) { File[] c = f.listFiles(); if (c != null) for (File x : c) deleteRec(x); }
        f.delete();
    }
}
