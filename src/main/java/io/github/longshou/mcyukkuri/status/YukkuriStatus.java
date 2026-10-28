package io.github.longshou.mcyukkuri.status;

import net.minecraft.world.entity.Entity;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import io.github.longshou.mcyukkuri.mind.MindLife;
import io.github.longshou.mcyukkuri.settlement.HabitatLife;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import java.util.ArrayList;
import java.util.List;

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
        if (y.profile().houseBody()) return Identity.HOUSE;
        if (y.pet().isPet()) return Identity.PET;
        if (y.pet().owner() != null) return Identity.OWNED;
        var residence = y.habitat().residence();
        // A grown wild individual without a recognised home (HabitatLife.Residence.VAGRANT) is a stray, whatever group record it still has.
        if (residence == HabitatLife.Residence.VAGRANT) return Identity.STRAY;
        if (y.settlement().groupId() != null) return Identity.SETTLER;
        boolean homed = y.habitat().hasMemory() || y.life().home() != null
                || residence == HabitatLife.Residence.DEPENDENT;
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
    public static void relate(YukkuriEntity from, Entity to, int score) {
        var data = YukkuriWorldData.get((ServerLevel) from.level());
        data.relate(from.getUUID(), to.getUUID(), Math.clamp(score, -100, 100) - data.affinity(from.getUUID(), to.getUUID()));
    }

    static String homeKind(YukkuriEntity y) {
        var shelter = y.habitat().shelter();
        if (shelter != null) return shelter.kind() == ShelterBlock.Kind.CARDBOARD ? "cardboard" : "burrow";
        if (y.habitat().hasMemory()) return "shelter";
        if (y.profile().houseBody() && y.house().homeStand() != null) return "house_nest";
        if (y.life().home() != null) return y.life().naturalHome() ? "cover" : "nest";
        if (y.habitat().vagrant() && y.habitat().digProgress() > 0) return "digging";
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
            if (value == null) continue;
            var group = root.getCompound(key.group());
            put(group, key.shortName(), value);
            root.put(key.group(), group);
        }
        var wounds = new ListTag();
        for (var w : y.body().wounds()) {
            var t = new CompoundTag();
            t.putString("kind", w.kind().id()); t.putInt("severity", w.severity()); t.putInt("triangle", w.triangle());
            wounds.add(t);
        }
        var health = root.getCompound("health"); health.put("wounds", wounds); root.put("health", health);
        var marks = root.getCompound("marks");
        var scars = new ListTag();
        for (var s : y.status().marks().scars()) { var t = new CompoundTag(); t.putInt("triangle", s.triangle()); t.putInt("severity", s.severity()); scars.add(t); }
        marks.put("scar_list", scars); root.put("marks", marks);
        var relations = root.getCompound("relations");
        if (y.level() instanceof ServerLevel level) {
            var objects = new ListTag();
            YukkuriWorldData.get(level).relationsFrom(y.getUUID()).forEach((other, relation) -> {
                var t = new CompoundTag(); t.putString("with", other.toString());
                t.putInt("closeness", relation.closeness()); t.putInt("fear", relation.fear()); t.putInt("fear_band", relation.fearBand());
                t.putLong("last_interaction", relation.lastInteraction());
                var peer = level.getEntity(other);
                if (peer != null) t.putString("name", peer.getDisplayName().getString() + "#" + peer.getId());
                objects.add(t);
            });
            relations.put("objects", objects);
            var children = new ListTag();
            for (UUID child : FamilyData.get(level).children(y.getUUID())) children.add(StringTag.valueOf(child.toString()));
            relations.put("children", children);
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
    public static List<String> describe(YukkuriEntity y) {
        var lines = new ArrayList<String>();
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
        if (!y.mind().givenName().isEmpty()) text.append(y.mind().givenName()).append(' ');
        text.append(get(y, StatusKey.BEHAVIOR)).append('/').append(get(y, StatusKey.ANIMATION));
        text.append(" 营养").append(get(y, StatusKey.NUTRITION)).append(" 困").append(get(y, StatusKey.SLEEPINESS));
        text.append(" 生命").append(get(y, StatusKey.HEALTH)).append('/').append(get(y, StatusKey.MAX_HEALTH));
        if (y.agitation().active()) text.append(" 状态:").append(switch (y.agitation().phase()) {
            case ONSET, RELIEF, HEAT -> "发情期";
            case HEAT_RELIEVED -> "素鸡立完成";
            case HEAT_UNMET -> "发情未解";
            case RECOVER -> "恢复";
            case ALICE_WATCH -> "攻击魔搜寻";
            case ALICE_APPROACH -> "攻击魔追击";
            case ALICE_ACT -> "素鸡立中";
            case HELD -> "被压制";
            default -> "平静";
        });
        if (y.family().inBreedingPeriod()) text.append(" 发情余").append((y.family().sexDrive()+19)/20).append("秒");
        if ((int) get(y, StatusKey.BLADDER) >= 50) text.append(" 便").append(get(y, StatusKey.BLADDER));
        if ((int) get(y, StatusKey.WETNESS) >= 20) text.append(" 湿").append(get(y, StatusKey.WETNESS));
        text.append(' ').append(switch(String.valueOf(get(y,StatusKey.STAGE))) {
            case "baby" -> "赤油"; case "juvenile" -> "子油"; case "subadult" -> "亚成体";
            case "elder" -> "老年"; default -> "成体";
        });
        if(!y.family().adult())text.append(" 距换阶段").append((y.family().stageRemainingTicks()+19)/20).append("秒");
        text.append(" 不安").append(get(y,StatusKey.UNEASE));
        if ((boolean) get(y, StatusKey.PREGNANT)) text.append(" 孕").append(get(y, StatusKey.BROOD_PROGRESS)).append('%');
        if ((boolean) get(y, StatusKey.AFTERMATH)) text.append(" 事后茎负担");
        if ((int) get(y, StatusKey.WOUND) > 0) text.append(" 伤").append(get(y, StatusKey.WOUND));
        if ((int) get(y, StatusKey.BURNS) > 0) text.append(" 灼").append(get(y, StatusKey.BURNS));
        if ((int) get(y, StatusKey.INFECTION) > 0) text.append(" 感染").append(get(y, StatusKey.INFECTION));
        if ((int) get(y, StatusKey.DIRT) > 0) text.append(" 脏").append(get(y, StatusKey.DIRT));
        if ((int) get(y, StatusKey.SCARS) > 0) text.append(" 疤").append(get(y, StatusKey.SCARS));
        var mind = y.mind();
        if (mind.nonYukkuri()) text.append(" 非油症");
        else if (mind.unease() >= MindLife.SWEETENED) text.append(" 甜化");
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
