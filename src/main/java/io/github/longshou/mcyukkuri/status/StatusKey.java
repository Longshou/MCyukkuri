package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.body.BodyWound;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * The register of scalar status entries. Each entry has one name ({@code group.name}), one reader that asks the
 * component that really owns the value, optionally one writer that goes through that component's own logic, and a
 * change bucket for the change event (0 = any change, n = a change of the rounded value divided by n).
 *
 * <p>Nothing here stores a value. Adding a status means adding one line, and every consumer (the observe label, the
 * /yukkuri status command, /data get, change events, scenarios) sees it.
 */
public enum StatusKey {
    // ---- needs -------------------------------------------------------------------------------------------------
    NUTRITION("needs", "nutrition", 10, y -> Math.round(y.life().nutrition()), need("nutrition")),
    ENERGY("needs", "energy", 10, y -> Math.round(y.life().energy()), need("energy")),
    SLEEPINESS("needs", "sleepiness", 0, y -> 100 - Math.round(y.life().energy()), null),
    HYGIENE("needs", "hygiene", 10, y -> Math.round(y.life().hygiene()), need("hygiene")),
    BLADDER("needs", "bladder", 10, y -> Math.round(y.life().bladder()), need("bladder")),
    WETNESS("needs", "wetness", 10, y -> Math.round(y.life().wetness()), need("wetness")),
    DRYNESS("needs", "dryness", 10, y -> Math.round(y.life().dryness()), need("dryness")),
    SLEEPING("needs", "sleeping", 0, y -> y.life().isSleeping(), null),
    TEMPERATURE("needs", "temperature", 0, y -> y.life().temperature().cold() ? "cold" : y.life().temperature().hot() ? "hot" : "comfortable", null),
    COLD_EXPOSURE("needs", "cold_exposure", 0, y -> y.life().temperature().coldExposure(), null),
    // ---- mood --------------------------------------------------------------------------------------------------
    STRESS("mood", "stress", 10, y -> y.pet().stress(), (y, raw) -> {
        Float v = number(raw);
        if (v == null) return "need a number";
        y.pet().addStress(Math.round(Math.max(0f, Math.min(100f, v.floatValue()))) - y.pet().stress());
        return null;
    }),
    AFFECTION_NEED("mood", "affection_need", 10, y -> y.dailyLife().affectionNeed(), null),
    BOREDOM("mood", "boredom", 10, y -> y.dailyLife().boredom(), null),
    PANIC("mood", "panic", 0, y -> y.isPanicking(), null),
    // ---- health ------------------------------------------------------------------------------------------------
    HEALTH("health", "health", 0, y -> Math.round(y.getHealth() * 10) / 10f, (y, raw) -> {
        Float v = number(raw);
        if (v == null || v <= 0) return "need a positive number (use damage to kill)";
        y.setHealth(Math.min(v, y.getMaxHealth()));
        return null;
    }),
    MAX_HEALTH("health", "max_health", 0, y -> Math.round(y.getMaxHealth() * 10) / 10f, null),
    WOUND("health", "wound", 0, y -> y.body().wound(), (y, raw) -> wound(y, raw, false)),
    BURNS("health", "burns", 0, y -> y.body().burns(), (y, raw) -> wound(y, raw, true)),
    INFECTION("health", "infection", 0, y -> y.body().infection(), (y, raw) -> {
        Float v = number(raw);
        if (v == null) return "need a number";
        for (int i = y.body().infection(); i < Math.min(8, Math.round(v)); i++) y.body().contaminated();
        return Math.round(v) < y.body().infection() ? "infection only rises by hand; treat it with medicine" : null;
    }),
    /** Write-only: apply a real treatment ({@code orange}, {@code bandage}, {@code medicine}, {@code mold}). */
    TREAT("health", "treat", 0, null, (y, raw) -> {
        boolean done = switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "orange" -> y.body().restorePhysical();
            case "bandage" -> y.body().treat(false);
            case "medicine" -> y.body().treat(true);
            case "mold" -> y.body().removeMold();
            default -> false;
        };
        if (done && raw.trim().equalsIgnoreCase("orange")) y.status().orangeRestored();
        return done ? null : "treatment refused (nothing to treat, panicking, or unknown: orange|bandage|medicine|mold)";
    }),
    BANDAGED("health", "bandaged", 0, y -> y.body().bandage() > 0, null),
    FILLING("health", "filling", 0, y -> y.body().filling(), null),
    HAIR_LOSS("health", "hair_loss", 0, y -> y.body().hairLoss(), null),
    MOLD("health", "mold", 0, y -> y.body().mold(), null),
    // ---- marks (appearance) -----------------------------------------------------------------------------------
    DIRT("marks", "dirt", 0, y -> y.status().marks().dirt(), (y, raw) -> {
        Float v = number(raw);
        return v != null && y.status().marks().setGrime(Math.round(v)) ? null : "dirt must be 0-3";
    }),
    GRIME("marks", "grime", 0, y -> y.status().marks().grime(), null),
    SCARS("marks", "scars", 0, y -> y.status().marks().scarCount(), (y, raw) -> {
        if (raw.equals("0")) { y.status().marks().clearScars(); return null; }
        return y.status().marks().addScar() ? null : "no free skin site or scar limit reached";
    }),
    // ---- reproduction -----------------------------------------------------------------------------------------
    PREGNANT("reproduction", "pregnant", 0, y -> {
        var b = y.family().brood();
        return b != null && !b.cancelled && !b.complete();
    }, null),
    BIRTH_KIND("reproduction", "birth_kind", 0, y -> {
        var b = y.family().brood();
        return b == null ? "none" : b.kind.name().toLowerCase(Locale.ROOT);
    }, null),
    BROOD_PROGRESS("reproduction", "brood_progress", 10, y -> {
        var b = y.family().brood();
        return b == null ? 0 : Math.round(b.progress() * 100);
    }, null),
    BROOD_UNBORN("reproduction", "brood_unborn", 0, y -> {
        var b = y.family().brood();
        return b == null ? 0 : b.unborn();
    }, null),
    STERILE("reproduction", "sterile", 0, y -> y.family().sterile(), null),
    CONCEPTIONS("reproduction", "conceptions", 0, y -> y.family().conceptions(), null),
    RECOVERY("reproduction", "recovery", 0, y -> y.family().recovery() > 0, null),
    // ---- growth -----------------------------------------------------------------------------------------------
    STAGE("growth", "stage", 0, y -> y.family().stage().name().toLowerCase(Locale.ROOT), null),
    AGE_TICKS("growth", "age_ticks", 1200, y -> y.family().age(), null),
    MATURITY_TICKS("growth", "maturity_ticks", 0, y -> y.family().maturityAge(), null),
    // Limited by whichever of age and nourishment is further behind (FamilyLife.growthProgress), so a hungry young shows why it is not growing.
    GROWTH_PERCENT("growth", "growth_percent", 10, y -> Math.round(100f * y.family().growthProgress()), null),
    STAGE_LEFT_TICKS("growth", "stage_left_ticks", 100, y -> y.family().stageRemainingTicks(), null),
    LIFESPAN_TICKS("growth", "lifespan_ticks", 0, y -> y.family().lifespan(), null),
    ELDER("growth", "elder", 0, y -> y.family().elder(), null),
    SCALE("growth", "scale", 0, y -> Math.round(y.growthScale() * 100) / 100f, null),
    // ---- identity ---------------------------------------------------------------------------------------------
    SPECIES("identity", "species", 0, y -> y.getVariant().assetId(), null),
    FORM("identity", "form", 0, y -> y.subspecies().assetId(), null),
    KIND("identity", "kind", 0, y -> YukkuriStatus.identity(y).name().toLowerCase(Locale.ROOT), null),
    PREDATOR("identity", "predator", 0, y -> y.getVariant().isPredator(), null),
    OWNER("identity", "owner", 0, y -> uuid(y.pet().owner()), null),
    PET_GRADE("identity", "pet_grade", 0, y -> y.pet().effectiveGrade().name().toLowerCase(Locale.ROOT), null),
    ABANDONED("identity", "abandoned", 0, y -> y.pet().abandoned(), null),
    SETTLEMENT("identity", "settlement", 0, y -> uuid(y.settlement().groupId()), null),
    SETTLEMENT_KIND("identity", "settlement_kind", 0, y -> y.settlement().kind(), null),
    SETTLEMENT_ROLE("identity", "settlement_role", 0, y -> y.settlement().groupId() == null ? "none" : y.settlement().role().name().toLowerCase(Locale.ROOT), null),
    // ---- home -------------------------------------------------------------------------------------------------
    HOME_KIND("home", "kind", 0, YukkuriStatus::homeKind, null),
    HOME_POS("home", "pos", 0, YukkuriStatus::homePos, null),
    // ---- family links -----------------------------------------------------------------------------------------
    PARTNER("relations", "partner", 0, y -> uuid(y.family().partner()), null),
    MOTHER("relations", "mother", 0, y -> uuid(y.family().mother()), null),
    FATHER("relations", "father", 0, y -> uuid(y.family().father()), null),
    // ---- behavior ---------------------------------------------------------------------------------------------
    BEHAVIOR("behavior", "behavior", 0, YukkuriEntity::getBehaviorId, null),
    ANIMATION("behavior", "animation", 0, YukkuriEntity::getAnimationId, null),
    EXPRESSION("behavior", "expression", 0, YukkuriEntity::getExpressionId, null),
    LIFE_ACTIVITY("behavior", "life_activity", 0, y -> y.life().activity(), null),
    REACTION("behavior", "reaction", 0, y -> y.status().reactions().describe(), null),
    SKY("behavior", "sky", 0, y -> y.status().reactions().weather().sky().name().toLowerCase(Locale.ROOT), null);

    /** A writer returns null on success, otherwise a short refusal. */
    @FunctionalInterface public interface Writer { String apply(YukkuriEntity entity, String raw); }

    private final String group, name;
    private final int bucket;
    private final Function<YukkuriEntity, Object> reader;
    private final Writer writer;

    StatusKey(String group, String name, int bucket, Function<YukkuriEntity, Object> reader, Writer writer) {
        this.group = group; this.name = name; this.bucket = bucket; this.reader = reader; this.writer = writer;
    }

    public String group() { return group; }
    public String shortName() { return name; }
    public String id() { return group + "." + name; }
    public boolean writable() { return writer != null; }
    public boolean readable() { return reader != null; }
    /** Null for write-only entries. */
    public Object read(YukkuriEntity entity) { return reader == null ? null : reader.apply(entity); }
    public String write(YukkuriEntity entity, String raw) { return writer == null ? "read-only" : writer.apply(entity, raw); }
    /** The value as compared by the change watcher: bucketed numbers, plain text otherwise. */
    String watched(YukkuriEntity entity) {
        Object value = read(entity);
        if (bucket > 0 && value instanceof Number n) return Long.toString(Math.floorDiv(n.longValue(), (long) bucket));
        return String.valueOf(value);
    }

    public static StatusKey byId(String id) {
        for (var key : values()) if (key.id().equals(id)) return key;
        for (var key : values()) if (key.name.equals(id)) return key;
        return null;
    }

    private static String uuid(UUID id) { return id == null ? "" : id.toString(); }
    private static Float number(String raw) {
        try { float v = Float.parseFloat(raw.trim()); return Float.isFinite(v) ? v : null; } catch (NumberFormatException ex) { return null; }
    }
    private static Writer need(String need) {
        return (y, raw) -> {
            Float v = number(raw);
            return v != null && y.life().setNeed(need, v) ? null : "need a number";
        };
    }
    /** {@code kind[:severity]} through the body's own wound logic (a real site on the skin, leaking, healing, scars). */
    private static String wound(YukkuriEntity y, String raw, boolean burn) {
        String[] parts = raw.trim().toLowerCase(Locale.ROOT).split(":");
        String kind = burn ? "scorch" : parts[0].isEmpty() || Character.isDigit(parts[0].charAt(0)) ? "dent" : parts[0];
        if (!java.util.Set.of("scratch", "tear", "dent", "scorch").contains(kind)) return "kind must be scratch, tear, dent or scorch";
        int severity = 1;
        try {
            String last = parts[parts.length - 1];
            if (!last.isEmpty() && (Character.isDigit(last.charAt(0)) || parts.length > 1)) severity = Integer.parseInt(last);
        } catch (NumberFormatException ex) { return "severity must be 1-3"; }
        if (severity < 1 || severity > 3) return "severity must be 1-3";
        var site = y.status().marks().woundSite();
        if (site == null) return "no skin site available";
        y.body().addWound(new BodyWound(UUID.randomUUID(), kind, site.triangle(), site.u(), site.v(), severity, 1, 0, 0));
        return null;
    }
}
