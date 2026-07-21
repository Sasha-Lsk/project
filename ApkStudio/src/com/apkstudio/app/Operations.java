package com.apkstudio.app;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Четыре операции. Все папки лежат в общей рабочей директории:
 *   /storage/emulated/0/Download/ApkStudio/
 *      decompiler_apk/<имя>/        — распакованный проект (smali или java)
 *      compiler_apk/<имя>.apk       — собранный подписанный APK
 */
public class Operations {

    public static final String ROOT =
            "/storage/emulated/0/Download/ApkStudio";
    public static final String DEC_DIR = ROOT + "/decompiler_apk";
    public static final String OUT_DIR = ROOT + "/compiler_apk";

    private final Context ctx;
    private final Toolchain tc;
    private final Toolchain.Log log;

    public Operations(Context ctx, Toolchain tc, Toolchain.Log log) {
        this.ctx = ctx;
        this.tc = tc;
        this.log = log;
    }

    private File dexOpt() {
        File d = new File(tc.frameworkDir.getParentFile(), "dexopt");
        d.mkdirs();
        return d;
    }

    private static String baseName(String apkName) {
        String n = new File(apkName).getName();
        int dot = n.toLowerCase().lastIndexOf(".apk");
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static void ensure(File d) { if (!d.exists()) d.mkdirs(); }

    // =========================================================
    //  APK -> SMALI (apktool d)
    // =========================================================
    public File apkToSmali(File apk) throws Exception {
        log.line("=====================================================");
        log.cmd("ОПЕРАЦИЯ: APK → Smali  (apktool d)");
        log.line("Вход: " + apk.getAbsolutePath() + "  (" + apk.length() + " байт)");

        File decRoot = new File(DEC_DIR);
        ensure(decRoot);
        File out = new File(decRoot, baseName(apk.getName()));
        if (out.exists()) {
            log.warn("Папка уже существует, очищаю: " + out.getName());
            deleteRec(out);
        }

        Apktool apktool = new Apktool(tc.apktoolJar, dexOpt(), log);
        apktool.decode(apk, out, tc.frameworkDir);

        if (!hasSmali(out)) throw new Exception("apktool не создал smali-папку — проверьте, что это валидный APK");

        log.ok("Готово. Smali-проект: " + out.getAbsolutePath());
        log.line("Структура (стандарт apktool):");
        log.line("  " + out.getName() + "/AndroidManifest.xml");
        log.line("  " + out.getName() + "/apktool.yml");
        log.line("  " + out.getName() + "/res/           ← ресурсы");
        log.line("  " + out.getName() + "/smali/         ← код (редактируйте .smali)");
        log.line("  " + out.getName() + "/smali_classesN/ ← multidex, если есть");
        log.line("  " + out.getName() + "/assets/, lib/, unknown/ ← как в оригинале");
        return out;
    }

    private boolean hasSmali(File dir) {
        if (dir == null || !dir.exists()) return false;
        File[] ch = dir.listFiles();
        if (ch == null) return false;
        for (File f : ch) if (f.isDirectory() && f.getName().startsWith("smali")) return true;
        return false;
    }

    // =========================================================
    //  APK -> JAVA (jadx)  — для ЧТЕНИЯ/понимания кода
    // =========================================================
    public File apkToJava(File apk) throws Exception {
        log.line("=====================================================");
        log.cmd("ОПЕРАЦИЯ: APK → Java  (jadx, декомпиляция для чтения)");
        log.warn("ВНИМАНИЕ: jadx-Java предназначен для ЧТЕНИЯ и правки логики.");
        log.warn("Обратная сборка Java→APK работает надёжно только для простого");
        log.warn("кода. Для гарантированной пересборки чужого APK используйте Smali.");
        log.line("Вход: " + apk.getAbsolutePath());

        File decRoot = new File(DEC_DIR);
        ensure(decRoot);
        File out = new File(decRoot, baseName(apk.getName()) + "_java");
        if (out.exists()) { deleteRec(out); }
        ensure(out);

        File srcDir = new File(out, "sources");
        srcDir.mkdirs();
        File resDir = new File(out, "resources");   // res/ + AndroidManifest.xml
        resDir.mkdirs();
        Jadx jadx = new Jadx(tc.jadxJar, dexOpt(), log);
        // ПОЛНЫЙ проект: sources + resources (res/ + AndroidManifest.xml).
        // Это делает jadx-вывод пригодным для прямой обратной сборки Java → APK
        // (как в AIDE): ProjectBuilder.detect() понимает раскладку sources/ +
        // resources/res + resources/AndroidManifest.xml.
        jadx.decompile(apk, srcDir, resDir);

        // Сохраняем копию исходного APK внутри проекта — гарантированный fallback,
        // если прямая сборка jadx-Java не пройдёт (битый декомпилированный код).
        try {
            copy(apk, new File(out, "original.apk"));
            log.line("Исходный APK сохранён в проект (original.apk) — резервный путь сборки.");
        } catch (Exception e) {
            log.warn("Не удалось сохранить копию APK: " + e);
        }

        int files = countJava(srcDir);
        boolean hasManifest = new File(resDir, "AndroidManifest.xml").exists();
        if (files == 0) {
            log.warn("Java-файлы не созданы — возможно, dex зашифрован/защищён. Содержимое:");
            listTop(out);
        }
        log.ok("Готово. Java-исходников: " + files + "  →  " + srcDir.getAbsolutePath());
        log.line("  " + out.getName() + "/sources/    ← .java файлы (по пакетам)");
        log.line("  " + out.getName() + "/resources/  ← res/ + AndroidManifest.xml"
                + (hasManifest ? "" : "  (манифест не извлечён)"));
        log.line("Обратная сборка: кнопка Java → APK соберёт этот проект напрямую.");
        return out;
    }

    // =========================================================
    //  SMALI -> APK (apktool b + zipalign + подпись apksig)
    // =========================================================
    public File smaliToApk(File projectDir, boolean bumpTarget) throws Exception {
        log.line("=====================================================");
        log.cmd("ОПЕРАЦИЯ: Smali → APK  (apktool b → zipalign → sign)");
        log.line("Проект: " + projectDir.getAbsolutePath());

        if (!new File(projectDir, "apktool.yml").exists())
            throw new Exception("Это не apktool-проект (нет apktool.yml). Выберите папку из decompiler_apk.");

        if (bumpTarget) bumpManifest(new File(projectDir, "AndroidManifest.xml"));

        ensure(new File(OUT_DIR));
        File unsigned = new File(OUT_DIR, projectDir.getName() + "_unsigned.apk");
        File aligned  = new File(OUT_DIR, projectDir.getName() + "_aligned.apk");
        File signed   = new File(OUT_DIR, projectDir.getName() + ".apk");
        unsigned.delete(); aligned.delete(); signed.delete();

        // apktool build через библиотечный API + нативный aapt2 устройства
        File tmpDir = new File(tc.frameworkDir.getParentFile(), "tmp");
        Apktool apktool = new Apktool(tc.apktoolJar, dexOpt(), log);
        apktool.build(projectDir, unsigned, tc.frameworkDir, tc.aapt2, tmpDir);
        if (!unsigned.exists()) throw new Exception("apktool build не создал APK");
        log.ok("1/3 apktool build: OK (" + unsigned.length() + " байт)");

        alignAndSign(unsigned, aligned, signed);
        return signed;
    }

    // =========================================================
    //  JAVA -> APK  (как в AIDE: сборка ИЗ ПАПКИ ИСХОДНИКОВ, без оригинала)
    //  Конвейер: aapt2 compile+link → R.java → ecj → d8 → package → sign.
    //  Оригинальный APK НЕ требуется.
    // =========================================================
    /** Маркер версии сборщика — виден в логе, чтобы отличить свежий APK от старого. */
    public static final String BUILDER_VERSION = "v5 (synthManifest+nested+diag)";

    public File javaToApk(File projectDir, boolean bumpTarget, File sourceApk) throws Exception {
        log.line("=====================================================");
        log.cmd("ОПЕРАЦИЯ: Java → APK  [" + BUILDER_VERSION + "]");
        log.line("Проект: " + projectDir.getAbsolutePath());

        // 1) apktool-проект (apktool.yml + smali) → надёжный smali-путь.
        if (new File(projectDir, "apktool.yml").exists() && hasSmali(projectDir)) {
            log.line("Обнаружен apktool-проект (smali) → собираю через apktool build.");
            return smaliToApk(projectDir, bumpTarget);
        }

        // 2) Полноценный Java-проект (манифест + исходники: AIDE/Gradle) →
        //    ПРЯМАЯ сборка из исходников как в AIDE (ecj → d8 → aapt2 → sign).
        ProjectBuilder pb = new ProjectBuilder(tc, log);
        ProjectBuilder.Layout L = pb.detect(projectDir);
        // Диагностика: что реально нашли (видно в логе на телефоне).
        log.line("detect(): manifest=" + (L.manifest != null ? L.manifest.getAbsolutePath() : "нет")
                + ", src-корней=" + L.srcRoots.size()
                + ", res=" + (L.resDir != null ? "есть" : "нет"));

        // Если есть исходники, но нет манифеста — синтезируем минимальный
        // (sources-only jadx-вывод тоже соберём напрямую, как AIDE).
        if (!L.srcRoots.isEmpty() && L.manifest == null) {
            File synth = pb.synthManifest(projectDir, L.srcRoots);
            if (synth != null) L = pb.detect(projectDir);
        }

        if (L.isValid()) {
            try {
                log.ok("Найдены AndroidManifest.xml + исходники .java → прямая сборка (как в AIDE).");
                return directBuild(pb, projectDir, bumpTarget, sourceApk);
            } catch (Throwable e) {
                // Прямая сборка не удалась (например, jadx-Java с
                // // decompilation error, или несовместимые ресурсы). Пытаемся
                // гарантированный fallback через оригинальный APK / smali.
                log.err("Прямая сборка не удалась: " + e);
                log.warn("Пробую резервный путь (оригинальный APK / smali)…");
                File fb = rebuildFromOriginal(projectDir, bumpTarget, sourceApk);
                if (fb != null) return fb;
                // fallback нечем сделать — пробрасываем исходную ошибку сборки
                throw (e instanceof Exception) ? (Exception) e : new Exception(e);
            }
        }

        // 3) Нет манифеста/исходников (обычный jadx-вывод <имя>_java/sources) →
        //    сборка через оригинальный APK / smali того же имени (как раньше).
        log.warn("В папке нет AndroidManifest.xml + исходников для прямой сборки.");
        log.line("Это похоже на jadx-вывод (только sources/). Собираю через");
        log.line("оригинальный APK/smali — гарантированный рабочий APK.");
        File fb = rebuildFromOriginal(projectDir, bumpTarget, sourceApk);
        if (fb != null) return fb;

        throw new Exception(
            "Не удалось собрать APK. Варианты:\n" +
            "• Заново сделайте APK → Java (тогда original.apk сохранится в проект\n" +
            "  автоматически и сборка пройдёт), ИЛИ\n" +
            "• выберите исходный APK сверху и повторите Java → APK, ИЛИ\n" +
            "• для правки логики: APK → Smali, правьте .smali, затем Smali → APK.\n" +
            "Для прямой сборки (как в AIDE) папка должна содержать\n" +
            "AndroidManifest.xml и исходники .java (src/ или java/ или sources/).");
    }

    /** Прямая сборка из папки исходников (ecj → d8 → aapt2 → package → sign). */
    private File directBuild(ProjectBuilder pb, File projectDir, boolean bumpTarget, File sourceApk) throws Exception {
        ensure(new File(OUT_DIR));
        File unsigned = new File(OUT_DIR, projectDir.getName() + "_unsigned.apk");
        File aligned  = new File(OUT_DIR, projectDir.getName() + "_aligned.apk");
        File signed   = new File(OUT_DIR, cleanName(projectDir.getName()) + ".apk");
        unsigned.delete(); aligned.delete(); signed.delete();

        // Оригинальный APK (если есть) — источник настоящего targetSdkVersion,
        // когда jadx его потерял в текстовом манифесте. Приоритет: сохранённый
        // в проекте original.apk → выбранный сверху sourceApk.
        File origApk = new File(projectDir, "original.apk");
        if (!(origApk.exists() && origApk.length() > 0)) origApk = sourceApk;

        File work = new File(tc.frameworkDir.getParentFile(), "build_" + projectDir.getName());
        pb.buildUnsigned(projectDir, work, unsigned, bumpTarget, origApk);
        if (!unsigned.exists()) throw new Exception("Сборка не создала APK");

        alignAndSign(unsigned, aligned, signed);
        return signed;
    }

    /**
     * Гарантированный резервный путь: пересборка через ОРИГИНАЛЬНЫЙ APK.
     * Источник APK: сохранённый в проекте original.apk → выбранный сверху →
     * smali-проект того же имени. Возвращает null, если нечем собрать.
     */
    private File rebuildFromOriginal(File projectDir, boolean bumpTarget, File sourceApk) throws Exception {
        // а) smali-проект того же имени
        String name = projectDir.getName();
        if (name.endsWith("_java")) name = name.substring(0, name.length() - 5);
        File smaliProj = new File(DEC_DIR, name);
        if (smaliProj.exists() && new File(smaliProj, "apktool.yml").exists()) {
            log.ok("Найден smali-проект того же имени: " + smaliProj.getName() + " → собираю его.");
            return smaliToApk(smaliProj, bumpTarget);
        }

        // б) исходный APK: сохранённый original.apk или выбранный сверху
        File apk = null;
        File saved = new File(projectDir, "original.apk");
        if (saved.exists() && saved.length() > 0) {
            apk = saved;
            log.ok("Использую сохранённый в проекте original.apk.");
        } else if (sourceApk != null && sourceApk.exists()) {
            apk = sourceApk;
            log.ok("Использую выбранный сверху APK: " + sourceApk.getName());
        }
        if (apk != null) {
            log.line("Авто-декомпиляция исходного APK в smali и сборка…");
            File auto = apkToSmali(apk);
            log.ok("Готов smali-проект: " + auto.getName() + " → собираю APK.");
            return smaliToApk(auto, bumpTarget);
        }
        return null;
    }

    private static String cleanName(String n) {
        if (n.endsWith("_java")) n = n.substring(0, n.length() - 5);
        return n;
    }

    // ---------------- zipalign + подпись ----------------
    private void alignAndSign(File unsigned, File aligned, File signed) throws Exception {
        StringBuilder o = new StringBuilder();
        int z = tc.runNative(tc.zipalign, new String[]{"-f", "-p", "4",
                unsigned.getAbsolutePath(), aligned.getAbsolutePath()}, o);
        if (!aligned.exists()) {
            log.warn("zipalign не сработал (код " + z + "), подписываю невыровненный.");
            copy(unsigned, aligned);
        } else {
            log.ok("2/3 zipalign: OK");
        }

        // Подпись библиотекой apksig (v1+v2+v3) тестовым ключом
        File dexOpt = new File(tc.frameworkDir.getParentFile(), "dexopt");
        dexOpt.mkdirs();
        Signer signer = new Signer(tc.apksigJar, dexOpt, log);
        signer.sign(aligned, signed, tc.keyPk8, tc.keyPem);
        if (!signed.exists()) throw new Exception("Подпись не создана");
        log.ok("3/3 подпись (v1+v2+v3): OK");
        // чистим промежуточные
        unsigned.delete(); aligned.delete();
        log.ok("ГОТОВО: " + signed.getAbsolutePath() + "  (" + signed.length() + " байт)");
    }

    // Минимальный targetSdk, при котором нет окна «для старой версии» (порог 23).
    private static final int MIN_TARGET_NO_WARNING = 23;

    /**
     * Нормализует targetSdk в apktool-манифесте: СОХРАНЯЕТ оригинальное значение
     * (чтобы приложение вело себя как оригинал), а поднимает ТОЛЬКО если оно ниже
     * порога окна 23. Раньше жёстко ставили 33 — это ломало старые приложения на
     * новых Android (runtime-permissions ≥23, scoped storage ≥29). Теперь smali-
     * путь остаётся «как оригинал», а окно исчезает.
     */
    private void bumpManifest(File manifest) {
        try {
            if (!manifest.exists()) return;
            String txt = readAll(manifest);
            String orig = txt;

            // Существующий target: сохраняем, поднимаем лишь до 23, если ниже.
            int existing = -1;
            java.util.regex.Matcher tm = java.util.regex.Pattern
                    .compile("targetSdkVersion=\"(\\d+)\"").matcher(txt);
            if (tm.find()) existing = Integer.parseInt(tm.group(1));
            int min = 1;
            java.util.regex.Matcher mm = java.util.regex.Pattern
                    .compile("minSdkVersion=\"(\\d+)\"").matcher(txt);
            if (mm.find()) min = Integer.parseInt(mm.group(1));

            int target;
            if (existing > 0) target = Math.max(existing, MIN_TARGET_NO_WARNING);
            else               target = MIN_TARGET_NO_WARNING;
            if (target < min) target = min;
            String ta = "android:targetSdkVersion=\"" + target + "\"";

            if (existing == target && existing > 0) {
                log.line("Манифест: targetSdkVersion=" + existing + " сохранён (как оригинал).");
            } else {
                if (txt.matches("(?s).*android:targetSdkVersion=\"[0-9]+\".*")) {
                    txt = txt.replaceAll("android:targetSdkVersion=\"[0-9]+\"", ta);
                } else if (txt.matches("(?s).*<uses-sdk\\b.*")) {
                    txt = txt.replaceFirst("(<uses-sdk\\b[^>]*?)(\\s*/?>)", "$1 " + ta + "$2");
                } else if (txt.matches("(?s).*<manifest\\b[^>]*>.*")) {
                    txt = txt.replaceFirst("(<manifest\\b[^>]*>)",
                            "$1\n    <uses-sdk android:minSdkVersion=\"" + Math.max(min, 1) + "\" " + ta + "/>");
                }
                if (!txt.equals(orig)) {
                    writeAll(manifest, txt);
                    log.line("Манифест: targetSdkVersion → " + target
                            + " (минимально, чтобы убрать окно «для старой версии»).");
                }
            }

            // apktool.yml targetSdkVersion — тоже НЕ ниже порога окна, но не
            // завышаем сверх оригинала.
            File yml = new File(manifest.getParentFile(), "apktool.yml");
            if (yml.exists()) {
                String y = readAll(yml);
                java.util.regex.Matcher ym = java.util.regex.Pattern
                        .compile("targetSdkVersion:\\s*'?(\\d+)'?").matcher(y);
                if (ym.find()) {
                    int yt = Integer.parseInt(ym.group(1));
                    int nt = Math.max(yt, MIN_TARGET_NO_WARNING);
                    if (nt != yt) {
                        y = y.replaceAll("targetSdkVersion:\\s*'?[0-9]+'?",
                                "targetSdkVersion: '" + nt + "'");
                        writeAll(yml, y);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Не удалось нормализовать targetSdk: " + e.getMessage());
        }
    }

    // ---------------- утилиты ----------------
    private int countJava(File dir) {
        int n = 0;
        File[] ch = dir.listFiles();
        if (ch == null) return 0;
        for (File f : ch) {
            if (f.isDirectory()) n += countJava(f);
            else if (f.getName().endsWith(".java")) n++;
        }
        return n;
    }

    private void listTop(File dir) {
        File[] ch = dir.listFiles();
        if (ch == null) return;
        for (File f : ch) log.line("  • " + f.getName() + (f.isDirectory() ? "/" : ""));
    }

    private static void deleteRec(File f) {
        if (f.isDirectory()) { File[] c = f.listFiles(); if (c != null) for (File x : c) deleteRec(x); }
        f.delete();
    }

    private static String readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return new String(b.toByteArray(), "UTF-8");
    }
    private static void writeAll(File f, String s) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        o.write(s.getBytes("UTF-8")); o.close();
    }
    private static void copy(File a, File b) throws Exception {
        InputStream in = new FileInputStream(a);
        OutputStream out = new FileOutputStream(b);
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close(); out.close();
    }
}
