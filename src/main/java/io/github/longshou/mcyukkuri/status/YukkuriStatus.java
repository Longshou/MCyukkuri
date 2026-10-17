package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;

/**
 * The status hub: one place that names, reads and (carefully) writes what is true of a yukkuri.
 *
 * <ul>
 *   <li>{@link StatusKey} is the register of scalar entries. Readers call the component that owns the value; the hub
 *       stores no copy, so it cannot disagree with the component.</li>
 *   <li>{@link #identity} derives what the individual is (wild, stray, pet, settler, house...) in one place.</li>
 *   <li>{@link #set} is the only sanctioned way for scenarios and commands to change a value from outside; it goes
 *       through the owning component (a wound is a real wound on a real skin site, a need is the real need).</li>
 *   <li>{@link #snapshot} is the NBT export, also written into the entity's saved data as {@code YukkuriStatusView}
 *       so {@code /data get entity <e> YukkuriStatusView} reads everything at once.</li>
 *   <li>{@link StatusChangedEvent} / {@link RelationChangedEvent} announce changes; {@link #label} feeds the
 *       playground's observe display.</li>
 * </ul>
 */
public final class YukkuriStatus {
    public static Identity identity(YukkuriEntity y) {
        if (y.getVariant().isHouse()) return Identity.HOUSE;
        if (y.pet().isPet()) return Identity.PET;
        if (y.pet().owner() != null) return Identity.OWNED;
        if (y.settlement().groupId() != null) return Identity.SETTLER;
        boolean homed = y.habitat().hasMemory() || y.life().home() != null || y.family().boatHome() != null;
        return homed ? Identity.WILD : Identity.STRAY;
    }

    public static Object get(YukkuriEntity y, StatusKey key) { return key.read(y); }

    /** @return null on success, otherwise why the write was refused. */
    public static String set(YukkuriEntity y, String id, String value) {
        if (y.level().isClientSide || !y.isAlive()) return "not a living server-side individual";
        var key = StatusKey.byId(id);
        if (key == null) return "unknown status " + id;
        return key.write(y, value);
    }

    /** Directed acquaintance score, through the ordinary relation store (fires {@link RelationChangedEvent}). */
    public static void relate(YukkuriEntity from, YukkuriEntity to, int score) {
        var data = YukkuriWorldData.get((ServerLevel) from.level());
        data.relate(from.getUUID(), to.getUUID(), Math.clamp(score, -100, 100) - data.affinity(from.getUUID(), to.getUUID()));
    }

    static String homeKind(YukkuriEntity y) {
        if (y.habitat().hasMemory()) return "shelter";
        if (y.getVariant().isHouse() && y.house().homeStand() != null) return "house_nest";
        if (y.life().home() != null) return y.life().naturalHome() ? "cover" : "nest";
        if (y.family().boatHome() != null) return "hat_boat";
        return "none";
    }
    static String homePos(YukkuriEntity y) {
        var stand = y.life().homeStand();
        return stand == null ? "" : stand.getX() + " " + stand.getY() + " " + stand.getZ();
    }

    /** The full export: scalar groups, wound list, marks, relations. */
    public static CompoundTag snapshot(YukkuriEntity y) {
        var root = new CompoundTag();
        for (var key : StatusKey.values()) {
            Object value;
            try { value = key.read(y); } catch (RuntimeException ex) { value = "error:" + ex.getClass().getSimpleName(); }
            var group = root.getCompound(key.group());
            put(group, key.shortName(), value);
            root.put(key.group(), group);
        }
        var wounds = new ListTag();
        for (var w : y.body().wounds()) {
            var t = new CompoundTag();
            t.putString("kind", w.kind()); t.putInt("severity", w.severity()); t.putInt("triangle", w.triangle());
            wounds.add(t);
        }
        root.getCompound("health");
        var health = root.getCompound("health"); health.put("wounds", wounds); root.put("health", health);
        var marks = root.getCompound("marks");
        var scars = new ListTag();
        for (var s : y.status().marks().scars()) { var t = new CompoundTag(); t.putInt("triangle", s.triangle()); t.putInt("severity", s.severity()); scars.add(t); }
        marks.put("scar_list", scars); root.put("marks", marks);
        var relations = root.getCompound("relations");
        if (y.level() instanceof ServerLevel level) {
            var scores = new ListTag();
            YukkuriWorldData.get(level).affinitiesFrom(y.getUUID()).forEach((other, score) -> {
                var t = new CompoundTag(); t.putString("with", other.toString()); t.putInt("score", score);
                var peer = level.getEntity(other);
                if (peer instanceof YukkuriEntity p) t.putString("name", p.getDisplayName().getString() + "#" + p.getId());
                scores.add(t);
            });
            relations.put("affinity", scores);
            var children = new ListTag();
            for (UUID child : FamilyData.get(level).children(y.getUUID())) children.add(StringTag.valueOf(child.toString()));
            relations.put("children", children);
            var players = new ListTag();
            y.life().personality().knownPlayers().forEach((player, memory) -> {
                var t = new CompoundTag(); t.putString("player", player.toString());
                t.putInt("trust", memory.trust()); t.putInt("fear", memory.fear()); t.putInt("interactions", memory.interactions());
                players.add(t);
            });
            relations.put("players", players);
        }
        root.put("relations", relations);
        return root;
    }

