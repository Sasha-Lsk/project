package com.apkstudio.app;

import android.app.Application;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;

public class App extends Application {

    private static App instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                writeCrash(e);
                if (def != null) def.uncaughtException(t, e);
            }
        });
    }

    public static App get() { return instance; }

    public static String stackToString(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    public static void writeCrash(Throwable e) {
        try {
            File dir = instance.getExternalFilesDir(null);
            if (dir == null) return;
            File f = new File(dir, "crash.txt");
            PrintWriter pw = new PrintWriter(f);
            pw.println(stackToString(e));
            pw.close();
        } catch (Throwable ignore) {}
    }
}
