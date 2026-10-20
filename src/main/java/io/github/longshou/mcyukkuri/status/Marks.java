package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.body.BodyWound;
import io.github.longshou.mcyukkuri.body.WoundKind;
import io.github.longshou.mcyukkuri.body.WoundSurface;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import java.util.Random;

/**
 * Lasting surface marks of a free-living individual: ground-in grime and scars.
 *
 * <p>Sources: a low-probability roll the first time a free individual is simulated; a long life without a home;
 * low hygiene; a deep wound that finished healing. Sinks: rain on bare ground, a real wash (a jump in hygiene).
 * The server owns the numbers and the sites; the client only draws the synced {@link BodyWound} list on the
 * posed skin, the same way it draws wounds (kinds {@code dirt} and {@code scar}).
 */
public final class Marks {
    public static final int MAX_SCARS = 3, MAX_PATCHES = 6;
    /** Odds that a free, non-baby individual starts with grime / a scar. Deliberately uncommon. */
    static final double START_GRIME = .10, START_SCAR = .06;

    private final YukkuriEntity entity;
    private boolean rolled;
    private int grime, soil, rain;
    private float lastHygiene = -1;
    private final List<BodyWound> scars = new ArrayList<>();
    private int shown = -1, shownScars = -1;

    Marks(YukkuriEntity entity) { this.entity = entity; }

    public int grime() { return grime; }
    public int scarCount() { return scars.size(); }
    public List<BodyWound> scars() { return List.copyOf(scars); }
    /** What is drawn: the larger of ground-in grime and the dirt a low hygiene value causes. */
    public int dirt() {
        float hygiene = entity.life().hygiene();
        int fromHygiene = hygiene < 15 ? 3 : hygiene < 30 ? 2 : hygiene < 45 ? 1 : 0;
        return Math.max(grime, fromHygiene);
    }

    /** Controlled writes (commands, scenarios). Returns false when the request is out of range. */
    public boolean setGrime(int level) {
        if (level < 0 || level > 3) return false;
        grime = level; soil = rain = 0; rolled = true; return true;
    }
    public boolean addScar() { rolled = true; return addScarAt(site(entity.getRandom().nextLong()), 1); }
    public void clearScars() {
        scars.clear(); rolled = true;
        entity.syncMarks(write(visible()));
        shown = shownScars = -1;
    }
    /** A random skin site for a new wound (front face and underside excluded). */
    public BodyWound woundSite() { return site(entity.getRandom().nextLong()); }

    private WoundSurface mesh() {
        try { return WoundSurface.get(entity.subspecies().assetId()); } catch (RuntimeException ex) { return null; }
    }
    /** A skin site that is not the face or the underside. Null when the mesh is unavailable. */
    private BodyWound site(long seed) {
        var mesh = mesh();
        if (mesh == null || mesh.skinCount < 3) return null;
        var random = new Random(seed);
        for (int attempt = 0; attempt < 48; attempt++) {
            int triangle = random.nextInt(mesh.skinCount / 3) * 3;
            var n = mesh.normal(triangle);
            if (!n.isFinite() || n.z < -.6f || n.y < -.5f) continue;
            float u = random.nextFloat(), v = random.nextFloat() * (1 - u);
            return new BodyWound(new UUID(seed, attempt), WoundKind.SCAR, triangle, u, v, 1, 1, 0, 0);
        }
        return null;
    }
    private boolean addScarAt(BodyWound site, int severity) {
        if (site == null || scars.size() >= MAX_SCARS) return false;
        scars.add(new BodyWound(UUID.randomUUID(), WoundKind.SCAR, site.triangle(), site.u(), site.v(), Math.clamp(severity, 1, 3),
                .8f + entity.getRandom().nextFloat() * .6f, 0, 0));
        return true;
    }

