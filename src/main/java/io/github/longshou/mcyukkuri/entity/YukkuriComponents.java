package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.life.EcologyRoutines;
import io.github.longshou.mcyukkuri.entity.component.ComponentKey;
import io.github.longshou.mcyukkuri.entity.component.ComponentSpec;
import io.github.longshou.mcyukkuri.entity.component.ComponentSpec.TickPhase;
import io.github.longshou.mcyukkuri.entity.component.ComponentSpec.Initialization;
import io.github.longshou.mcyukkuri.entity.component.ComponentGoal;
import io.github.longshou.mcyukkuri.entity.component.LoadContext;
import io.github.longshou.mcyukkuri.entity.component.Interrupt;
import io.github.longshou.mcyukkuri.entity.component.InterruptContext;
import io.github.longshou.mcyukkuri.entity.component.InterruptPhase;
import io.github.longshou.mcyukkuri.entity.component.SyncSlot;
import io.github.longshou.mcyukkuri.rare.RareForms;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import io.github.longshou.mcyukkuri.subspecies.AquaticLife;
import io.github.longshou.mcyukkuri.life.YukkuriLife;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.life.WaterDistress;
import io.github.longshou.mcyukkuri.pet.PetLife;
import io.github.longshou.mcyukkuri.social.YukkuriSocial;
import io.github.longshou.mcyukkuri.interaction.OperationLife;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.family.ChildhoodLife;
import io.github.longshou.mcyukkuri.house.HouseLife;
import io.github.longshou.mcyukkuri.settlement.SettlementLife;
import io.github.longshou.mcyukkuri.settlement.HabitatLife;
import io.github.longshou.mcyukkuri.social.GroupInteractionGoal;
import io.github.longshou.mcyukkuri.social.TreasureLife;
import io.github.longshou.mcyukkuri.headwear.Headwear;
import io.github.longshou.mcyukkuri.social.Cannibalism;
import io.github.longshou.mcyukkuri.social.GriefLife;
import io.github.longshou.mcyukkuri.social.SelfOffering;
import io.github.longshou.mcyukkuri.play.PlayLife;
import io.github.longshou.mcyukkuri.social.DailyLife;
import io.github.longshou.mcyukkuri.social.WoundComfort;
import io.github.longshou.mcyukkuri.predator.PredatorLife;
import io.github.longshou.mcyukkuri.status.StatusLife;
import io.github.longshou.mcyukkuri.life.SpeciesLife;
import io.github.longshou.mcyukkuri.rare.MyonLife;
import io.github.longshou.mcyukkuri.life.AgitationLife;
import io.github.longshou.mcyukkuri.mind.MindLife;
import io.github.longshou.mcyukkuri.family.IndependenceLife;
import io.github.longshou.mcyukkuri.body.BodyLife;
import io.github.longshou.mcyukkuri.life.SurfaceLife;
import io.github.longshou.mcyukkuri.headwear.Sanction;
import io.github.longshou.mcyukkuri.settlement.ForageTrip;
import io.github.longshou.mcyukkuri.family.CareApproach;
import io.github.longshou.mcyukkuri.season.WinteringLife;
import io.github.longshou.mcyukkuri.subspecies.ShellRetraction;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.ai.goal.GoalSelector;

/** The sole component catalogue. New components declare their factory, independent lifecycle
 * orders, optional goals and sync slots here; the entity needs no additional field or getter.
 * Existing constructors/goals stay in place until E6. Getters below only adapt those instances.
 */
