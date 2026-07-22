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

        // КРИТИЧНО для корректной обратной сборки (round-trip): по умолчанию
        // apktool, встретив ресурс/XML, который не смог декодировать, МОЛЧА
        // выбрасывает его или заменяет "FALSE value" — тогда при обратной сборке
        // этот ресурс отсутствует: APK становится меньше, а приложение вылетает
        // (ссылка на пропавший ресурс). setKeepBrokenResources(true) заставляет
        // apktool СОХРАНЯТЬ такие ресурсы (как это делает Apktool-M опцией
        // keep_broken_res), чтобы они вернулись в APK при сборке.
        try {
            decoderC.getMethod("setKeepBrokenResources", boolean.class).invoke(decoder, true);
            log.line("apktool: keepBrokenResources = true (не терять ресурсы при разборе)");
        } catch (Throwable t) {
            log.warn("apktool: setKeepBrokenResources недоступен: " + t);
        }
        // Декодирование исходников (smali) и ресурсов (в XML) идёт по дефолту
        // apktool — DECODE_SOURCES_SMALI + DECODE_RESOURCES_FULL, менять их не
        // нужно. Главное — keepBrokenResources выше, чтобы ничего не терялось.

        log.line("apktool: декодирование ресурсов и smali…");
        // Перехватываем вывод логгера apktool — теперь в журнале видно
        // предупреждения вида "Could not decode file..." / "Skipping...",
        // т.е. пользователь СРАЗУ узнает, если apktool что-то не смог разобрать
        // (это и есть причина «APK меньше + вылет» при последующей сборке).
        Object logHook = attachApktoolLogger();
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
            detachApktoolLogger(logHook);
        }
        // Проверка целостности разбора: сверяем число файлов в проекте с числом
        // записей исходного APK. Заметная недостача → предупреждаем (round-trip
        // будет неполным, APK может получиться меньше).
        try { warnIfDecodeIncomplete(apk, outDir); } catch (Throwable ignore) {}
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
     * Предупреждает, если декомпиляция похоже НЕПОЛНАЯ: сравнивает число
     * не-dex/не-подписных записей исходного APK с числом файлов, реально
     * появившихся в проекте (res+assets+lib+unknown+manifest). Значительная
     * недостача означает, что apktool не смог разобрать часть содержимого —
     * тогда обратная сборка даст APK меньшего размера, который может вылетать.
     */
    private void warnIfDecodeIncomplete(File apk, File outDir) {
        int apkFiles = 0;
        java.util.zip.ZipInputStream zis = null;
        try {
            zis = new java.util.zip.ZipInputStream(new java.io.FileInputStream(apk));
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (!e.isDirectory()) {
                    String n = e.getName();
                    // dex превращается в smali (другое число файлов), META-INF/
                    // подпись не переносится в проект — их не считаем.
                    if (n.endsWith(".dex")) { zis.closeEntry(); continue; }
                    if (n.startsWith("META-INF/") &&
                        (n.endsWith(".RSA") || n.endsWith(".SF") || n.endsWith(".MF") ||
                         n.endsWith(".DSA") || n.endsWith(".EC"))) { zis.closeEntry(); continue; }
                    apkFiles++;
                }
                zis.closeEntry();
            }
        } catch (Throwable ignore) {
        } finally {
            if (zis != null) try { zis.close(); } catch (Throwable ig) {}
        }
        // Считаем перенесённые файлы: всё, кроме smali*/ (код) и служебных.
        int[] proj = {0};
        File[] kids = outDir.listFiles();
        if (kids != null) for (File k : kids) {
            String nm = k.getName();
            if (nm.equals("build") || nm.equals("dist") || nm.startsWith("smali")
                    || nm.equals("apktool.yml")) continue;
            if (k.isDirectory()) countFiles(k, proj); else proj[0]++;
        }
        if (apkFiles > 20 && proj[0] < apkFiles * 0.9) {
            log.warn("ВНИМАНИЕ: перенесено файлов проекта ~" + proj[0] + " из ~" + apkFiles
                    + " (ресурсы/assets/lib). Часть содержимого apktool разобрать не смог —");
            log.warn("обратная сборка может дать APK меньшего размера. Смотрите выше строки");
            log.warn("apktool «Could not decode…» / «Skipping…».");
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

        // Уже показанные в логе файлы (по относительному пути) — чтобы каждый
        // файл выводился в ленту РОВНО ОДИН РАЗ, а не терялся между опросами.
        private final java.util.LinkedHashSet<String> seen =
                new java.util.LinkedHashSet<String>();

        void start() {
            log.progress(stage, 0, total, null);
            thread = new Thread(new Runnable() { public void run() {
                while (running) {
                    poll();
                    try { Thread.sleep(60); } catch (InterruptedException e) { break; }
                }
            }});
            thread.setDaemon(true);
            thread.start();
        }

        /**
         * Один опрос: находит ВСЕ файлы, появившиеся в проекте с прошлого раза,
         * и печатает КАЖДЫЙ из них в лог + двигает полосу. Так лента показывает
         * каждый распакованный smali/xml/png/… а не один файл раз в четверть сек.
         */
        private void poll() {
            java.util.ArrayList<String> files = new java.util.ArrayList<String>();
            list(outDir, "", files);
            for (String rel : files) {
                if (seen.add(rel)) {
                    int done = Math.min(seen.size(), total);
                    // Имя с коротким путём (напр. smali/.../X.smali или res/.../y.png)
                    log.progress(stage, done, total, rel);
                }
            }
        }

        void stop(boolean success) {
            running = false;
            if (thread != null) thread.interrupt();
            poll();   // добираем всё, что появилось в самом конце
            if (success) log.progress(stage, total, total, null);
        }

        /** Рекурсивно собирает относительные пути всех файлов под dir. */
        private void list(File dir, String prefix, java.util.ArrayList<String> out) {
            File[] kids = dir.listFiles();
            if (kids == null) return;
            for (File k : kids) {
                String rel = prefix.isEmpty() ? k.getName() : prefix + "/" + k.getName();
                if (k.isDirectory()) list(k, rel, out);
                else out.add(rel);
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
        // Страховка: фиктивные <drawable/color>false</> заглушки apktool
        // (см. DummyResFix) — на случай использования нашего aapt2 напрямую.
        try {
            File res = new File(projectDir, "res");
            if (res.isDirectory()) DummyResFix.fix(res, log);
        } catch (Throwable t) {
            log.warn("res: правка заглушек drawable/color перед сборкой пропущена: " + t);
        }
        // Разовая ПОЧИНКА испорченного public.xml. Прошлые версии ApkStudio при
        // повторной попытке дважды комментировали <public> → в файле остались
        // вложенные "<!-- <!-- … --> -->", из-за которых aapt2 падал с
        // "public.xml:0: not well-formed". Разворачиваем их обратно, чтобы файл
        // снова был валидным XML (сборка при этом становится возможной).
        try {
            File res = new File(projectDir, "res");
            if (res.isDirectory()) repairPublicXml(res);
        } catch (Throwable t) {
            log.warn("res: починка public.xml перед сборкой пропущена: " + t);
        }

        // Заставляем apktool НЕ проглатывать stderr aapt2: в brut.util.OS есть
        // статический флаг verbose-ошибок — тогда BrutException при "exit code=1"
        // несёт реальный вывод aapt2 (настоящую причину падения link/compile).
        enableApktoolVerboseErrors();

        Method build = androlibC.getMethod("build", extFileC, File.class);
        int total = countBuildUnits(projectDir);

        // ── БЫСТРЫЙ ПУТЬ (как в Apktool-M) ─────────────────────────────────
        // Сразу пробуем apktool build БЕЗ предварительной чистки ресурсов.
        // Раньше мы ВСЕГДА гоняли полный aapt2 compile+link всех ресурсов ДО
        // сборки (precheck), а apktool потом компилировал их ЕЩЁ РАЗ — т.е.
        // ресурсы компилировались 2–3 раза, отсюда 20+ минут вместо 5–8.
        // Большинство проектов собирается сразу; чистку включаем только если
        // прямая сборка упала.
        log.line("apktool: сборка ресурсов (aapt2) и dex из smali…");
        // Перехватываем ЖИВОЙ вывод логгера apktool (Building resources... /
        // Copying ... / Building apk file...) и льём его в наш журнал — так
        // пользователь видит подробную ленту этапов и файлов, ровно как в
        // Apktool-M (тот печатает те же строки из brut.androlib.Androlib).
        Object logHook = attachApktoolLogger();
        BuildProgress bp = new BuildProgress("apktool: сборка", projectDir, outApk, tmpDir, total, log);
        bp.start();
        try {
            build.invoke(androlib, extFile, outApk);
            bp.stop(true);
            logBuildContents(outApk);
            return;                                   // успех без чистки — быстро
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            bp.stop(false);
            String base = c != null ? c.toString() : e.toString();
            log.warn("apktool: прямая сборка не удалась — запускаю восстановление ресурсов…");

            // ── ПУТЬ ВОССТАНОВЛЕНИЯ (только при ошибке) ────────────────────
            // Теперь (и только теперь) прогоняем итеративную чистку ссылок:
            // aapt2 compile→link с захватом stderr, добавляем заглушки на
            // недостающие ресурсы/атрибуты, чиним приватные framework-ссылки.
            boolean cleaned = false;
            try {
                prevalidateAndCleanResources(aapt2, projectDir, frameworkDir);
                cleaned = true;
            } catch (Throwable ig) {
                log.warn("res: восстановление ресурсов пропущено: " + ig);
            }

            if (cleaned) {
                log.warn("apktool: ресурсы дочищены — повторная сборка…");
                BuildProgress bp2 = new BuildProgress("apktool: сборка (повтор)", projectDir, outApk, tmpDir, total, log);
                bp2.start();
                try {
                    build.invoke(androlib, extFile, outApk);
                    bp2.stop(true);
                    logBuildContents(outApk);
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
        } finally {
            detachApktoolLogger(logHook);
        }
    }

    /**
     * Подключает Handler к логгерам apktool (корневой + brut.androlib*),
     * перенаправляя КАЖДУЮ строку («Building resources...», «Copying assets and
     * libs...», «Building apk file...» и т.п.) в наш журнал. Возвращает объект
     * для последующего снятия (detach). При любой ошибке возвращает null —
     * сборка от этого не зависит.
     */
    private Object attachApktoolLogger() {
        try {
            final java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
            final java.util.logging.Logger brut = java.util.logging.Logger.getLogger("brut.androlib.Androlib");
            java.util.logging.Handler h = new java.util.logging.Handler() {
                public void publish(java.util.logging.LogRecord r) {
                    if (r == null) return;
                    String msg = r.getMessage();
                    if (msg == null || msg.length() == 0) return;
                    int lvl = r.getLevel().intValue();
                    // Ленту сборки показываем как обычные строки; предупреждения
                    // и ошибки apktool — соответствующим цветом.
                    if (lvl >= java.util.logging.Level.SEVERE.intValue()) log.err("apktool: " + msg);
                    else if (lvl >= java.util.logging.Level.WARNING.intValue()) log.warn("apktool: " + msg);
                    else log.line("apktool: " + msg);
                }
                public void flush() {}
                public void close() {}
            };
            h.setLevel(java.util.logging.Level.ALL);
            root.addHandler(h);
            root.setLevel(java.util.logging.Level.ALL);
            brut.setLevel(java.util.logging.Level.ALL);
            brut.setUseParentHandlers(true);
            return h;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Снимает ранее подключённый Handler логгера apktool. */
    private void detachApktoolLogger(Object handler) {
        if (!(handler instanceof java.util.logging.Handler)) return;
        try {
            java.util.logging.Logger.getLogger("").removeHandler((java.util.logging.Handler) handler);
        } catch (Throwable ignore) {}
    }

    /**
     * Подробная лента содержимого собранного APK: перечисляет добавленные
     * classes*.dex, resources.arsc, AndroidManifest.xml и итог по ресурсам/
     * assets/lib. Пишется в журнал ПОСЛЕ успешной сборки, чтобы пользователь
     * видел, что именно вошло в APK (что просил: «какие файлы добавляются в
     * classes.dex, в resources.arsc»).
     */
    private void logBuildContents(File apk) {
        if (apk == null || !apk.exists()) return;
        java.util.zip.ZipFile zf = null;
        try {
            zf = new java.util.zip.ZipFile(apk);
            int res = 0, assets = 0, lib = 0, other = 0;
            java.util.List<String> dex = new java.util.ArrayList<String>();
            boolean arsc = false, manifest = false;
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName();
                if (n.matches("classes\\d*\\.dex")) dex.add(n + " (" + e.getSize() + " б)");
                else if (n.equals("resources.arsc")) { arsc = true; }
                else if (n.equals("AndroidManifest.xml")) manifest = true;
                else if (n.startsWith("res/")) res++;
                else if (n.startsWith("assets/")) assets++;
                else if (n.startsWith("lib/")) lib++;
                else other++;
            }
            log.ok("APK собран — содержимое:");
            for (String d : dex) log.line("    • dex: " + d);
            if (arsc)     log.line("    • resources.arsc (таблица ресурсов)");
            if (manifest) log.line("    • AndroidManifest.xml");
            log.line("    • ресурсов res/: " + res + ", assets: " + assets
                    + ", нативных lib: " + lib + ", прочих: " + other);
        } catch (Throwable t) {
            log.warn("Не удалось перечислить содержимое APK: " + t);
        } finally {
            if (zf != null) try { zf.close(); } catch (Throwable ig) {}
        }
    }

    /**
     * Оценка числа «единиц сборки» для % прогресса. Прогресс мы считаем по
     * файлам, реально появляющимся в build/ во время apktool build, поэтому
     * total должен соответствовать тому, что там окажется:
     *   • ресурсы: каждый файл из res/ → скомпилированный файл в build/apk/res
     *     (примерно 1:1) + записи в build/apk/ (manifest, resources.arsc и пр.);
     *   • код: множество .smali сворачивается apktool'ом в НЕСКОЛЬКО classes*.dex
     *     — поэтому берём не число smali-файлов, а по одному dex на каждую
     *     smali*-папку (обычно 1–3), плюс небольшой запас на прочие файлы.
     * Так полоса растёт равномерно и доходит до реального финала (а не
     * «застревает» и не «перепрыгивает»).
     */
    private int countBuildUnits(File projectDir) {
        int[] res = {0};
        File resDir = new File(projectDir, "res");
        if (resDir.isDirectory()) countFiles(resDir, res);
        int smaliDirs = 0;
        File[] kids = projectDir.listFiles();
        if (kids != null) for (File k : kids) {
            if (k.isDirectory() && k.getName().startsWith("smali")) smaliDirs++;
        }
        // res-файлы + по dex на smali-папку + запас (assets/lib/unknown/manifest).
        int total = res[0] + smaliDirs + 8;
        return total > 0 ? total : 1;
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

        // ── ВАЖНО: public.xml НЕ ТРОГАЕМ ────────────────────────────────────
        // Apktool-M СОХРАНЯЕТ активные <public> — они фиксируют точные id
        // ресурсов (0x7fXXXXXX). Раньше мы их комментировали ("нейтрализация"),
        // из-за чего aapt2 переназначал id, они переставали совпадать с
        // числами, зашитыми в smali → приложение вылетало при запуске, а часть
        // ресурсов пропадала (APK становился меньше). Теперь public.xml остаётся
        // как есть; недостающие ссылки закрываем заглушками (ниже), НЕ ломая id.

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
     * ПОЛНОЕ восстановление public.xml к рабочему (активному) виду — как у
     * Apktool-M. Прошлые версии ApkStudio комментировали <public>-объявления
     * (а при повторе — дважды, создавая вложенные комментарии). Это КРИТИЧЕСКАЯ
     * ошибка: public.xml фиксирует ТОЧНЫЕ id ресурсов (0x7fXXXXXX). Если его
     * нейтрализовать, aapt2 назначает ресурсам ДРУГИЕ id, а в smali-коде id
     * зашиты числами → при запуске Resources.NotFoundException и вылет; заодно
     * пропадают ресурсы → APK меньше. Возвращаем ВСЕ <public> в активное
     * состояние и удаляем добавленные заглушки, чтобы id совпадали с оригиналом.
     *
     * Делаем в два шага:
     *   1) снимаем возможную вложенность "<!-- <!-- <public/> --> -->";
     *   2) раскомментируем одиночные "<!-- <public/> -->" → "<public/>".
     */
    private void repairPublicXml(File res) {
        File values = new File(res, "values");
        File pub = new File(values, "public.xml");
        // Удаляем ранее добавленные заглушки — они искажают набор ресурсов.
        File stub = new File(values, "apkstudio_stubs.xml");
        if (stub.isFile()) {
            if (stub.delete()) log.ok("res: удалён apkstudio_stubs.xml (мешал совпадению ресурсов).");
        }
        if (!pub.isFile()) return;
        try {
            String orig = readAll(pub);
            String text = orig;
            // 1) снять любую вложенность комментариев вокруг <public>
            String prev; int guard = 0;
            do {
                prev = text;
                text = text.replaceAll(
                        "<!--\\s*(<!--\\s*<public\\b[^>]*/>\\s*-->)\\s*-->", "$1");
                guard++;
            } while (!text.equals(prev) && guard < 50);
            // 2) раскомментировать одиночные <!-- <public .../> --> → <public .../>
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<!--\\s*(<public\\b[^>]*/>)\\s*-->").matcher(text);
            StringBuffer sb = new StringBuffer();
            int restored = 0;
            while (m.find()) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(1)));
                restored++;
            }
            m.appendTail(sb);
            text = sb.toString();
            if (!text.equals(orig)) {
                writeAll(pub, text);
                log.ok("res: public.xml восстановлен — активных <public>: " + restored
                        + " (id ресурсов совпадут с оригиналом, без вылета).");
            }
        } catch (Throwable t) {
            log.warn("res: не удалось восстановить public.xml: " + t);
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
     * но во время работы он ПОСТЕПЕННО раскладывает промежуточные результаты в
     * подпапку build/ проекта (build/apk/ — скомпилированные ресурсы, копии
     * assets/lib/unknown, а также classes*.dex из smali) и в конце пакует всё
     * это в выходной APK. Фоновый поток каждые ~250 мс рекурсивно сканирует эти
     * места, считает реально появившиеся файлы и репортит done/total + имя
     * самого свежего файла (по mtime) — так полоса и % РАСТУТ по ходу работы, а
     * под полосой видно каждый обрабатываемый файл (как в стадии декодирования).
     *
     * Прежняя реализация читала только записи выходного APK / build/resources.zip;
     * но APK пишется целиком лишь в самом конце, а resources.zip apktool 2.7 в
     * aapt2-режиме здесь не создаёт — поэтому полоса «застревала» на 0% и имя
     * файла не менялось. Скан дерева build/ устраняет это.
     */
    private final class BuildProgress {
        private final String stage;
        private final File projectDir, outApk, tmpDir;
        private final int total;
        private final Toolchain.Log log;
        private volatile boolean running = true;
        private Thread thread;
        private long startedAt;
        private boolean srcShown = false;   // исходники уже показаны как fallback?

        BuildProgress(String stage, File projectDir, File outApk, File tmpDir, int total, Toolchain.Log log) {
            this.stage = stage; this.projectDir = projectDir; this.outApk = outApk;
            this.tmpDir = tmpDir; this.total = total; this.log = log;
        }

        // Каждый файл/запись показываем в ленте РОВНО ОДИН РАЗ.
        private final java.util.LinkedHashSet<String> seen =
                new java.util.LinkedHashSet<String>();

        void start() {
            log.progress(stage, 0, total, null);
            startedAt = System.currentTimeMillis();
            thread = new Thread(new Runnable() { public void run() {
                while (running) {
                    poll();
                    try { Thread.sleep(60); } catch (InterruptedException e) { break; }
                }
            }});
            thread.setDaemon(true);
            thread.start();
        }

        /**
         * Один опрос ленты сборки. apktool 2.7 в aapt2-режиме основную работу
         * ведёт во ВРЕМЕННОЙ папке (java.io.tmpdir) и в projectDir/build/apk,
         * причём часто заполняет их «пачкой» в самом конце. Поэтому смотрим ВСЕ
         * места, а если за первую секунду там пусто — показываем ИСХОДНЫЕ файлы
         * проекта (smali/res/assets/lib), которые как раз и упаковываются: так
         * лента при сборке не бывает пустой. В конце добираем записи APK.
         */
        private void poll() {
            java.util.ArrayList<String> files = new java.util.ArrayList<String>();
            File buildDir = new File(projectDir, "build");
            if (buildDir.isDirectory()) list(buildDir, "build", files);
            if (tmpDir != null && tmpDir.isDirectory()) list(tmpDir, "tmp", files);

            // Fallback: если реальные промежуточные файлы ещё не появились
            // (>0.8с прошло, seen пуст) — показываем исходники проекта как ленту
            // «упаковывается …». Один раз, чтобы не дублировать.
            if (!srcShown && seen.isEmpty()
                    && System.currentTimeMillis() - startedAt > 400) {
                srcShown = true;
                addProjectSources(files);
            }

            // Записи финального APK (в самом конце) — с префиксом apk/.
            if (outApk.exists()) {
                try {
                    java.util.zip.ZipFile zf = new java.util.zip.ZipFile(outApk);
                    try {
                        java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                        while (en.hasMoreElements()) {
                            java.util.zip.ZipEntry e = en.nextElement();
                            if (!e.isDirectory()) files.add("apk/" + e.getName());
                        }
                    } finally { zf.close(); }
                } catch (Throwable ignore) {}
            }
            for (String rel : files) {
                if (seen.add(rel)) {
                    int done = Math.min(seen.size(), total);
                    log.progress(stage, done, total, labelForBuildFile(rel));
                }
            }
        }

        /**
         * Человекочитаемая подпись для файла, участвующего в сборке APK, — чтобы
         * в логе/окне прогресса было понятно, ЧТО именно обрабатывается/куда
         * добавляется (запись финального APK: код classes.dex, таблица
         * resources.arsc, манифест, ресурс, нативная библиотека, ассет).
         */
        private String labelForBuildFile(String rel) {
            if (rel == null) return "";
            String r = rel;
            if (r.startsWith("apk/")) r = r.substring(4);   // запись финального APK
            else return rel;                                // промежуточный build/…, tmp/…, smali/…
            if (r.equals("resources.arsc")) return "APK ← resources.arsc (таблица ресурсов)";
            if (r.equals("AndroidManifest.xml")) return "APK ← AndroidManifest.xml (манифест)";
            if (r.matches("classes\\d*\\.dex")) return "APK ← код " + r;
            if (r.startsWith("res/")) return "APK ← ресурс " + r;
            if (r.startsWith("lib/")) return "APK ← нативная lib " + r;
            if (r.startsWith("assets/")) return "APK ← ассет " + r;
            return "APK ← " + r;
        }

        /** Исходные файлы проекта, которые apktool упаковывает в APK. */
        private void addProjectSources(java.util.ArrayList<String> out) {
            String[] dirs = { "smali", "smali_classes2", "smali_classes3",
                    "smali_classes4", "smali_classes5", "res", "assets", "lib",
                    "kotlin", "unknown" };
            for (String d : dirs) {
                File f = new File(projectDir, d);
                if (f.isDirectory()) list(f, d, out);
            }
        }

        void stop(boolean success) {
            running = false;
            if (thread != null) thread.interrupt();
            poll();   // добираем всё, что появилось в самом конце (в т.ч. APK)
            if (success) log.progress(stage, total, total, null);
        }

        /** Рекурсивно собирает относительные пути всех файлов под dir. */
        private void list(File dir, String prefix, java.util.ArrayList<String> out) {
            File[] kids = dir.listFiles();
            if (kids == null) return;
            for (File k : kids) {
                String rel = prefix.isEmpty() ? k.getName() : prefix + "/" + k.getName();
                if (k.isDirectory()) list(k, rel, out);
                else out.add(rel);
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
