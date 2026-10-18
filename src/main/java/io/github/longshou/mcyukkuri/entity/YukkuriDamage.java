package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.entity.component.Interrupt;
import io.github.longshou.mcyukkuri.entity.component.InterruptContext;
import io.github.longshou.mcyukkuri.entity.component.InterruptPhase;
import io.github.longshou.mcyukkuri.event.HomeClaim;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriLife;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.social.GroupInteractionGoal;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** One synchronous hurt/death transaction owner; public entity overrides delegate once. */
final class YukkuriDamage {
    private final YukkuriEntity owner;
    YukkuriDamage(YukkuriEntity owner) { this.owner = owner; }
    private boolean deathRecorded;
    private boolean discardCleanupStarted;
    private boolean sukkiriExhaustion;
    private int exhaustionDamageDepth;
    private boolean boutDamage;
    private float pendingHitHealth;
    void markSukkiriExhaustion() { sukkiriExhaustion = true; }
    boolean hurtInBout(DamageSource source,float amount) {
        boutDamage=true;try{return hurt(source,amount);}finally{boutDamage=false;}
    }
    boolean hurt(DamageSource source, float amount) {
        exhaustionDamageDepth++;
        try {
            if (owner.shellRetraction().protects(source)) amount *= .5f; // Actual withdrawn shell takes half a blow [original]
            float incoming = amount;
            if (!owner.level().isClientSide && owner.body() != null) {
                amount = owner.body().guardCore(source, amount); // the centre filling keeps a body with an unhurt core alive (komicolle 11518802)
                // A blow turned aside by the core still lands (flinch, knockback, wound) without taking the last health.
                if (amount <= 0 && !owner.body().absorbedBlow()) return false;
            }
            float before=owner.getHealth();
            float previousHit=pendingHitHealth;
            pendingHitHealth=before;
            boolean damaged;
            try { damaged = owner.hurtBase(source, amount); }
            finally { pendingHitHealth=previousHit; }
            if (damaged && amount < incoming) owner.rememberHit(incoming); // the cooldown remembers the real blow
            // Soaking is attrition too: panicking every time the rain stung (every 5 s, panic 5.6 s) kept soaked settlers from ever reaching cover.
            // A wound's own bleeding, infection or mould (body.tick) is not an attack: no startle, no panic, nothing interrupted.
            // Before this an unbandaged wound sent its owner into a 100 tick panic every ten seconds and cancelled any comforting kin.
            if (damaged && !owner.level().isClientSide && owner.isAlive() && (owner.body().internalDamage() || YukkuriLife.isSoaking(source))) return damaged;
            if (damaged && !owner.level().isClientSide && owner.isAlive()) {
                var interruption = InterruptContext.hurt(source);
                owner.interrupt(interruption, InterruptPhase.BEFORE_INJURY);
                var struckPart=owner.body().strikePart();
                owner.body().injured(source,before-owner.getHealth());
                owner.headwear().struck(source,incoming,struckPart);
                owner.status().hurt(source);
                owner.interrupt(interruption, InterruptPhase.AFTER_STATUS_HURT);
                if(!boutDamage&&source.getEntity() instanceof YukkuriEntity attacker)GroupInteractionGoal.witnessedAttack(owner,attacker);
                owner.operations().damaged(source);
                owner.settlement().attackedBy(source,false);
                if(source.getEntity() instanceof Player attackerPlayer)HomeClaim.attacked(owner,attackerPlayer);
                owner.interrupt(interruption, InterruptPhase.AFTER_ATTACK_NOTIFICATIONS);
                if(!boutDamage)owner.life().personality().attacked(source.getEntity()==null?null:source.getEntity().getUUID(),source.getEntity() instanceof Player);
                owner.setPanicTicks(112);
                if (owner.myon().standGround(source)) owner.setPanicTicks(0); // a healthy Myon fights back instead of fleeing
                owner.directNavigation().stop();
                if (owner.shellRetracts()) owner.shellRetraction().tick(owner.panicTicksValue());
                else owner.setAnimation(YukkuriAction.STARTLE, true);
            }
            return damaged;
        } finally {
            // Covers guardCore/soaking early returns too; nested hurt must not clear an outer marker.
            if (--exhaustionDamageDepth == 0) sukkiriExhaustion = false;
        }
    }
    void die(DamageSource source) {
        if (!owner.level().isClientSide) {
            var interruption = InterruptContext.death(source, false, false);
            owner.interrupt(interruption, InterruptPhase.DEATH_PREPARE);
            if(!deathRecorded)owner.body().fatalInjury(source,pendingHitHealth);
            if(!deathRecorded)owner.settlement().attackedBy(source,true);
            owner.interrupt(interruption, InterruptPhase.DEATH_CLEANUP);
            if(!deathRecorded) {
                // Capture the active burden before family.died cancels and clears maternal gestation.
                var pregnancy=owner.family().brood();
                boolean stemDepletion=source.is(DamageTypes.STARVE)&&owner.life().nutrition()==0
                        &&pregnancy!=null&&!pregnancy.cancelled&&!pregnancy.complete()
                        &&pregnancy.kind==FamilyData.BirthKind.STEM&&pregnancy.incidentSource!=null;
                boolean sukkiriExhaustion=this.sukkiriExhaustion;
                deathRecorded = true;
                owner.interrupt(InterruptContext.death(source, stemDepletion, sukkiriExhaustion), InterruptPhase.DEATH_ONCE);
            }
            owner.interrupt(interruption, InterruptPhase.DEATH_FINISH);
        }
        owner.dieBase(source);
    }
    void remove(Entity.RemovalReason reason) {
        // Capture registers custody before discard. Unload, dimension transfer and KILLED are not this path.
        if (reason == Entity.RemovalReason.DISCARDED && !owner.isRemoved() && !discardCleanupStarted
                && owner.level() instanceof ServerLevel server && !YukkuriWorldData.get(server).isCaptured(owner.getUUID())) {
            discardCleanupStarted = true;
            owner.interrupt(Interrupt.REMOVED);
        }
        owner.removeBase(reason);
    }
    void cancelRemovedBrood() {
        // Same ledger cancellation as playground clearing; detached broods are excluded by family.brood().
        // Cancel marks only unborn children LOST. It does not record a death or create/drop remains.
        var brood = owner.family().brood();
        if (brood != null) FamilyData.get((ServerLevel) owner.level()).cancel(brood);
    }
    boolean repeatHitIgnored(DamageSource source,float amount){return owner.invulnerableTime>10&&!source.is(DamageTypeTags.BYPASSES_COOLDOWN)&&amount<=owner.lastHurtValue();}
    void saveDeathRecorded(CompoundTag tag) { tag.putBoolean("FamilyDeathRecorded", deathRecorded); }
    void loadDeathRecorded(CompoundTag tag) { deathRecorded = tag.getBoolean("FamilyDeathRecorded"); }
    void resetLoadedExhaustion() { sukkiriExhaustion = false; }
}