public final class YukkuriComponents {
    public static final int SAVE_VERSION = 1;
    public static final ComponentKey<SubspeciesLife> SUBSPECIES = new ComponentKey<>("subspecies", SubspeciesLife.class);
    public static final ComponentKey<AquaticLife> AQUATIC_LIFE = new ComponentKey<>("aquaticLife", AquaticLife.class);
    public static final ComponentKey<YukkuriLife> LIFE = new ComponentKey<>("life", YukkuriLife.class);
    public static final ComponentKey<WaterDistress> WATER_DISTRESS = new ComponentKey<>("waterDistress", WaterDistress.class);
    public static final ComponentKey<PetLife> PET = new ComponentKey<>("pet", PetLife.class);
    public static final ComponentKey<YukkuriSocial> SOCIAL = new ComponentKey<>("social", YukkuriSocial.class);
    public static final ComponentKey<OperationLife> OPERATIONS = new ComponentKey<>("operations", OperationLife.class);
    public static final ComponentKey<FamilyLife> FAMILY = new ComponentKey<>("family", FamilyLife.class);
    public static final ComponentKey<ChildhoodLife> CHILDHOOD = new ComponentKey<>("childhood", ChildhoodLife.class);
    public static final ComponentKey<HouseLife> HOUSE = new ComponentKey<>("house", HouseLife.class);
    public static final ComponentKey<SettlementLife> SETTLEMENT = new ComponentKey<>("settlement", SettlementLife.class);
    public static final ComponentKey<HabitatLife> HABITAT = new ComponentKey<>("habitat", HabitatLife.class);
    public static final ComponentKey<GroupInteractionGoal> GROUP_INTERACTION = new ComponentKey<>("groupInteraction", GroupInteractionGoal.class);
    public static final ComponentKey<TreasureLife> TREASURE = new ComponentKey<>("treasure", TreasureLife.class);
    public static final ComponentKey<Headwear> HEADWEAR = new ComponentKey<>("headwear", Headwear.class);
    public static final ComponentKey<Cannibalism> CANNIBALISM = new ComponentKey<>("cannibalism", Cannibalism.class);
    public static final ComponentKey<GriefLife> GRIEF = new ComponentKey<>("grief", GriefLife.class);
    public static final ComponentKey<SelfOffering> OFFERING = new ComponentKey<>("offering", SelfOffering.class);
    public static final ComponentKey<PlayLife> PLAY_LIFE = new ComponentKey<>("playLife", PlayLife.class);
    public static final ComponentKey<DailyLife> DAILY_LIFE = new ComponentKey<>("dailyLife", DailyLife.class);
    public static final ComponentKey<WoundComfort> WOUND_COMFORT = new ComponentKey<>("woundComfort", WoundComfort.class);
    public static final ComponentKey<PredatorLife> PREDATOR = new ComponentKey<>("predator", PredatorLife.class);
    public static final ComponentKey<StatusLife> STATUS = new ComponentKey<>("status", StatusLife.class);
    public static final ComponentKey<SpeciesLife> SPECIES = new ComponentKey<>("species", SpeciesLife.class);
    public static final ComponentKey<MyonLife> MYON = new ComponentKey<>("myon", MyonLife.class);
    public static final ComponentKey<AgitationLife> AGITATION = new ComponentKey<>("agitation", AgitationLife.class);
    public static final ComponentKey<MindLife> MIND = new ComponentKey<>("mind", MindLife.class);
    public static final ComponentKey<IndependenceLife> INDEPENDENCE = new ComponentKey<>("independence", IndependenceLife.class);
    public static final ComponentKey<BodyLife> BODY = new ComponentKey<>("body", BodyLife.class);
    public static final ComponentKey<SurfaceLife> SURFACE_LIFE = new ComponentKey<>("surfaceLife", SurfaceLife.class);
    public static final ComponentKey<Sanction> SANCTION = new ComponentKey<>("sanction", Sanction.class);
    public static final ComponentKey<ForageTrip> FORAGE_TRIP = new ComponentKey<>("forageTrip", ForageTrip.class);
    public static final ComponentKey<CareApproach> CARE_APPROACH = new ComponentKey<>("careApproach", CareApproach.class);
    public static final ComponentKey<WinteringLife> WINTERING = new ComponentKey<>("wintering", WinteringLife.class);
    public static final ComponentKey<ShellRetraction> SHELL_RETRACTION = new ComponentKey<>("shellRetraction", ShellRetraction.class);
    private static final ComponentKey<YukkuriEntity> HOOKS = new ComponentKey<>("entityHooks", YukkuriEntity.class);

