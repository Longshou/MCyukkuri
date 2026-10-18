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
    /** The authoritative "cannot take it easy" value (mind/MindLife); mood.stress above is the same value rounded. */
    UNEASE("mood", "unease", 5, y -> Math.round(y.mind().unease() * 10) / 10f, (y, raw) -> {
        Float v = number(raw);
        return v != null && y.mind().set(v) ? null : "need a number 0-100";
    }),
    /** calm | uneasy | sweetened | non_yukkuri_frenzy | non_yukkuri_withering; write frenzy|withering|recover to force the course. */
    MIND_STAGE("mood", "mind_stage", 0, y -> y.mind().stageId(), (y, raw) -> y.mind().force(raw.trim().toLowerCase(Locale.ROOT)) ? null : "frenzy, withering or recover"),
    SWEETNESS("mood", "sweetness", 10, y -> Math.round(y.mind().sweetness()), (y, raw) -> {
        Float v = number(raw);
        return v != null && y.mind().setSweetness(v) ? null : "need a number 0-100";
    }),
    NON_YUKKURI_TICKS("mood", "non_yukkuri_ticks", 1200, y -> y.mind().nonYukkuri() ? y.mind().courseTicks() : 0, null),
    /** Current pressures and reliefs, per minute (1200 ticks), from the last sample. */
    UNEASE_CAUSES("mood", "unease_causes", 0, y -> y.mind().causesText(), null),
    NAMINGS("mood", "namings", 0, y -> y.mind().namings(), null),
    AFFECTION_NEED("mood", "affection_need", 10, y -> y.dailyLife().affectionNeed(), null),
    BOREDOM("mood", "boredom", 10, y -> y.dailyLife().boredom(), null),
    PANIC("mood", "panic", 0, y -> y.isPanicking(), null),
    AGITATION_PHASE("agitation", "phase", 0, y -> y.agitation().phase().name().toLowerCase(Locale.ROOT), null),
    AGITATION_TICKS("agitation", "phase_ticks", 20, y -> y.agitation().phaseTicks(), null),
    AGITATION_COOLDOWN("agitation", "cooldown", 200, y -> y.agitation().cooldown(), null),
    AGITATION_THREAT("agitation", "threat", 0, y -> y.agitation().agitated(), null),
    AGITATION_ENCOUNTER("agitation", "encounter", 0, y -> y.agitation().encounterDescription(), null),
    BREEDING_PERIOD_TICKS("agitation", "breeding_period_ticks", 20, y -> y.family().sexDrive(), null),
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
            case "paste" -> y.body().patchSkin();
            default -> false;
        };
        if (done && raw.trim().equalsIgnoreCase("orange")) y.status().orangeRestored();
        return done ? null : "treatment refused (nothing to treat, panicking, or unknown: orange|paste|bandage|medicine|mold)";
    }),
    BANDAGED("health", "bandaged", 0, y -> y.body().bandage() > 0, null),
    /** Portions of ordinary filling (0-12). Writable for tests and scenarios (a sagging body, a bare core). */
    FILLING("health", "filling", 0, y -> y.body().filling(), (y, raw) -> {
        Float v = number(raw);
        if (v == null || v < 0 || v > 12) return "need 0-12";
        y.body().setFilling(Math.round(v));
        return null;
    }),
    /** Sweetness a portion released now would carry (60 plain, up to 100 sweetened, 5 = foul once non-yukkuri). */
    FILLING_SWEETNESS("health", "filling_sweetness", 10, y -> y.mind().fillingSweetness(), null),
    HAIR_LOSS("health", "hair_loss", 0, y -> y.body().hairLoss(), null),
    MOLD("health", "mold", 0, y -> y.body().mold(), (y, raw) -> {
        Float v = number(raw);
        if (v == null) return "need a number (0-8; 8 is the terminal stage)";
        y.body().setMold(Math.round(v));
        return null;
    }),
    // ---- body details (worldview package 3 and 11) -------------------------------------------------------------
    /** Centre filling: {@code intact} or {@code damaged}. Write {@code damage} to hurt the core (fatal). */
    CORE("health", "core", 0, y -> y.body().coreIntact() ? "intact" : "damaged", (y, raw) -> {
        if (!raw.trim().equalsIgnoreCase("damage")) return "write 'damage' to hurt the core (fatal)";
        y.body().damageCore("status");
        return null;
    }),
    /** Ticks left of the dying state after the core was hurt (0 = not dying). */
    DYING("health", "dying", 0, y -> y.body().dyingTicks(), null),
    /** A deep wound is open and so little filling is left that the centre filling shows. */
    CORE_EXPOSED("health", "core_exposed", 0, y -> y.body().coreExposed(), null),
    /** Regions with a deep wound (top, front, side, back, bottom): cuts and heavy blows there can reach the core. */
    DEEP_PARTS("health", "deep_parts", 0, y -> String.join(",", y.body().deepParts().stream().map(io.github.longshou.mcyukkuri.body.BodyPart::id).toList()), null),
    /** Chance (0-1) that an ordinary cut or heavy blow through a deep wound reaches the core now. */
    CORE_CHANCE("health", "core_chance", 0, y -> Math.round(y.body().coreChance() * 100) / 100f, null),
    /** Every wound as region:kind+severity, e.g. {@code front:tear3 bottom:dent1}. */
    WOUND_SITES("health", "wound_sites", 0, y -> {
        var mesh = io.github.longshou.mcyukkuri.body.WoundSurface.get(y.subspecies().assetId());
        return String.join(" ", y.body().wounds().stream().filter(mesh::valid)
                .map(w -> io.github.longshou.mcyukkuri.body.BodyPart.of(mesh, w).id() + ":" + w.kind() + w.severity()).toList());
    }, null),
    /** Write-only: {@code part:kind:severity} puts a wound on that region through the body's own wound logic. */
    WOUND_AT("health", "wound_at", 0, null, (y, raw) -> {
        String[] parts = raw.trim().toLowerCase(Locale.ROOT).split(":");
        if (parts.length != 3) return "part:kind:severity, e.g. front:tear:3";
        var part = io.github.longshou.mcyukkuri.body.BodyPart.parse(parts[0]);
        if (part == null) return "part must be top, front, side, back or bottom";
        if (!java.util.Set.of("scratch", "tear", "dent", "scorch").contains(parts[1])) return "kind must be scratch, tear, dent or scorch";
        int severity;
        try { severity = Integer.parseInt(parts[2]); } catch (NumberFormatException ex) { return "severity must be 1-3"; }
        if (severity < 1 || severity > 3) return "severity must be 1-3";
        return y.body().woundAt(part, parts[1], severity) ? null : "no skin site on that region";
    }),
    /** Filling is low enough that the body is too weak to run or jump, but the core keeps it alive. */
    WEAK("health", "weak", 0, y -> y.body().weak(), null),
    /** Poison load from bitter, spicy or toxic food: 0-12 (5 = sick, 9 = lethal). */
    TOXIN("health", "toxin", 0, y -> y.body().toxin(), (y, raw) -> {
        Float v = number(raw);
        if (v == null) return "need a number";
        y.body().setToxin(Math.round(v));
        return null;
    }),
    /** A baked sole never recovers. Write {@code true} to burn the sole, {@code false} to reset it (tests only). */
    FOOT_BURNED("health", "foot_burned", 0, y -> y.body().footBurned(), (y, raw) -> {
        if (raw.trim().equalsIgnoreCase("true")) { y.body().burnFoot(); return null; }
        if (raw.trim().equalsIgnoreCase("false")) { y.body().mendFoot(); return null; }
        return "true or false";
    }),
    /** Write-only: let go by itself, as in sudden fright ({@code force} skips the bladder roll). */
    LEAK("needs", "leak", 0, null, (y, raw) -> y.life().involuntaryRelease("status", true) ? null : "nothing released (bladder under 15, on cooldown, or blocked)"),
    FLOUR_ADDICTION("health", "flour_addiction", 0, y -> y.life().addicted(), null),
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
    AFTERMATH("reproduction", "aftermath", 0, y -> {
        var b = y.family().brood();
        return b != null && b.incidentSource != null && !b.cancelled && !b.complete();
    }, null),
    INCIDENT_SOURCE("reproduction", "incident_source", 0, y -> {
        var b = y.family().brood();
        return b == null || b.incidentSource == null ? "" : b.incidentSource.toString();
    }, null),
    BURDEN_TICKS("reproduction", "burden_ticks", 20, y -> {
        var b = y.family().brood();
        return b == null ? 0 : b.burdenTicks;
    }, null),
    STERILE("reproduction", "sterile", 0, y -> y.family().sterile(), null),
    BIRTH_BLOCK("reproduction", "birth_block", 20, y -> y.family().birthBlock(), null),
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
    NAME("identity", "name", 0, y -> y.mind().givenLabel(), null),
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
    // ---- treasures (worldview package 10): at most three small finds kept by a free-living individual ------------
    /** Treasure count. Write an item id (a tagged one, e.g. minecraft:flint) to give one, or {@code clear} to drop them. */
    TREASURES("home", "treasures", 0, y -> y.treasure().count(), (y, raw) -> {
        if (raw.trim().equalsIgnoreCase("clear")) { y.treasure().dropAll(); return null; }
        var id = net.minecraft.resources.ResourceLocation.tryParse(raw.trim());
        var item = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null) return "need a treasure item id or clear";
        return y.treasure().add(new net.minecraft.world.item.ItemStack(item)) ? null : "not a treasure item, or already holding three";
    }),
    // ---- death and the dead (worldview packages 7 and 9) --------------------------------------------------------------------
    /** Smell pressure (unease per minute) of stale or rotten remains reaching this individual now. */
    STENCH("mood", "stench", 0, y -> y.level() instanceof net.minecraft.server.level.ServerLevel l ? io.github.longshou.mcyukkuri.body.RemainsRot.pressureAt(l, y.position()) : 0f, null),
    /** Why it is or is not eating remains, hunting the hatless or avoiding a smell (social/Cannibalism). */
    CANNIBAL_STATE("home", "cannibal_state", 0, y -> y.cannibalism().describe(), null),
    /** Mourning visits (social/GriefLife). Write {@code visit} to forget earlier visits and look for a body or grave at once. */
    GRIEF_STATE("home", "grief_state", 0, y -> y.grief().describe(), (y, raw) -> {
        if (!raw.trim().equalsIgnoreCase("visit")) return "write 'visit' to scan for a body or grave now";
        y.grief().forceVisit();
        return null;
    }),
    TREASURE_STATE("home", "treasure_state", 0, y -> y.treasure().describe(), null),
    // ---- accessory as a second life (worldview package 6, headwear.Headwear) and the sanction ----------------------
    /** worn | off (taken or afloat as a hat boat) | shattered | reassembling | lost. Write shatter, burn, lose, gather, knit or restore. */
    ACCESSORY_STATE("accessory", "state", 0, y -> y.headwear().stateId(), (y, raw) -> y.headwear().force(raw.trim().toLowerCase(Locale.ROOT))),
    /** Worn damage 0 intact, 1 frayed, 2 torn; write 3 to shatter it. */
    ACCESSORY_DAMAGE("accessory", "damage", 0, y -> y.headwear().damage(), (y, raw) -> {
        Float v = number(raw);
        return v == null ? "need 0-3" : y.headwear().setDamage(Math.round(v));
    }),
    /** Hat size relative to the body (0.6-1); a hat only grows while worn and while its owner can take it easy. */
    ACCESSORY_FIT("accessory", "fit", 0, y -> Math.round(y.headwear().relativeFit() * 100) / 100f, (y, raw) -> {
        Float v = number(raw);
        return v == null ? "need a ratio 0.6-1" : y.headwear().setFit(v);
    }),
    ACCESSORY_SHARDS("accessory", "shards", 0, y -> y.headwear().shardsHeld() + "/" + y.headwear().shardsTotal(), null),
    ACCESSORY_DETAIL("accessory", "detail", 0, y -> y.headwear().describe(), null),
    /** Executor side and open case. Write a reason (e.g. theft) to have the community sanction this individual (test door for settlement rules). */
    SANCTION("accessory", "sanction", 0, y -> y.sanction().describe(), (y, raw) -> {
        String reason = raw.trim().toLowerCase(Locale.ROOT);
        if (reason.isEmpty()) return "need a reason";
        return io.github.longshou.mcyukkuri.headwear.Sanction.request(y, reason, null) ? null : "no executor (disabled, protected, or no harsh adult of its community in sight)";
    }),
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
