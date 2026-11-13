package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.life.EcologyRoutines;
import io.github.longshou.mcyukkuri.life.BehaviorContinuity;
import io.github.longshou.mcyukkuri.entity.presentation.PresentationGoals;
import io.github.longshou.mcyukkuri.event.HomeClaim;
import io.github.longshou.mcyukkuri.event.HomeClaimGoal;
import io.github.longshou.mcyukkuri.event.WildEventGoal;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.family.LeaveHostGoal;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.intent.AskLedger;
import io.github.longshou.mcyukkuri.intent.Conductor;
import io.github.longshou.mcyukkuri.intent.Drive;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.life.AgitationLife;
import io.github.longshou.mcyukkuri.life.EscapeWater;
import io.github.longshou.mcyukkuri.mind.MindLife;
import io.github.longshou.mcyukkuri.pet.PetLife;
import io.github.longshou.mcyukkuri.predator.PredatorLife;
import io.github.longshou.mcyukkuri.settlement.ForageGoal;
import io.github.longshou.mcyukkuri.settlement.ForageTrip;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.social.Kindness;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Reactions;
import io.github.longshou.mcyukkuri.status.LearnedFear;
import io.github.longshou.mcyukkuri.social.HookRescueGoal;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.TemptGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.phys.Vec3;

