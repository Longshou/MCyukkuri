package io.github.longshou.mcyukkuri.rare;

import io.github.longshou.mcyukkuri.dialogue.SpeechQuirk;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Per-individual traits of the Myon (Youmu) kind, komicolle 40720 No.11518905: "身體素質僅次於攻击魔，勇敢但智能也比較低",
 * one of two mutually exclusive tics, and a stick carried as a weapon called 觀樓劍 (Kanroken).
 *
 * <ul>
 *   <li>The tic is chosen once, when the individual first ticks on the server (spawn egg, natural spawn, birth or
 *       summon), and saved; a Myon born to a Myon parent takes that parent's tic three times in four [project rule].
 *   <li>The Kanroken is a real stick: adults start with one, the young and anyone who lost theirs pick up a dropped stick
 *       (consuming the item) or now and then break one off near trees. It drops as an ordinary stick on death or when a
 *       hard blow knocks it out of the mouth. It is shown in the mouth whenever the mouth is free (client).
 *   <li>Bravery: a healthy Myon does not bolt when hit or when a predator or attacker comes; it turns on the attacker and
 *       strikes with the stick (2 damage, 1 bare-mouthed), protecting kin. Below 40% health it flees like anyone else.
 * </ul>
 * Physique (14 health, 0.20 speed) is in {@link #attributes}; lower innate cognition and quicker foraging are applied by
 * {@code cognition/Cognition} and {@code settlement/ForageGoal} through {@link #cognitionScale} and {@link #forageScale}.
 */
public final class MyonLife {
    /** Innate cognition of a natural Myon is this fraction of the usual draw ("智能也比較低"; project number). */
    public static final double COGNITION_SCALE = .6;
    /** Forage searches come this much sooner and reach this much farther (plan 4.2 "采集效率更高"; project number). */
    public static final double FORAGE_SCALE = 1.5;
    private static final int STRIKE_COOLDOWN = 24, FOE_MEMORY = 200, STICK_SEARCH = 200;
    private final YukkuriEntity entity;
    private String quirk;
    private boolean kanroken, initialized;
    private int strikeCooldown, searchCooldown, foeTicks;
    private UUID foe;

    public MyonLife(YukkuriEntity entity) { this.entity = entity; }

    public static AttributeSupplier.Builder attributes(YukkuriVariant variant) {
        var builder = YukkuriEntity.createAttributes();
        if (variant == YukkuriVariant.MYON) builder.add(Attributes.MAX_HEALTH, 14.0).add(Attributes.MOVEMENT_SPEED, .20);
        return builder;
    }
    public static double cognitionScale(YukkuriEntity entity) { return entity.getVariant() == YukkuriVariant.MYON ? COGNITION_SCALE : 1; }
    public static double forageScale(YukkuriEntity entity) { return entity.getVariant() == YukkuriVariant.MYON ? FORAGE_SCALE : 1; }

    private boolean myon() { return entity.getVariant() == YukkuriVariant.MYON; }
    /** The saved tic of a species with tics; null otherwise. Never derived from the UUID once saved. */
    public String quirk() {
        if (quirk == null && !entity.level().isClientSide) initialize();
        return quirk;
    }
    public boolean hasKanroken() { return kanroken; }
    public void setKanroken(boolean value) { kanroken = value && myon(); }
    public boolean setQuirk(String value) {
        if (!SpeechQuirk.valid(entity.getVariant().dialogueSpecies(), value)) return false;
        quirk = value; initialized = true; return true;
    }
    public boolean fighting() { return foe != null && foeTicks > 0; }

    private void initialize() {
        if (initialized) return;
        initialized = true;
        String species = entity.getVariant().dialogueSpecies();
        if (SpeechQuirk.has(species) && quirk == null) {
            String inherited = parentQuirk(species);
            quirk = inherited != null && entity.getRandom().nextInt(4) != 0 ? inherited : SpeechQuirk.choose(species, entity.getRandom());
        }
        // Adults arrive armed; the young find their first stick themselves.
        if (myon() && entity.family().adult()) kanroken = true;
    }
    private String parentQuirk(String species) {
        if (!(entity.level() instanceof ServerLevel level)) return null;
        var person = io.github.longshou.mcyukkuri.family.FamilyData.get(level).person(entity.getUUID());
        if (person == null) return null;
        for (UUID parent : new UUID[] {person.mother, person.father})
            if (parent != null && level.getEntity(parent) instanceof YukkuriEntity p && p.getVariant().dialogueSpecies().equals(species))
                return p.myon().quirk();
        return null;
    }

    public void tick() {
        if (entity.level().isClientSide || !entity.isAlive()) return;
        if (!initialized) initialize();
        if (!myon()) return;
        if (strikeCooldown > 0) strikeCooldown--;
        if (foeTicks > 0 && --foeTicks == 0) foe = null;
        if (searchCooldown > 0) searchCooldown--;
        else if (!kanroken && !entity.family().stage().equals(io.github.longshou.mcyukkuri.family.FamilyLife.Stage.BABY)) {
            searchCooldown = STICK_SEARCH;
            findStick();
        }
    }

    /** Pick up a dropped stick in reach, or (rarely, near trees) break one off: the new Kanroken. */
    private void findStick() {
        if (entity.isNoAi() || entity.life().isSleeping() || !entity.getMouthItem().isEmpty() || entity.isPanicking()) return;
        var near = entity.level().getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(1.2),
                item -> item.isAlive() && item.getItem().is(Items.STICK));
        if (!near.isEmpty()) {
            near.getFirst().getItem().shrink(1);
            if (near.getFirst().getItem().isEmpty()) near.getFirst().discard();
            armed();
            return;
        }
        var pos = entity.blockPosition();
        boolean trees = false;
        for (var p : net.minecraft.core.BlockPos.betweenClosed(pos.offset(-3, -1, -3), pos.offset(3, 3, 3)))
            if (entity.level().getBlockState(p).is(BlockTags.LEAVES) || entity.level().getBlockState(p).is(BlockTags.LOGS)) { trees = true; break; }
        if (trees && entity.getRandom().nextInt(6) == 0) armed();
    }
    private void armed() {
        kanroken = true;
        entity.showReaction("happy", "kanroken_found", 40);
        entity.setPersistenceRequired();
    }

    /** A hit: a healthy Myon turns on a living attacker instead of bolting. Returns true when the panic is cancelled. */
    public boolean standGround(DamageSource source) {
        if (!myon() || !(source.getEntity() instanceof LivingEntity attacker) || attacker == entity || !brave()) return false;
        if (kanroken && entity.getRandom().nextInt(8) == 0 && source.getEntity() instanceof Player) { drop(); return false; }
        engage(attacker);
        return true;
    }
    /** A predator or attacker frightens the group: a healthy Myon confronts it instead. */
    public boolean confront(YukkuriEntity threat) {
        if (!myon() || threat == null || !brave()) return false;
        engage(threat);
        return true;
    }
    private boolean brave() {
        return entity.getHealth() > entity.getMaxHealth() * .4f && !entity.isOnFire() && !entity.isInWaterOrBubble()
                && entity.family().stage() != io.github.longshou.mcyukkuri.family.FamilyLife.Stage.BABY && !entity.body().dying();
    }
    private void engage(LivingEntity attacker) {
        boolean fresh = foe == null || !foe.equals(attacker.getUUID());
        foe = attacker.getUUID(); foeTicks = FOE_MEMORY;
        if (fresh) entity.showReaction("angry", kanroken ? "kanroken_draw" : "afraid", 40);
    }
    private LivingEntity foeEntity() {
        if (foe == null || !(entity.level() instanceof ServerLevel level)) return null;
        var found = level.getEntity(foe);
        return found instanceof LivingEntity living && living.isAlive() && living.distanceToSqr(entity) < 256 ? living : null;
    }
    /** Strike when touching the foe. Damage is the stick's (2) or a bare body slam (1); project numbers. */
    private void strike(LivingEntity target) {
        if (strikeCooldown > 0 || entity.distanceToSqr(target) > 2.2) return;
        strikeCooldown = STRIKE_COOLDOWN;
        entity.lookAt(target, 30, 30);
        target.hurt(entity.damageSources().mobAttack(entity), kanroken ? 2 : 1);
        double dx = target.getX() - entity.getX(), dz = target.getZ() - entity.getZ(), d = Math.max(.1, Math.sqrt(dx * dx + dz * dz));
        target.knockback(.25, -dx / d, -dz / d);
        if (entity.getRandom().nextInt(3) == 0) entity.speak("kanroken_draw");
    }
    public void drop() {
        if (!kanroken) return;
        kanroken = false;
        entity.spawnAtLocation(new ItemStack(Items.STICK));
        entity.showReaction("teary", "kanroken_lost", 50);
        searchCooldown = STICK_SEARCH * 3;
    }
    public void died() {
        if (kanroken && entity.level() instanceof ServerLevel) { kanroken = false; entity.spawnAtLocation(new ItemStack(Items.STICK)); }
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        if (quirk != null) tag.putString("SpeechQuirk", quirk);
        tag.putBoolean("Kanroken", kanroken); tag.putBoolean("Initialized", initialized);
        return tag;
    }
    public void load(CompoundTag tag) {
        String saved = tag.getString("SpeechQuirk");
        quirk = SpeechQuirk.valid(entity.getVariant().dialogueSpecies(), saved) ? saved : null;
        kanroken = tag.getBoolean("Kanroken") && myon();
        initialized = tag.getBoolean("Initialized");
    }

    public Goal goal() { return new FightGoal(); }

    /** Walk at the remembered foe and strike; ends when the foe is gone, the memory fades or courage fails. */
    private final class FightGoal extends Goal {
        FightGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return myon() && fighting() && brave() && foeEntity() != null && !entity.isNoAi(); }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() {
            var target = foeEntity();
            if (target == null) return;
            entity.getLookControl().setLookAt(target, 30, 30);
            if (entity.distanceToSqr(target) > 2.2) {
                if (entity.tickCount % 10 == 0) entity.getNavigation().moveTo(target, 1.5);
            } else { entity.getNavigation().stop(); strike(target); }
        }
        @Override public void stop() { entity.getNavigation().stop(); }
    }

    /** Kept for the status register and tests: who the Myon is fighting, or "none". */
    public String foeDescription() {
        Entity found = foeEntity();
        return found == null ? "none" : found.getType().builtInRegistryHolder().key().location().getPath();
    }
}
