package io.github.longshou.mcyukkuri.body;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.List;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;

/**
 * Where a blow lands, what wound it makes and whether it reaches the centre filling (docs/INJURIES_AND_REMAINS.md, "受伤模型").
 *
 * <p>Komicolle 11518802: "不傷及中樞餡都不會造成饅的死亡". Ordinary blows wear the skin and the filling; the core is reached only
 * through a region that already has a deep wound, by a blow that goes straight through (trident, a hard arrow, a very heavy
 * hit), by crushing, lava, drowning (the body dissolves) or long burning. The numbers are project design.
 */
public final class InjuryModel {
    public enum Outcome { NONE, DYING, INSTANT }

    /** A blow analysed before vanilla applies it; consumed when its wound is recorded. */
    public static final class Strike {
        public final DamageSource source;
        public final float amount;
        public final WoundSurface.Hit hit;
        public final WoundKind kind;
        public final int severity;
        public final BodyPart part;
        /** The region already had a deep wound before this blow. */
        public final boolean deepBefore;
        public Outcome outcome = Outcome.NONE;
        public double chance;
        /** The blow would have killed but met an unhurt core: it worsens the wound and squeezes filling instead. */
        public boolean blocked;
        Strike(DamageSource source, float amount, WoundSurface.Hit hit, WoundKind kind, int severity, BodyPart part, boolean deepBefore) {
            this.source = source; this.amount = amount; this.hit = hit; this.kind = kind; this.severity = severity; this.part = part; this.deepBefore = deepBefore;
        }
    }

    private InjuryModel() {}

    public static boolean underfoot(DamageSource s) {
        return s.is(DamageTypes.HOT_FLOOR) || s.is(DamageTypes.CAMPFIRE) || s.is(DamageTypes.IN_FIRE) || s.is(DamageTypes.LAVA);
    }
    /** Damage from inside or from the air (hunger, cold, poison, wither): it wears the body down and leaves no wound. */
    public static boolean attrition(DamageSource s) {
        return s.is(DamageTypes.STARVE) || s.is(DamageTypeTags.IS_FREEZING) || s.is(DamageTypes.MAGIC) || s.is(DamageTypes.INDIRECT_MAGIC)
                || s.is(DamageTypes.WITHER) || s.is(DamageTypes.DRY_OUT);
    }
    public static boolean crush(DamageSource s) { return s.is(DamageTypes.FALLING_ANVIL) || s.is(DamageTypes.FALLING_BLOCK); }
    /** Goes straight through intact skin: a trident, a hard arrow or an extremely heavy blow. */
    public static boolean piercing(DamageSource s, float amount) {
        return s.is(DamageTypes.TRIDENT) || s.is(DamageTypeTags.IS_PROJECTILE) && amount >= 6 || amount >= 9 && !s.is(DamageTypeTags.IS_FIRE);
    }