    // Numeric orders follow the lifecycle baseline, with gaps for explicit insertions.
    // Wintering is saved/loaded by Life; AquaticLife by Subspecies. Neither is persisted twice.
    // Cannibalism cooldown and Sanction respite already have their own persistence owners.
    private static final List<ComponentSpec<?>> SPECS = List.of(
        ComponentSpec.ready(SUBSPECIES, YukkuriEntity::subspecies)
                .tick(10, SubspeciesLife::tick)
                .save(40, (c, tag) -> tag.put("YukkuriSubspecies", c.save()))
                .load(10, (c, ctx) -> c.load(ctx.root().getCompound("YukkuriSubspecies"))).build(),
        ComponentSpec.ready(AQUATIC_LIFE, YukkuriEntity::aquaticLife)
                .tick(20, EcologyRoutines::tick)
                .interrupt(Interrupt.DEATH, InterruptPhase.DEATH_ONCE, 10, (c, ctx) -> c.dropStick()).build(),
        ComponentSpec.ready(LIFE, YukkuriEntity::life)
                .tick(30, YukkuriLife::tick)
                .save(10, (c, tag) -> tag.put("YukkuriLife", c.save()))
                .load(20, (c, ctx) -> c.load(ctx.root().getCompound("YukkuriLife")))
                .interrupt(Interrupt.FRIGHT, InterruptPhase.MAIN, 80, (c, ctx) -> c.interrupt())
                .interrupt(Interrupt.COMMAND, InterruptPhase.MAIN, 80, (c, ctx) -> c.interrupt())
                .interrupt(Interrupt.HURT, InterruptPhase.AFTER_ATTACK_NOTIFICATIONS, 20, (c, ctx) -> c.interrupt())
                .interrupt(Interrupt.BURNING, InterruptPhase.MAIN, 20, (c, ctx) -> c.interrupt())
                .interrupt(Interrupt.PET_LOCK, InterruptPhase.MAIN, 10, (c, ctx) -> c.interrupt())
                .interrupt(Interrupt.DEATH, InterruptPhase.DEATH_FINISH, 10, (c, ctx) -> c.interrupt())
                .interrupt(Interrupt.DEATH, InterruptPhase.DEATH_FINISH, 30, (c, ctx) -> c.releaseHome()).build(),
        ComponentSpec.ready(WATER_DISTRESS, YukkuriEntity::waterDistress)
                .tick(40, WaterDistress::tick)
                .save(20, (c, tag) -> tag.put("WaterDistress", c.save()))
                .load(250, (c, ctx) -> c.load(ctx.root().getCompound("WaterDistress"))).build(),
        ComponentSpec.ready(PET, YukkuriEntity::pet)
                .tick(60, PetLife::tick)
                .save(50, (c, tag) -> tag.put("YukkuriPet", c.save()))
                .load(40, (c, ctx) -> c.load(ctx.root().getCompound("YukkuriPet")))
                .interrupt(Interrupt.DEATH, InterruptPhase.DEATH_PREPARE, 10, (c, ctx) -> c.care().died()).build(),
        ComponentSpec.ready(SOCIAL, YukkuriEntity::social)
                .tick(70, YukkuriSocial::tick)
                .interrupt(Interrupt.FRIGHT, InterruptPhase.MAIN, 70, (c, ctx) -> c.stop())
                .interrupt(Interrupt.COMMAND, InterruptPhase.MAIN, 90, (c, ctx) -> c.stop())
                .interrupt(Interrupt.HURT, InterruptPhase.AFTER_ATTACK_NOTIFICATIONS, 30, (c, ctx) -> c.stop())
                .interrupt(Interrupt.BURNING, InterruptPhase.MAIN, 30, (c, ctx) -> c.stop())
                .interrupt(Interrupt.PET_LOCK, InterruptPhase.MAIN, 20, (c, ctx) -> c.stop())
                .interrupt(Interrupt.DEATH, InterruptPhase.DEATH_FINISH, 20, (c, ctx) -> c.stop()).build(),
        ComponentSpec.ready(OPERATIONS, YukkuriEntity::operations)
                .tick(80, OperationLife::tick)
                .save(270, (c, tag) -> tag.put("YukkuriOperations", c.save()))
                .load(240, (c, ctx) -> c.load(ctx.root().getCompound("YukkuriOperations")))
                .interrupt(Interrupt.FRIGHT, InterruptPhase.MAIN, 20, (c, ctx) -> c.end())
                .interrupt(Interrupt.COMMAND, InterruptPhase.MAIN, 30, (c, ctx) -> c.end())
                .interrupt(Interrupt.DEATH, InterruptPhase.DEATH_CLEANUP, 60, (c, ctx) -> c.end()).build(),
        ComponentSpec.ready(FAMILY, YukkuriEntity::family)
                .tick(90, FamilyLife::tick)
                .save(150, (c, tag) -> tag.put("YukkuriFamily", c.save()))
                .load(170, (c, ctx) -> { if (ctx.root().contains("YukkuriFamily")) c.load(ctx.root().getCompound("YukkuriFamily")); })
                .interrupt(Interrupt.DEATH, InterruptPhase.DEATH_PREPARE, 60, (c, ctx) -> c.cancelBraidRescue())
                .interrupt(Interrupt.DEATH, InterruptPhase.DEATH_ONCE, 20, (c, ctx) -> c.died()).build(),
        ComponentSpec.ready(CHILDHOOD, YukkuriEntity::childhood)
                .tick(100, ChildhoodLife::tick)
                .save(170, (c, tag) -> tag.put("YukkuriChildhood", c.save()))
                .load(180, (c, ctx) -> c.load(ctx.root().getCompound("YukkuriChildhood"))).build(),
        ComponentSpec.ready(HOUSE, YukkuriEntity::house)
                .tick(110, EcologyRoutines::tick)
                .save(180, (c, tag) -> tag.put("YukkuriHouse", c.save()))
                .load(200, (c, ctx) -> { if (ctx.root().contains("YukkuriHouse")) c.load(ctx.root().getCompound("YukkuriHouse")); })
                .interrupt(Interrupt.DEATH, InterruptPhase.DEATH_ONCE, 30, (c, ctx) -> c.dropCarrying()).build(),
        ComponentSpec.ready(SETTLEMENT, YukkuriEntity::settlement)
                .tick(120, SettlementLife::tick)
                .save(280, (c, tag) -> tag.put("YukkuriSettlement", c.save()))
                .load(270, (c, ctx) -> c.load(ctx.root().getCompound("YukkuriSettlement")))
                .interrupt(Interrupt.DEATH, InterruptPhase.DEATH_ONCE, 60, (c, ctx) -> c.duties().dropCarrying()).build(),
        ComponentSpec.ready(HABITAT, YukkuriEntity::habitat)
                .tick(130, HabitatLife::tick)
                .save(290, (c, tag) -> tag.put("YukkuriHabitat", c.save()))
                .load(280, (c, ctx) -> c.load(ctx.root().getCompound("YukkuriHabitat")))
                .interrupt(Interrupt.DEATH, InterruptPhase.DEATH_ONCE, 40, (c, ctx) -> c.dropCarrying()).build(),
        ComponentSpec.ready(GROUP_INTERACTION, YukkuriEntity::groupInteraction)
                .tick(140, GroupInteractionGoal::clock)
                .save(120, (c, tag) -> tag.put("YukkuriGroupInteraction", c.save()))
                .load(120, (c, ctx) -> c.load(ctx.root().getCompound("YukkuriGroupInteraction")))
                .interrupt(Interrupt.FRIGHT, InterruptPhase.MAIN, 30, (c, ctx) -> c.stop())
                .interrupt(Interrupt.COMMAND, InterruptPhase.MAIN, 40, (c, ctx) -> c.stop())
                .interrupt(Interrupt.HURT, InterruptPhase.AFTER_STATUS_HURT, 10, (c, ctx) -> c.stop())
                .interrupt(Interrupt.PET_LOCK, InterruptPhase.MAIN, 30, (c, ctx) -> c.stop())
                .interrupt(Interrupt.DEATH, InterruptPhase.DEATH_CLEANUP, 10, (c, ctx) -> c.stop()).build(),
        ComponentSpec.ready(TREASURE, YukkuriEntity::treasure)
                .tick(150, TreasureLife::clock)
                .save(80, (c, tag) -> tag.put("YukkuriTreasure", c.save()))
                .load(70, (c, ctx) -> c.load(ctx.root().getCompound("YukkuriTreasure")))
                .interrupt(Interrupt.DEATH, InterruptPhase.DEATH_PREPARE, 20, (c, ctx) -> c.dropAll()).build(),
        ComponentSpec.ready(HEADWEAR, YukkuriEntity::headwear)
                .tick(160, Headwear::clock)
                .save(90, (c, tag) -> tag.put("YukkuriHeadwear", c.save()))
                .load(80, (c, ctx) -> c.load(ctx.root().getCompound("YukkuriHeadwear"))).build(),
        ComponentSpec.ready(CANNIBALISM, YukkuriEntity::cannibalism)
                .tick(170, Cannibalism::clock)
                .interrupt(Interrupt.REMOVED, InterruptPhase.MAIN, 20, (c, ctx) -> c.stop()).build(),
        ComponentSpec.ready(GRIEF, YukkuriEntity::grief)
                .tick(180, GriefLife::clock).build(),
        ComponentSpec.ready(OFFERING, YukkuriEntity::offering)
                .tick(190, SelfOffering::clock)
                .save(100, (c, tag) -> tag.put("YukkuriOffering", c.save()))
                .load(90, (c, ctx) -> c.load(ctx.root().getCompound("YukkuriOffering"))).build(),
        ComponentSpec.ready(PLAY_LIFE, YukkuriEntity::playLife)
                .tick(200, PlayLife::clock)
                .save(140, (c, tag) -> tag.put("YukkuriPlay", c.save()))
                .load(140, (c, ctx) -> c.load(ctx.root().getCompound("YukkuriPlay")))
                .interrupt(Interrupt.FRIGHT, InterruptPhase.MAIN, 60, (c, ctx) -> c.stop())
                .interrupt(Interrupt.COMMAND, InterruptPhase.MAIN, 70, (c, ctx) -> c.stop())
                .interrupt(Interrupt.HURT, InterruptPhase.AFTER_STATUS_HURT, 40, (c, ctx) -> c.stop())
                .interrupt(Interrupt.PET_LOCK, InterruptPhase.MAIN, 40, (c, ctx) -> c.stop())
                .interrupt(Interrupt.DEATH, InterruptPhase.DEATH_CLEANUP, 40, (c, ctx) -> c.stop()).build(),
        ComponentSpec.ready(DAILY_LIFE, YukkuriEntity::dailyLife)
                .tick(210, DailyLife::clock)
                .save(60, (c, tag) -> tag.put("YukkuriDailyLife", c.save()))
                .load(50, (c, ctx) -> c.load(ctx.root().getCompound("YukkuriDailyLife")))
                .interrupt(Interrupt.FRIGHT, InterruptPhase.MAIN, 40, (c, ctx) -> c.stop())
                .interrupt(Interrupt.COMMAND, InterruptPhase.MAIN, 50, (c, ctx) -> c.stop())
                .interrupt(Interrupt.HURT, InterruptPhase.AFTER_STATUS_HURT, 20, (c, ctx) -> c.stop())
                .interrupt(Interrupt.PET_LOCK, InterruptPhase.MAIN, 50, (c, ctx) -> c.stop())
                .interrupt(Interrupt.DEATH, InterruptPhase.DEATH_CLEANUP, 20, (c, ctx) -> c.stop()).build(),
        ComponentSpec.ready(WOUND_COMFORT, YukkuriEntity::woundComfort)
                .tick(220, WoundComfort::clock)
                .save(110, (c, tag) -> tag.put("YukkuriWoundComfort", c.save()))
                .load(110, (c, ctx) -> c.load(ctx.root().getCompound("YukkuriWoundComfort")))
                .interrupt(Interrupt.FRIGHT, InterruptPhase.MAIN, 50, (c, ctx) -> c.stop())
                .interrupt(Interrupt.COMMAND, InterruptPhase.MAIN, 60, (c, ctx) -> c.stop())
                .interrupt(Interrupt.HURT, InterruptPhase.AFTER_STATUS_HURT, 30, (c, ctx) -> c.stop())
                .interrupt(Interrupt.DEATH, InterruptPhase.DEATH_CLEANUP, 30, (c, ctx) -> c.stop()).build(),
        ComponentSpec.ready(PREDATOR, YukkuriEntity::predator)
                .tick(230, EcologyRoutines::tick)
                .interrupt(Interrupt.PET_LOCK, InterruptPhase.MAIN, 60, (c, ctx) -> c.stop())
                .interrupt(Interrupt.REMOVED, InterruptPhase.MAIN, 50, (c, ctx) -> c.stop()).build(),
        ComponentSpec.ready(STATUS, YukkuriEntity::status)
                .tick(260, StatusLife::tick)
                .save(230, (c, tag) -> tag.put("YukkuriStatus", c.save()))
                .load(220, (c, ctx) -> c.load(ctx.root().getCompound("YukkuriStatus"))).build(),
        ComponentSpec.ready(SPECIES, YukkuriEntity::species)
                .tick(270, SpeciesLife::tick)
                .save(70, (c, tag) -> tag.put("YukkuriSpecies", c.save()))
                .load(60, (c, ctx) -> c.load(ctx.root().getCompound("YukkuriSpecies"))).build(),
        ComponentSpec.ready(MYON, YukkuriEntity::myon)
                .tick(280, MyonLife::tick)
                .save(250, (c, tag) -> tag.put("YukkuriMyon", c.save()))
                .load(100, (c, ctx) -> c.load(ctx.root().getCompound("YukkuriMyon")))
                .interrupt(Interrupt.DEATH, InterruptPhase.DEATH_PREPARE, 30, (c, ctx) -> c.died()).build(),
        ComponentSpec.ready(AGITATION, YukkuriEntity::agitation)
                .tick(310, AgitationLife::clock)
                .save(30, (c, tag) -> tag.put("YukkuriAgitation", c.save()))
                .load(310, (c, ctx) -> c.load(ctx.root().getCompound("YukkuriAgitation")))
                .interrupt(Interrupt.FRIGHT, InterruptPhase.MAIN, 10, (c, ctx) -> c.stop())
                .interrupt(Interrupt.COMMAND, InterruptPhase.MAIN, 20, (c, ctx) -> c.stop())
                .interrupt(Interrupt.HURT, InterruptPhase.BEFORE_INJURY, 10, (c, ctx) -> c.stop())
                .interrupt(Interrupt.DEATH, InterruptPhase.DEATH_PREPARE, 40, (c, ctx) -> c.stop()).build(),
        ComponentSpec.ready(MIND, YukkuriEntity::mind)
                .tick(330, MindLife::tick)
                .save(240, (c, tag) -> tag.put("YukkuriMind", c.save()))
                .load(230, (c, ctx) -> c.load(ctx.root().getCompound("YukkuriMind"), ctx.root().getCompound("YukkuriPet"))).build(),
        ComponentSpec.ready(INDEPENDENCE, YukkuriEntity::independence)
                .tick(340, IndependenceLife::clock)
                .save(300, (c, tag) -> tag.put("YukkuriIndependence", c.save()))
                .load(290, (c, ctx) -> c.load(ctx.root().getCompound("YukkuriIndependence")))
                .interrupt(Interrupt.DEATH, InterruptPhase.DEATH_ONCE, 50, (c, ctx) -> c.abandon()).build(),
        ComponentSpec.ready(BODY, YukkuriEntity::body)
                .tick(360, BodyLife::tick)
                .save(220, (c, tag) -> tag.put("YukkuriBody", c.save()))
                .load(210, (c, ctx) -> { if (ctx.root().contains("YukkuriBody")) c.load(ctx.root().getCompound("YukkuriBody")); })
                .interrupt(Interrupt.DEATH, InterruptPhase.DEATH_ONCE, 70, (c, ctx) -> c.died(ctx.source(), ctx.stemDepletion(), ctx.sukkiriExhaustion())).build(),
        ComponentSpec.ready(SURFACE_LIFE, YukkuriEntity::surfaceLife)
                .tick(380, SurfaceLife::tick)
                .save(130, (c, tag) -> tag.put("YukkuriSurface", c.save()))
                .load(130, (c, ctx) -> c.load(ctx.root().getCompound("YukkuriSurface"))).build(),
        ComponentSpec.ready(SANCTION, YukkuriEntity::sanction)
                .interrupt(Interrupt.REMOVED, InterruptPhase.MAIN, 10, (c, ctx) -> c.stop()).build(),
        ComponentSpec.ready(FORAGE_TRIP, YukkuriEntity::forageTrip).build(),
        ComponentSpec.ready(CARE_APPROACH, YukkuriEntity::careApproach)
                .interrupt(Interrupt.REMOVED, InterruptPhase.MAIN, 30, (c, ctx) -> c.cancelIncoming())
                .interrupt(Interrupt.REMOVED, InterruptPhase.MAIN, 40, (c, ctx) -> c.cancel()).build(),
        ComponentSpec.ready(WINTERING, YukkuriEntity::wintering).build(),
        ComponentSpec.ready(SHELL_RETRACTION, YukkuriEntity::shellRetraction)
                .interrupt(Interrupt.COMMAND, InterruptPhase.MAIN, 10, (c, ctx) -> c.reset()).build(),
        ComponentSpec.ready(HOOKS, e -> e)
                .tick(50, YukkuriEntity::syncWaterDistress)
                .tick(240, YukkuriEntity::syncFamily)
                .tick(250, YukkuriEntity::syncAquaticInteraction)
                .tick(290, YukkuriEntity::syncKanroken)
                .tick(300, RareForms::tick)
                .tick(320, YukkuriEntity::syncAgitation)
                .tick(350, YukkuriEntity::syncCarryingAndCare)
                .tick(370, YukkuriEntity::syncBody)
                .tick(TickPhase.AFTER_ALIVE_CHECK, 400, YukkuriEntity::syncAppearance)
                .tick(TickPhase.AFTER_ALIVE_CHECK, 410, YukkuriEntity::tickFireAndShellCommand)
                .tick(TickPhase.AFTER_ALIVE_CHECK, 430, YukkuriEntity::tickShellRetraction)
                .save(160, YukkuriEntity::saveDeathRecorded)
                .save(190, (e, tag) -> { if (debugViewsEnabled()) e.saveDebugGoals(tag); })
                .save(200, (e, tag) -> { if (debugViewsEnabled() && e.forageTrip() != null) tag.put("YukkuriForageView", e.forageTrip().debug()); })
                .save(210, (e, tag) -> { if (debugViewsEnabled()) tag.put("YukkuriHabitatView", e.habitat().debug()); })
                .save(260, (e, tag) -> { if (debugViewsEnabled()) e.saveStatusView(tag); })
                .save(310, (e, tag) -> { if (EcologyRoutines.predator(e)) tag.put("YukkuriPredator", e.predator().save()); })
                .load(30, (e, ctx) -> e.resetLoadedCommands())
                .load(150, (e, ctx) -> e.resetLoadedPresentation())
                .load(190, (e, ctx) -> e.loadDeathRecorded(ctx.root()))
                .load(260, (e, ctx) -> e.syncWaterDistress())
                .load(300, (e, ctx) -> { if (EcologyRoutines.predator(e)) { e.predator().load(ctx.root().getCompound("YukkuriPredator")); e.setNoGravity(false); } })
                .load(320, (e, ctx) -> e.syncAgitation())
                .load(330, (e, ctx) -> e.syncHairLoss())
                .interrupt(Interrupt.HURT, InterruptPhase.AFTER_ATTACK_NOTIFICATIONS, 10, (c, ctx) -> c.clearRequestedAction())
                .interrupt(Interrupt.BURNING, InterruptPhase.MAIN, 10, (c, ctx) -> c.clearRequestedAction())
                .interrupt(Interrupt.DEATH, InterruptPhase.DEATH_PREPARE, 50, (c, ctx) -> c.syncAgitation())
                .interrupt(Interrupt.DEATH, InterruptPhase.DEATH_CLEANUP, 50, (c, ctx) -> c.clearRequestedAction())
                .interrupt(Interrupt.DEATH, InterruptPhase.DEATH_FINISH, 40, (c, ctx) -> c.resetDeathPresentation())
                .interrupt(Interrupt.REMOVED, InterruptPhase.MAIN, 60, (c, ctx) -> c.cancelRemovedBrood()).build()
    );

