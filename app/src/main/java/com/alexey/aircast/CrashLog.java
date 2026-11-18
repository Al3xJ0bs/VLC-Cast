package com.alexey.aircast;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Crash capture for AirCast v0.1.1.
 *
 * Pure Java on purpose: it must work even if the Kotlin runtime / dex
 * verification of MainActivity fails. Installed from AirCastApp.onCreate
 * (also a Java class), before any Activity is created.
 *
 * - install(): Thread.setDefaultUncaughtExceptionHandler. On a crash it
 *   appends the full stacktrace + device info + last startup marker to
 *   filesDir/aircast-crash.log (fsync), sets prefs flag "crashed", then
 *   delegates to the previous default handler (system crash dialog / kill).
 * - mark(): appends one timestamped line to filesDir/aircast-startup.log
 *   (fsync). MainActivity calls it before every init step, so a crash
 *   mid-startup leaves the last completed step on disk.
 *
 * Everything here is defensive: the handler must never throw itself.
 */
public final class CrashLog {
    // v0.9.3.2: синхронизировать вручную с MainActivity.VERSION при каждом
    // релизе. CrashLog намеренно не ссылается на MainActivity (Kotlin):
    // он должен работать, даже если Kotlin runtime / dex MainActivity
    // сломан, поэтому версия здесь — своя захардкоженная строка.
    public static final String VERSION = "0.9.3.6";
    private static final String CRASH_FILE = "aircast-crash.log";
    private static final String STARTUP_FILE = "aircast-startup.log";
    private static final String PREFS = "aircast";
    private static final String FLAG_CRASHED = "crashed";

    private static volatile File filesDir;
    private static volatile Context appContext;

    private CrashLog() {}

    public static void install(Context context) {
        try {
            appContext = context.getApplicationContext();
            filesDir = appContext.getFilesDir();
            final Thread.UncaughtExceptionHandler prev =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    try {
                        writeCrash(t, e);
                    } catch (Throwable ignored) {
                    }
                    try {
                        if (prev != null) {
                            prev.uncaughtException(t, e);
                            return;
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        android.os.Process.killProcess(android.os.Process.myPid());
                    } catch (Throwable ignored) {
                    }
                    try {
                        System.exit(10);
                    } catch (Throwable ignored) {
                    }
                }
            });
            mark("CrashLog.install: handler installed");
        } catch (Throwable ignored) {
        }
    }

    /** One startup-step marker, flushed to disk immediately. */
    public static void mark(String step) {
        mark(appContext, step);
    }

    public static void mark(Context context, String step) {
        File dir = filesDir;
        if (dir == null && context != null) {
            try {
                dir = context.getApplicationContext().getFilesDir();
            } catch (Throwable ignored) {
                return;
            }
        }
        if (dir == null) return;
        FileOutputStream out = null;
        try {
            File f = new File(dir, STARTUP_FILE);
            out = new FileOutputStream(f, true);
            String line = stamp() + " " + step + "\n";
            out.write(line.getBytes("UTF-8"));
            out.getFD().sync();
        } catch (Throwable ignored) {
        } finally {
            try {
                if (out != null) out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public static String lastMarker(Context context) {
        try {
            File dir = filesDir;
            if (dir == null && context != null) dir = context.getApplicationContext().getFilesDir();
            if (dir == null) return "(нет каталога файлов)";
            File f = new File(dir, STARTUP_FILE);
            if (!f.exists()) return "(startup-лог пуст — падение до первого маркера)";
            byte[] bytes = new byte[(int) Math.min(f.length(), 65536)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int read = in.read(bytes);
                if (read <= 0) return "(startup-лог пуст)";
                String text = new String(bytes, 0, read, "UTF-8");
                String[] lines = text.split("\n");
                for (int i = lines.length - 1; i >= 0; i--) {
                    if (!lines[i].trim().isEmpty()) return lines[i];
                }
                return "(startup-лог пуст)";
            } finally {
                in.close();
            }
        } catch (Throwable e) {
            return "(не читается startup-лог: " + e.getMessage() + ")";
        }
    }

    public static String readCrashLog(Context context) {
        try {
            File dir = filesDir;
            if (dir == null && context != null) dir = context.getApplicationContext().getFilesDir();
            if (dir == null) return "(нет каталога файлов)";
            File f = new File(dir, CRASH_FILE);
            if (!f.exists()) return "(crash-лог отсутствует)";
            byte[] bytes = new byte[(int) Math.min(f.length(), 262144)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int read = in.read(bytes);
                return read > 0 ? new String(bytes, 0, read, "UTF-8") : "(crash-лог пуст)";
            } finally {
                in.close();
            }
        } catch (Throwable e) {
            return "(не читается crash-лог: " + e.getMessage() + ")";
        }
    }

    /**
     * True only while the "crashed" flag is set (i.e. a crash was recorded
     * and the user has not yet acknowledged it on the crash screen).
     * The crash log file alone does NOT trigger the screen: clearCrashed()
     * archives it, so continuing from the crash screen does not loop.
     */
    public static boolean hasCrashed(Context context) {
        try {
            SharedPreferences p = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return p.getBoolean(FLAG_CRASHED, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean hasCrashLogFile(Context context) {
        try {
            File dir = filesDir;
            if (dir == null) dir = context.getApplicationContext().getFilesDir();
            return dir != null && new File(dir, CRASH_FILE).exists();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void clearCrashed(Context context) {
        try {
            context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(FLAG_CRASHED).apply();
        } catch (Throwable ignored) {
        }
        try {
            File dir = filesDir;
            if (dir == null) dir = context.getApplicationContext().getFilesDir();
            if (dir != null) {
                File cur = new File(dir, CRASH_FILE);
                File prev = new File(dir, "aircast-crash-prev.log");
                if (cur.exists()) {
                    if (prev.exists()) prev.delete();
                    cur.renameTo(prev);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void writeCrash(Thread t, Throwable e) {
        File dir = filesDir;
        if (dir == null && appContext != null) {
            try {
                dir = appContext.getFilesDir();
            } catch (Throwable ignored) {
                return;
            }
        }
        if (dir == null) return;
        FileOutputStream out = null;
        try {
            // Flag first: even if the stacktrace write fails, next launch
            // must show the crash screen.
            try {
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(FLAG_CRASHED, true).commit();
            } catch (Throwable ignored) {
            }
            File f = new File(dir, CRASH_FILE);
            out = new FileOutputStream(f, true);
            StringBuilder sb = new StringBuilder();
            sb.append("=== CRASH ").append(stamp()).append(" ===\n");
            sb.append("app: AirCast ").append(VERSION).append("\n");
            sb.append("thread: ").append(t != null ? t.getName() : "?").append("\n");
            try {
                sb.append("device: ").append(Build.MANUFACTURER).append(" ")
                        .append(Build.MODEL).append(" / Android ")
                        .append(Build.VERSION.RELEASE).append(" (SDK ")
                        .append(Build.VERSION.SDK_INT).append(")\n");
            } catch (Throwable ignored) {
            }
            sb.append("last startup marker: ").append(lastMarker(appContext)).append("\n");
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            sb.append(sw.toString()).append("\n");
            out.write(sb.toString().getBytes("UTF-8"));
            out.getFD().sync();
        } catch (Throwable ignored) {
        } finally {
            try {
                if (out != null) out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String stamp() {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        } catch (Throwable ignored) {
            return "?";
        }
    }
}
