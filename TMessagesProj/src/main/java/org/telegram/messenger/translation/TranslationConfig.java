package org.telegram.messenger.translation;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;

public final class TranslationConfig {
    private static final String PREFS_NAME = "telegram_translation";
    private static final String BACKEND_URL_KEY = "backend_url";

    private TranslationConfig() {
    }

    public static String getBackendUrl() {
        SharedPreferences prefs = ApplicationLoader.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String stored = prefs.getString(BACKEND_URL_KEY, null);
        if (stored != null && !stored.trim().isEmpty()) {
            return stored.trim();
        }
        return BuildVars.TRANSLATION_BACKEND_URL;
    }

    public static void setBackendUrl(String url) {
        ApplicationLoader.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(BACKEND_URL_KEY, url.trim())
                .apply();
    }

    public static String getAppSecret() {
        return BuildVars.APP_SHARED_SECRET;
    }
}