    private static boolean debugViewsEnabled() {
        return YukkuriConfig.SPEC.isLoaded() && YukkuriConfig.DEBUG_ENTITY_SAVE_VIEWS.get();
    }

    private static final List<SyncSlot<?>> SYNC_SLOTS = SPECS.stream().flatMap(spec -> spec.syncSlots().stream()).toList();
    private static boolean schemaInstalled;
    static {
        var ids = new HashSet<String>();
        for (var spec : SPECS) {
            if (!ids.add(spec.key().id())) throw new IllegalStateException("Duplicate component ID: " + spec.key().id());
        }
        if (new HashSet<>(SYNC_SLOTS).size() != SYNC_SLOTS.size()) throw new IllegalStateException("Duplicate component sync slot");
    }

    /** Called exactly once, after YukkuriEntity has declared all legacy accessors. */
    static void installSyncSchema(SyncSlot.Allocator allocator) {
        if (schemaInstalled) throw new IllegalStateException("Component schema already installed");
        for (var slot : SYNC_SLOTS) slot.install(allocator);
        schemaInstalled = true;
    }

    static void defineSynchedData(SynchedEntityData.Builder builder) {
        for (var slot : SYNC_SLOTS) slot.define(builder);
    }

    private record InterruptCall(Interrupt reason, InterruptPhase phase, int order,
            Consumer<InterruptContext> action) {}
    private record TickCall(TickPhase phase, int order, Runnable action) {}
    private record OrderedCall<T>(int order, Consumer<T> action) {}
    private final YukkuriEntity owner;
    private final Map<ComponentKey<?>, Object> instances = new HashMap<>();
    private final List<TickCall> ticks = new ArrayList<>();
    private final List<OrderedCall<CompoundTag>> saves = new ArrayList<>();
    private final List<OrderedCall<LoadContext>> loads = new ArrayList<>();
    private final List<InterruptCall> interruptions = new ArrayList<>();
    private final List<Runnable> goalBindings = new ArrayList<>();
    private boolean goalsRegistered;
    private boolean ready;

