package io.github.longshou.mcyukkuri.body;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.item.OperationToolItem;
import io.github.longshou.mcyukkuri.life.Facilities;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Pure impact geometry and deterministic core rules; only accepted health loss is settled. */
public final class InjuryModel {
    public enum Outcome { NONE, DYING, INSTANT, CORE_KILLED }

    public record Strike(DamageSource source, WoundSurface.Hit hit, WoundKind kind, WoundShape shape,
                         BodyPart part, boolean aimed) {}

    private InjuryModel() {}

    public static boolean underfoot(DamageSource s) {
        return s.is(DamageTypes.HOT_FLOOR) || s.is(DamageTypes.CAMPFIRE);
    }
    /** Damage from inside or from the air (hunger, cold, poison, wither): it wears the body down and leaves no wound. */
    public static boolean attrition(DamageSource s) {
        return s.is(DamageTypes.STARVE) || s.is(DamageTypeTags.IS_FREEZING) || s.is(DamageTypes.MAGIC) || s.is(DamageTypes.INDIRECT_MAGIC)
                || s.is(DamageTypes.WITHER) || s.is(DamageTypes.DRY_OUT);
    }
    public static boolean crush(DamageSource s) { return s.is(DamageTypes.FALLING_ANVIL) || s.is(DamageTypes.FALLING_BLOCK); }
    /** Resolve the actual mechanism before broad tags. Internal/system losses never invent a mechanical opening. */
    public static WoundShape shape(YukkuriEntity entity, DamageSource source) {
        if (underfoot(source)) return WoundShape.SOLE_BURN;
        if (source.is(DamageTypes.LIGHTNING_BOLT)) return WoundShape.NONE;
        if (source.is(DamageTypeTags.IS_FIRE)) return WoundShape.WHOLE_BURN;
        if (source.is(Facilities.ABRASION)) return WoundShape.ABRASION;
        boolean melee = source.is(DamageTypes.PLAYER_ATTACK) || source.is(DamageTypes.MOB_ATTACK) || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO);
        if (melee && entity.operations().settlingDamage()) return entity.operations().settlingShape();
        if (melee && source.getEntity() instanceof LivingEntity attacker) {
            var item = attacker.getMainHandItem();
            String id = BuiltInRegistries.ITEM.getKey(item.getItem()).getPath();
            if (item.is(ItemTags.SWORDS) || item.is(ItemTags.AXES) || id.equals("cutting_knife")
                    || id.equals("sterilizing_shears")) return WoundShape.CUT;
            if (item.is(Items.TRIDENT) || id.equals("care_awl") || id.equals("heated_awl")
                    || id.equals("pin_tool") || id.equals("hanging_hook")) return WoundShape.PUNCTURE;
            // Only a known biting attacker supplies bite marks; ordinary mob_attack alone does not.
            boolean bite = source.getDirectEntity() == attacker && item.isEmpty()
                    && (attacker instanceof YukkuriEntity y && y.profile().dangerToYukkuri()
                    || attacker instanceof Wolf || attacker instanceof Fox
                    || attacker instanceof Cat || attacker instanceof Ocelot
                    || attacker instanceof PolarBear);
            return bite ? WoundShape.BITE : WoundShape.BLUNT;
        }
        if (source.is(DamageTypes.ARROW) || source.is(DamageTypes.TRIDENT) || source.is(DamageTypes.STING)) return WoundShape.PUNCTURE;
        if (source.is(DamageTypes.CACTUS)) return WoundShape.FINE_PRICK;
        if (source.is(DamageTypes.SWEET_BERRY_BUSH)) return WoundShape.ABRASION;
        if (source.is(DamageTypes.STALAGMITE) || source.is(DamageTypes.FALLING_STALACTITE)) return WoundShape.CONE;
        if (source.is(DamageTypeTags.IS_EXPLOSION) || source.is(DamageTypes.FIREWORKS) || source.is(DamageTypes.BAD_RESPAWN_POINT)) return WoundShape.BLAST;
        if (source.is(DamageTypes.FALL) || source.is(DamageTypes.FLY_INTO_WALL) || crush(source)
                || source.is(DamageTypes.MOB_PROJECTILE) || source.is(DamageTypes.WITHER_SKULL) || source.is(DamageTypes.WIND_CHARGE)) return WoundShape.BLUNT;
        // Cramming has no source position or contacting entity in vanilla. Retain the real health loss without guessing a site.
        return WoundShape.NONE;
    }
    public static WoundKind kind(YukkuriEntity entity, DamageSource source, float amount) {
        return shape(entity, source).kind();
    }

    public static int severity(YukkuriEntity entity, DamageSource source, float amount) {
        int severity = Math.clamp((int) Math.ceil(amount / 2), 1, 3);
        if (source.getEntity() instanceof LivingEntity attacker
                && source.getDirectEntity() == attacker
                && (!entity.operations().settlingDamage() || entity.operations().settlingShape() == WoundShape.PUNCTURE)
                && BuiltInRegistries.ITEM.getKey(attacker.getMainHandItem().getItem()).getPath().equals("heated_awl")
                && OperationToolItem.isHot(attacker.getMainHandItem(), entity.level())) severity = Math.max(2, severity);
        return severity;
    }

    /** Null when the blow finds no skin (the vanilla box accepted it but no surface triangle lies on the line). */
    public static Strike analyse(YukkuriEntity entity, DamageSource source, float amount,
                                 List<BodyWound> wounds, boolean bite) {
        WoundShape shape = bite ? WoundShape.BITE : shape(entity, source);
        if (shape == WoundShape.NONE) return null;
        boolean plant=source.is(DamageTypes.CACTUS)||source.is(DamageTypes.SWEET_BERRY_BUSH);
        var hit = underfoot(source) ? soleSite(entity) : plant ? plantSite(entity,source) : WoundPlacement.hit(entity, source, false);
        boolean aimed = hit != null && !underfoot(source) && !plant && source.getDirectEntity() != null;
        if(plant&&hit==null)return null;
        if (hit == null) hit = WoundPlacement.hit(entity, source);
        if (hit == null) return null;
        var mesh = WoundSurface.get(entity.subspecies().assetId());
        var part = BodyPart.of(mesh, hit.triangle(), mesh.anchor(hit));
        return new Strike(source, hit, shape.kind(), shape, part, aimed);
    }

    /** Contact extent is one real plant block, not invented individual thorn strikes. */
    private static WoundSurface.Hit plantSite(YukkuriEntity entity,DamageSource source) {
        var box=entity.getBoundingBox();var center=box.getCenter();Vec3 closest=null;double distance=Double.MAX_VALUE;
        var plant=source.is(DamageTypes.CACTUS)?Blocks.CACTUS:Blocks.SWEET_BERRY_BUSH;
        for(var pos:BlockPos.betweenClosed(BlockPos.containing(box.minX,box.minY,box.minZ),BlockPos.containing(box.maxX,box.maxY,box.maxZ))){
            if(!entity.level().getBlockState(pos).is(plant)||!box.intersects(pos.getX(),pos.getY(),pos.getZ(),pos.getX()+1,pos.getY()+1,pos.getZ()+1))continue;
            double y=(Math.max(box.minY,pos.getY())+Math.min(box.maxY,pos.getY()+1))/2;
            var contact=new Vec3(pos.getX()+.5,y,pos.getZ()+.5);double d=contact.distanceToSqr(center);
            if(d<distance){closest=contact;distance=d;}
        }
        return closest==null?null:WoundPlacement.contact(entity,closest);
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
        if (strike.shape() == WoundShape.PUNCTURE && (hit.source().is(DamageTypes.TRIDENT) && loss >= threshold
                || hit.source().is(DamageTypeTags.IS_PROJECTILE) && loss >= (thick ? 8 : 6))) return Outcome.DYING;
        return Outcome.NONE;
    }

    /** A point on the underside where a hot floor or campfire touches the sole. */
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
