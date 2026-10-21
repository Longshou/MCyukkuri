package io.github.longshou.mcyukkuri.body;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.item.OperationToolItem;
import io.github.longshou.mcyukkuri.life.Facilities;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.entity.animal.Ocelot;
import net.minecraft.world.entity.animal.PolarBear;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.item.Items;

/** Pure impact geometry and deterministic core rules; only accepted health loss is settled. */
public final class InjuryModel {
    public enum Outcome { NONE, DYING, INSTANT, CORE_KILLED }

    public record Strike(DamageSource source, WoundSurface.Hit hit, WoundKind kind,
                         BodyPart part, boolean aimed) {}

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
    /** Wound kind from the source: blades, points, bites and thorns cut; blunt force dents; fire scorches; a hard landing splits the sole. */
    public static WoundKind kind(YukkuriEntity entity, DamageSource source, float amount) {
        if (underfoot(source) || source.is(DamageTypeTags.IS_FIRE)) return WoundKind.SCORCH;
        WoundKind kind = WoundKind.DENT;
        if (source.is(DamageTypeTags.IS_PROJECTILE) || source.is(DamageTypes.TRIDENT) || source.is(DamageTypeTags.IS_EXPLOSION)
                || source.is(DamageTypes.CACTUS) || source.is(DamageTypes.SWEET_BERRY_BUSH) || source.is(DamageTypes.STING)
                || source.is(DamageTypes.THORNS) || source.is(DamageTypes.STALAGMITE) || source.is(DamageTypes.FALLING_STALACTITE)) kind = WoundKind.TEAR;
        if (source.is(DamageTypeTags.IS_FALL) && amount >= 4) kind = WoundKind.TEAR;
        if (source.getEntity() instanceof LivingEntity attacker) {
            var item = attacker.getMainHandItem();
            String id = BuiltInRegistries.ITEM.getKey(item.getItem()).getPath();
            if (item.is(ItemTags.SWORDS) || item.is(ItemTags.AXES) || item.is(Items.TRIDENT)
                    || Set.of("care_awl", "cutting_knife", "pin_tool", "hanging_hook").contains(id)) kind = WoundKind.TEAR;
            if (id.equals("heated_awl")) kind = OperationToolItem.isHot(item, entity.level()) ? WoundKind.SCORCH : WoundKind.TEAR;
            // Teeth and claws cut: predator yukkuri, wolves, foxes, cats and spiders.
            boolean bite = source.getDirectEntity() == attacker && item.isEmpty()
                    && (attacker instanceof YukkuriEntity y && y.profile().dangerToYukkuri()
                    || attacker instanceof Wolf || attacker instanceof Fox
                    || attacker instanceof Cat || attacker instanceof Ocelot
                    || attacker instanceof PolarBear || attacker instanceof Spider);
            if (bite) kind = WoundKind.TEAR;
        }
        String pose = entity.contactPose();
        if (pose.equals("ROLL") || pose.equals("SLIDE")) kind = WoundKind.SCRATCH;
        if (pose.equals("GRIP")) kind = WoundKind.TEAR;
        if (source.is(Facilities.ABRASION)) kind = WoundKind.SCRATCH;
        return kind;
    }

    public static int severity(YukkuriEntity entity, DamageSource source, float amount) {
        int severity = Math.clamp((int) Math.ceil(amount / 2), 1, 3);
        if (source.getEntity() instanceof LivingEntity attacker
                && BuiltInRegistries.ITEM.getKey(attacker.getMainHandItem().getItem()).getPath().equals("heated_awl")
                && OperationToolItem.isHot(attacker.getMainHandItem(), entity.level())) severity = Math.max(2, severity);
        return severity;
    }

    /** Null when the blow finds no skin (the vanilla box accepted it but no surface triangle lies on the line). */
    public static Strike analyse(YukkuriEntity entity, DamageSource source, float amount,
                                 List<BodyWound> wounds, boolean bite) {
        var hit = underfoot(source) ? soleSite(entity) : WoundPlacement.hit(entity, source, false);
        boolean aimed = hit != null && !underfoot(source) && source.getDirectEntity() != null;
        if (hit == null) hit = WoundPlacement.hit(entity, source);
        if (hit == null) return null;
        var mesh = WoundSurface.get(entity.subspecies().assetId());
        var part = BodyPart.of(mesh, hit.triangle(), mesh.anchor(hit));
        return new Strike(source, hit, bite ? WoundKind.TEAR : kind(entity, source, amount), part, aimed);
    }

    /** Destruction leaves no usable ordinary food, including when it interrupts an existing dying state. */
    public static boolean destroysBody(DamageSource source, float loss, float before, float incoming) {
        boolean lethal = loss >= before;
        return source.is(DamageTypes.LAVA) && lethal
                || crush(source) && (lethal || incoming >= 8)
                || source.is(DamageTypeTags.IS_FALL) && incoming >= 10
                || source.is(DamageTypeTags.IS_EXPLOSION) && lethal && incoming >= 10
                || source.is(DamageTypeTags.IS_DROWNING) && lethal;
    }

    public static Outcome decide(YukkuriEntity entity, BodyHit hit, float loss) {
        if (loss <= 0) return Outcome.NONE;
        if (destroysBody(hit.source(), loss, hit.healthBefore(), hit.incoming())) return Outcome.INSTANT;
        var strike = hit.strike();
        if (strike == null) return Outcome.NONE;
        if (hit.coreExposedAtHit()) return Outcome.CORE_KILLED;
        boolean thick = (entity.subspeciesFlags() & SubspeciesLife.DEIBU_FLAG) != 0;
        float threshold = thick ? 4 : 2;
        if (hit.source().is(DamageTypes.TRIDENT) && loss >= threshold
                || hit.source().is(DamageTypeTags.IS_PROJECTILE) && loss >= (thick ? 8 : 6)) return Outcome.DYING;
        return Outcome.NONE;
    }

    /** A point on the underside where a hot floor, campfire or lava touches the sole. */
    public static WoundSurface.Hit soleSite(YukkuriEntity entity) {
        var mesh = WoundSurface.get(entity.subspecies().assetId());
        var b = mesh.bounds();
        float limit = b[1] + (b[4] - b[1]) * .2f;
        var low = new ArrayList<Integer>();
        for (int t = 0; t + 2 < mesh.skinCount; t += 3) if ((mesh.rest(t).y + mesh.rest(t + 1).y + mesh.rest(t + 2).y) / 3 <= limit) low.add(t);
        if (low.isEmpty()) return null;
        return new WoundSurface.Hit(low.get(entity.getRandom().nextInt(low.size())), .33f, .33f, 0);
    }
}
