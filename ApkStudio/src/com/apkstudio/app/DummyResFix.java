package com.apkstudio.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Чинит «заглушки» и потерянные при декомпиляции значения ресурсов, которые
 * apktool оставляет в проекте, но которые НЕ принимает чистый aapt2 (путь
 * java→APK в ProjectBuilder). Apktool собирает такие проекты своим aapt, а
 * прямой aapt2 compile/link — строже. Две правки:
 *
 * 1) DUMMY-DRAWABLE. Если ресурс объявлен в resources.arsc, но его данных нет в
 *    APK (обычно ресурсы Google Play Services / фреймворка), apktool пишет
 *    фиктивную запись:
 *        &lt;drawable name="common_ic_googleplayservices"&gt;false&lt;/drawable&gt;
 *        &lt;drawable name="APKTOOL_DUMMY_13"&gt;false&lt;/drawable&gt;
 *    aapt2 compile падает: "res/values/drawables.xml:3: error: invalid drawable"
 *    ("false" — недопустимое значение для &lt;drawable&gt;/&lt;color&gt;).
 *    Решение: "false" внутри &lt;drawable&gt;/&lt;color&gt; заменяем на прозрачный
 *    цвет "#00000000" (валидный ColorDrawable/цвет). Тип, имя и id ресурса
 *    (public.xml) сохраняются, ссылки остаются разрешимыми.
 *    ВАЖНО: трогаем ТОЛЬКО &lt;drawable&gt;/&lt;color&gt; со значением "false".
 *    Легитимные булевы (&lt;item ...&gt;false&lt;/item&gt;, &lt;bool&gt;false
 *    &lt;/bool&gt;) НЕ затрагиваются.
 *
 * 2) RAW-ENUM. apktool иногда оставляет числовое значение enum/flag-атрибута
 *    вместо символьного имени:
 *        &lt;item name="buyButtonText"&gt;5&lt;/item&gt;
 *    aapt2 link падает: "expected enum but got (raw string) 5". Собираем из всех
 *    attrs.xml карту enum-атрибутов (attr → {число → имя}) и заменяем голое
 *    число на соответствующее имя enum (5 → buy_with, -1 → match_parent …). Так
 *    link проходит, а значение атрибута остаётся тем же (то же число в enum).
 */
public final class DummyResFix {

    private DummyResFix() {}

    // <drawable ...>false</drawable>  или  <color ...>false</color>
    // Группа 1 — открывающий тег до '>', группа 2 — закрывающий тег.
    private static final Pattern DUMMY = Pattern.compile(
            "(<(?:drawable|color)\\b[^>]*>)\\s*false\\s*(</(?:drawable|color)>)");

    // <attr name="X" ...> … <enum name="Y" value="Z" /> … </attr>
    private static final Pattern ATTR_BLOCK = Pattern.compile(
            "<attr\\b[^>]*\\bname=\"([^\"]+)\"[^>]*>(.*?)</attr>", Pattern.DOTALL);
    private static final Pattern ENUM_ITEM = Pattern.compile(
            "<enum\\b[^>]*\\bname=\"([^\"]+)\"[^>]*\\bvalue=\"([^\"]+)\"[^>]*/?>");
    // <item name="attr">RAW</item> — RAW без пробелов/тегов внутри
    private static final Pattern ITEM_VAL = Pattern.compile(
            "(<item\\b[^>]*\\bname=\"([^\"]+)\"[^>]*>)\\s*([^<\\s]+)\\s*(</item>)");

    /** Чинит res/ на месте. Возвращает число исправленных значений. */
    public static int fix(File resDir, Toolchain.Log log) {
        if (resDir == null || !resDir.isDirectory()) return 0;

        // Заглушки/значения живут в res/values*/*.xml — там и ищем.
        List<File> valuesXml = new ArrayList<File>();
        collectValuesXml(resDir, valuesXml);
        if (valuesXml.isEmpty()) return 0;

        // 1) dummy drawable/color false → #00000000
        int totalFixed = 0, patchedFiles = 0;
        for (File x : valuesXml) {
            try {
                int n = patch(x);
                if (n > 0) { totalFixed += n; patchedFiles++; }
            } catch (Throwable t) {
                log.warn("res: не удалось починить заглушки в " + x.getName() + ": " + t);
            }
        }
        if (totalFixed > 0) {
            log.ok("res: исправлено заглушек drawable/color 'false' → '#00000000': "
                    + totalFixed + " (в " + patchedFiles + " файле(ах)) — иначе "
                    + "aapt2 падал с 'invalid drawable'.");
        }

        // 2) raw-число enum-атрибута → имя enum
        try {
            Map<String, Map<String, String>> enums = collectEnumAttrs(valuesXml);
            if (!enums.isEmpty()) {
                int enumFixed = 0, enumFiles = 0;
                for (File x : valuesXml) {
                    try {
                        int n = patchEnums(x, enums);
                        if (n > 0) { enumFixed += n; enumFiles++; }
                    } catch (Throwable t) {
                        log.warn("res: не удалось починить enum-значения в " + x.getName() + ": " + t);
                    }
                }
                if (enumFixed > 0) {
                    log.ok("res: исправлено raw-значений enum-атрибутов → имена enum: "
                            + enumFixed + " (в " + enumFiles + " файле(ах)) — иначе "
                            + "aapt2 link падал с 'expected enum but got raw string'.");
                    totalFixed += enumFixed;
                }
            }
        } catch (Throwable t) {
            log.warn("res: правка enum-значений пропущена: " + t);
        }
        return totalFixed;
    }

