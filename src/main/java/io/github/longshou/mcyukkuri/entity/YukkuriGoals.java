package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.life.EcologyRoutines;
import io.github.longshou.mcyukkuri.entity.presentation.PresentationGoals;
import io.github.longshou.mcyukkuri.event.HomeClaim;
import io.github.longshou.mcyukkuri.event.HomeClaimGoal;
import io.github.longshou.mcyukkuri.event.WildEventGoal;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.human.FactoryWorkGoal;
import io.github.longshou.mcyukkuri.life.AgitationLife;
import io.github.longshou.mcyukkuri.mind.MindLife;
import io.github.longshou.mcyukkuri.pet.PetLife;
import io.github.longshou.mcyukkuri.predator.PredatorLife;
import io.github.longshou.mcyukkuri.settlement.ForageGoal;
import io.github.longshou.mcyukkuri.settlement.ForageTrip;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.status.Reactions;
import java.util.EnumSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.TemptGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.phys.Vec3;

/** Existing ordered goals and their presentation identity bindings. No second component catalogue. */
final class YukkuriGoals {
    private final YukkuriEntity owner;
    private final GoalSelector goalSelector;
    private PresentationGoals presentationGoals;
    YukkuriGoals(YukkuriEntity owner, GoalSelector goalSelector) {
        this.owner = owner;
        this.goalSelector = goalSelector;
    }
    void registerGoals() {
        owner.initializeComponents();
        presentationGoals = new PresentationGoals(owner);
        goalSelector.addGoal(0, new EscapeWaterGoal());
        goalSelector.addGoal(0, new HouseExitGoal());
        goalSelector.addGoal(0, new OperationGoal());
        goalSelector.addGoal(1, new FleeGoal());
        owner.createMyon();
        goalSelector.addGoal(1, owner.myon().goal());
        goalSelector.addGoal(1, presentationGoals.bind(PresentationGoals.MIND, new MindLife.FrenzyGoal(owner)));
        owner.createSanction();
        goalSelector.addGoal(2, presentationGoals.bind(PresentationGoals.SANCTION, owner.sanction()));
        owner.createHeadwear();
        goalSelector.addGoal(3, presentationGoals.bind(PresentationGoals.HEADWEAR, owner.headwear()));
        goalSelector.addGoal(1, presentationGoals.bind(PresentationGoals.THREAT, new Reactions.ThreatGoal(owner)));
        goalSelector.addGoal(2, presentationGoals.bind(PresentationGoals.WEATHER, new Reactions.WeatherGoal(owner)));
        goalSelector.addGoal(1, new ShellGoal());
        goalSelector.addGoal(2, new HoldActionGoal());
        goalSelector.addGoal(2, presentationGoals.bind(PresentationGoals.CARE_RECEIVER, new CareReceiverGoal()));
        goalSelector.addGoal(2, presentationGoals.bind(PresentationGoals.COMFORT_RECEIVER, new ComfortReceiverGoal()));
        goalSelector.addGoal(2, new PetLife.SessionGoal(owner));
        goalSelector.addGoal(3, presentationGoals.bind(PresentationGoals.PREDATOR, new PredatorLife.BehaviorGoal(owner)));
        owner.createAquaticLife();
        goalSelector.addGoal(3, presentationGoals.bind(PresentationGoals.AQUATIC, owner.aquaticLife()));
        goalSelector.addGoal(3, presentationGoals.bind(PresentationGoals.CHILDHOOD, new ChildhoodGoal()));
        goalSelector.addGoal(3, presentationGoals.bind(PresentationGoals.LIFE, new LifeGoal()));
        goalSelector.addGoal(3, presentationGoals.bind(PresentationGoals.HOME_CLAIM, new HomeClaimGoal(owner)));
        goalSelector.addGoal(4, presentationGoals.bind(PresentationGoals.FACTORY, new FactoryWorkGoal(owner)));
        goalSelector.addGoal(4, new TemptGoal(owner, 1.15, Ingredient.of(YukkuriEntity.FOOD), false) {
            @Override public boolean canUse() { return owner.life().canEat() && super.canUse() && owner.life().personality().fear(player.getUUID()) < BondLevels.SOCIAL_FEAR_LIMIT; }
            @Override public boolean canContinueToUse() { return owner.life().canEat() && super.canContinueToUse(); }
        });
        goalSelector.addGoal(5, presentationGoals.bind(PresentationGoals.FAMILY, new FamilyGoal()));
        goalSelector.addGoal(5, new AgitationLife.BehaviorGoal(owner));
        goalSelector.addGoal(1, new AgitationLife.HeldGoal(owner));
        goalSelector.addGoal(4, presentationGoals.bind(PresentationGoals.HOUSE, new HouseGoal()));
        owner.createForageTrip();
        goalSelector.addGoal(ForageTrip.GOAL_PRIORITY, presentationGoals.bind(PresentationGoals.FORAGE_TRIP, owner.forageTrip()));
        goalSelector.addGoal(4, presentationGoals.bind(PresentationGoals.FORAGE, new ForageGoal(owner)));
        owner.createTreasure();
        goalSelector.addGoal(4, presentationGoals.bind(PresentationGoals.TREASURE, owner.treasure()));
        owner.createCannibalism();
        goalSelector.addGoal(2, presentationGoals.bind(PresentationGoals.CANNIBALISM, owner.cannibalism()));
        owner.createOffering();
        goalSelector.addGoal(2, presentationGoals.bind(PresentationGoals.SELF_OFFERING, owner.offering()));
        goalSelector.addGoal(4, presentationGoals.bind(PresentationGoals.HABITAT, new HabitatGoal()));
        owner.createIndependence();
        // Priority 4: at 6 the (also priority 6) social goals kept the move flag whenever the child was seeking its parents' company, so the leave-taking never got its turn.
        goalSelector.addGoal(4, presentationGoals.bind(PresentationGoals.INDEPENDENCE, owner.independence()));
        goalSelector.addGoal(6, presentationGoals.bind(PresentationGoals.DUTIES, new SettlementDutyGoal()));
        goalSelector.addGoal(6, presentationGoals.bind(PresentationGoals.SETTLEMENT, new SettlementGoal()));
        // A migration (discussion, packing, march) outranks daily, social and family routines; the life needs (3) still come first.
        goalSelector.addGoal(4, presentationGoals.bind(PresentationGoals.MIGRATION, new MigrationGoal()));
        goalSelector.addGoal(5, presentationGoals.bind(PresentationGoals.WILD_EVENT, new WildEventGoal(owner)));
        goalSelector.addGoal(4, presentationGoals.bind(PresentationGoals.COMFORT_GIVER, new ComfortGoal()));
        goalSelector.addGoal(5, presentationGoals.bind(PresentationGoals.DAILY, new DailyGoal()));
        goalSelector.addGoal(6, presentationGoals.bind(PresentationGoals.SOCIAL, new SocialGoal()));
        goalSelector.addGoal(5, presentationGoals.bind(PresentationGoals.GROUP, new GroupGoal()));
        goalSelector.addGoal(6,presentationGoals.bind(PresentationGoals.PLAY, new PlaygroundGoal()));
        owner.createWintering();
        goalSelector.addGoal(7, presentationGoals.bind(PresentationGoals.WINTERING, owner.wintering()));
        goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(owner, 1.0) {
            // House residents with a nest or corner do not roam the room: their patrols are the scouting rounds of the house goal.
            @Override public boolean canUse() { return owner.childhood().canLearn() && (!EcologyRoutines.house(owner) || owner.house().homeless()) && super.canUse(); }
            @Override public boolean canContinueToUse() { return owner.childhood().canLearn() && (!EcologyRoutines.house(owner) || owner.house().homeless()) && super.canContinueToUse(); }
        });
        goalSelector.addGoal(8, new LookAtPlayerGoal(owner, Player.class, 6.0F));
        goalSelector.addGoal(9, new RandomLookAroundGoal(owner));
        owner.registerComponentGoals(goalSelector);
    }
    private final class CareReceiverGoal extends Goal {
        CareReceiverGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return owner.careApproach().waiting(); }
        @Override public boolean canContinueToUse() { return owner.careApproach().waiting(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.careApproach().tickReceiver(); }
        @Override public void stop() { owner.careApproach().cancelIncoming(); }
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
        @Override public boolean canUse() { return owner.requestedActionValue() != null; }
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
        @Override public boolean canUse() { return owner.panicTicksValue() > 0 && !owner.shellRetracts() && !owner.shelteredInHat(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() {
            if (owner.panicTicksValue() > 100) { owner.directNavigation().stop(); return; }
            if (owner.directNavigation().isDone() || owner.tickCount % 10 == 0) {
                if (owner.getLastHurtByMob() instanceof YukkuriEntity threat && threat.profile().dangerToYukkuri()) {
                    if (owner.pet().fleeToOwner()) return;
                    var safe = owner.life().homeStand();
                    if (safe != null && owner.life().navigate(safe, 1.7, false)) return;
                }
                Vec3 target = owner.getLastHurtByMob() == null ? DefaultRandomPos.getPos(owner, 6, 3)
                        : DefaultRandomPos.getPosAway(owner, 6, 3, owner.getLastHurtByMob().position());
                if (target != null) owner.directNavigation().moveTo(target.x, target.y, target.z, 1.7);
            }
        }
        @Override public void stop() { owner.directNavigation().stop(); }
    }
    private final class LifeGoal extends Goal {
        LifeGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return owner.life().plan(); }
        @Override public boolean canContinueToUse() { return owner.life().plan(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.life().tickGoal(); }
        @Override public void stop() { owner.life().stopGoal(); }
    }
    private final class HabitatGoal extends Goal {
        HabitatGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return !HomeClaim.holds(owner) && owner.habitat().canUse(); }
        @Override public boolean canContinueToUse() { return !HomeClaim.holds(owner) && owner.habitat().canContinue(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.habitat().tickGoal(); }
        @Override public void stop() { owner.habitat().stop(); }
    }
    private final class SettlementDutyGoal extends Goal {
        SettlementDutyGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return owner.settlement().duties().canUse(); }
        @Override public boolean canContinueToUse() { return owner.settlement().duties().canContinue(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.settlement().duties().tickGoal(); }
        @Override public void stop() { owner.settlement().duties().stop(); }
    }
    private final class SettlementGoal extends Goal {
        SettlementGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return !HomeClaim.holds(owner) && !owner.settlement().underway() && owner.settlement().canUse(); }
        @Override public boolean canContinueToUse() { return !HomeClaim.holds(owner) && owner.settlement().canContinue(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.settlement().tickGoal(); }
        @Override public void stop() { owner.settlement().stop(); }
    }
    /** The same settlement walk while this member takes part in a migration that has not reached the new site. */
    private final class MigrationGoal extends Goal {
        MigrationGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return !HomeClaim.holds(owner) && owner.settlement().underway() && owner.settlement().canUse(); }
        @Override public boolean canContinueToUse() { return !HomeClaim.holds(owner) && owner.settlement().canContinue(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.settlement().tickGoal(); }
        @Override public void stop() { owner.settlement().stop(); }
    }
    private final class PlaygroundGoal extends Goal {
        PlaygroundGoal(){setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK,Flag.JUMP));}
        @Override public boolean canUse(){return owner.playLife().canUse();}
        @Override public boolean canContinueToUse(){return owner.playLife().canContinue();}
        @Override public boolean requiresUpdateEveryTick(){return true;}
        @Override public void tick(){owner.playLife().tickGoal();}
        @Override public void stop(){owner.playLife().stop();}
    }
    private final class GroupGoal extends Goal {
        GroupGoal(){setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK));}
        @Override public boolean canUse(){return owner.groupInteraction().canUse();}
        @Override public boolean canContinueToUse(){return owner.groupInteraction().canContinueToUse();}
        @Override public boolean requiresUpdateEveryTick(){return true;}
        @Override public void tick(){owner.groupInteraction().tick();}
        @Override public void stop(){owner.groupInteraction().stop();}
    }
    /** A mate or kin walks up to an injured companion and licks it in worry (comfort only, never treatment). */
    private final class ComfortGoal extends Goal {
        ComfortGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return owner.woundComfort().plan(); }
        @Override public boolean canContinueToUse() { return owner.woundComfort().licking() && owner.woundComfort().valid(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.woundComfort().tickGoal(); }
        @Override public void stop() { owner.woundComfort().stop(); }
    }
    /** The injured companion stays and faces the kin that came to lick it. */
    private final class ComfortReceiverGoal extends Goal {
        ComfortReceiverGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return owner.woundComfort().receiving(); }
        @Override public boolean canContinueToUse() { return owner.woundComfort().receiving(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.woundComfort().tickReceiver(); }
        @Override public void stop() { owner.woundComfort().stop(); }
    }
    private final class DailyGoal extends Goal {
        DailyGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return owner.dailyLife().plan(); }
        @Override public boolean canContinueToUse() { return owner.dailyLife().valid(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.dailyLife().tickGoal(); }
        @Override public void stop() { owner.dailyLife().stop(); }
    }
    private final class SocialGoal extends Goal {
        SocialGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return owner.social().canUse(); }
        @Override public boolean canContinueToUse() { return owner.social().canContinue(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.social().tickGoal(); }
        @Override public void stop() { owner.social().stop(); }
    }
    private final class EscapeWaterGoal extends Goal {
        EscapeWaterGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP, Flag.LOOK)); }
        @Override public boolean canUse() { return owner.isInWaterOrBubble() || owner.waterDistress().restingBreath(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.waterDistress().escapeTick(); }
        @Override public void stop() { owner.directNavigation().stop(); }
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
        @Override public boolean canUse(){return owner.house().canUse();}
        @Override public boolean canContinueToUse(){return owner.house().canContinue();}
        @Override public boolean requiresUpdateEveryTick(){return true;}
        @Override public void tick(){owner.house().tickGoal();}
        @Override public void stop(){owner.house().stop();}
    }
    private final class FamilyGoal extends Goal {
        FamilyGoal() { setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK)); }
        @Override public boolean canUse() { return (!WildEvents.reserved(owner)||owner.life().hasUrgentNeed())&&owner.family().canUse(); }
        @Override public boolean canContinueToUse() { return (!WildEvents.reserved(owner)||owner.life().hasUrgentNeed())&&owner.family().canContinue(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { owner.family().tickGoal(); }
        @Override public void stop() { owner.family().stop(); }
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
}
