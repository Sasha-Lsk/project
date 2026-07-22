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
 * Движок компиляции/декомпиляции.
 *
 * Использует уже дексованные jar-движки из assets/engine:
 *   apktool.jar  -> brut.apktool.Main   (apk<->smali, ресурсы)
 *   jadx.jar     -> jadx.cli.JadxCLI    (apk -> java)
 *   apksig.jar   -> com.android.apksig  (подпись apk)
 * и нативные бинарники aapt/aapt2/zipalign из assets/bin/<abi>.
 *
 * Все jar загружаются через DexClassLoader и вызываются рефлексией, поэтому
 * само приложение не зависит от их классов на этапе компиляции в AIDE.
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

    /** apk -> java (jadx). Возвращает выходную папку. */
    public File apkToJava(File apk, File outRoot) throws Exception {
        prepare();
        File out = new File(outRoot, baseName(apk) + "_java");
        rmdir(out);
        log.log("Декомпиляция APK -> java: " + apk.getName());
        log.progress(10);
        out.mkdirs();
        runJadxApi(apk, out);
        log.progress(85);

        // Формат AIDE (как в рабочем примере): sources/ = разобранный classes.dex
        // в .java, resources/ = манифест+res+assets+lib+META-INF БЕЗ файла
        // classes.dex. AIDE сам компилирует .java обратно в classes.dex при
        // сборке проекта. Поэтому убираем dex-файлы из вывода jadx.
        removeDexFiles(out);

        log.log("Готово: sources/ (java из classes.dex) + resources/ (ресурсы).");
        log.log("Открывайте папку в AIDE как проект — AIDE соберёт classes.dex из java.");

        log.progress(90);
        listTree(out);
        log.progress(100);
        return out;
    }

    /** Удалить classes*.dex из вывода jadx: в формате AIDE их быть не должно. */
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
            if (removed > 0) log.log("Убрано classes.dex-файлов (формат AIDE): " + removed);
        } catch (Throwable t) {
            log.err("removeDexFiles: " + t);
        }
    }

    /** (не используется в формате AIDE) извлечь classes*.dex из APK. */
    private void ensureDexExtracted(File apk, File out) {
        try {
            File resDir = new File(out, "resources");
            resDir.mkdirs();
            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apk);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            int copied = 0;
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                String n = e.getName();
                boolean isDex = n.matches("classes\\d*\\.dex");
                if (!isDex) continue;
                File dst = new File(resDir, n);
                if (dst.exists() && dst.length() > 0) continue; // уже сохранён jadx
                InputStream is = zf.getInputStream(e);
                FileOutputStream os = new FileOutputStream(dst);
                byte[] b = new byte[65536]; int r;
                while ((r = is.read(b)) > 0) os.write(b, 0, r);
                os.close(); is.close();
                copied++;
                log.log("  + resources/" + n + " (извлечён из APK)");
            }
            zf.close();
            if (copied > 0) {
                log.log("classes.dex сохранён для обратной сборки: " + copied + " шт.");
            } else {
                log.log("classes.dex уже присутствует в resources/.");
            }
        } catch (Throwable t) {
            log.err("Не удалось извлечь classes.dex из APK: " + t);
        }
    }

    /** smali-папка (формат apktool) -> apk. */
    public File smaliToApk(File projectDir, File outRoot, int androidVer) throws Exception {
        prepare();
        File built = new File(ctx.getCacheDir(), "build_" + System.currentTimeMillis() + ".apk");
        File finalApk = new File(outRoot, projectDir.getName() + "_rebuilt.apk");
        log.log("Сборка smali-проекта -> APK: " + projectDir.getName());
        log.progress(5);

        if (androidVer > 0) {
            patchTargetSdk(new File(projectDir, "apktool.yml"), androidVer);
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
     * java-проект -> apk.
     *
     * Определяет формат каталога:
     *  1) apktool-проект (есть apktool.yml или smali/) -> apktool b (см. smaliToApk).
     *  2) jadx/AIDE-формат: каталог resources/ (или сам каталог) с уже
     *     декодированными AndroidManifest.xml + res/ + classes.dex + lib/assets.
     *     Такой каталог apktool b НЕ понимает (ресурсы уже в текстовом виде,
     *     код в dex, а не smali), поэтому собираем нативным aapt2:
     *       aapt2 compile -> aapt2 link -> + classes.dex/lib/assets -> zipalign -> sign.
     *     Этот путь не использует java.awt/ImageIO и работает на Android.
     */
    public File javaToApk(File projectDir, File outRoot, int androidVer) throws Exception {
        prepare();
        log.log("Сборка java-проекта -> APK: " + projectDir.getName());

        // apktool-формат имеет приоритет
        if (new File(projectDir, "apktool.yml").exists()
                || new File(projectDir, "smali").isDirectory()) {
            return smaliToApk(projectDir, outRoot, androidVer);
        }

        // Ищем каталог с AndroidManifest.xml: сам projectDir или resources/
        File src = locateResRoot(projectDir);
        if (src == null) {
            throw new Exception("Не найден AndroidManifest.xml. Ожидается apktool-проект "
                    + "(apktool.yml/smali) или jadx/AIDE-каталог с resources/ "
                    + "(AndroidManifest.xml + res + classes.dex).");
        }
        log.log("Формат jadx/AIDE, корень ресурсов: " + src.getName());
        File finalApk = new File(outRoot, projectDir.getName() + "_rebuilt.apk");
        buildWithAapt2(src, finalApk, androidVer);
        log.progress(100);
        return finalApk;
    }

    /** Найти каталог, содержащий AndroidManifest.xml. */
    private static File locateResRoot(File dir) {
        if (new File(dir, "AndroidManifest.xml").exists()) return dir;
        File r = new File(dir, "resources");
        if (new File(r, "AndroidManifest.xml").exists()) return r;
        return null;
    }

    // ---------- Внутреннее ----------

    /**
     * Полноценная сборка APK нативным aapt2 из декодированного каталога.
     * Работает для jadx/AIDE-формата (classes.dex уже готов).
     */
    private void buildWithAapt2(File root, File finalApk, int androidVer) throws Exception {
        File manifest = new File(root, "AndroidManifest.xml");
        File srcRes = new File(root, "res");
        File work = new File(ctx.getCacheDir(), "aapt_" + System.currentTimeMillis());
        File compiled = new File(work, "compiled");
        compiled.mkdirs();
        File linkedApk = new File(work, "base.apk");

        // Копируем res/ в рабочую папку и чиним несовместимые с aapt2 места
        // (apktool-dummy ресурсы, public.xml и т.п.), не трогая исходник.
        File resDir = new File(work, "res");
        int rc;
        java.util.List<File> flats = new java.util.ArrayList<File>();
        if (srcRes.isDirectory()) {
            log.log("Подготовка ресурсов (фикс apktool-заглушек)...");
            copyDir(srcRes, resDir);
            sanitizeValues(resDir);
            fixEnumInts(resDir);
            log.log("aapt2 compile ресурсов (пофайлово)...");
            log.progress(20);
            // Компилируем каждый файл отдельно: один битый ресурс не рушит всё.
            flats = compileResPerFile(resDir, compiled);
        } else {
            log.log("Каталог res/ отсутствует — собираю только с манифестом.");
        }

        log.log("aapt2 link...");
        log.progress(45);
        ArrayList<String> link = new ArrayList<String>();
        link.add(aapt2.getAbsolutePath());
        link.add("link");
        link.add("-o"); link.add(linkedApk.getAbsolutePath());
        link.add("--manifest"); link.add(manifest.getAbsolutePath());
        link.add("-I"); link.add(androidFramework());
        link.add("--auto-add-overlay");
        // Не требуем строгой валидации версий/атрибутов старых apk.
        link.add("--warn-manifest-validation");
        int minSdk = 9, targetSdk = androidVer > 0 ? Integer.parseInt(sdkOf(androidVer)) : 25;
        link.add("--min-sdk-version"); link.add(String.valueOf(minSdk));
        link.add("--target-sdk-version"); link.add(String.valueOf(targetSdk));
        for (File f : flats) link.add(f.getAbsolutePath());
        rc = exec(link.toArray(new String[0]));
        if (rc != 0 || !linkedApk.exists()) {
            // Повтор без --warn-manifest-validation (старые aapt2 его не знают)
            log.log("Повтор aapt2 link без доп. флагов...");
            ArrayList<String> l2 = new ArrayList<String>();
            l2.add(aapt2.getAbsolutePath()); l2.add("link");
            l2.add("-o"); l2.add(linkedApk.getAbsolutePath());
            l2.add("--manifest"); l2.add(manifest.getAbsolutePath());
            l2.add("-I"); l2.add(androidFramework());
            l2.add("--auto-add-overlay");
            l2.add("--min-sdk-version"); l2.add(String.valueOf(minSdk));
            l2.add("--target-sdk-version"); l2.add(String.valueOf(targetSdk));
            for (File f : flats) l2.add(f.getAbsolutePath());
            rc = exec(l2.toArray(new String[0]));
            if (rc != 0 || !linkedApk.exists()) {
                throw new Exception("aapt2 link завершился с кодом " + rc
                        + ". Проверьте res/ и AndroidManifest.xml.");
            }
        }

        log.log("Упаковка classes.dex / lib / assets...");
        log.progress(65);
        addFilesToApk(linkedApk, root);

        finalizeApk(linkedApk, finalApk);
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
                if (rc == 0) {
                    ok++;
                } else {
                    fail++;
                    log.err("Пропущен ресурс (aapt2): " + td.getName() + "/" + f.getName());
                }
            }
        }
        // Собираем все .flat, что реально создались
        File[] produced = outDir.listFiles();
        if (produced != null) for (File p : produced) {
            if (p.getName().endsWith(".flat")) flats.add(p);
        }
        log.log("Скомпилировано ресурсов: " + ok + ", пропущено: " + fail
                + ", .flat: " + flats.size());
        return flats;
    }

    /**
     * Чинит значения ресурсов, несовместимые с aapt2:
     *  - apktool-заглушки <drawable name="x">false</drawable> (старый формат,
     *    aapt2 ругается "invalid drawable") -> <item type="drawable">@null</item>;
     *  - пустые/битые <drawable>...</drawable> со скалярным значением.
     */
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
                    // <drawable name="X">СКАЛЯР</drawable> где скаляр не ссылка/цвет
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("<drawable(\\s+name=\"[^\"]*\")\\s*>\\s*([^<@#][^<]*?)\\s*</drawable>")
                            .matcher(c);
                    StringBuffer sb = new StringBuffer();
                    while (m.find()) {
                        m.appendReplacement(sb,
                                java.util.regex.Matcher.quoteReplacement(
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

    /**
     * apktool при декомпиляции нередко пишет значения enum/flag-атрибутов как
     * сырые числа (<item name="attr">5</item>), а строгий aapt2 требует
     * символьное имя из <enum>. Читаем attrs*.xml, строим карту
     * attr -> {intValue -> enumName} и заменяем числа на имена.
     */
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
                        if (m == null) { m = new java.util.HashMap<String, String>(); }
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
                            im.appendReplacement(sb,
                                    java.util.regex.Matcher.quoteReplacement(im.group(0)));
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

    /** Дописать в собранный aapt2 apk dex, lib, assets и прочее содержимое. */
    private void addFilesToApk(File apk, File root) throws Exception {
        java.util.Map<String, File> add = new java.util.LinkedHashMap<String, File>();
        File[] top = root.listFiles();
        if (top != null) for (File f : top) {
            if (f.isFile() && f.getName().matches("classes\\d*\\.dex")) {
                add.put(f.getName(), f);
                log.log("  + " + f.getName());
            }
        }
        collect(new File(root, "lib"), root, add);
        collect(new File(root, "assets"), root, add);
        if (top != null) for (File f : top) {
            String n = f.getName();
            if (f.isFile() && !n.matches("classes\\d*\\.dex")
                    && !n.equals("AndroidManifest.xml")
                    && !n.equals("resources.arsc")
                    && !n.equals("apktool.yml")) {
                add.put(n, f);
            }
        }
        mergeIntoZip(apk, add);
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

    /** Добавить файлы в существующий zip/apk (без пересжатия исходного). */
    private void mergeIntoZip(File apk, java.util.Map<String, File> add) throws Exception {
        if (add.isEmpty()) return;
        File tmp = new File(apk.getParentFile(), apk.getName() + ".merge");
        java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(apk)));
        java.util.zip.ZipOutputStream zout = new java.util.zip.ZipOutputStream(
                new java.io.BufferedOutputStream(new java.io.FileOutputStream(tmp)));
        byte[] buf = new byte[65536];
        java.util.HashSet<String> existing = new java.util.HashSet<String>();
        java.util.zip.ZipEntry e;
        while ((e = zin.getNextEntry()) != null) {
            existing.add(e.getName());
            zout.putNextEntry(new java.util.zip.ZipEntry(e.getName()));
            int n; while ((n = zin.read(buf)) > 0) zout.write(buf, 0, n);
            zout.closeEntry();
        }
        zin.close();
        for (java.util.Map.Entry<String, File> en : add.entrySet()) {
            if (existing.contains(en.getKey())) continue;
            zout.putNextEntry(new java.util.zip.ZipEntry(en.getKey()));
            java.io.FileInputStream fin = new java.io.FileInputStream(en.getValue());
            int n; while ((n = fin.read(buf)) > 0) zout.write(buf, 0, n);
            fin.close();
            zout.closeEntry();
        }
        zout.close();
        if (!apk.delete() || !tmp.renameTo(apk)) {
            throw new Exception("Не удалось пересобрать apk с dex/lib/assets.");
        }
    }

    /** Путь к SDK android.jar (с resources.arsc) для aapt2 -I. */
    private String androidFramework() throws Exception {
        File jar = new File(dirEngine, "android.jar");
        if (jar.exists() && jar.length() > 0) return jar.getAbsolutePath();
        throw new Exception("Не найден android.jar для aapt2 (assets/engine/android.jar).");
    }

    /** zipalign + подпись testkey. */
    private void finalizeApk(File in, File out) throws Exception {
        log.log("zipalign...");
        File aligned = new File(ctx.getCacheDir(), "aligned_" + System.currentTimeMillis() + ".apk");
        int rc = exec(new String[]{zipalign.getAbsolutePath(), "-f", "-p", "4",
                in.getAbsolutePath(), aligned.getAbsolutePath()});
        File toSign = (rc == 0 && aligned.exists()) ? aligned : in;
        if (rc != 0) log.err("zipalign вернул код " + rc + ", подписываю без выравнивания.");
        log.progress(85);
        log.log("Подпись APK (testkey)...");
        signApk(toSign, out);
        log.log("Готово: " + out.getAbsolutePath());
    }

    /** Подпись через com.android.apksig.ApkSigner (рефлексия). */
    private void signApk(File in, File out) throws Exception {
        ClassLoader cl = loader("apksig.jar");
        File pk8 = new File(dirEngine, "testkey.pk8");
        File pem = new File(dirEngine, "testkey.x509.pem");

        java.security.PrivateKey key = ApkSignHelper.readPk8(pk8);
        java.security.cert.X509Certificate cert = ApkSignHelper.readCert(pem);

        Class<?> signerConfigBuilder = cl.loadClass("com.android.apksig.ApkSigner$SignerConfig$Builder");
        Object scb = signerConfigBuilder
                .getConstructor(String.class, java.security.PrivateKey.class, java.util.List.class)
                .newInstance("CERT", key, java.util.Collections.singletonList(cert));
        Object signerConfig = signerConfigBuilder.getMethod("build").invoke(scb);

        Class<?> apkSignerBuilder = cl.loadClass("com.android.apksig.ApkSigner$Builder");
        Object builder = apkSignerBuilder
                .getConstructor(java.util.List.class)
                .newInstance(java.util.Collections.singletonList(signerConfig));
        apkSignerBuilder.getMethod("setInputApk", File.class).invoke(builder, in);
        apkSignerBuilder.getMethod("setOutputApk", File.class).invoke(builder, out);
        // v1+v2+v3 для совместимости со всеми версиями Android
        tryCall(apkSignerBuilder, builder, "setV1SigningEnabled", boolean.class, true);
        tryCall(apkSignerBuilder, builder, "setV2SigningEnabled", boolean.class, true);
        tryCall(apkSignerBuilder, builder, "setV3SigningEnabled", boolean.class, true);

        Object apkSigner = apkSignerBuilder.getMethod("build").invoke(builder);
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
     * jadx через публичный API (без System.exit, безопасно на Android):
     *   JadxArgs a = new JadxArgs();
     *   a.setInputFiles(list); a.setOutDir(out);
     *   JadxDecompiler d = new JadxDecompiler(a); d.load(); d.save();
     */
    private void runJadxApi(File apk, File out) throws Exception {
        log.log("jadx (API): " + apk.getName() + " -> " + out.getAbsolutePath());
        ClassLoader cl = loader("jadx.jar");

        PrintStream oldOut = System.out, oldErr = System.err;
        System.setOut(new PrintStream(new LogStream(false), true));
        System.setErr(new PrintStream(new LogStream(true), true));
        try {
            Class<?> argsCls = cl.loadClass("jadx.api.JadxArgs");
            Object args = argsCls.getConstructor().newInstance();

            java.util.List<File> in = new java.util.ArrayList<File>();
            in.add(apk);
            try {
                argsCls.getMethod("setInputFiles", java.util.List.class).invoke(args, in);
            } catch (NoSuchMethodException e) {
                argsCls.getMethod("setInputFile", File.class).invoke(args, apk);
            }
            argsCls.getMethod("setOutDir", File.class).invoke(args, out);

            Class<?> decCls = cl.loadClass("jadx.api.JadxDecompiler");
            Object dec = decCls.getConstructor(argsCls).newInstance(args);
            decCls.getMethod("load").invoke(dec);
            decCls.getMethod("save").invoke(dec);
            try { decCls.getMethod("close").invoke(dec); } catch (Throwable ignore) {}
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    /** Исключение вместо System.exit(code). */
    static class ExitTrap extends SecurityException {
        final int code;
        ExitTrap(int code) { this.code = code; }
    }

    /**
     * Загрузить jar и вызвать public static void main(String[]).
     * apktool 2.7.0 на ошибке вызывает System.exit(1) — чтобы это не убило
     * приложение, ставим временный SecurityManager, превращающий exit в
     * исключение. На Android 34+ SecurityManager недоступен — тогда ловим
     * штатно (apktool на успехе завершает без exit).
     */
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
        } catch (Throwable ignore) {
            // Android 34+: SecurityManager запрещён — продолжаем без него.
        }

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
                // Разворачиваем ExceptionInInitializerError -> реальная причина
                Throwable real = cause;
                while (real instanceof ExceptionInInitializerError
                        && real.getCause() != null) {
                    real = real.getCause();
                }
                if (real != null) {
                    log.err("Причина сбоя движка: " + real);
                    StackTraceElement[] st = real.getStackTrace();
                    for (int i = 0; i < Math.min(st.length, 10); i++) {
                        log.err("    at " + st[i]);
                    }
                }
                throw new Exception(real != null ? String.valueOf(real) : e.toString(), real);
            }
        } finally {
            if (smInstalled) {
                try { System.setSecurityManager(oldSm); } catch (Throwable ignore) {}
            }
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    private ClassLoader loader(String jar) {
        File f = new File(dirEngine, jar);
        File opt = new File(ctx.getCacheDir(), "dexopt");
        opt.mkdirs();
        return new DexClassLoader(f.getAbsolutePath(), opt.getAbsolutePath(),
                null, ctx.getClassLoader());
    }

    /** Поток, перенаправляющий вывод движков построчно в лог. */
    private class LogStream extends OutputStream {
        private final boolean isErr;
        private final StringBuilder sb = new StringBuilder();
        LogStream(boolean isErr) { this.isErr = isErr; }
        @Override public void write(int b) {
            if (b == '\n') { flushLine(); }
            else if (b != '\r') sb.append((char) b);
        }
        private void flushLine() {
            String s = sb.toString(); sb.setLength(0);
            if (s.trim().isEmpty()) return;
            // Известные НЕфатальные ошибки jadx на Android (XML security feature
            // отсутствует в парсере Android). Ресурсы всё равно выгружаются —
            // помечаем как предупреждение, а не как красную ошибку.
            if (s.contains("disallow-doctype-decl")
                    || s.contains("Failed to create ManifestAttributes")
                    || s.contains("ManifestAttributes")
                    || s.contains("Xml load error, file: /android/attrs.xml")
                    || s.contains("Failed to parse '.arsc'")
                    || s.contains("updateManifestAttribMap")
                    || s.contains("jadx.core.xmlgen")
                    || s.contains("RootNode.loadResources")
                    || s.contains("XmlSecurity")
                    || s.contains("DocumentBuilderFactoryImpl")
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

        // aapt2/aapt/zipalign лежат в nativeLibraryDir как lib*.so — это
        // единственное место, откуда Android разрешает выполнять бинарники
        // (в /data/.../files/ стоит noexec, отсюда error=13 Permission denied).
        aapt2 = nativeBin("libaapt2.so");
        aapt = nativeBin("libaapt.so");
        zipalign = nativeBin("libzipalign.so");

        copyAsset("engine/apktool.jar", new File(dirEngine, "apktool.jar"));
        copyAsset("engine/jadx.jar", new File(dirEngine, "jadx.jar"));
        copyAsset("engine/apksig.jar", new File(dirEngine, "apksig.jar"));
        copyAsset("engine/android.jar", new File(dirEngine, "android.jar"));
        copyAsset("key/testkey.pk8", new File(dirEngine, "testkey.pk8"));
        copyAsset("key/testkey.x509.pem", new File(dirEngine, "testkey.x509.pem"));
        new File(dirEngine, "framework").mkdirs();

        setupSystemProps();

        prepared = true;
        log.log("Движки развёрнуты.");
    }

    /**
     * apktool/jadx рассчитаны на настольную JVM и в статических инициализаторах
     * читают системные свойства, которых на Android нет или они пустые. Без них
     * падает ExceptionInInitializerError. Проставляем совместимые значения.
     */
    private void setupSystemProps() {
        File tmp = new File(ctx.getCacheDir(), "tmp");
        tmp.mkdirs();
        setProp("java.io.tmpdir", tmp.getAbsolutePath());
        setProp("user.home", ctx.getFilesDir().getAbsolutePath());
        setProp("user.dir", ctx.getFilesDir().getAbsolutePath());
        // OSDetection в apktool смотрит os.name; на Android бывает "Linux",
        // но иногда null — фиксируем явно как Linux (aapt мы всё равно задаём сами).
        String osName = System.getProperty("os.name");
        if (osName == null || osName.trim().isEmpty()) setProp("os.name", "Linux");
        setProp("os.arch", is64Bit() ? "aarch64" : "arm");
        setProp("sun.arch.data.model", is64Bit() ? "64" : "32");
        // jadx: включаем режим, не требующий отсутствующей на Android XML-фичи
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

    /**
     * Найти исполняемый бинарник в nativeLibraryDir (папка lib установленного
     * приложения). Файлы вида lib*.so из libs/<abi> Android распаковывает сюда
     * и разрешает исполнять. Это обходит error=13 Permission denied.
     */
    private File nativeBin(String soName) throws Exception {
        String nativeDir = ctx.getApplicationInfo().nativeLibraryDir;
        File f = new File(nativeDir, soName);
        if (f.exists()) {
            f.setExecutable(true, false);
            return f;
        }
        // Fallback: попытаться найти через System.mapLibraryName в других путях
        throw new Exception("Не найден бинарник " + soName + " в nativeLibraryDir ("
                + nativeDir + "). Проверьте, что libs/<abi>/" + soName
                + " попал в APK (в AIDE — папка libs).");
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
        if (dst.exists() && dst.length() > 0) return; // уже развёрнуто
        AssetManager am = ctx.getAssets();
        InputStream is = am.open(assetPath);
        FileOutputStream os = new FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int n;
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

    /** Android версия -> API level. */
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
                else {
                    log.log("  + " + rel(root, k));
                    count++;
                }
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
        for (String s : a) { sb.append(s).append(' '); }
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
