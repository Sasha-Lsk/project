package com.apktool.app;

/**
 * Приёмник логов и прогресса. Реализуется в MainActivity, чтобы Engine мог
 * писать сообщения в UI из фонового потока.
 */
public interface Logger {
    /** Обычная строка лога (зелёный текст). */
    void log(String msg);
    /** Строка ошибки (красный текст). */
    void err(String msg);
    /** Прогресс 0..100. */
    void progress(int percent);
}