    /** Called every 20 ticks on the server. Returns true when the drawn marks changed. */
    boolean tick(Identity identity) {
        if (!rolled && entity.tickCount > 40) {
            rolled = true;
            if (identity.free() && entity.family().stage() != FamilyLife.Stage.BABY) {
                var random = entity.getRandom();
                if (random.nextDouble() < START_GRIME) grime = random.nextDouble() < .7 ? 1 : random.nextDouble() < .85 ? 2 : 3;
                if (random.nextDouble() < START_SCAR) addScarAt(site(random.nextLong()), 1 + random.nextInt(2));
            }
        }
        float hygiene = entity.life().hygiene();
        if (lastHygiene >= 0 && hygiene - lastHygiene >= 10 && grime > 0) { grime--; soil = 0; }   // a real wash
        lastHygiene = hygiene;
        if (identity == Identity.STRAY && grime < 2 && hygiene < 80 && ++soil >= 360) { grime++; soil = 0; }  // ~6 min wandering
        if (Sky.at(entity.level(), entity.blockPosition()).wet()) {
            if (grime > 0 && ++rain >= 40) { grime--; rain = 0; }  // ~40 s in the rain
        } else rain = 0;
        int level = dirt();
        boolean changed = level != shown || scars.size() != shownScars;
        shown = level; shownScars = scars.size();
        return changed;
    }

    /** Called only when gradual healing or licking closes the final injury level. */
    public void healed(BodyWound wound) {
        if (entity.level().isClientSide || !entity.isAlive() || scars.size() >= MAX_SCARS) return;
        double base = switch (wound.kind()) {
            case TEAR -> .6;
            case SCORCH -> .5;
            case DENT -> .25;
            case SCRATCH -> .12;
            default -> 0;
        };
        if (base == 0) return;
        double chance = Math.min(1, base + .2 * (wound.peakSeverity() - 1));
        if (entity.getRandom().nextDouble() < chance) {
            addScarAt(wound, wound.peakSeverity());
            MCYukkuri.LOGGER.info("[body] {} #{} scar formed from {} wound (peak {}, chance {}%)", entity.getVariant().assetId(), entity.getId(),
                    wound.kind().id(), wound.peakSeverity(), Math.round(chance * 100));
        }
    }

    /** The synced list: dirt patches, then scars. Dirt positions are deterministic per individual so they stay put. */
    public List<BodyWound> visible() {
        var result = new ArrayList<BodyWound>();
        int level = dirt();
        for (int i = 0; i < Math.min(MAX_PATCHES, level * 2); i++) {
            var spot = site(entity.getUUID().getLeastSignificantBits() * 31 + i * 7919L);
            if (spot != null) result.add(new BodyWound(new UUID(entity.getUUID().getMostSignificantBits(), i), WoundKind.DIRT, spot.triangle(),
                    spot.u(), spot.v(), level, 1.1f + (i % 3) * .35f, 0, 0));
        }
        result.addAll(scars);
        return result;
    }
    public static CompoundTag write(List<BodyWound> marks) { return BodyWound.write(marks); }
    /** Unlike {@link BodyWound#read}, the mark list is not capped at the eight-wound limit. */
    public static List<BodyWound> read(CompoundTag tag) {
        var result = new ArrayList<BodyWound>();
        for (Tag raw : tag.getList("Wounds", Tag.TAG_COMPOUND)) {
            var w = (CompoundTag) raw;
            if (!w.hasUUID("Id")) continue;
            result.add(new BodyWound(w.getUUID("Id"), w.getString("Kind"), w.getInt("Triangle"), w.getFloat("U"), w.getFloat("V"),
                    Math.max(1, w.getInt("Severity")), w.getFloat("Size"), 0, 0));
            if (result.size() >= 16) break;
        }
        return List.copyOf(result);
    }

    CompoundTag save() {
        var tag = new CompoundTag();
        tag.putBoolean("Rolled", rolled); tag.putInt("Grime", grime); tag.putInt("Soil", soil); tag.putInt("Rain", rain);
        var list = new ListTag();
        scars.forEach(s -> list.add(s.save()));
        tag.put("Scars", list);
        return tag;
    }
    void load(CompoundTag tag) {
        rolled = tag.getBoolean("Rolled"); grime = Math.clamp(tag.getInt("Grime"), 0, 3);
        soil = Math.clamp(tag.getInt("Soil"), 0, 100000); rain = Math.clamp(tag.getInt("Rain"), 0, 1000);
        scars.clear();
        for (Tag raw : tag.getList("Scars", Tag.TAG_COMPOUND)) {
            var w = (CompoundTag) raw;
            if (!w.hasUUID("Id") || scars.size() >= MAX_SCARS) continue;
            scars.add(new BodyWound(w.getUUID("Id"), WoundKind.SCAR, w.getInt("Triangle"), w.getFloat("U"), w.getFloat("V"),
                    Math.max(1, w.getInt("Severity")), w.getFloat("Size"), 0, 0));
        }
        shown = shownScars = -1; lastHygiene = -1;
    }
}
