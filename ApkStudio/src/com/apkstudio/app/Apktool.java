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
        // apktool.decode() — единый непрозрачный вызов, поэтому реальный прогресс
        // считаем по числу файлов, реально появляющихся в выходной папке, а за
        // 100% берём число записей исходного APK (+запас на smali из dex).
        int total = estimateDecodeTotal(apk);
        DecodeProgress dp = new DecodeProgress("apktool: декодирование", outDir, total, log);
        dp.start();
        boolean decoded = false;
        try {
            decoderC.getMethod("decode").invoke(decoder);
            try { decoderC.getMethod("close").invoke(decoder); } catch (Throwable ignore) {}
            decoded = true;
        } finally {
            // stop(true) при успехе выставит 100%; при ошибке — просто остановит
            // наблюдатель без искусственного «100%». Исключение пробрасывается
            // самим finally, дополнительный throw не нужен (иначе ecj требует
            // объявления Throwable в throws).
            dp.stop(decoded);
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
        // Ресурсы с '$' в имени (inline из animated-vector/adaptive-icon)
        // aapt2 не принимает при обратной сборке — переименовываем сразу.
        try {
            File res = new File(outDir, "res");
            if (res.isDirectory()) DollarResFix.fix(res, log);
        } catch (Throwable t) {
            log.warn("res: правка '$'-имён пропущена: " + t);
        }
    }

    /**
     * Оценка полного числа файлов декомпиляции: записи APK (ресурсы, assets,
     * lib, META-INF) + запас на .smali (каждый .dex разворачивается в множество
     * .smali-файлов — берём грубый коэффициент по размеру dex).
     */
    private int estimateDecodeTotal(File apk) {
        int entries = 0; long dexBytes = 0;
        java.util.zip.ZipInputStream zis = null;
        try {
            zis = new java.util.zip.ZipInputStream(new java.io.FileInputStream(apk));
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (!e.isDirectory()) {
                    entries++;
                    String n = e.getName();
                    if (n.endsWith(".dex")) dexBytes += Math.max(e.getSize(), 0);
                }
                zis.closeEntry();
            }
        } catch (Throwable ignore) {
        } finally {
            if (zis != null) try { zis.close(); } catch (Throwable ig) {}
        }
        // ~1 smali-файл на 1.5 КБ dex — грубая, но устойчивая оценка.
        int smali = (int) (dexBytes / 1536L);
        int total = entries + smali;
        return total > 0 ? total : 1;
    }

    /**
     * Реальный прогресс декомпиляции: фоновый поток считает файлы, реально
     * появившиеся в выходной папке проекта, и репортит имя последнего +
     * done/total. Так полоса и % отражают фактически распакованное, а имя
     * файла показывается под полосой.
     */
    private final class DecodeProgress {
        private final String stage;
        private final File outDir;
        private final int total;
        private final Toolchain.Log log;
        private volatile boolean running = true;
        private Thread thread;

        DecodeProgress(String stage, File outDir, int total, Toolchain.Log log) {
            this.stage = stage; this.outDir = outDir; this.total = total; this.log = log;
        }

        void start() {
            log.progress(stage, 0, total, null);
            thread = new Thread(new Runnable() { public void run() {
                int lastDone = -1; String lastName = null;
                while (running) {
                    int[] n = {0}; String[] last = {null};
                    scan(outDir, n, last);
                    int done = Math.min(n[0], total);
                    if (done != lastDone || (last[0] != null && !last[0].equals(lastName))) {
                        log.progress(stage, done, total, last[0]);
                        lastDone = done; lastName = last[0];
                    }
                    try { Thread.sleep(250); } catch (InterruptedException e) { break; }
                }
            }});
            thread.setDaemon(true);
            thread.start();
        }

        void stop(boolean success) {
            running = false;
            if (thread != null) thread.interrupt();
            if (success) log.progress(stage, total, total, null);
        }

        /** Считает файлы под dir, запоминает имя самого свежего (по mtime). */
        private void scan(File dir, int[] n, String[] last) {
            File[] kids = dir.listFiles();
            if (kids == null) return;
            for (File k : kids) {
                if (k.isDirectory()) scan(k, n, last);
                else { n[0]++; last[0] = k.getName(); }
            }
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
        // Страховка для старых декомпиляций: ресурсы с '$' в имени.
        try {
            File res = new File(projectDir, "res");
            if (res.isDirectory()) DollarResFix.fix(res, log);
        } catch (Throwable t) {
            log.warn("res: правка '$'-имён перед сборкой пропущена: " + t);
        }

        // Заставляем apktool НЕ проглатывать stderr aapt2: в brut.util.OS есть
        // статический флаг verbose-ошибок — тогда BrutException при "exit code=1"
        // несёт реальный вывод aapt2 (настоящую причину падения link/compile).
        enableApktoolVerboseErrors();

        // ПРОАКТИВНАЯ ЧИСТКА РЕСУРСОВ ДО apktool build.
        // Главная причина падения apktool на этапе aapt2 link у декомпилированных
        // androidx/Material-приложений: стили/темы ссылаются на ресурсы и
        // атрибуты, которых нет в проекте (частичный набор ресурсов) или в
        // bundled-framework. compile при этом проходит, а link падает
        // "failed linking references" — apktool показывает лишь "exit code = 1".
        // Мы сами прогоняем aapt2 compile→link с захватом stderr, читаем ТОЧНЫЕ
        // строки "file:line: error: ... not found" и убираем именно их
        // (битые <item> по номеру строки, битые parent="@style/…"), повторяя,
        // пока link не станет чистым. Тогда apktool build проходит без ошибок.
        try {
            prevalidateAndCleanResources(aapt2, projectDir, frameworkDir);
        } catch (Throwable t) {
            log.warn("res: предварительная валидация ресурсов пропущена: " + t);
        }

        log.line("apktool: сборка ресурсов (aapt2) и dex из smali…");
        // Реальный прогресс сборки: считаем smali-классы и файлы ресурсов и
        // отслеживаем их появление в выходном APK фоновым наблюдателем — так
        // % и имена файлов отражают фактическое состояние работы, а не таймер.
        Method build = androlibC.getMethod("build", extFileC, File.class);
        int total = countBuildUnits(projectDir);
        BuildProgress bp = new BuildProgress("apktool: сборка", projectDir, outApk, total, log);
        bp.start();
        try {
            build.invoke(androlib, extFile, outApk);
            bp.stop(true);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            bp.stop(false);
            // Даже с verbose-флагом надёжнее самим воспроизвести полный конвейер
            // aapt2 (compile → link) с захватом stderr — так видно ТОЧНУЮ строку
            // ошибки link (её apktool показывает лишь как "exit code = 1").
            String base = c != null ? c.toString() : e.toString();

            // Реактивная страховка: ещё раз прогоняем итеративную чистку ссылок
            // (предпроверка перед build могла быть пропущена или apktool
            // модифицировал ресурсы) и пробуем собрать ещё раз.
            boolean cleaned = false;
            try {
                prevalidateAndCleanResources(aapt2, projectDir, frameworkDir);
                cleaned = true;
            } catch (Throwable ig) {}

            if (cleaned) {
                log.warn("apktool: ресурсы дочищены — повторная сборка…");
                BuildProgress bp2 = new BuildProgress("apktool: сборка (повтор)", projectDir, outApk, total, log);
                bp2.start();
                try {
                    build.invoke(androlib, extFile, outApk);
                    bp2.stop(true);
                    return;   // успех после авто-исправления
                } catch (java.lang.reflect.InvocationTargetException e2) {
                    bp2.stop(false);
                    Throwable c2 = e2.getCause();
                    String d2 = diagnoseAapt2Link(aapt2, projectDir, frameworkDir);
                    throw new Exception("apktool build: " + (c2 != null ? c2.toString() : e2.toString())
                            + (d2 != null ? "\n\n=== aapt2 link вывод (реальная причина) ===\n" + d2 : ""), c2);
                }
            }
            String detail = diagnoseAapt2Link(aapt2, projectDir, frameworkDir);
            throw new Exception("apktool build: " + base
                    + (detail != null ? "\n\n=== aapt2 link вывод (реальная причина) ===\n" + detail : ""), c);
        }
    }

    /** Число «единиц сборки»: файлы ресурсов + smali-классы (для % прогресса). */
    private int countBuildUnits(File projectDir) {
        int[] n = {0};
        File res = new File(projectDir, "res");
        if (res.isDirectory()) countFiles(res, n);
        File[] kids = projectDir.listFiles();
        if (kids != null) for (File k : kids) {
            if (k.isDirectory() && k.getName().startsWith("smali")) countFiles(k, n);
        }
        return n[0] > 0 ? n[0] : 1;
    }

    private static void countFiles(File dir, int[] n) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) countFiles(k, n);
            else n[0]++;
        }
    }

    /** Включает verbose-режим ошибок brut.util.OS (реальный stderr в исключении). */
    private void enableApktoolVerboseErrors() {
        try {
            Class<?> os = cl.loadClass("brut.util.OS");
            os.getMethod("setVerboseErrors", boolean.class).invoke(null, Boolean.TRUE);
        } catch (Throwable ignore) { /* другой билд apktool — не критично */ }
    }

    /**
     * Проактивно прогоняет aapt2 compile→link (тем же нашим bundled-framework,
     * что и apktool) и итеративно удаляет из res/values-*.xml ссылки на
     * несуществующие ресурсы/атрибуты, на которые ругается link, пока он не
     * станет чистым. Выполняется ДО apktool build, поэтому основная сборка
     * потом проходит без ошибки "failed linking references".
     *
     * Удаляем аккуратно:
     *  • "file.xml:LINE: error: ... not found" → удаляем эту строку (битый
     *    &lt;item&gt; стиля/темы) точно по номеру строки в нужном values-файле;
     *  • "resource style/NAME not found" без номера строки (обычно битый
     *    parent) → убираем атрибут parent="@style/NAME" из &lt;style&gt;.
     */
    private void prevalidateAndCleanResources(File aapt2, File projectDir, File frameworkDir) {
        File res = new File(projectDir, "res");
        if (!res.isDirectory()) return;
        File fw = new File(frameworkDir, "1.apk");
        File manifest = new File(projectDir, "AndroidManifest.xml");
        File buildDir = new File(projectDir, "build");
        buildDir.mkdirs();
        File resZip = new File(buildDir, "aapt2_precheck_res.zip");
        File outApk = new File(buildDir, "aapt2_precheck_out.apk");

        // aapt2 печатает не более ~20 ошибок за прогон, поэтому при большом
        // числе битых <public> в public.xml нужно много итераций. Берём с
        // запасом (public.xml декомпилированных apk бывает на тысячи строк).
        final int MAX_ITERS = 120;
        // Полное число файлов ресурсов — для % пофайлового прогресса компиляции.
        final int resTotal = countResFiles(res);

        // ── ШАГ 0 (по образцу apktool M) ────────────────────────────────────
        // Нейтрализуем активные <public> в values/public.xml, превращая их в
        // XML-комментарии. У apktool M режим public.xml по умолчанию (r=0) НЕ
        // пишет активных <public>, поэтому его проекты не дают ошибок
        // "no definition for declared symbol". Декомпилированные же проекты
        // часто содержат тысячи <public> на ресурсы, которых нет в res/ →
        // именно они роняют aapt2 link. Комментирование убирает эту причину
        // ЦЕЛИКОМ, не удаляя ни одной строки данных (id всё равно назначит aapt2).
        int commented = commentOutPublic(res);
        if (commented > 0)
            log.ok("res: public.xml — нейтрализовано <public> объявлений: " + commented);

        // Множества недостающих ссылок, для которых генерируем заглушки.
        // ВАЖНО: мы НЕ удаляем определения/атрибуты проекта (это ломало
        // зависимые стили каскадом). Вместо этого добавляем минимальные
        // ресурсы-заглушки в res/values/apkstudio_stubs.xml — так все ссылки
        // разрешаются, link проходит, а структура проекта сохраняется целиком.
        java.util.TreeSet<String> stubStyles = new java.util.TreeSet<String>();
        java.util.TreeSet<String> stubAttrs = new java.util.TreeSet<String>();
        java.util.TreeMap<String, java.util.TreeSet<String>> stubVals =
                new java.util.TreeMap<String, java.util.TreeSet<String>>();
        int totalStubs = 0;
        int totalPrivate = 0;
        log.line("res: предварительная проверка ссылок (aapt2 link)…");
        for (int iter = 0; iter < MAX_ITERS; iter++) {
            // 1) compile (только на первой итерации показываем ПОФАЙЛОВЫЙ прогресс:
            //    aapt2 -v печатает "…/file.ext: note: compiling …" — по этим
            //    строкам обновляем полосу и имя файла под ней в реальном времени)
            java.util.List<String> compile = new java.util.ArrayList<String>();
            compile.add(aapt2.getAbsolutePath());
            compile.add("compile"); compile.add("--dir"); compile.add(res.getAbsolutePath());
            compile.add("--legacy"); compile.add("-o"); compile.add(resZip.getAbsolutePath());
            final boolean showProgress = (iter == 0);
            if (showProgress) compile.add("-v");
            StringBuilder cOut = new StringBuilder();
            int cCode;
            try {
                final int[] done = {0};
                cCode = runCapture(compile, cOut, 0, false, showProgress ? new LineCB() {
                    public void onLine(String line) {
                        int idx = line.indexOf(": note: compiling");
                        if (idx < 0) idx = line.indexOf(": error");
                        if (idx > 0) {
                            String path = line.substring(0, idx);
                            String name = new File(path).getName();
                            done[0]++;
                            log.progress("aapt2: компиляция ресурсов", done[0], resTotal, name);
                        }
                    }
                } : null);
            }
            catch (Throwable t) { log.warn("res: precheck compile: " + t); return; }
            if (cCode != 0) {
                // compile-ошибка — это НЕ наш случай (link), отдаём apktool как есть
                log.line("res: precheck — compile вернул " + cCode + ", пропускаю чистку.");
                return;
            }
            // 2) link с -v (тогда ошибки содержат file:line)
            java.util.List<String> link = new java.util.ArrayList<String>();
            link.add(aapt2.getAbsolutePath());
            link.add("link"); link.add("-o"); link.add(outApk.getAbsolutePath());
            link.add("--allow-reserved-package-id");
            link.add("--package-id"); link.add("127");
            link.add("--no-resource-deduping");
            if (fw.exists()) { link.add("-I"); link.add(fw.getAbsolutePath()); }
            if (manifest.exists()) { link.add("--manifest"); link.add(manifest.getAbsolutePath()); }
            link.add("-v");
            link.add(resZip.getAbsolutePath());
            StringBuilder lOut = new StringBuilder();
            int lCode;
            try { lCode = runCapture(link, lOut, 0, false); }
            catch (Throwable t) { log.warn("res: precheck link: " + t); return; }
            String out = lOut.toString();
            if (lCode == 0) {
                if (totalStubs > 0 || totalPrivate > 0)
                    log.ok("res: ссылки восстановлены (заглушек: " + totalStubs
                            + ", приватных framework: " + totalPrivate
                            + ", итераций: " + iter + ") — link чистый.");
                else
                    log.ok("res: предварительная проверка link — ошибок нет.");
                return;
            }
            // 3a) приватные framework-ресурсы: "android:type/name is private" —
            //     ссылка на реально существующий, но приватный ресурс фреймворка.
            //     Лечится штатным aapt-синтаксисом принудительного доступа:
            //     @android:type/name → @*android:type/name (значение сохраняется).
            int privFixed = fixPrivateRefs(out);
            if (privFixed > 0) totalPrivate += privFixed;

            // 3b) недостающие ссылки → добавляем заглушки (НЕ удаляя ничего).
            boolean added = collectMissingRefs(out, stubStyles, stubAttrs, stubVals);
            int count = totalStubs;
            if (added) count = writeStubs(res, stubStyles, stubAttrs, stubVals);
            totalStubs = count;

            if (!added && privFixed == 0) {
                // ни новых недостающих ссылок, ни приватных не осталось, а link
                // всё ещё падает — причина иная (compile-уровень/повреждённый
                // ресурс). Отдаём apktool: его build покажет полную диагностику.
                log.warn("res: остались неустранимые ошибки link — показываю первые строки:");
                logFirstErrors(out, 8);
                return;
            }
            log.progress("res: восстановление ресурсов", iter + 1, MAX_ITERS,
                    "заглушек: " + totalStubs + ", приватных: " + totalPrivate);
        }
        log.warn("res: достигнут предел итераций восстановления (" + MAX_ITERS + ").");
    }

    /** Логирует первые n строк с "error:" из вывода aapt2. */
    private void logFirstErrors(String out, int n) {
        int shown = 0;
        for (String l : out.split("\n")) {
            if (l.contains("error:")) { log.err("  aapt2: " + l); if (++shown >= n) break; }
        }
    }

    /**
     * Нейтрализует активные объявления &lt;public .../&gt; в
     * res/values/public.xml, оборачивая каждое в XML-комментарий. Возвращает
     * число закомментированных объявлений.
     *
     * Зачем: у декомпилированных apk public.xml часто содержит тысячи записей
     * &lt;public&gt; на ресурсы, которых нет в проекте, что даёт при aapt2 link
     * ошибки "no definition for declared symbol". apktool M в дефолтном режиме
     * вообще не пишет активных &lt;public&gt;. Мы приводим проект к такому же
     * состоянию БЕЗ потери информации: id ресурсам всё равно назначит aapt2, а
     * закомментированные строки остаются для справки.
     */
    private int commentOutPublic(File res) {
        File pub = new File(new File(res, "values"), "public.xml");
        if (!pub.isFile()) return 0;
        try {
            String text = readAll(pub);
            // Уже нейтрализовано (наш прогон повторно) — не трогаем.
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<public\\b[^>]*/>").matcher(text);
            StringBuffer sb = new StringBuffer();
            int n = 0;
            while (m.find()) {
                String tag = m.group();
                // защита от двойного комментирования при повторном запуске
                m.appendReplacement(sb, java.util.regex.Matcher
                        .quoteReplacement("<!-- " + tag + " -->"));
                n++;
            }
            m.appendTail(sb);
            if (n > 0) writeAll(pub, sb.toString());
            return n;
        } catch (Throwable t) {
            log.warn("res: не удалось нейтрализовать public.xml: " + t);
            return 0;
        }
    }

    /**
     * Разбирает вывод aapt2 link и добавляет недостающие ссылки в множества для
     * генерации заглушек. Возвращает true, если появились НОВЫЕ ссылки.
     *
     * Обрабатываемые формы:
     *  • "resource &lt;type&gt;/&lt;name&gt; … not found"     (битый parent/ссылка);
     *  • "style attribute 'attr/&lt;name&gt; …' not found"    (недостающий attr).
     * Определения/атрибуты проекта НЕ удаляются — только добавляются заглушки.
     */
    private boolean collectMissingRefs(String aapt2Out,
                                       java.util.TreeSet<String> styles,
                                       java.util.TreeSet<String> attrs,
                                       java.util.TreeMap<String, java.util.TreeSet<String>> vals) {
        java.util.regex.Pattern pRes = java.util.regex.Pattern.compile(
                "resource (?:[\\w.]+:)?(\\w+)/([\\w.]+)");
        java.util.regex.Pattern pAttr = java.util.regex.Pattern.compile(
                "attribute '(?:attr/)?([\\w.]+)");
        boolean added = false;
        for (String line : aapt2Out.split("\n")) {
            if (line.indexOf("not found") < 0) continue;
            if (line.indexOf("style attribute") >= 0) {
                java.util.regex.Matcher ma = pAttr.matcher(line);
                if (ma.find()) {
                    String name = ma.group(1);
                    int c = name.lastIndexOf(':');
                    if (c >= 0) name = name.substring(c + 1);
                    if (attrs.add(name)) added = true;
                }
                continue;
            }
            java.util.regex.Matcher m = pRes.matcher(line);
            if (m.find()) {
                String type = m.group(1), name = m.group(2);
                if (type.equals("style")) {
                    if (styles.add(name)) added = true;
                } else if (type.equals("attr")) {
                    if (attrs.add(name)) added = true;
                } else {
                    java.util.TreeSet<String> set = vals.get(type);
                    if (set == null) { set = new java.util.TreeSet<String>(); vals.put(type, set); }
                    if (set.add(name)) added = true;
                }
            }
        }
        return added;
    }

    /**
     * Пишет res/values/apkstudio_stubs.xml с минимальными определениями для всех
     * накопленных недостающих ссылок. Возвращает общее число заглушек.
     */
    private int writeStubs(File res,
                           java.util.TreeSet<String> styles,
                           java.util.TreeSet<String> attrs,
                           java.util.TreeMap<String, java.util.TreeSet<String>> vals) {
        File dir = new File(res, "values");
        dir.mkdirs();
        File stub = new File(dir, "apkstudio_stubs.xml");
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n");
        int n = 0;
        // attr — с широким набором форматов, чтобы подходил под любое значение.
        for (String a : attrs) {
            sb.append("    <attr name=\"").append(a)
              .append("\" format=\"reference|color|dimension|string|integer|boolean|fraction|float\"/>\n");
            n++;
        }
        for (String s : styles) {
            sb.append("    <style name=\"").append(s).append("\"/>\n");
            n++;
        }
        for (java.util.Map.Entry<String, java.util.TreeSet<String>> e : vals.entrySet()) {
            String type = e.getKey();
            for (String name : e.getValue()) {
                if (type.equals("color"))
                    sb.append("    <color name=\"").append(name).append("\">#00000000</color>\n");
                else if (type.equals("dimen"))
                    sb.append("    <dimen name=\"").append(name).append("\">0dp</dimen>\n");
                else if (type.equals("string"))
                    sb.append("    <string name=\"").append(name).append("\"></string>\n");
                else if (type.equals("integer"))
                    sb.append("    <integer name=\"").append(name).append("\">0</integer>\n");
                else if (type.equals("bool"))
                    sb.append("    <bool name=\"").append(name).append("\">false</bool>\n");
                else if (type.equals("id"))
                    sb.append("    <item type=\"id\" name=\"").append(name).append("\"/>\n");
                else
                    sb.append("    <item type=\"").append(type).append("\" name=\"")
                      .append(name).append("\">@null</item>\n");
                n++;
            }
        }
        sb.append("</resources>\n");
        try { writeAll(stub, sb.toString()); }
        catch (Throwable t) { log.warn("res: не удалось записать заглушки: " + t); }
        return n;
    }

    /**
     * Обрабатывает ошибки вида
     *   "&lt;file&gt;.xml:&lt;line&gt;: error: resource android:&lt;type&gt;/&lt;name&gt; is private."
     * Это ссылка на реально существующий, но ПРИВАТНЫЙ ресурс фреймворка Android
     * (например @android:color/btn_default_material_dark). Заглушку тут делать
     * нельзя — ресурс есть во фреймворке. Правильное лечение — штатный
     * aapt-синтаксис принудительного доступа к приватным ресурсам: префикс '*'
     * после '@', т.е. @android:type/name → @*android:type/name. Значение
     * ресурса при этом сохраняется. Возвращает число исправленных ссылок.
     */
    private int fixPrivateRefs(String aapt2Out) {
        // "<путь>.xml:<line>: error: resource android:<type>/<name> is private."
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "((?:/[^\\s:]+)+\\.xml):(\\d+): error: resource android:(\\w+)/([\\w.]+) is private");
        // сгруппировать правки по файлу: строка → множество "type/name"
        java.util.HashMap<String, java.util.HashMap<Integer, java.util.LinkedHashSet<String>>> byFile =
                new java.util.HashMap<String, java.util.HashMap<Integer, java.util.LinkedHashSet<String>>>();
        java.util.regex.Matcher m = p.matcher(aapt2Out);
        while (m.find()) {
            String path = m.group(1);
            int ln = Integer.parseInt(m.group(2));
            String tn = m.group(3) + "/" + m.group(4);
            java.util.HashMap<Integer, java.util.LinkedHashSet<String>> lines = byFile.get(path);
            if (lines == null) { lines = new java.util.HashMap<Integer, java.util.LinkedHashSet<String>>(); byFile.put(path, lines); }
            java.util.LinkedHashSet<String> set = lines.get(ln);
            if (set == null) { set = new java.util.LinkedHashSet<String>(); lines.put(ln, set); }
            set.add(tn);
        }
        int fixed = 0;
        for (java.util.Map.Entry<String, java.util.HashMap<Integer, java.util.LinkedHashSet<String>>> fe : byFile.entrySet()) {
            File vf = new File(fe.getKey());
            if (!vf.isFile()) continue;
            try {
                String text = readAll(vf);
                String[] arr = text.split("\n", -1);
                boolean changed = false;
                for (java.util.Map.Entry<Integer, java.util.LinkedHashSet<String>> le : fe.getValue().entrySet()) {
                    int idx = le.getKey() - 1;
                    if (idx < 0 || idx >= arr.length) continue;
                    String lineStr = arr[idx];
                    for (String tn : le.getValue()) {
                        // @android:type/name (не уже '@*android') → @*android:type/name
                        String needle = "@android:" + tn;
                        String repl = "@*android:" + tn;
                        if (lineStr.indexOf(needle) >= 0) {
                            lineStr = lineStr.replace(needle, repl);
                            changed = true; fixed++;
                        }
                    }
                    arr[idx] = lineStr;
                }
                if (changed) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < arr.length; i++) { if (i > 0) sb.append('\n'); sb.append(arr[i]); }
                    writeAll(vf, sb.toString());
                }
            } catch (Throwable t) {
                log.warn("res: правка приватных ссылок в " + vf.getName() + ": " + t);
            }
        }
        return fixed;
    }

    private static String readAll(File f) throws Exception {
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
        try { byte[] b = new byte[(int) raf.length()]; raf.readFully(b);
              return new String(b, "UTF-8"); }
        finally { raf.close(); }
    }
    private static void writeAll(File f, String s) throws Exception {
        java.io.OutputStream os = new java.io.FileOutputStream(f);
        try { os.write(s.getBytes("UTF-8")); } finally { os.close(); }
    }

    /** Последний захваченный вывод aapt2 link — для подсказок в UI/логе. */
    private String lastAapt2Output;

    /**
     * Диагностика: сам воспроизводит полный конвейер apktool —
     *   aapt2 compile --dir res → resources.zip
     *   aapt2 link -o out.apk ... -I framework/1.apk --manifest ... resources.zip
     * с захватом stdout+stderr. Именно на стадии link apktool падает с
     * "exit code = 1", проглатывая причину. Возвращает вывод link (или compile,
     * если упал он). Результат также кэшируется в lastAapt2Output для авто-фикса.
     */
    private String diagnoseAapt2Link(File aapt2, File projectDir, File frameworkDir) {
        try {
            File res = new File(projectDir, "res");
            if (!res.isDirectory()) return null;
            File buildDir = new File(projectDir, "build");
            buildDir.mkdirs();
            File resZip = new File(buildDir, "aapt2_diag_resources.zip");

            // 1) compile всех ресурсов в zip
            java.util.List<String> compile = new java.util.ArrayList<String>();
            compile.add(aapt2.getAbsolutePath());
            compile.add("compile");
            compile.add("--dir"); compile.add(res.getAbsolutePath());
            compile.add("--legacy");
            compile.add("-o"); compile.add(resZip.getAbsolutePath());
            log.line("aapt2: диагностика — compile ресурсов…");
            StringBuilder cOut = new StringBuilder();
            int cCode = runCapture(compile, cOut, 200);
            if (cCode != 0) {
                cOut.append("aapt2 compile exit code = ").append(cCode).append('\n');
                log.err("aapt2: compile упал (exit=" + cCode + ") — причина в ресурсах");
                lastAapt2Output = cOut.toString();
                return lastAapt2Output;
            }

            // 2) link — та самая стадия, где apktool падает
            File fw = new File(frameworkDir, "1.apk");
            File manifest = new File(projectDir, "AndroidManifest.xml");
            File outApk = new File(buildDir, "aapt2_diag_out.apk");
            java.util.List<String> link = new java.util.ArrayList<String>();
            link.add(aapt2.getAbsolutePath());
            link.add("link");
            link.add("-o"); link.add(outApk.getAbsolutePath());
            link.add("--auto-add-overlay");
            link.add("--allow-reserved-package-id");
            link.add("--package-id"); link.add("127");
            if (fw.exists()) { link.add("-I"); link.add(fw.getAbsolutePath()); }
            if (manifest.exists()) { link.add("--manifest"); link.add(manifest.getAbsolutePath()); }
            link.add(resZip.getAbsolutePath());
            log.line("aapt2: диагностика — link (реальная стадия падения)…");
            StringBuilder lOut = new StringBuilder();
            int lCode = runCapture(link, lOut, 200);
            lOut.append("aapt2 link exit code = ").append(lCode).append('\n');
            log.line("aapt2: диагностика завершена (link exit=" + lCode + ")");
            lastAapt2Output = lOut.toString();
            return lastAapt2Output;
        } catch (Throwable t) {
            log.warn("aapt2: не удалось выполнить диагностический прогон: " + t);
            return null;
        }
    }

    /** Колбэк на каждую прочитанную строку вывода процесса (для пофайлового прогресса). */
    private interface LineCB { void onLine(String line); }

    /** Запускает команду, захватывает объединённый stdout+stderr, логирует ошибки. */
    private int runCapture(java.util.List<String> cmd, StringBuilder sink, int keepLines) throws Exception {
        return runCapture(cmd, sink, keepLines, true, null);
    }

    private int runCapture(java.util.List<String> cmd, StringBuilder sink, int keepLines, boolean logErrors) throws Exception {
        return runCapture(cmd, sink, keepLines, logErrors, null);
    }

    /**
     * Запускает команду, захватывает объединённый stdout+stderr.
     * @param keepLines сколько первых «неинтересных» строк тоже сохранить
     * @param logErrors логировать ли строки-ошибки в UI (false — тихий прогон,
     *                  напр. при итеративной чистке, чтобы не спамить журнал)
     * @param cb        колбэк на каждую строку (может быть null)
     */
    private int runCapture(java.util.List<String> cmd, StringBuilder sink, int keepLines,
                           boolean logErrors, LineCB cb) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
        String ln; int lines = 0;
        while ((ln = r.readLine()) != null) {
            if (cb != null) { try { cb.onLine(ln); } catch (Throwable ignore) {} }
            String low = ln.toLowerCase();
            boolean interesting = low.contains("error") || low.contains("fail")
                    || low.contains("invalid") || low.contains("not found")
                    || low.contains("duplicate") || low.contains("unexpected");
            if (interesting || lines < keepLines) sink.append(ln).append('\n');
            if (interesting && logErrors) log.err("  aapt2: " + ln);
            lines++;
        }
        return p.waitFor();
    }

    /** Число файлов ресурсов под res/ (для % компиляции). */
    private int countResFiles(File res) {
        int[] n = {0};
        countFiles(res, n);
        return n[0] > 0 ? n[0] : 1;
    }


    /**
     * Реальный прогресс сборки APK. apktool.build() — единый непрозрачный вызов,
     * но результат он пишет в выходной APK (zip). Фоновый поток периодически
     * читает список записей выходного APK и папку build/ и репортит фактический
     * прогресс (done/total) + имя последней добавленной записи — под полосой.
     */
    private final class BuildProgress {
        private final String stage;
        private final File projectDir, outApk;
        private final int total;
        private final Toolchain.Log log;
        private volatile boolean running = true;
        private Thread thread;

        BuildProgress(String stage, File projectDir, File outApk, int total, Toolchain.Log log) {
            this.stage = stage; this.projectDir = projectDir; this.outApk = outApk;
            this.total = total; this.log = log;
        }

        void start() {
            log.progress(stage, 0, total, null);
            thread = new Thread(new Runnable() { public void run() {
                String lastEntry = null; int lastDone = -1;
                while (running) {
                    int done = 0; String entry = null;
                    // Пока выходной APK ещё не появился — считаем прогресс по
                    // скомпилированным ресурсам в build/ (resources.zip растёт).
                    try {
                        if (outApk.exists()) {
                            String[] r = readZipProgress(outApk);
                            done = Integer.parseInt(r[0]);
                            entry = r[1];
                        } else {
                            File resZip = new File(projectDir, "build/resources.zip");
                            if (resZip.exists()) {
                                String[] r = readZipProgress(resZip);
                                done = Integer.parseInt(r[0]);
                                entry = r[1];
                            }
                        }
                    } catch (Throwable ignore) {}
                    if (done > total) done = total;
                    if (done != lastDone || (entry != null && !entry.equals(lastEntry))) {
                        log.progress(stage, done, total, entry);
                        lastDone = done; lastEntry = entry;
                    }
                    try { Thread.sleep(250); } catch (InterruptedException e) { break; }
                }
            }});
            thread.setDaemon(true);
            thread.start();
        }

        void stop(boolean success) {
            running = false;
            if (thread != null) thread.interrupt();
            if (success) log.progress(stage, total, total, null);
        }

        /**
         * Возвращает [числоЗаписей, имяПоследнейЗаписи] в zip. Используем
         * ZipFile — читает только центральный каталог (дёшево), не весь поток,
         * поэтому опрос большого APK каждые ~250 мс не грузит устройство.
         */
        private String[] readZipProgress(File zip) {
            java.util.zip.ZipFile zf = null;
            try {
                zf = new java.util.zip.ZipFile(zip);
                int n = 0; String last = null;
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry e = en.nextElement();
                    if (!e.isDirectory()) { n++; last = new File(e.getName()).getName(); }
                }
                return new String[]{ String.valueOf(n), last };
            } catch (Throwable t) {
                // Файл может быть в момент записи (частичный zip) — не страшно.
                return new String[]{ "0", null };
            } finally {
                if (zf != null) try { zf.close(); } catch (Throwable ig) {}
            }
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