    /** Wound kind from the source: blades, points, bites and thorns cut; blunt force dents; fire scorches; a hard landing splits the sole. */
    public static WoundKind kind(YukkuriEntity entity, DamageSource source, float amount) {
        if (source.is(DamageTypeTags.IS_FIRE)) return WoundKind.SCORCH;
        WoundKind kind = WoundKind.DENT;
        if (source.is(DamageTypeTags.IS_PROJECTILE) || source.is(DamageTypes.TRIDENT) || source.is(DamageTypeTags.IS_EXPLOSION)
                || source.is(DamageTypes.CACTUS) || source.is(DamageTypes.SWEET_BERRY_BUSH) || source.is(DamageTypes.STING)
                || source.is(DamageTypes.THORNS) || source.is(DamageTypes.STALAGMITE) || source.is(DamageTypes.FALLING_STALACTITE)) kind = WoundKind.TEAR;
        if (source.is(DamageTypeTags.IS_FALL) && amount >= 4) kind = WoundKind.TEAR;
        if (source.getEntity() instanceof net.minecraft.world.entity.LivingEntity attacker) {
            var item = attacker.getMainHandItem();
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).getPath();
            if (item.is(net.minecraft.tags.ItemTags.SWORDS) || item.is(net.minecraft.tags.ItemTags.AXES) || item.is(net.minecraft.world.item.Items.TRIDENT)
                    || java.util.Set.of("care_awl", "cutting_knife", "pin_tool", "hanging_hook").contains(id)) kind = WoundKind.TEAR;
            if (id.equals("heated_awl")) kind = io.github.longshou.mcyukkuri.item.OperationToolItem.isHot(item, entity.level()) ? WoundKind.SCORCH : WoundKind.TEAR;
            // Teeth and claws cut: predator yukkuri, wolves, foxes, cats and spiders.
            boolean bite = source.getDirectEntity() == attacker && item.isEmpty()
                    && (attacker instanceof YukkuriEntity y && y.profile().dangerToYukkuri()
                    || attacker instanceof net.minecraft.world.entity.animal.Wolf || attacker instanceof net.minecraft.world.entity.animal.Fox
                    || attacker instanceof net.minecraft.world.entity.animal.Cat || attacker instanceof net.minecraft.world.entity.animal.Ocelot
                    || attacker instanceof net.minecraft.world.entity.animal.PolarBear || attacker instanceof net.minecraft.world.entity.monster.Spider);
            if (bite) kind = WoundKind.TEAR;
            // A sanction bite (headwear.Sanction, bullying): the teeth tear the skin a little.
            if (attacker instanceof YukkuriEntity y && y.sanction() != null && y.sanction().biting()) kind = WoundKind.TEAR;
        }
        String pose = entity.contactPose();
        if (pose.equals("ROLL") || pose.equals("SLIDE")) kind = WoundKind.SCRATCH;
        if (pose.equals("GRIP")) kind = WoundKind.TEAR;
        if (source.is(io.github.longshou.mcyukkuri.life.Facilities.ABRASION)) kind = WoundKind.SCRATCH;
        return kind;
    }

    public static int severity(YukkuriEntity entity, DamageSource source, float amount) {
        int severity = Math.clamp((int) Math.ceil(amount / 2), 1, 3);
        if (source.getEntity() instanceof net.minecraft.world.entity.LivingEntity attacker
                && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(attacker.getMainHandItem().getItem()).getPath().equals("heated_awl")
                && io.github.longshou.mcyukkuri.item.OperationToolItem.isHot(attacker.getMainHandItem(), entity.level())) severity = Math.max(2, severity);
        return severity;
    }

    /** Null when the blow finds no skin (the vanilla box accepted it but no surface triangle lies on the line). */
    public static Strike analyse(YukkuriEntity entity, DamageSource source, float amount, List<BodyWound> wounds) {
        var hit = underfoot(source) ? soleSite(entity) : WoundPlacement.hit(entity, source);
        if (hit == null) return null;
        var mesh = WoundSurface.get(entity.subspecies().assetId());
        var anchor = mesh.anchor(hit);
        var part = BodyPart.of(mesh, hit.triangle(), anchor);
        boolean deep = wounds.stream().anyMatch(w -> mesh.valid(w) && BodySigns.deep(w) && BodyPart.of(mesh, w) == part);
        return new Strike(source, amount, hit, kind(entity, source, amount), severity(entity, source, amount), part, deep);
    }

    /** Chance that an ordinary cut or heavy blow through a deep wound reaches the core: more likely as the filling runs low. */
    public static double throughWound(int filling, boolean fire) {
        return fire ? .08 + .02 * (12 - filling) : .25 + .05 * (12 - filling);
    }

    /** Decide the outcome of a blow on a body whose core is still whole. */
    public static void decide(YukkuriEntity entity, Strike s, float health, int filling) {
        boolean lethal = s.amount >= health;
        var src = s.source;
        if (lethal && (src.is(DamageTypes.LAVA) || crush(src) || src.is(DamageTypeTags.IS_EXPLOSION) && s.amount >= 10)
                || crush(src) && s.amount >= 8 || src.is(DamageTypeTags.IS_FALL) && s.amount >= 10) {
            s.outcome = Outcome.INSTANT; s.chance = 1; return;
        }
        boolean fire = src.is(DamageTypeTags.IS_FIRE);
        double chance = 0;
        if (!fire && piercing(src, s.amount)) chance = s.deepBefore ? 1 : .5;
        else if (s.deepBefore && (fire || s.kind==WoundKind.TEAR || s.amount >= 4)) chance = throughWound(filling, fire);
        // DOS bodies are thick: damage hardly gets through to the centre (wiki dos.html: 中枢餡までダメージを通しにくい).
        if ((entity.subspeciesFlags() & io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.DEIBU_FLAG) != 0) chance *= .5;
        s.chance = Math.min(chance, chance >= 1 ? 1 : .9);
        if (s.chance > 0 && entity.getRandom().nextDouble() < s.chance) s.outcome = Outcome.DYING;
    }

    /** A point on the underside where a hot floor, campfire or lava touches the sole. */
    public static WoundSurface.Hit soleSite(YukkuriEntity entity) {
        var mesh = WoundSurface.get(entity.subspecies().assetId());
        var b = mesh.bounds();
        float limit = b[1] + (b[4] - b[1]) * .2f;
        var low = new java.util.ArrayList<Integer>();
        for (int t = 0; t + 2 < mesh.skinCount; t += 3) if ((mesh.rest(t).y + mesh.rest(t + 1).y + mesh.rest(t + 2).y) / 3 <= limit) low.add(t);
        if (low.isEmpty()) return null;
        return new WoundSurface.Hit(low.get(entity.getRandom().nextInt(low.size())), .33f, .33f, 0);
    }
}
