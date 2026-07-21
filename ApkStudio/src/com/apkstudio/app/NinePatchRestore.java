package com.apkstudio.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Восстановление ИСХОДНЫХ 9-patch (.9.png) после декомпиляции APK.
 *
 * Проблема: в собранном APK 9-patch хранятся в «скомпилированном» виде —
 * картинка БЕЗ чёрной граничной рамки, а данные растяжения/контента лежат в
 * PNG-чанке `npTc` (aapt) или `npLb`/`npOl`. Наш декодер apktool (пропатчен
 * из-за отсутствия javax.imageio.ImageIO на Android) просто копирует такой PNG
 * как есть. Тогда при обратной сборке aapt2 compile ругается:
 *   "9-patch malformed: No marked region found along edge",
 *   "top-left corner pixel must be either opaque white or transparent".
 *
 * Решение: пройтись по всем *.9.png в res/, у кого есть чанк npTc — прочитать
 * из него divs/padding, декодировать саму картинку через Android BitmapFactory
 * (ImageIO не нужен), пересобрать Bitmap размером (W+2)x(H+2) с чёрными
 * маркерами по краям (верх/лево — растяжение, право/низ — content/padding) и
 * перезаписать PNG. aapt2 при обратной сборке снова создаст корректный npTc.
 */
public final class NinePatchRestore {

    private NinePatchRestore() {}

    /** Рекурсивно чинит все *.9.png под resDir. Возвращает число исправленных. */
    public static int restoreAll(File resDir, Toolchain.Log log) {
        List<File> nine = new ArrayList<File>();
        collect(resDir, nine);
        if (nine.isEmpty()) return 0;
        log.line("9-patch: восстановление рамок для " + nine.size() + " файлов (.9.png)…");
        int fixed = 0, i = 0;
        for (File f : nine) {
            i++;
            try {
                if (restoreOne(f)) {
                    fixed++;
                    log.progress("9-patch: восстановление", i, nine.size(), f.getName());
                }
            } catch (Throwable t) {
                log.warn("9-patch: пропущен " + f.getName() + ": " + t);
            }
        }
        log.progress("9-patch: восстановление", nine.size(), nine.size(), null);
        log.ok("9-patch: восстановлено рамок: " + fixed + " из " + nine.size());
        return fixed;
    }

