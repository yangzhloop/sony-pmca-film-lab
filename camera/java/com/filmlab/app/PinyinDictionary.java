package com.filmlab.app;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Single-syllable character candidates, kept independent of any system IME. */
public final class PinyinDictionary {
    private static Map<String, String> entries;
    private PinyinDictionary() { }

    public static synchronized boolean load(Context context) {
        if (entries != null) return true;
        try { return load(context.getAssets().open("pinyin.tsv")); }
        catch (Exception unavailable) { return false; }
    }

    /** Consumes and closes the stream. Invalid data never replaces a dictionary. */
    public static synchronized boolean load(InputStream input) {
        if (input == null) return false;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
            Map<String, String> loaded = new HashMap<String, String>();
            int total = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                total += line.length();
                if (total > 160000 || line.length() > 4000 || loaded.size() > 600) return false;
                int tab = line.indexOf('\t');
                if (tab < 1 || tab > 7 || tab == line.length() - 1) return false;
                String key = line.substring(0, tab);
                String candidates = line.substring(tab + 1);
                if (!key.matches("[a-z]{1,7}") || loaded.containsKey(key)) return false;
                for (int i = 0; i < candidates.length(); i++) {
                    char value = candidates.charAt(i);
                    if (value < 0x3400 || value > 0x9fff) return false;
                }
                loaded.put(key, candidates);
            }
            if (loaded.size() == 0) return false;
            entries = loaded;
            return true;
        } catch (Exception invalid) { return false; }
        finally {
            try { if (reader != null) reader.close(); else input.close(); }
            catch (Exception ignored) { }
        }
    }

    public static synchronized String lookup(String syllable) {
        if (entries == null || syllable == null) return "";
        String key = syllable.trim().toLowerCase(Locale.US).replace('\u00fc', 'v');
        if (!key.matches("[a-z]{1,7}")) return "";
        String result = entries.get(key);
        return result == null ? "" : result;
    }

    public static synchronized boolean available() { return entries != null; }
}
