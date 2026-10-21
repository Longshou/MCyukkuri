package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.body.BodyFood;
import io.github.longshou.mcyukkuri.body.BodyHit;
import io.github.longshou.mcyukkuri.body.HurtResult;
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
    private Transaction current;

    private static final class Transaction {
        final BodyHit hit;
        HurtResult result;
        Transaction(BodyHit hit) { this.hit = hit; }
    }

    void markSukkiriExhaustion() { sukkiriExhaustion = true; }
    boolean hurtInBout(DamageSource source,float amount) { return apply(source,amount,BodyHit.Kind.BOUT).accepted(); }
    boolean hurtInternal(DamageSource source,float amount) { return apply(source,amount,BodyHit.Kind.INTERNAL).accepted(); }
    boolean hurtTerminal(DamageSource source,float amount) { return apply(source,amount,BodyHit.Kind.TERMINAL).accepted(); }
    BodyFood.Bite hurtBite(DamageSource source,float amount) { return apply(source,amount,BodyHit.Kind.BITE).food(); }
    boolean hurtBiteWound(DamageSource source,float amount) { return apply(source,amount,BodyHit.Kind.BITE_WOUND).accepted(); }
    boolean hurt(DamageSource source,float amount) { return apply(source,amount,BodyHit.Kind.ATTACK).accepted(); }

    private HurtResult settle(Transaction transaction,boolean accepted) {
        if(transaction.result==null) {
            float loss=Math.max(0,transaction.hit.healthBefore()-owner.getHealth());
            transaction.result=owner.body().onHurt(transaction.hit,accepted,loss);
        }
        return transaction.result;
    }

    private HurtResult apply(DamageSource source,float amount,BodyHit.Kind kind) {
        if(!Float.isFinite(amount)||amount<=0)return HurtResult.REJECTED;
        exhaustionDamageDepth++;
        Transaction previous=current;
        try {
            if(kind!=BodyHit.Kind.TERMINAL&&owner.shellRetraction().protects(source))amount*=.5f;
            if(owner.level().isClientSide||owner.body()==null)
                return new HurtResult(owner.hurtBase(source,amount),0,null,BodyFood.Bite.EMPTY);
            var transaction=new Transaction(owner.body().prepareHurt(source,amount,kind));
            current=transaction;
            boolean accepted=owner.hurtBase(source,amount);
            if(accepted && source.is(DamageTypes.HOT_FLOOR))owner.life().hotFloor().burned();
            boolean physicalHit=accepted&&transaction.hit.physical()&&!YukkuriLife.isSoaking(source);
            boolean notify=physicalHit&&owner.isAlive();
            var interruption=InterruptContext.hurt(source);
            if(notify) {
                owner.life().wakeForDanger("damage");
                owner.interrupt(interruption,InterruptPhase.BEFORE_INJURY);
            }
            var result=settle(transaction,accepted);
            // Witnesses see a lethal hit too; the victim's own recovery and panic require it to remain alive.
            if(physicalHit&&!owner.isAlive())owner.status().hurt(source);
            if(notify&&owner.isAlive()) {
                if(result.loss()>0)owner.headwear().struck(source,result.loss(),result.part());
                owner.status().hurt(source);
                owner.interrupt(interruption,InterruptPhase.AFTER_STATUS_HURT);
                if(kind!=BodyHit.Kind.BOUT&&source.getEntity() instanceof YukkuriEntity attacker)
                    GroupInteractionGoal.witnessedAttack(owner,attacker);
                owner.operations().damaged(source);
                owner.settlement().attackedBy(source,false);
                if(source.getEntity() instanceof Player attackerPlayer)HomeClaim.attacked(owner,attackerPlayer);
                owner.interrupt(interruption,InterruptPhase.AFTER_ATTACK_NOTIFICATIONS);
                if(kind!=BodyHit.Kind.BOUT)owner.life().personality().attacked(
                        source.getEntity()==null?null:source.getEntity().getUUID(),source.getEntity() instanceof Player);
                if(!owner.body().dying()) {
                    boolean peerConflict = kind != BodyHit.Kind.BOUT && source.getEntity() instanceof YukkuriEntity attacker
                            && owner.getVariant() != YukkuriVariant.MYON && !owner.profile().dangerToYukkuri()
                            && !attacker.profile().dangerToYukkuri();
                    boolean retaliating = kind != BodyHit.Kind.BOUT && owner.profile().dangerToYukkuri()
                            && source.getEntity() instanceof YukkuriEntity attacker
                            && owner.predator().retaliate(attacker);
                    boolean defending = peerConflict && owner.groupInteraction().defense().respond((YukkuriEntity) source.getEntity());
                    owner.setPanicTicks(112);
                    if(defending || retaliating || !peerConflict && owner.myon().standGround(source))owner.setPanicTicks(0);
                    owner.directNavigation().stop();
                    if(owner.shellRetracts())owner.shellRetraction().tick(owner.panicTicksValue());
                    else owner.setAnimation(YukkuriAction.STARTLE,true);
                }
            }
            return result;
        } finally {
            current=previous;
            if(--exhaustionDamageDepth==0)sukkiriExhaustion=false;
        }
    }
    void die(DamageSource source) {
        if (!owner.level().isClientSide) {
            var interruption = InterruptContext.death(source, false, false);
            owner.interrupt(interruption, InterruptPhase.DEATH_PREPARE);
            if(!deathRecorded&&current!=null)settle(current,true);
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
        if (brood != null) FamilyData.get((ServerLevel) owner.level()).cancel(brood, owner, "body_died");
    }
    boolean repeatHitIgnored(DamageSource source,float amount){return owner.invulnerableTime>10&&!source.is(DamageTypeTags.BYPASSES_COOLDOWN)&&amount<=owner.lastHurtValue();}
    void saveDeathRecorded(CompoundTag tag) { tag.putBoolean("FamilyDeathRecorded", deathRecorded); }
    void loadDeathRecorded(CompoundTag tag) { deathRecorded = tag.getBoolean("FamilyDeathRecorded"); }
    void resetLoadedExhaustion() { sukkiriExhaustion = false; }
}
