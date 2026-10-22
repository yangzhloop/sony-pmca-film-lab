// SPDX-License-Identifier: MIT
package com.filmlab.app;

/** A detached recipe value. Store methods copy values at their boundaries. */
public final class Recipe {
    public String id;
    public String name;
    public String baseId;
    public String wb = "auto";
    public int revision = 1;
    public boolean user;
    public int color = 100;
    public int tone = 100;
    public int shadows;
    public int highlights;
    public int kelvin = 5500;
    public int ab;
    public int gm;

    public Recipe copy() {
        Recipe r = new Recipe();
        r.id = id; r.name = name; r.baseId = baseId; r.wb = wb;
        r.revision = revision; r.user = user; r.color = color; r.tone = tone;
        r.shadows = shadows; r.highlights = highlights; r.kelvin = kelvin;
        r.ab = ab; r.gm = gm;
        return r;
    }

    public boolean isMonochrome() {
        return "fuji-acros".equals(baseId) || "ricoh-hcbw".equals(baseId)
            || "ricoh-daido".equals(baseId);
    }

    public String displayName(String language) {
        if (user) return name;
        return RecipeStore.baseName(baseId, language);
    }

    public String code() { return RecipeStore.baseCode(baseId); }
}