/** Existing ordered goals and their presentation identity bindings. No second component catalogue. */
final class YukkuriGoals {
    private static final int FAMILY_PRIORITY = 5;
    private final YukkuriEntity owner;
    private final GoalSelector goalSelector;
    private PresentationGoals presentationGoals;
    private RoamingGoal roaming;
    private Kindness kindness;
    RoamingGoal roaming() { return roaming; }
    Kindness kindness() { return kindness; }
    YukkuriGoals(YukkuriEntity owner, GoalSelector goalSelector) {
        this.owner = owner;
        this.goalSelector = goalSelector;
    }
    void registerGoals() {
        owner.initializeComponents();
        presentationGoals = new PresentationGoals(owner);
        goalSelector.addGoal(0, new EscapeWaterGoal());
        // A young the catch hat is unloading leaves on its own (walk to the brim or hop out); the taker's own goal only signals it.
        goalSelector.addGoal(2, new LeaveHostGoal(owner));
        goalSelector.addGoal(0, new HotFloorGoal());
        goalSelector.addGoal(0, presentationGoals.bind(PresentationGoals.HURT_CRY, new HurtCryGoal()));
        goalSelector.addGoal(0, new HouseExitGoal());
        goalSelector.addGoal(0, new OperationGoal());
        owner.createGrief();
        goalSelector.addGoal(1, presentationGoals.bind(PresentationGoals.PROTECTION, owner.grief().goal()));
        goalSelector.addGoal(1, new FleeGoal());
        owner.createMyon();
        goalSelector.addGoal(1, owner.myon().goal());
        goalSelector.addGoal(5, owner.myon().stickGoal());
        goalSelector.addGoal(1, presentationGoals.bind(PresentationGoals.MIND, new MindLife.FrenzyGoal(owner)));
        owner.createSanction();
        goalSelector.addGoal(2, presentationGoals.bind(PresentationGoals.SANCTION, owner.sanction()));
        owner.createHeadwear();
        goalSelector.addGoal(3, landBinding(PresentationGoals.HEADWEAR, owner.headwear()));
        goalSelector.addGoal(3, landBinding(PresentationGoals.SOCIAL, new AccessoryGoal()));
        goalSelector.addGoal(1, new HookRescueGoal(owner));
        goalSelector.addGoal(1, presentationGoals.bind(PresentationGoals.THREAT, new Reactions.ThreatGoal(owner)));
        goalSelector.addGoal(2, new LearnedFear.RetreatGoal(owner));
        goalSelector.addGoal(2, landBinding(PresentationGoals.WEATHER, new Reactions.WeatherGoal(owner)));
        goalSelector.addGoal(1, landBinding(PresentationGoals.LIFE, new FatigueGoal()));
        goalSelector.addGoal(1, new ShellGoal());
        goalSelector.addGoal(2, new HoldActionGoal());
        goalSelector.addGoal(2, presentationGoals.bind(PresentationGoals.FAMILY, new BirthGoal()));
        // Component goals retain the CareReceiver insertion point; EARLY factories may only retain the owner here.
        owner.registerComponentGoals(goalSelector, presentationGoals);
        // Add-on domain goals (the aquatic life): registered by the registration entry, never named here.
        DomainHooks.addGoals(owner, goalSelector, presentationGoals);
        goalSelector.addGoal(2, presentationGoals.bind(PresentationGoals.COMFORT_RECEIVER, new ComfortReceiverGoal()));
        goalSelector.addGoal(2, new PetLife.SessionGoal(owner));
        goalSelector.addGoal(3, presentationGoals.bind(PresentationGoals.PREDATOR, new PredatorLife.BehaviorGoal(owner)));
        goalSelector.addGoal(3, landBinding(PresentationGoals.CHILDHOOD, new ChildhoodGoal()));
        goalSelector.addGoal(3, landBinding(PresentationGoals.LIFE, new LifeGoal()));
        owner.createMoldExile();
        goalSelector.addGoal(3, landBinding(PresentationGoals.MOLD_EXILE, owner.moldExile()));
        // Below the life needs, above every errand: a slave kept in the waste pit stays in it (settlement/PitSlave).
        goalSelector.addGoal(4, presentationGoals.bind(PresentationGoals.PIT_SLAVE, new PitSlaveGoal()));
        goalSelector.addGoal(3, landBinding(PresentationGoals.HOME_CLAIM, new HomeClaimGoal(owner)));
        goalSelector.addGoal(4, landBinding(PresentationGoals.TEMPT, new TemptGoal(owner, 1.15, Ingredient.of(YukkuriEntity.FOOD), false) {
            @Override public boolean canUse() {
                return !owner.isPassenger() && owner.life().canEat() && super.canUse()
                        && owner.life().seeksOfferedFood(player)
                        && (LearnedFear.hungerOverrides(owner)||owner.life().personality().fear(player.getUUID())<BondLevels.SOCIAL_FEAR_LIMIT)
                        && !owner.social().takeFoodApproach(player);
            }
            @Override public boolean canContinueToUse() {
                // Vanilla's continuation calls canUse() again, including the same contact handoff.
                return !owner.isPassenger() && owner.life().canEat() && super.canContinueToUse();
            }
        }));
        goalSelector.addGoal(FAMILY_PRIORITY, landBinding(PresentationGoals.FAMILY, new FamilyGoal()));
        goalSelector.addGoal(5, new AgitationLife.BehaviorGoal(owner));
        goalSelector.addGoal(1, new AgitationLife.HeldGoal(owner));
        goalSelector.addGoal(4, landBinding(PresentationGoals.HOUSE, new HouseGoal()));
        owner.createForageTrip();
        goalSelector.addGoal(ForageTrip.GOAL_PRIORITY, landBinding(PresentationGoals.FORAGE_TRIP, owner.forageTrip()));
        goalSelector.addGoal(4, landBinding(PresentationGoals.FORAGE, new ForageGoal(owner)));
        owner.createTreasure();
        goalSelector.addGoal(4, landBinding(PresentationGoals.TREASURE, owner.treasure()));
        owner.createCannibalism();
        goalSelector.addGoal(2, presentationGoals.bind(PresentationGoals.CANNIBALISM, owner.cannibalism()));
        owner.createOffering();
        goalSelector.addGoal(2, presentationGoals.bind(PresentationGoals.SELF_OFFERING, owner.offering()));
        goalSelector.addGoal(4, landBinding(PresentationGoals.HABITAT, new HabitatGoal()));
        owner.createIndependence();
        // Priority 4: at 6 the (also priority 6) social goals kept the move flag whenever the child was seeking its parents' company, so the leave-taking never got its turn.
        goalSelector.addGoal(4, landBinding(PresentationGoals.INDEPENDENCE, owner.independence()));
        goalSelector.addGoal(6, landBinding(PresentationGoals.DUTIES, new SettlementDutyGoal()));
        goalSelector.addGoal(6, landBinding(PresentationGoals.SETTLEMENT, new SettlementGoal()));
        // A migration (discussion, packing, march) outranks daily, social and family routines; the life needs (3) still come first.
        goalSelector.addGoal(4, landBinding(PresentationGoals.MIGRATION, new MigrationGoal()));
        goalSelector.addGoal(3, landBinding(PresentationGoals.WILD_EVENT, new WildEventGoal(owner, true)));
        goalSelector.addGoal(5, landBinding(PresentationGoals.WILD_EVENT, new WildEventGoal(owner, false)));
        goalSelector.addGoal(4, presentationGoals.bind(PresentationGoals.COMFORT_GIVER, new ComfortGoal()));
        kindness = new Kindness(owner);
        goalSelector.addGoal(4, kindness);
        goalSelector.addGoal(5, landBinding(PresentationGoals.DAILY, new DailyGoal()));
        goalSelector.addGoal(6, landBinding(PresentationGoals.SOCIAL, new SocialGoal()));
        goalSelector.addGoal(5, landBinding(PresentationGoals.GROUP, new GroupGoal()));
        goalSelector.addGoal(6,landBinding(PresentationGoals.PLAY, new PlaygroundGoal()));
        owner.createWintering();
        goalSelector.addGoal(7, landBinding(PresentationGoals.WINTERING, owner.wintering()));
        roaming = new RoamingGoal(owner);
        goalSelector.addGoal(7, landBinding(PresentationGoals.ROAMING, roaming));
        goalSelector.addGoal(8, landGoal(new LookAtPlayerGoal(owner, Player.class, 6.0F)));
        goalSelector.addGoal(9, landGoal(new RandomLookAroundGoal(owner)));
    }
    /** The bound wrapper retains its selector identity and delegates the real goal only in its land territory. */
    private <G extends Goal> Goal landBinding(PresentationGoals.Source<G> source, G delegate) {
        return presentationGoals.bind(source, landGoal(delegate), delegate);
    }
    private Goal landGoal(Goal delegate) {
        return new Goal() {
            { setFlags(delegate.getFlags()); }
            @Override public boolean canUse() { return delegate.canUse(); }
            @Override public boolean canContinueToUse() { return delegate.canContinueToUse(); }
            @Override public boolean isInterruptable() { return delegate.isInterruptable(); }
            @Override public boolean requiresUpdateEveryTick() { return delegate.requiresUpdateEveryTick(); }
            @Override public void start() { delegate.start(); }
            @Override public void tick() { delegate.tick(); }
            @Override public void stop() { delegate.stop(); }
            @Override public String toString() { return delegate.getClass().getSimpleName(); }
        };
    }
    private final class ChildhoodGoal extends Goal {
        ChildhoodGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return owner.childhood().canUse(); }
        @Override public boolean canContinueToUse() { return owner.childhood().canContinue(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.childhood().tickGoal(); }
        @Override public void stop() { owner.childhood().stop(); }
    }
    private final class OperationGoal extends Goal {
        OperationGoal(){setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK));}
        @Override public boolean canUse(){return owner.operations().controlling();}
        @Override public boolean canContinueToUse(){return owner.operations().controlling();}
        @Override public boolean requiresUpdateEveryTick(){return true;}
        @Override public void start(){owner.directNavigation().stop();}
        @Override public void tick(){owner.directNavigation().stop();}
    }
    private final class HoldActionGoal extends Goal {
        HoldActionGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return owner.requestedActionValue() != null || owner.bereaving(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() { owner.directNavigation().stop(); }
        @Override public void tick() { owner.directNavigation().stop(); }
    }
    private final class ShellGoal extends Goal {
        ShellGoal(){setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK));}
        @Override public boolean canUse(){return owner.shellRetraction().controlsMovement();}
        @Override public boolean canContinueToUse(){return owner.shellRetraction().controlsMovement();}
        @Override public boolean requiresUpdateEveryTick(){return true;}
        @Override public void tick(){owner.directNavigation().stop();}
    }
    private final class FleeGoal extends Goal {
        FleeGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return owner.panicTicksValue() > 0 && !owner.predator().recoveringFlight()
                && !owner.grief().protecting() && !owner.shellRetracts() && !owner.isPassenger()
                && !owner.family().carry().shelteredFrom(owner.getLastHurtByMob()); }
        @Override public void start() {
            if (owner.getLastHurtByMob() != null) owner.family().carry().startFlight("panic", owner.getLastHurtByMob());
        }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() {
            if (owner.panicTicksValue() > 100) { owner.directNavigation().stop(); return; }
            if (owner.family().carry().tickFlight(owner.getLastHurtByMob())) return;
            // A clever wild Reimu that has run into her den seals its mouth (settlement/DenSeal); the sealing owns the body meanwhile.
            if (owner.getLastHurtByMob() instanceof YukkuriEntity danger && danger.profile().dangerToYukkuri()
                    && owner.status().reactions().threat().seal().drive(danger)) return;
            if (owner.directNavigation().isDone() || owner.tickCount % 10 == 0) {
                var threat = owner.getLastHurtByMob();
                if (threat != null) {
                    if (owner.pet().fleeToOwner()) return;
                    var safe = owner.life().homeStand();
                    if (safe != null && Vec3.atBottomCenterOf(safe).distanceToSqr(threat.position()) > 25
                            && owner.life().navigate(safe, 1.2, false)) return;
                    // A young runs to the parent it recognises; a body without a home runs for cover, then for a dry point away.
                    var parent = ParentCarry.flightEscort(owner, threat);
                    if (parent != null && owner.life().navigate(parent.blockPosition(), 1.2, false)) {
                        GameplayLog.limited(owner, "threat", "flight.parent", 200, "fleeing to parent #{} distance={} danger=#{}",
                                parent.getId(), Math.round(owner.distanceTo(parent)), threat.getId());
                        return;
                    }
                    if (owner.status().reactions().threat().flight(threat, 10)) return;
                }
                Vec3 target = owner.getLastHurtByMob() == null ? DefaultRandomPos.getPos(owner, 6, 3)
                        : Reactions.awayFrom(owner, 6, owner.getLastHurtByMob().position());
                if (target != null) owner.directNavigation().moveTo(target.x, target.y, target.z, 1.2);
            }
        }
        @Override public void stop() { owner.family().carry().endFlight(); owner.directNavigation().stop(); }
    }
    /** A hurt newborn stays where it is and cries; fire, water, being carried or held still override it. */
    private final class HurtCryGoal extends Goal {
        HurtCryGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        private boolean holds() { return owner.hurtCrying() && !owner.isPassenger() && !owner.isOnFire() && !owner.isInWaterOrBubble() && !owner.agitation().held(); }
        @Override public boolean canUse() { return holds(); }
        @Override public boolean canContinueToUse() { return holds(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() { owner.life().interrupt(); owner.directNavigation().stop(); owner.getNavigation().stop(); }
        @Override public void tick() { owner.directNavigation().stop(); owner.getNavigation().stop(); }
        @Override public void stop() { owner.endHurtCry(owner.hurtCrying() ? "interrupted" : "lapsed"); owner.directNavigation().stop(); }
    }
    private final class HotFloorGoal extends Goal {
        HotFloorGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP)); }
        @Override public boolean canUse() { return owner.life().hotFloor().active() && !owner.isInWaterOrBubble(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() { owner.life().wakeForDanger("hot_floor"); owner.life().interrupt(); }
        @Override public void tick() { owner.life().hotFloor().tick(); }
        @Override public void stop() { owner.directNavigation().stop(); }
    }
    private final class FatigueGoal extends Goal {
        FatigueGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP)); }
        @Override public boolean canUse() { return owner.life().wantsFatigueRest(); }
        @Override public boolean canContinueToUse() { return owner.life().fatigueActive() && owner.life().canFatigueRest(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() { owner.life().startFatigueRest(); }
        @Override public void tick() { owner.life().tickFatigueRest(); }
        @Override public void stop() { owner.life().stopFatigueRest(); }
    }
    private final class LifeGoal extends Goal {
        @Override public boolean isInterruptable() { return BehaviorContinuity.emergency(owner) || owner.family().carry().setDownDue()
                || !(owner.life().protectsStep()); }
        LifeGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return owner.life().plan(); }
        @Override public boolean canContinueToUse() { return owner.life().plan(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.life().tickGoal(); }
        @Override public void stop() { owner.life().stopGoal(); }
    }
    private final class HabitatGoal extends Goal {
        HabitatGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return !HomeClaim.holds(owner) && !owner.agitation().hunting() && owner.habitat().canUse(); }
        @Override public boolean canContinueToUse() { return !HomeClaim.holds(owner) && owner.habitat().canContinue(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.habitat().tickGoal(); }
        @Override public void stop() { owner.habitat().stop(); }
    }
    private final class PitSlaveGoal extends Goal {
        PitSlaveGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return owner.settlement().duties().pit().canUse(); }
        @Override public boolean canContinueToUse() { return owner.settlement().duties().pit().canContinue(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.settlement().duties().pit().tick(); }
        @Override public void stop() { owner.settlement().duties().pit().stop(); }
    }
    private final class SettlementDutyGoal extends Goal {
        SettlementDutyGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return !owner.agitation().hunting() && owner.settlement().duties().canUse(); }
        @Override public boolean canContinueToUse() { return owner.settlement().duties().canContinue(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.settlement().duties().tickGoal(); }
        @Override public void stop() { owner.settlement().duties().stop(); }
    }
    private final class SettlementGoal extends Goal {
        SettlementGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return !HomeClaim.holds(owner) && !owner.agitation().hunting() && !owner.settlement().underway() && owner.settlement().canUse(); }
        @Override public boolean canContinueToUse() { return !HomeClaim.holds(owner) && owner.settlement().canContinue(); }
        @Override public void start() { owner.settlement().startGoal(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.settlement().tickGoal(); }
        @Override public void stop() { owner.settlement().stop(); }
    }
    /** The same settlement walk while this member takes part in a migration that has not reached the new site. */
    private final class MigrationGoal extends Goal {
        MigrationGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return !HomeClaim.holds(owner) && !owner.agitation().hunting() && owner.settlement().underway() && owner.settlement().canUse(); }
        @Override public boolean canContinueToUse() { return !HomeClaim.holds(owner) && owner.settlement().canContinue(); }
        @Override public void start() { owner.settlement().startGoal(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.settlement().tickGoal(); }
        @Override public void stop() { owner.settlement().stop(); }
    }
    private final class PlaygroundGoal extends Goal {
        @Override public boolean isInterruptable() { return BehaviorContinuity.emergency(owner) || !(owner.playLife().active()); }
        PlaygroundGoal(){setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK,Flag.JUMP));}
        @Override public boolean canUse(){return !owner.agitation().hunting() && owner.playLife().canUse();}
        @Override public boolean canContinueToUse(){return owner.playLife().canContinue();}
        @Override public boolean requiresUpdateEveryTick(){return true;}
        @Override public void tick(){owner.playLife().tickGoal();}
        @Override public void stop(){owner.playLife().stop();}
    }
    private final class GroupGoal extends Goal {
        @Override public boolean isInterruptable() { return BehaviorContinuity.emergency(owner) || !(owner.groupInteraction().active()); }
        GroupGoal(){setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK));}
        @Override public boolean canUse(){return !owner.agitation().hunting() && owner.groupInteraction().canUse();}
        @Override public boolean canContinueToUse(){return owner.groupInteraction().canContinueToUse();}
        @Override public boolean requiresUpdateEveryTick(){return true;}
        @Override public void tick(){owner.groupInteraction().tick();}
        @Override public void stop(){owner.groupInteraction().stop();}
    }
    /** A mate or kin walks up to an injured companion and licks it in worry (comfort only, never treatment). */
    private final class ComfortGoal extends Goal {
        @Override public boolean isInterruptable() { return BehaviorContinuity.emergency(owner) || !(owner.woundComfort().inContact()); }
        ComfortGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return owner.woundComfort().plan(); }
        @Override public boolean canContinueToUse() { return owner.woundComfort().groundLicking() && owner.woundComfort().valid(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.woundComfort().tickGoal(); }
        @Override public void stop() { owner.woundComfort().stop(); }
    }
    /** The injured companion stays and faces the kin that came to lick it. */
    private final class ComfortReceiverGoal extends Goal {
        @Override public boolean isInterruptable() { return BehaviorContinuity.emergency(owner) || !(owner.woundComfort().inContact()); }
        ComfortReceiverGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return owner.woundComfort().groundReceiving(); }
        @Override public boolean canContinueToUse() { return owner.woundComfort().groundReceiving(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.woundComfort().tickReceiver(); }
        @Override public void stop() { owner.woundComfort().stop(); }
    }
    private final class DailyGoal extends Goal {
        @Override public boolean isInterruptable() { return BehaviorContinuity.emergency(owner) || !(owner.dailyLife().active()); }
        DailyGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return !owner.agitation().hunting() && owner.dailyLife().plan(); }
        @Override public boolean canContinueToUse() { return owner.dailyLife().valid(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.dailyLife().tickGoal(); }
        @Override public void stop() { owner.dailyLife().stop(); }
    }
    private final class AccessoryGoal extends Goal {
        AccessoryGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return owner.social().startAccessory(); }
        @Override public boolean canContinueToUse() { return owner.social().retrievingAccessory() && owner.social().canContinue(); }
        @Override public boolean isInterruptable() { return BehaviorContinuity.emergency(owner); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.social().tickGoal(); }
        @Override public void stop() { owner.social().stop(); }
    }
    private final class SocialGoal extends Goal {
        @Override public boolean isInterruptable() {
            return BehaviorContinuity.emergency(owner) || owner.social().humanContact() || !owner.social().active();
        }
        SocialGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return owner.social().canUse(); }
        @Override public boolean canContinueToUse() { return owner.social().canContinue(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.social().tickGoal(); }
        @Override public void stop() { owner.social().stop(); }
    }
    /** In water the body works its way out through one EscapeWater run per fall; exhaustion does not renew its attempts within the fall
     *  (a finished run is dropped only once the body stops struggling, so the next fall gets its own).
     *  Out of the water it holds still
     *  for the breath. The edges whose climb failed are remembered here across runs. It owns the fall's incident: opened
     *  once the plunge's impact is over and the body works its way out, so helpers near decide once each, and settled once the body
     *  is out (aboard, held, ashore). */
    private final class EscapeWaterGoal extends Goal {
        private final Map<BlockPos, Long> avoid = new HashMap<>();
        private IntentRunner runner;
        private String incident;
        EscapeWaterGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP, Flag.LOOK)); }
        @Override public boolean canUse() { return (owner.waterDistress().escaping() || owner.waterDistress().climbing() || owner.waterDistress().restingBreath()
                || incident != null && out()
                || runner != null && runner.result() == IntentRunner.Result.RUNNING)
                && !bodyOwnedInWater() && !owner.predatorFlying(); }
        private boolean bodyOwnedInWater() {
            var intent = Drive.running(owner, "aquatic");
            return intent != null && intent.ownsBodyInWater() || owner.social().ownsBodyInWater();
        }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() {
            boolean struggles = owner.waterDistress().struggles();
            // out (standing, aboard, held): the fall is over, and a fall during the breath is a new incident
            if (incident != null && out()) settle();
            // past the plunge's impact (a wade through a puddle opens none)
            if (incident == null && struggles) incident = AskLedger.server().open(owner, "water",
                    "stage=" + owner.family().stage() + " landing=" + owner.family().landingOutcome() + " last=" + owner.motor().lastDetail());
            if (!struggles && (runner == null || owner.isInWaterOrBubble() || out())) {
                halt(out() ? "escape_safe" : "escape_unavailable"); owner.getNavigation().stop();
                // plunged again after a finished run: this fall is new and gets its own run once its impact is over
                if (runner != null && runner.result() != IntentRunner.Result.RUNNING) runner = null;
                return;
            }
            if (runner == null) {
                runner = new IntentRunner(owner, new EscapeWater(owner, avoid), "escape_water").target(() -> EscapeWater.target(owner));
                runner.start();
            }
            if (runner != null) runner.tick();
        }
        private void halt(String why) { if (runner != null) runner.stop(why); }
        @Override public void stop() {
            halt(out() ? "escape_safe" : "escape_stop");
            owner.directNavigation().stop();
            if (incident != null && out()) settle();
            else if (runner != null && runner.result() == IntentRunner.Result.INTERRUPTED) runner = null;
        }
        /** Out of the fall: standing or aboard (a hat, a carrier); a body still in the air settles once it lands (its breath). */
        private boolean out() { return EscapeWater.safe(owner); }
        private void settle() {
            halt("escape_safe");
            runner = null;
            AskLedger.server().settle(incident, owner.getVehicle() instanceof YukkuriEntity ? "held" : owner.isPassenger() ? "aboard" : "ashore");
            incident = null;
        }
    }
    private final class HouseExitGoal extends Goal {
        HouseExitGoal(){setFlags(EnumSet.of(Flag.MOVE));}
        @Override public boolean canUse(){return owner.house().mustExit();}
        @Override public boolean canContinueToUse(){return owner.house().mustExit();}
        @Override public boolean requiresUpdateEveryTick(){return true;}
        @Override public void tick(){owner.house().tickExit();}
        @Override public void stop(){owner.house().stopExit();}
    }
    private final class HouseGoal extends Goal {
        HouseGoal(){setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK));}
        @Override public boolean canUse(){return !owner.agitation().hunting() && owner.house().canUse();}
        @Override public boolean canContinueToUse(){return owner.house().canContinue();}
        @Override public boolean requiresUpdateEveryTick(){return true;}
        @Override public void tick(){owner.house().tickGoal();}
        @Override public void stop(){owner.house().stop();}
    }
    private final class FamilyGoal extends Goal {
        @Override public boolean isInterruptable() { return BehaviorContinuity.emergency(owner) || owner.family().carry().setDownDue()
                || !(owner.family().active() && !owner.family().yieldsToComfort()); }
        FamilyGoal() { setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK)); }
        @Override public boolean canUse() { return !owner.family().prioritizesBirth()&&(!WildEvents.reserved(owner)||owner.life().hasUrgentNeed())&&owner.family().canUse(); }
        @Override public boolean canContinueToUse() { return !owner.family().prioritizesBirth()&&(!WildEvents.reserved(owner)||owner.life().hasUrgentNeed())&&owner.family().canContinue(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.family().tickGoal(); }
        @Override public void stop() { owner.family().stop(); }
    }
    /**
     * Both roles of a running birth: while the current beat casts this body, it runs the beat's intent for it with
     * its own IntentRunner and reports the end to the choreography; a role with nothing to run holds still and plays its beat.
     * Otherwise the birth-priority family duty (comforting a hurt newborn).
     */
    private final class BirthGoal extends Goal {
        private IntentRunner runner;
        private Conductor.Role runnerRole;
        BirthGoal() { setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK)); }
        @Override public boolean canUse() { return owner.family().birthRole()!=null||owner.family().prioritizesBirth()&&owner.family().canUse(); }
        @Override public boolean canContinueToUse() { return owner.family().birthRole()!=null||owner.family().prioritizesBirth()&&owner.family().canContinue(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        // The displaced ordinary family goal may have stopped after canUse; reacquire the same birth duty.
        @Override public void start() { if(owner.family().birthRole()==null)owner.family().canUse(); }
        @Override public void tick() {
            var role=owner.family().birthRole();
            if(role==null){ release("role_ended"); owner.family().tickGoal(); return; }
            var intent=role.choreography().intentFor(owner.getUUID());
            if(runner!=null&&runner.intent()!=intent) release("beat_changed");
            if(intent==null){ owner.family().holdBirthRole(role); return; }
            if(owner.life().mealTicks()>0)return;
            if(runner==null){ runner=new IntentRunner(owner,intent,"birth_"+role.role()); runnerRole=role; runner.start(); owner.family().birthIntentStarted(role,intent); }
            runner.tick();
            owner.family().watchBirth(role);
            if(runner.result()!=IntentRunner.Result.RUNNING){ role.choreography().report(owner.getUUID(),intent,runner.result()); runner=null; }
        }
        @Override public void stop() { release("birth_goal_stop"); owner.family().stopBirthGoal(); }
        // A runner cut off (the body panics, is carried, the goal yields) reports INTERRUPTED, so its beat branches at once; a stale one is ignored.
        private void release(String why) { if(runner!=null){ runner.stop(why); runnerRole.choreography().report(owner.getUUID(),runner.intent(),runner.result()); runner=null; } }
    }
    void saveDebugGoals(CompoundTag tag) {
        if (EcologyRoutines.house(owner)) {
            // Read-only observation for operators and automated checks: which goals are running right now.
            var running = new ListTag();
            goalSelector.getAvailableGoals().stream().filter(WrappedGoal::isRunning)
                    .forEach(goal -> running.add(StringTag.valueOf(goal.getGoal().getClass().getSimpleName() + "@" + goal.getPriority())));
            tag.put("YukkuriDebugGoals", running);
        }
    }
    PresentationGoals.Running runningGoals() {
        return presentationGoals.running(goalSelector.getAvailableGoals());
    }
    List<String> runningGoalIds() {
        return goalSelector.getAvailableGoals().stream().filter(WrappedGoal::isRunning)
                .map(goal -> goal.getGoal().toString() + "@" + goal.getPriority()).toList();
    }
    /** The actual higher-priority MOVE owner which can displace family, using the trace identity. */
    String competingMoveGoalId() {
        return goalSelector.getAvailableGoals().stream()
                .filter(goal -> goal.isRunning() && goal.getPriority() < FAMILY_PRIORITY && goal.getFlags().contains(Goal.Flag.MOVE))
                .map(goal -> goal.getGoal().toString() + "@" + goal.getPriority()).findFirst().orElse(null);
    }
}
