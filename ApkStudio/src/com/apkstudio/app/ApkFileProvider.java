package com.apkstudio.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/**
 * Пустой ContentProvider-заглушка для authority fileprovider.
 * Мы не раздаём файлы через content:// напрямую (используем file:// для
 * открытия папки системными приложениями на старых устройствах и ACTION_VIEW
 * с DocumentsUI на новых), но объявление провайдера в манифесте делает
 * authority валидным, если понадобится grantUriPermission.
 */
public class ApkFileProvider extends ContentProvider {
    public boolean onCreate() { return true; }
    public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    public String getType(Uri u) { return null; }
    public Uri insert(Uri u, ContentValues v) { return null; }
    public int delete(Uri u, String s, String[] a) { return 0; }
    public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
