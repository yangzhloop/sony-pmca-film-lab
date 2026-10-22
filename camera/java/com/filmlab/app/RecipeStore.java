// SPDX-License-Identifier: MIT
package com.filmlab.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.SyncFailedException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;

/** Private, bounded recipe storage with validated writes and a previous-good backup. */
public final class RecipeStore {
    public static final int MAX_USERS = 200;
    private static final int MAGIC = 0x464c5232;
    private static final int MAX_BYTES = 262144;
    private static final String[][] BASES = {
        {"pop-color", "PROVIA", "标准正片", "PR"},
        {"fuji-velvia", "VELVIA", "鲜艳反转片", "VL"},
        {"fuji-astia", "ASTIA", "柔和反转片", "AS"},
        {"fuji-chrome", "Classic Chrome", "经典正片", "CC"},
        {"fuji-reala", "REALA ACE", "真实色彩", "RA"},
        {"fuji-proneg", "PRO Neg.", "专业负片", "PN"},
        {"fuji-negative", "Classic Negative", "经典负片", "CN"},
        {"fuji-eterna", "ETERNA", "电影胶片", "ET"},
        {"fuji-bleach", "ETERNA Bleach Bypass", "电影漂白旁路", "EB"},
        {"fuji-acros", "ACROS", "细腻黑白", "AC"},
        {"ricoh-positive", "Positive Film", "理光正片", "RP"},
        {"ricoh-negative", "Negative Film", "理光负片", "RN"},
        {"ricoh-hcbw", "High Contrast B&W", "高反差黑白", "BW"},
        {"ricoh-daido", "Daido Style", "森山风格", "DD"},
        {"ricoh-cross", "Cross Process", "正负逆冲", "XP"}
    };
    private final File directory;
    private State state;
    private boolean primaryValid;
    private String recoveryNotice;

    private static final class State {
        final Map<String, Recipe> users = new LinkedHashMap<String, Recipe>();
        final Set<String> favorites = new LinkedHashSet<String>();
        Recipe active;
        State copy() {
            State s = new State();
            for (Recipe r : users.values()) s.users.put(r.id, r.copy());
            s.favorites.addAll(favorites);
            s.active = active == null ? null : active.copy();
            return s;
        }
    }

    public RecipeStore(File directory) throws IOException {
        if (directory == null) throw new IllegalArgumentException("directory");
        this.directory = directory;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("recipe_directory");
        File main = file("recipes.bin"), backup = file("recipes.bak");
        if (main.exists()) {
            try { state = read(main); primaryValid = true; }
            catch (IOException failure) {
                if (!backup.exists()) throw new IOException("recipe_store_corrupt_no_backup");
                state = read(backup);
                recoveryNotice = "recovered_backup";
            }
        } else if (backup.exists()) {
            state = read(backup);
            recoveryNotice = "recovered_backup";
        } else state = new State();
    }

    public synchronized String getRecoveryNotice() { return recoveryNotice; }

    public static List<Recipe> builtins() {
        List<Recipe> result = new ArrayList<Recipe>();
        for (String[] base : BASES) result.add(builtin(base));
        return result;
    }

    public static String baseName(String id, String language) {
        for (String[] base : BASES) if (base[0].equals(id)) return base["zh".equals(language) ? 2 : 1];
        return id == null ? "" : id;
    }

    public static String baseCode(String id) {
        for (String[] base : BASES) if (base[0].equals(id)) return base[3];
        return "--";
    }

    public synchronized Recipe get(String id) {
        Recipe r = find(state, id);
        return r == null ? null : r.copy();
    }

    public synchronized List<Recipe> list(String tab) {
        if (!"builtin".equals(tab) && !"user".equals(tab) && !"favorites".equals(tab) && !"all".equals(tab))
            throw new IllegalArgumentException("tab");
        List<Recipe> result = new ArrayList<Recipe>();
        if ("builtin".equals(tab) || "all".equals(tab)) result.addAll(builtins());
        if ("user".equals(tab) || "all".equals(tab))
            for (Recipe r : state.users.values()) result.add(r.copy());
        if ("favorites".equals(tab)) for (String id : state.favorites) {
            Recipe r = find(state, id);
            if (r != null) result.add(r.copy());
        }
        return result;
    }