    private static void collect(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) collect(k, out);
            else if (k.getName().endsWith(".9.png")) out.add(k);
        }
    }

    /**
     * Восстанавливает одну картинку. Возвращает true, если рамка добавлена
     * (файл был скомпилированным 9-patch), false — если уже исходный (пропуск).
     */
    static boolean restoreOne(File file) throws Exception {
        byte[] data = readAll(file);
        NpTc np = parseNpTc(data);
        if (np == null) {
            // Нет npTc — возможно уже исходный 9-patch (с рамкой) либо обычный PNG.
            // Проверим: если уже есть корректная рамка (1px border), не трогаем.
            return false;
        }
        // Декодируем ПИКСЕЛИ картинки (без рамки).
        Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (src == null) throw new Exception("BitmapFactory вернул null");
        int w = src.getWidth(), h = src.getHeight();

        Bitmap out = Bitmap.createBitmap(w + 2, h + 2, Bitmap.Config.ARGB_8888);
        // прозрачный фон рамки
        out.eraseColor(0x00000000);
        // копируем исходную картинку со смещением (1,1)
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            src.getPixels(row, 0, w, 0, y, w, 1);
            out.setPixels(row, 0, w, 1, y + 1, w, 1);
        }
        final int BLACK = 0xFF000000;

        // ВЕРХНЯЯ граница (растяжение по X): чёрные пиксели в диапазонах xDivs.
        // xDivs — пары [start,end) в координатах картинки.
        boolean markedTop = false;
        for (int d = 0; d + 1 < np.xDivs.length; d += 2) {
            int s = clamp(np.xDivs[d], 0, w), e = clamp(np.xDivs[d + 1], 0, w);
            for (int x = s; x < e; x++) { out.setPixel(x + 1, 0, BLACK); markedTop = true; }
        }
        // ЛЕВАЯ граница (растяжение по Y): чёрные пиксели в диапазонах yDivs.
        boolean markedLeft = false;
        for (int d = 0; d + 1 < np.yDivs.length; d += 2) {
            int s = clamp(np.yDivs[d], 0, h), e = clamp(np.yDivs[d + 1], 0, h);
            for (int y = s; y < e; y++) { out.setPixel(0, y + 1, BLACK); markedLeft = true; }
        }
        // aapt2 требует хотя бы один отмеченный пиксель на КАЖДОЙ из границ
        // растяжения (иначе "No marked region found along edge"). Если divs
        // пусты/выродились — отмечаем всю границу как растяжимую.
        if (!markedTop) for (int x = 0; x < w; x++) out.setPixel(x + 1, 0, BLACK);
        if (!markedLeft) for (int y = 0; y < h; y++) out.setPixel(0, y + 1, BLACK);
        // ПРАВАЯ/НИЖНЯЯ границы (padding/content). Если padding не задан
        // (все нули) — по умолчанию content = вся область растяжения. aapt2
        // требует хотя бы один отмеченный пиксель на нижней/правой границе,
        // иначе снова ошибка. Используем padding, а при отсутствии — весь размер.
        int padL = np.padLeft, padR = np.padRight, padT = np.padTop, padB = np.padBottom;
        int cxs, cxe, cys, cye;
        if (padL == 0 && padR == 0 && padT == 0 && padB == 0) {
            cxs = 0; cxe = w; cys = 0; cye = h;   // весь размер
        } else {
            cxs = clamp(padL, 0, w);       cxe = clamp(w - padR, 0, w);
            cys = clamp(padT, 0, h);       cye = clamp(h - padB, 0, h);
            if (cxe <= cxs) { cxs = 0; cxe = w; }
            if (cye <= cys) { cys = 0; cye = h; }
        }
        // нижняя граница (content по X)
        for (int x = cxs; x < cxe; x++) out.setPixel(x + 1, h + 1, BLACK);
        // правая граница (content по Y)
        for (int y = cys; y < cye; y++) out.setPixel(w + 1, y + 1, BLACK);

        // Перезаписываем PNG (без рамки-инфо; aapt2 сгенерит npTc заново).
        File tmp = new File(file.getAbsolutePath() + ".tmp9");
        OutputStream os = new FileOutputStream(tmp);
        boolean ok = out.compress(Bitmap.CompressFormat.PNG, 100, os);
        os.flush(); os.close();
        src.recycle(); out.recycle();
        if (!ok) { tmp.delete(); throw new Exception("Bitmap.compress вернул false"); }
        if (!file.delete()) { /* перезапишем всё равно */ }
        if (!tmp.renameTo(file)) {
            // fallback: копируем содержимое
            byte[] nb = readAll(tmp);
            OutputStream fo = new FileOutputStream(file);
            fo.write(nb); fo.flush(); fo.close();
            tmp.delete();
        }
        return true;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---- разбор PNG-чанка npTc ---------------------------------------------

    static final class NpTc {
        int[] xDivs = new int[0];
        int[] yDivs = new int[0];
        int padLeft, padRight, padTop, padBottom;
    }

    /**
     * Ищет в PNG чанк "npTc" и разбирает сериализованную Res_png_9patch.
     * Формат (big-endian, как в файле PNG):
     *   1  wasDeserialized
     *   1  numXDivs
     *   1  numYDivs
     *   1  numColors
     *   4  xDivsOffset   (игнор)
     *   4  yDivsOffset   (игнор)
     *   4  colorsOffset  (игнор)
     *   4  paddingLeft
     *   4  paddingRight
     *   4  paddingTop
     *   4  paddingBottom
     *   4*numXDivs  xDivs (int32)
     *   4*numYDivs  yDivs (int32)
     *   4*numColors colors (uint32)
     * Возвращает null, если чанк не найден/повреждён.
     */
    static NpTc parseNpTc(byte[] d) {
        // PNG сигнатура 8 байт, далее чанки: len(4) type(4) data(len) crc(4).
        if (d.length < 8) return null;
        int p = 8;
        while (p + 8 <= d.length) {
            int len = be32(d, p);
            if (len < 0 || p + 12 + len > d.length) break;
            String type = new String(d, p + 4, 4);
            int dataStart = p + 8;
            if ("npTc".equals(type)) {
                return parseNpTcData(d, dataStart, len);
            }
            p = dataStart + len + 4; // + CRC
        }
        return null;
    }

    private static NpTc parseNpTcData(byte[] d, int off, int len) {
        try {
            if (len < 32) return null;
            int q = off;
            q++; // wasDeserialized
            int numX = d[q++] & 0xFF;
            int numY = d[q++] & 0xFF;
            int numC = d[q++] & 0xFF;
            q += 12; // 3 offset-указателя игнорируем
            NpTc np = new NpTc();
            np.padLeft   = be32(d, q); q += 4;
            np.padRight  = be32(d, q); q += 4;
            np.padTop    = be32(d, q); q += 4;
            np.padBottom = be32(d, q); q += 4;
            np.xDivs = new int[numX];
            for (int i = 0; i < numX && q + 4 <= off + len; i++) { np.xDivs[i] = be32(d, q); q += 4; }
            np.yDivs = new int[numY];
            for (int i = 0; i < numY && q + 4 <= off + len; i++) { np.yDivs[i] = be32(d, q); q += 4; }
            // colors не нужны для рамки
            return np;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16)
                | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static byte[] readAll(File f) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            long n = raf.length();
            if (n > Integer.MAX_VALUE) throw new Exception("файл слишком большой");
            byte[] b = new byte[(int) n];
            raf.readFully(b);
            return b;
        } finally { raf.close(); }
    }
}
