package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.life.EcologyRoutines;
import io.github.longshou.mcyukkuri.life.FoodNoticeGoal;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.BehaviorContinuity;
import io.github.longshou.mcyukkuri.entity.presentation.PresentationGoals;
import io.github.longshou.mcyukkuri.pet.rules.ConscienceGoal;
import io.github.longshou.mcyukkuri.pet.rules.TrystGoal;
import io.github.longshou.mcyukkuri.event.HomeClaim;
import io.github.longshou.mcyukkuri.event.HomeClaimGoal;
import io.github.longshou.mcyukkuri.event.HomeTerritoryGoal;
import io.github.longshou.mcyukkuri.event.WildEventGoal;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.family.FarewellArbitration;
import io.github.longshou.mcyukkuri.family.LeaveHostGoal;
import io.github.longshou.mcyukkuri.intent.AskLedger;
import io.github.longshou.mcyukkuri.intent.Conductor;
import io.github.longshou.mcyukkuri.intent.Drive;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.life.AgitationLife;
import io.github.longshou.mcyukkuri.life.EscapeWater;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import io.github.longshou.mcyukkuri.mind.EmotionCourse;
import io.github.longshou.mcyukkuri.mind.MindLife;
import io.github.longshou.mcyukkuri.pet.PetLife;
import io.github.longshou.mcyukkuri.predator.PredatorLife;
import io.github.longshou.mcyukkuri.settlement.ForageGoal;
import io.github.longshou.mcyukkuri.settlement.DenSeal;
import io.github.longshou.mcyukkuri.settlement.ForageTrip;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.social.Kindness;
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
    private ForageGoal forage;
    private HomeTerritoryGoal homeTerritory;
    RoamingGoal roaming() { return roaming; }
    Kindness kindness() { return kindness; }
    ForageGoal forage() { return forage; }
    HomeTerritoryGoal homeTerritory() { return homeTerritory; }
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
        var hookRescue = new HookRescueGoal(owner);
        goalSelector.addGoal(1, hookRescue);
        goalSelector.addGoal(2, owner.kinProtection());
        goalSelector.addGoal(1, presentationGoals.bind(PresentationGoals.THREAT, new Reactions.ThreatGoal(owner, hookRescue)));
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
        // Above the life errands (3) and every daily goal: a pet caught breaking a rule, or owning up to it, is not drawn away.
        goalSelector.addGoal(2, landBinding(PresentationGoals.CONSCIENCE, new ConscienceGoal(owner)));
        goalSelector.addGoal(TrystGoal.PRIORITY, landBinding(PresentationGoals.TRYST, new TrystGoal(owner)));
        // A strong feeling's peak holds the body above errands; its easing idles listlessly above roaming (mind/EmotionCourse).
        goalSelector.addGoal(3, landBinding(PresentationGoals.EMOTION, new EmotionCourse.CourseGoal(owner, 3)));
        goalSelector.addGoal(6, landBinding(PresentationGoals.EMOTION, new EmotionCourse.CourseGoal(owner, 6)));
        goalSelector.addGoal(3, landBinding(PresentationGoals.DEN_SEAL, new DenSeal.FamilyGoal(owner)));
        owner.createMoldExile();
        goalSelector.addGoal(3, landBinding(PresentationGoals.MOLD_EXILE, owner.moldExile()));
        // Below the life needs, above every errand: a slave kept in the waste pit stays in it (settlement/PitSlave).
        goalSelector.addGoal(4, presentationGoals.bind(PresentationGoals.PIT_SLAVE, new PitSlaveGoal()));
        homeTerritory = new HomeTerritoryGoal(owner);
        goalSelector.addGoal(3, landBinding(PresentationGoals.HOME_TERRITORY, homeTerritory));
        goalSelector.addGoal(3, landBinding(PresentationGoals.HOME_CLAIM, new HomeClaimGoal(owner)));
        goalSelector.addGoal(4, basicBinding(PresentationGoals.TEMPT, new TemptGoal(owner, 1.15, Ingredient.of(YukkuriEntity.FOOD), false) {
            @Override public boolean canUse() {
                // A free stray has its own wider, line-of-sight scan (FoodNoticeGoal); this one keeps pets, house bodies and the wild.
                return !owner.isPassenger() && !owner.life().widensFoodNotice() && owner.life().canEat() && super.canUse()
                        && owner.life().seeksOfferedFood(player)
                        && (LearnedFear.hungerOverrides(owner)||owner.life().personality().fear(player.getUUID())<BondLevels.SOCIAL_FEAR_LIMIT)
                        && !owner.social().takeFoodApproach(player);
            }
            @Override public boolean canContinueToUse() {
                // Vanilla's continuation calls canUse() again, including the same contact handoff.
                return !owner.isPassenger() && owner.life().canEat() && super.canContinueToUse();
            }
        }));
        goalSelector.addGoal(4, basicBinding(PresentationGoals.TEMPT, new FoodNoticeGoal(owner)));
        goalSelector.addGoal(FAMILY_PRIORITY, landBinding(PresentationGoals.FAMILY, new FamilyGoal()));
        goalSelector.addGoal(5, new AgitationLife.BehaviorGoal(owner));
        goalSelector.addGoal(1, new AgitationLife.HeldGoal(owner));
        goalSelector.addGoal(4, basicBinding(PresentationGoals.HOUSE, new HouseGoal()));
        owner.createForageTrip();
        goalSelector.addGoal(ForageTrip.GOAL_PRIORITY, basicBinding(PresentationGoals.FORAGE_TRIP, owner.forageTrip()));
        forage = new ForageGoal(owner);
        goalSelector.addGoal(4, basicBinding(PresentationGoals.FORAGE, forage));
        owner.createTreasure();
        goalSelector.addGoal(4, landBinding(PresentationGoals.TREASURE, owner.treasure()));
        owner.createCannibalism();
        goalSelector.addGoal(2, presentationGoals.bind(PresentationGoals.CANNIBALISM, owner.cannibalism()));
        owner.createOffering();
        goalSelector.addGoal(2, presentationGoals.bind(PresentationGoals.SELF_OFFERING, owner.offering()));
        owner.createIndependence();
        // A reply may displace errands. Earlier priority-3 owners win ties, and the reply yields to active duties.
        goalSelector.addGoal(FarewellArbitration.ANSWER_PRIORITY, landBinding(PresentationGoals.INDEPENDENCE, owner.independence().seeOffGoal()));
        goalSelector.addGoal(4, basicBinding(PresentationGoals.HABITAT, new HabitatGoal()));
        // Priority 4: at 6 the (also priority 6) social goals kept the move flag whenever the child was seeking its parents' company, so the leave-taking never got its turn.
        goalSelector.addGoal(FarewellArbitration.DEPARTURE_PRIORITY, landBinding(PresentationGoals.INDEPENDENCE, owner.independence()));
        goalSelector.addGoal(6, landBinding(PresentationGoals.DUTIES, new SettlementDutyGoal()));
        goalSelector.addGoal(6, landBinding(PresentationGoals.SETTLEMENT, new SettlementGoal()));
        // A migration (discussion, packing, march) outranks daily, social and family routines; the life needs (3) still come first.
        goalSelector.addGoal(4, landBinding(PresentationGoals.MIGRATION, new MigrationGoal()));
        goalSelector.addGoal(3, landBinding(PresentationGoals.WILD_EVENT, new WildEventGoal(owner, true)));
        goalSelector.addGoal(5, landBinding(PresentationGoals.WILD_EVENT, new WildEventGoal(owner, false)));
        goalSelector.addGoal(4, presentationGoals.bind(PresentationGoals.COMFORT_GIVER, new ComfortGoal()));
        kindness = new Kindness(owner);
        goalSelector.addGoal(4, kindness);
        goalSelector.addGoal(4, landGoal(owner.outing(), false));
        goalSelector.addGoal(5, basicBinding(PresentationGoals.DAILY, new DailyGoal()));
        goalSelector.addGoal(6, basicBinding(PresentationGoals.SOCIAL, new SocialGoal()));
        goalSelector.addGoal(5, basicBinding(PresentationGoals.GROUP, new GroupGoal()));
        goalSelector.addGoal(6, basicBinding(PresentationGoals.PLAY, new PlaygroundGoal()));
        goalSelector.addGoal(6, landBinding(PresentationGoals.KEEPSAKE, new KeepsakeGoal()));
        owner.createWintering();
        goalSelector.addGoal(7, basicBinding(PresentationGoals.WINTERING, owner.wintering()));
        // Needs and duties can take the flags from a parent that is watching its child leave.
        goalSelector.addGoal(EmotionCourse.PARTING_PRIORITY, basicBinding(PresentationGoals.EMOTION,
                new EmotionCourse.CourseGoal(owner, EmotionCourse.PARTING_PRIORITY)));
        goalSelector.addGoal(8, landGoal(new PetLife.VigilGoal(owner), true));
        roaming = new RoamingGoal(owner);
        goalSelector.addGoal(9, basicBinding(PresentationGoals.ROAMING, roaming));
        goalSelector.addGoal(10, landGoal(new LookAtPlayerGoal(owner, Player.class, 6.0F), true));
        goalSelector.addGoal(11, landGoal(new RandomLookAroundGoal(owner), true));
    }
    /** The bound wrapper retains its selector identity and delegates the real goal only in its land territory. */
    private <G extends Goal> Goal landBinding(PresentationGoals.Source<G> source, G delegate) {
        return presentationGoals.bind(source, landGoal(delegate, false), delegate);
    }
    private <G extends Goal> Goal basicBinding(PresentationGoals.Source<G> source, G delegate) {
        return presentationGoals.bind(source, landGoal(delegate, true), delegate);
    }
    private Goal landGoal(Goal delegate, boolean basic) {
        return new Goal() {
            { setFlags(delegate.getFlags()); }
            @Override public boolean canUse() {
                return !(basic && (owner.duty().claimed() || owner.family().claimHomeWatch()
                        || owner.family().stockedChildMealDue())) && delegate.canUse();
            }
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
        @Override public boolean canUse() {
            // The grief course holds and turns the body during a running bereavement.
            return owner.requestedActionValue() != null || owner.bereaving() && !owner.mind().feeling().stricken()
                    || HomeClaim.declaring(owner) && !homeTerritory.active() && !homeTerritory.declarationChallenged();
        }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() { tick(); }
        @Override public void tick() {
            owner.directNavigation().stop();
            if (HomeClaim.declaring(owner)) SurfaceApproach.halt(owner);
        }
        @Override public void stop() {
            if (!homeTerritory.active()) HomeClaim.interruptDeclaration(owner);
        }
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
            // A skilled resident that has reached its den can seal while fleeing, whatever drove it home.
            if (owner.status().reactions().threat().seal().drive(owner.getLastHurtByMob())) return;
            var threat = owner.getLastHurtByMob();
            var flight = owner.status().reactions().threat().flight();
            if (threat == null) flight.fleeWithoutSource();
            else if (threat.isAlive() && threat.level() == owner.level() && threat.distanceToSqr(owner) <= 576) flight.flee(threat);
            else SurfaceApproach.halt(owner);
        }
        @Override public void stop() {
            owner.family().carry().endFlight();
            owner.status().reactions().threat().seal().reset();
            owner.status().reactions().threat().flight().forgetRoute();
            owner.directNavigation().stop();
        }
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
        @Override public boolean isInterruptable() { return BehaviorContinuity.emergency(owner) || owner.pet().conscience().catchPending() || !(owner.playLife().active()); }
        PlaygroundGoal(){setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK,Flag.JUMP));}
        @Override public boolean canUse(){return !owner.agitation().hunting() && !owner.mind().feeling().subdued()
                && io.github.longshou.mcyukkuri.human.ChildPlay.offerTo(owner) == null && owner.playLife().canUse();}   // a child's offer is answered first (SocialGoal), free play waits
        @Override public boolean canContinueToUse(){return owner.playLife().canContinue();}
        @Override public boolean requiresUpdateEveryTick(){return true;}
        @Override public void tick(){owner.playLife().tickGoal();}
        @Override public void stop(){owner.playLife().stop();}
    }
    private final class KeepsakeGoal extends Goal {
        KeepsakeGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return !owner.agitation().hunting() && !owner.mind().feeling().subdued() && owner.keepsake().canUse(); }
        @Override public boolean canContinueToUse() { return owner.keepsake().canContinue(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.keepsake().tickGoal(); }
        @Override public void stop() { owner.keepsake().stop(); }
    }
    private final class GroupGoal extends Goal {
        @Override public boolean isInterruptable() { return BehaviorContinuity.emergency(owner) || owner.pet().conscience().catchPending() || !(owner.groupInteraction().active()); }
        GroupGoal(){setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK));}
        @Override public boolean canUse(){return !owner.agitation().hunting() && !owner.mind().feeling().subdued() && owner.groupInteraction().canUse();}
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
        // A catch the owner just made is the one thing that breaks into a song or a workout (the bound penance takes the body).
        @Override public boolean isInterruptable() { return BehaviorContinuity.emergency(owner) || owner.pet().conscience().catchPending() || !(owner.dailyLife().active()); }
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
            return BehaviorContinuity.emergency(owner) || owner.social().humanContact() || !owner.social().active()
                    || owner.family().canAnswerDenial();
        }
        SocialGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return !owner.mind().feeling().subdued() && owner.social().canUse(); }
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
        @Override public boolean canUse() {
            var water = owner.waterDistress();
            // A failed last step on the dry lip must give other movement goals a turn.
            boolean seekingBank = water.escaping() && (owner.isInWaterOrBubble() || runner == null);
            return (seekingBank || water.climbing() || water.restingBreath() || incident != null && out()
                    || runner != null && runner.active()) && !bodyOwnedInWater() && !owner.predatorFlying();
        }
        @Override public void start() {
            if (owner.isInWaterOrBubble() && runner != null && !runner.active()) runner = null;
        }
        private boolean bodyOwnedInWater() {
            var intent = Drive.running(owner, "aquatic");
            return intent != null && intent.ownsBodyInWater() || owner.social().ownsBodyInWater();
        }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() {
            boolean struggles = owner.waterDistress().struggles();
            // out (standing, aboard, held): the fall is over, and a fall during the breath is a new incident
            if (incident != null && out()) settle();
            // After the plunge's impact, or as soon as a wading body can work its way out.
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
            if (owner.isAlive()) owner.life().personality().experienced(Trait.BOLDNESS, 1, "water_escape");
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
                || owner.family().watchingHome() || !(owner.family().active() && !owner.family().yieldsToComfort()); }
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
    boolean canJoinDeparture() {
        return goalSelector.getAvailableGoals().stream().noneMatch(goal -> goal.isRunning()
                && (goal.getPriority() <= TrystGoal.PRIORITY || !goal.isInterruptable())
                && (goal.getFlags().contains(Goal.Flag.MOVE) || goal.getFlags().contains(Goal.Flag.LOOK)));
    }
    /** The actual higher-priority MOVE owner which can displace family, using the trace identity. */
    String competingMoveGoalId() {
        return goalSelector.getAvailableGoals().stream()
                .filter(goal -> goal.isRunning() && goal.getPriority() < FAMILY_PRIORITY && goal.getFlags().contains(Goal.Flag.MOVE))
                .map(goal -> goal.getGoal().toString() + "@" + goal.getPriority()).findFirst().orElse(null);
    }
}
