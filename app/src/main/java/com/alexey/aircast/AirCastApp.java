package com.alexey.aircast;

import android.app.Application;

/**
 * AirCast Application — pure Java on purpose.
 *
 * Installs the global crash handler before any Activity (and before any
 * Kotlin code) runs, so even an instant crash on launch — class loading,
 * theme inflation, WebView provider init — is captured to disk instead of
 * vanishing silently. See CrashLog.
 */
public class AirCastApp extends Application {
    @Override
    public void onCreate() {
        CrashLog.mark(this, "AirCastApp.onCreate: enter");
        super.onCreate();
        CrashLog.install(this);
        CrashLog.mark("AirCastApp.onCreate: handler installed");
    }
}