    /** Заменяет в файле все <drawable|color>false</...> на #00000000. */
    private static int patch(File xml) throws Exception {
        String text = readText(xml);
        if (text.indexOf("false") < 0) return 0;   // быстрый выход: заглушек нет
        Matcher m = DUMMY.matcher(text);
        StringBuffer sb = new StringBuffer();
        int n = 0;
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + "#00000000" + m.group(2)));
            n++;
        }
        m.appendTail(sb);
        if (n > 0) writeText(xml, sb.toString());
        return n;
    }

    /**
     * Собирает из всех values-XML карту enum-атрибутов:
     *   attrName → ( value(строка-число) → enumName ).
     * Учитываются только атрибуты, у которых есть хотя бы один &lt;enum&gt;.
     * Если одному значению соответствует несколько имён (алиасы) — берём первое
     * (aapt2 принимает любое валидное имя из набора).
     */
    private static Map<String, Map<String, String>> collectEnumAttrs(List<File> valuesXml) throws Exception {
        Map<String, Map<String, String>> res = new HashMap<String, Map<String, String>>();
        for (File x : valuesXml) {
            String text;
            try { text = readText(x); } catch (Throwable t) { continue; }
            if (text.indexOf("<attr") < 0 || text.indexOf("<enum") < 0) continue;
            Matcher am = ATTR_BLOCK.matcher(text);
            while (am.find()) {
                String attr = am.group(1);
                String body = am.group(2);
                if (body.indexOf("<enum") < 0) continue;   // flag/format-only — пропускаем
                Matcher em = ENUM_ITEM.matcher(body);
                Map<String, String> map = res.get(attr);
                while (em.find()) {
                    String name = em.group(1);
                    String val = normNum(em.group(2));
                    if (val == null) continue;
                    if (map == null) { map = new HashMap<String, String>(); res.put(attr, map); }
                    if (!map.containsKey(val)) map.put(val, name);
                }
            }
        }
        return res;
    }

    /**
     * Заменяет в файле голые числовые значения enum-атрибутов на имена enum.
     * Меняем ТОЛЬКО если: (а) атрибут известен как enum, (б) значение — голое
     * число, (в) это число есть среди enum данного атрибута. Ссылки (@…),
     * размеры (48dp), символьные значения не трогаем.
     */
    private static int patchEnums(File xml, Map<String, Map<String, String>> enums) throws Exception {
        String text = readText(xml);
        if (text.indexOf("<item") < 0) return 0;
        Matcher m = ITEM_VAL.matcher(text);
        StringBuffer sb = new StringBuffer();
        int n = 0;
        while (m.find()) {
            String open = m.group(1);
            String attr = m.group(2);
            String value = m.group(3);
            String close = m.group(4);
            String replacement = open + value + close;   // по умолчанию без изменений
            String key = normNum(value);
            if (key != null) {
                Map<String, String> map = enums.get(attr);
                // атрибут может быть указан с префиксом (android:…) — берём хвост
                if (map == null) {
                    int colon = attr.indexOf(':');
                    if (colon >= 0) map = enums.get(attr.substring(colon + 1));
                }
                if (map != null) {
                    String enumName = map.get(key);
                    if (enumName != null) {
                        replacement = open + enumName + close;
                        n++;
                    }
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        if (n > 0) writeText(xml, sb.toString());
        return n;
    }

    /**
     * Нормализует строку значения к целому числу (для сопоставления с enum
     * value). Возвращает каноничную десятичную запись или null, если это не
     * голое целое (например "48dp", "@color/x", "true", "match_parent").
     */
    private static String normNum(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.length() == 0) return null;
        try {
            long v;
            if (s.startsWith("0x") || s.startsWith("0X"))
                v = Long.parseLong(s.substring(2), 16);
            else
                v = Long.parseLong(s);   // допускает ведущий '-'
            return Long.toString(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void collectValuesXml(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) {
                // res/values, res/values-ru, res/values-v21 и т.п.
                if (k.getName().startsWith("values")) collectValuesXml(k, out);
            } else if (k.getName().endsWith(".xml")) {
                out.add(k);
            }
        }
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