    YukkuriComponents(YukkuriEntity owner) { this.owner = owner; }

    public <C> C get(ComponentKey<C> key) {
        return key.type().cast(Objects.requireNonNull(instances.get(key), "Component not initialized: " + key.id()));
    }

    void registerGoals(GoalSelector selector) {
        if (goalsRegistered) throw new IllegalStateException("Component goals already registered");
        goalsRegistered = true;
        for (var spec : SPECS) if (spec.initialization() == Initialization.EARLY) initialize(spec);
        for (var spec : SPECS) registerGoals(spec, selector);
    }

    private <C> void registerGoals(ComponentSpec<C> spec, GoalSelector selector) {
        for (var goal : spec.goals()) {
            var bridge = new ComponentGoal(goal.flags());
            selector.addGoal(goal.priority(), bridge);
            goalBindings.add(() -> bridge.bind(goal.factory().apply(get(spec.key()), owner)));
        }
    }

    void initializeReady() {
        if (ready || !goalsRegistered) throw new IllegalStateException("Invalid component initialization order");
        for (var spec : SPECS) if (spec.initialization() == Initialization.READY) initialize(spec);
        // Bind only after ALL instances exist, never from a parent-constructor method reference.
        goalBindings.forEach(Runnable::run);
        goalBindings.clear();
        ticks.sort(Comparator.comparing(TickCall::phase).thenComparingInt(TickCall::order));
        saves.sort(Comparator.comparingInt(call -> call.order()));
        loads.sort(Comparator.comparingInt(call -> call.order()));
        interruptions.sort(Comparator.comparing(InterruptCall::reason)
                .thenComparing(InterruptCall::phase).thenComparingInt(InterruptCall::order));
        ready = true;
    }