    private static void put(CompoundTag tag, String name, Object value) {
        if (value instanceof Boolean b) tag.putBoolean(name, b);
        else if (value instanceof Integer i) tag.putInt(name, i);
        else if (value instanceof Long l) tag.putLong(name, l);
        else if (value instanceof Float f) tag.putFloat(name, f);
        else if (value instanceof Double d) tag.putDouble(name, d);
        else tag.putString(name, String.valueOf(value));
    }

    /** Multi-line readable text for chat and logs. */
    public static java.util.List<String> describe(YukkuriEntity y) {
        var lines = new java.util.ArrayList<String>();
        lines.add(y.getType().getDescription().getString() + " #" + y.getId() + " " + y.getUUID() + " [" + identity(y).name() + "]");
        var snapshot = snapshot(y);
        for (String group : snapshot.getAllKeys()) {
            var tag = snapshot.getCompound(group);
            var line = new StringBuilder("  ").append(group).append(':');
            for (String name : tag.getAllKeys()) {
                var value = tag.get(name);
                if (value instanceof ListTag list) { if (!list.isEmpty()) line.append(' ').append(name).append('=').append(list.size()); continue; }
                String text = value.getAsString();
                if (text.isEmpty() || text.equals("none") || text.equals("0") && !group.equals("needs")) continue;
                line.append(' ').append(name).append('=').append(text);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    /** The one-line text over a head in the playground observe mode; built only from the register. */
    public static String label(YukkuriEntity y) {
        var text = new StringBuilder();
        text.append(y.life().isSleeping() ? "睡觉" : String.valueOf(get(y, StatusKey.LIFE_ACTIVITY))).append('/').append(get(y, StatusKey.ANIMATION));
        text.append(" 饥").append(get(y, StatusKey.NUTRITION)).append(" 困").append(get(y, StatusKey.SLEEPINESS));
        if ((int) get(y, StatusKey.BLADDER) >= 50) text.append(" 便").append(get(y, StatusKey.BLADDER));
        if ((int) get(y, StatusKey.WETNESS) >= 20) text.append(" 湿").append(get(y, StatusKey.WETNESS));
        if (y.family().adult()) text.append(" 成");
        else text.append(' ').append(y.family().stage() == io.github.longshou.mcyukkuri.family.FamilyLife.Stage.BABY ? "赤" : "幼")
                .append(get(y, StatusKey.GROWTH_PERCENT)).append('%');
        if ((boolean) get(y, StatusKey.PREGNANT)) text.append(" 孕").append(get(y, StatusKey.BROOD_PROGRESS)).append('%');
        if ((int) get(y, StatusKey.WOUND) > 0) text.append(" 伤").append(get(y, StatusKey.WOUND));
        if ((int) get(y, StatusKey.BURNS) > 0) text.append(" 灼").append(get(y, StatusKey.BURNS));
        if ((int) get(y, StatusKey.INFECTION) > 0) text.append(" 感染").append(get(y, StatusKey.INFECTION));
        if ((int) get(y, StatusKey.DIRT) > 0) text.append(" 脏").append(get(y, StatusKey.DIRT));
        if ((int) get(y, StatusKey.SCARS) > 0) text.append(" 疤").append(get(y, StatusKey.SCARS));
        text.append(" [").append(switch (identity(y)) {
            case HOUSE -> "家油"; case PET -> "宠物"; case OWNED -> "有主"; case SETTLER -> "聚落"; case STRAY -> "流浪"; case WILD -> "野生";
        }).append(']');
        String reaction = y.status().reactions().describe();
        if (!reaction.equals("none")) text.append(' ').append(reaction);
        if (y.getVehicle() != null) text.append(" 乘");
        return text.toString();
    }

    private YukkuriStatus() {}
}