    public synchronized Recipe createDraft(String baseId) {
        Recipe base = findBuiltin(baseId);
        if (base == null) throw new IllegalArgumentException("base_id");
        base.id = null; base.user = true;
        return base;
    }

    /** Duplication opens an unsaved draft; cancelling it never leaves an unwanted record. */
    public synchronized Recipe duplicate(String id) {
        Recipe base = find(state, id);
        if (base == null) throw new IllegalArgumentException("recipe_missing");
        Recipe draft = base.copy();
        draft.id = null; draft.user = true; draft.revision = 1;
        return draft;
    }

    public synchronized Recipe save(Recipe draft) throws IOException {
        Recipe r = validated(draft);
        if (!r.user) throw new IllegalArgumentException("builtin_readonly");
        State next = state.copy();
        if (r.id == null) {
            if (next.users.size() >= MAX_USERS) throw new IllegalArgumentException("recipe_limit");
            do { r.id = "user-" + UUID.randomUUID().toString(); } while (next.users.containsKey(r.id));
            r.revision = 1;
        } else {
            Recipe stored = next.users.get(r.id);
            if (stored == null) throw new IllegalArgumentException("user_recipe_missing");
            if (r.revision != stored.revision) throw new IllegalArgumentException("stale_recipe");
            if (stored.revision == Integer.MAX_VALUE) throw new IllegalArgumentException("revision_limit");
            r.revision = stored.revision + 1;
        }
        next.users.put(r.id, r);
        commit(next);
        return r.copy();
    }

    public synchronized Recipe rename(String id, String name) throws IOException {
        Recipe r = state.users.get(id);
        if (r == null) throw new IllegalArgumentException("user_recipe_missing");
        if (r.revision == Integer.MAX_VALUE) throw new IllegalArgumentException("revision_limit");
        r = r.copy(); r.name = validName(name); r.revision++;
        State next = state.copy(); next.users.put(id, r);
        // Renaming changes only the displayed name of an already applied snapshot.
        // Its settings and applied revision remain the ones accepted by the camera.
        if (next.active != null && id.equals(next.active.id)) next.active.name = r.name;
        commit(next);
        return r.copy();
    }

    public synchronized void delete(String id) throws IOException {
        if (!state.users.containsKey(id)) throw new IllegalArgumentException("user_recipe_missing");
        State next = state.copy();
        next.users.remove(id); next.favorites.remove(id);
        if (next.active != null && id.equals(next.active.id)) next.active.id = null;
        commit(next);
    }

    public synchronized boolean isFavorite(String id) { return state.favorites.contains(id); }

    public synchronized void setFavorite(String id, boolean favorite) throws IOException {
        if (find(state, id) == null) throw new IllegalArgumentException("recipe_missing");
        if (state.favorites.contains(id) == favorite) return;
        State next = state.copy();
        if (favorite) next.favorites.add(id); else next.favorites.remove(id);
        commit(next);
    }

    public synchronized void toggleFavorite(String id) throws IOException { setFavorite(id, !isFavorite(id)); }

    public synchronized Recipe getActive() { return state.active == null ? null : state.active.copy(); }

    /** Call only after camera parameter application succeeds. Save and rename never apply settings. */
    public synchronized void activate(Recipe recipe) throws IOException {
        Recipe snapshot = validated(recipe);
        if (!snapshot.user) {
            Recipe builtin = findBuiltin(snapshot.id);
            if (builtin == null || !sameSettings(snapshot, builtin)) throw new IllegalArgumentException("builtin_readonly");
            snapshot = builtin;
        } else if (snapshot.id != null && !state.users.containsKey(snapshot.id)) snapshot.id = null;
        State next = state.copy(); next.active = snapshot;
        commit(next);
    }