    private <C> void initialize(ComponentSpec<C> spec) {
        C instance = Objects.requireNonNull(spec.factory().apply(owner), "Null component: " + spec.key().id());
        if (instances.putIfAbsent(spec.key(), instance) != null) throw new IllegalStateException("Component initialized twice: " + spec.key().id());
        for (var action : spec.interruptions()) interruptions.add(new InterruptCall(action.reason(), action.phase(),
                action.order(), context -> action.action().accept(instance, context)));
        for (var tick : spec.ticks()) ticks.add(new TickCall(tick.phase(), tick.order(), () -> tick.action().accept(instance)));
        for (var save : spec.saves()) saves.add(new OrderedCall<>(save.order(), tag -> save.action().accept(instance, tag)));
        for (var load : spec.loads()) loads.add(new OrderedCall<>(load.order(), ctx -> load.action().accept(instance, ctx)));
    }

    void interrupt(InterruptContext context, InterruptPhase phase) {
        for (var call : interruptions) {
            if (call.reason() == context.reason() && call.phase() == phase) call.action().accept(context);
        }
    }

    void tick(TickPhase phase) {
        for (var call : ticks) if (call.phase() == phase) call.action().run();
    }
    void save(CompoundTag root) { for (var call : saves) call.action().accept(root); }
    void load(LoadContext context) { for (var call : loads) call.action().accept(context); }
}
