// SPDX-License-Identifier: MIT
package com.filmlab.app;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import java.util.Locale;

/** App-process resource selection only; never changes camera/system settings.
 * Sony framework translations still depend on firmware-installed languages. */
public final class AppLanguage {
    private AppLanguage() { }

    public static boolean applySaved(Context context) {
        if (context == null) return false;
        try { return apply(context, context.getSharedPreferences("film_lab", 0).getString("language", "en")); }
        catch (RuntimeException unavailable) { return false; }
    }

    public static boolean apply(Context context, String language) {
        if (context == null) return false;
        Locale selected = "zh".equals(language) ? Locale.SIMPLIFIED_CHINESE : Locale.US;
        try {
            Resources activity = context.getResources();
            boolean changed = update(activity, selected);
            Context application = context.getApplicationContext();
            if (application != null) {
                Resources app = application.getResources();
                if (app != activity) changed = update(app, selected) && changed;
            }
            return changed;
        } catch (RuntimeException unavailable) { return false; }
    }

    private static boolean update(Resources resources, Locale locale) {
        if (resources == null || resources == Resources.getSystem()) return false;
        Configuration config = new Configuration(resources.getConfiguration());
        if (locale.equals(config.locale)) return true;
        config.locale = locale;
        resources.updateConfiguration(config, resources.getDisplayMetrics());
        return true;
    }
}