    public static Recipe validated(Recipe input) {
        if (input == null) throw new IllegalArgumentException("recipe_missing");
        Recipe r = input.copy();
        if (findBuiltin(r.baseId) == null) throw new IllegalArgumentException("base_id");
        r.name = validName(r.name);
        if (r.revision <= 0) throw new IllegalArgumentException("revision");
        if (!"auto".equals(r.wb) && !"kelvin".equals(r.wb)) throw new IllegalArgumentException("white_balance");
        range(r.color, 0, 100); range(r.tone, 0, 100);
        range(r.shadows, -3, 3); range(r.highlights, -3, 3);
        range(r.kelvin, 2500, 10000); range(r.ab, -7, 7); range(r.gm, -7, 7);
        if (r.isMonochrome()) r.color = 100;
        if (r.user && r.id != null && (!r.id.startsWith("user-") || r.id.length() > 64))
            throw new IllegalArgumentException("user_id");
        if (!r.user && (!r.baseId.equals(r.id) || !sameSettings(r, findBuiltin(r.id))))
            throw new IllegalArgumentException("builtin_readonly");
        return r;
    }

    public static String validName(String input) {
        if (input == null) throw new IllegalArgumentException("name_required");
        String name = input.trim();
        if (name.length() == 0) throw new IllegalArgumentException("name_required");
        int count = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c)) throw new IllegalArgumentException("name_control");
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= name.length() || !Character.isLowSurrogate(name.charAt(++i)))
                    throw new IllegalArgumentException("name_unicode");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("name_unicode");
            count++;
        }
        if (count > 32) throw new IllegalArgumentException("name_long");
        return name;
    }

    private static void range(int value, int min, int max) {
        if (value < min || value > max) throw new IllegalArgumentException("setting_range");
    }

    private static boolean sameSettings(Recipe a, Recipe b) {
        return b != null && a.baseId.equals(b.baseId) && a.color == b.color && a.tone == b.tone
            && a.shadows == b.shadows && a.highlights == b.highlights && a.wb.equals(b.wb)
            && a.kelvin == b.kelvin && a.ab == b.ab && a.gm == b.gm;
    }

    private static Recipe builtin(String[] base) {
        Recipe r = new Recipe(); r.id = base[0]; r.baseId = base[0]; r.name = base[1];
        return r;
    }

    private static Recipe findBuiltin(String id) {
        for (String[] base : BASES) if (base[0].equals(id)) return builtin(base);
        return null;
    }

    private static Recipe find(State s, String id) {
        Recipe r = findBuiltin(id);
        return r == null ? s.users.get(id) : r;
    }

    private File file(String name) { return new File(directory, name); }

    private void commit(State next) throws IOException {
        File pending = file("recipes.new"), main = file("recipes.bin"), backup = file("recipes.bak");
        byte[] bytes = encode(next);
        FileOutputStream out = new FileOutputStream(pending);
        try {
            out.write(bytes);
            out.flush();
            try { out.getFD().sync(); }
            catch (SyncFailedException unavailable) {
                // Some Sony filesystems reject fsync. Complete close and readback
                // still verify bytes; this does not promise power-loss durability.
            }
        } finally { out.close(); }
        read(pending); // Validate complete bytes before moving the current good state.
        boolean moved = false;
        if (primaryValid && main.exists()) {
            if (backup.exists() && !backup.delete()) throw new IOException("recipe_backup_remove");
            if (!main.renameTo(backup)) throw new IOException("recipe_backup_create");
            moved = true;
        } else if (main.exists() && !main.delete()) throw new IOException("recipe_corrupt_remove");
        if (!pending.renameTo(main)) {
            if (moved) backup.renameTo(main);
            throw new IOException("recipe_commit_failed");
        }
        // The active look is a detached section in this same transaction, so deleting
        // its saved record and clearing its id cannot disagree after a process restart.
        state = next;
        primaryValid = true;
        recoveryNotice = null;
    }

    private static byte[] encode(State s) throws IOException {
        ByteArrayOutputStream payloadBuffer = new ByteArrayOutputStream();
        DataOutputStream payload = new DataOutputStream(payloadBuffer);
        payload.writeInt(s.users.size());
        for (Recipe r : s.users.values()) writeRecipe(payload, r);
        payload.writeInt(s.favorites.size());
        for (String id : s.favorites) payload.writeUTF(id);
        payload.writeBoolean(s.active != null);
        if (s.active != null) writeRecipe(payload, s.active);
        payload.close();
        byte[] body = payloadBuffer.toByteArray();
        if (body.length > MAX_BYTES - 16) throw new IOException("recipe_store_size");
        CRC32 crc = new CRC32(); crc.update(body);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(MAGIC); out.writeInt(body.length); out.writeLong(crc.getValue()); out.write(body); out.close();
        return buffer.toByteArray();
    }

    private static State read(File source) throws IOException {
        if (!source.isFile() || source.length() < 16 || source.length() > MAX_BYTES)
            throw new IOException("recipe_store_size");
        DataInputStream in = new DataInputStream(new FileInputStream(source));
        byte[] body;
        long checksum;
        try {
            if (in.readInt() != MAGIC) throw new IOException("recipe_store_version");
            int size = in.readInt(); checksum = in.readLong();
            if (size < 0 || size != source.length() - 16) throw new IOException("recipe_store_size");
            body = new byte[size]; in.readFully(body);
            if (in.read() != -1) throw new IOException("recipe_store_trailing");
        } finally { in.close(); }
        CRC32 crc = new CRC32(); crc.update(body);
        if (checksum != crc.getValue()) throw new IOException("recipe_store_checksum");
        DataInputStream payload = new DataInputStream(new ByteArrayInputStream(body));
        State s = new State();
        try {
            int users = payload.readInt();
            if (users < 0 || users > MAX_USERS) throw new IOException("recipe_user_count");
            for (int i = 0; i < users; i++) {
                Recipe r = readRecipe(payload);
                if (!r.user || r.id == null || s.users.containsKey(r.id)) throw new IOException("recipe_user_record");
                s.users.put(r.id, r);
            }
            int favorites = payload.readInt();
            if (favorites < 0 || favorites > MAX_USERS + BASES.length) throw new IOException("recipe_favorite_count");
            for (int i = 0; i < favorites; i++) {
                String id = payload.readUTF();
                if (find(s, id) == null || !s.favorites.add(id)) throw new IOException("recipe_favorite_record");
            }
            if (payload.readBoolean()) {
                s.active = readRecipe(payload);
                if (s.active.id != null && find(s, s.active.id) == null) throw new IOException("recipe_active_record");
            }
            if (payload.read() != -1) throw new IOException("recipe_payload_trailing");
        } catch (IllegalArgumentException failure) { throw new IOException("recipe_invalid_record"); }
        finally { payload.close(); }
        return s;
    }

    private static void writeRecipe(DataOutputStream out, Recipe r) throws IOException {
        out.writeBoolean(r.id != null); if (r.id != null) out.writeUTF(r.id);
        out.writeUTF(r.name); out.writeUTF(r.baseId); out.writeUTF(r.wb);
        out.writeInt(r.revision); out.writeBoolean(r.user);
        out.writeInt(r.color); out.writeInt(r.tone); out.writeInt(r.shadows); out.writeInt(r.highlights);
        out.writeInt(r.kelvin); out.writeInt(r.ab); out.writeInt(r.gm);
    }

    private static Recipe readRecipe(DataInputStream in) throws IOException {
        Recipe r = new Recipe();
        r.id = in.readBoolean() ? in.readUTF() : null;
        r.name = in.readUTF(); r.baseId = in.readUTF(); r.wb = in.readUTF();
        r.revision = in.readInt(); r.user = in.readBoolean();
        r.color = in.readInt(); r.tone = in.readInt(); r.shadows = in.readInt(); r.highlights = in.readInt();
        r.kelvin = in.readInt(); r.ab = in.readInt(); r.gm = in.readInt();
        return validated(r);
    }
}
