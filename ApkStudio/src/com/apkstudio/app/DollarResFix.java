package com.apkstudio.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Чинит ресурсы с недопустимым для aapt2 именем, начинающимся на '$'.
 *
 * Откуда берутся: inline-ресурсы из animated-vector / adaptive-icon, заданные
 * в оригинале через &lt;aapt:attr name="android:drawable"&gt;. aapt при сборке
 * генерирует для них анонимные вложенные ресурсы с именами вида
 * "$parent__0", "$parent__1"... При декомпиляции apktool 2.7.0 вытаскивает их
 * как отдельные файлы res/drawable/$parent__0.xml и в родителе ссылается
 * @drawable/$parent__0. При обратной сборке aapt2 compile падает:
 *   error: resource 'drawable/$parent__0' has invalid entry name '$parent__0'.
 *
 * Решение: '$' недопустим в entry name → переименовываем такие файлы, убирая
 * ведущий '$' (в имени файла), и правим ВСЕ ссылки на них в XML внутри res/
 * (родительские drawable, public.xml и т.п.): @type/$name → @type/name,
 * name="$name" → name="name". Имя без '$' валидно и уникально.
 */
public final class DollarResFix {

    private DollarResFix() {}

    /** Чинит res/ на месте. Возвращает число переименованных файлов. */
    public static int fix(File resDir, Toolchain.Log log) {
        if (resDir == null || !resDir.isDirectory()) return 0;

        // 1) Найти файлы, имя которых начинается на '$'.
        List<File> dollarFiles = new ArrayList<File>();
        collectDollar(resDir, dollarFiles);
        if (dollarFiles.isEmpty()) return 0;

        log.line("res: обнаружено ресурсов с '$' в имени: " + dollarFiles.size()
                + " — переименовываю (aapt2 не принимает '$').");

        // 2) Переименовать файлы, убрав ведущий '$' из имени (до расширения).
        int renamed = 0, i = 0;
        for (File f : dollarFiles) {
            i++;
            String name = f.getName();               // напр. $avd__0.xml
            String newName = stripLeadingDollar(name); // avd__0.xml
            if (newName.equals(name)) continue;
            File dst = new File(f.getParentFile(), newName);
            try {
                if (dst.exists()) dst.delete();
                if (f.renameTo(dst)) {
                    renamed++;
                    log.progress("res: правка $-имён", i, dollarFiles.size(), newName);
                } else {
                    log.warn("res: не удалось переименовать " + name);
                }
            } catch (Throwable t) {
                log.warn("res: ошибка переименования " + name + ": " + t);
            }
        }
        log.progress("res: правка $-имён", dollarFiles.size(), dollarFiles.size(), null);

        // 3) Поправить ссылки во ВСЕХ .xml внутри res/.
        List<File> xmls = new ArrayList<File>();
        collectXml(resDir, xmls);
        int patched = 0;
        for (File x : xmls) {
            try {
                if (patchReferences(x)) patched++;
            } catch (Throwable t) {
                log.warn("res: не удалось обновить ссылки в " + x.getName() + ": " + t);
            }
        }
        log.ok("res: переименовано файлов: " + renamed + ", обновлено XML со ссылками: " + patched);
        return renamed;
    }

    private static void collectDollar(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) collectDollar(k, out);
            else if (k.getName().startsWith("$")) out.add(k);
        }
    }

    private static void collectXml(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) collectXml(k, out);
            else if (k.getName().endsWith(".xml")) out.add(k);
        }
    }

    private static String stripLeadingDollar(String name) {
        int i = 0;
        while (i < name.length() && name.charAt(i) == '$') i++;
        return name.substring(i);
    }

    // Ссылка на ресурс с '$': @[pkg:]type/$name  ИЛИ  атрибут name="$name"
    // '$' может встречаться в цепочке (@drawable/$a), но всегда как префикс
    // токена имени. Убираем каждый '$', который стоит после '/' или после '"'
    // и перед буквой/подчёркиванием (начало валидного имени ресурса).
    private static final Pattern REF_SLASH = Pattern.compile("/\\$(?=[A-Za-z_])");
    private static final Pattern REF_NAME  = Pattern.compile("(name=\")\\$(?=[A-Za-z_])");

    private static boolean patchReferences(File xml) throws Exception {
        String text = readText(xml);
        if (text.indexOf('$') < 0) return false;   // быстрый выход
        String orig = text;
        text = REF_SLASH.matcher(text).replaceAll("/");
        Matcher m = REF_NAME.matcher(text);
        text = m.replaceAll("$1");
        if (text.equals(orig)) return false;
        writeText(xml, text);
        return true;
    }

    private static String readText(File f) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            long n = raf.length();
            byte[] b = new byte[(int) n];
            raf.readFully(b);
            return new String(b, "UTF-8");
        } finally { raf.close(); }
    }

    private static void writeText(File f, String s) throws Exception {
        OutputStream os = new FileOutputStream(f);
        try { os.write(s.getBytes("UTF-8")); os.flush(); }
        finally { os.close(); }
    }
}
