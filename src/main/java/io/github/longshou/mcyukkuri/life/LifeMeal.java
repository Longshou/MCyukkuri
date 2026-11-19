package io.github.longshou.mcyukkuri.life;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.life.Taste.Verdict;
import io.github.longshou.mcyukkuri.body.BodyEffects;
import io.github.longshou.mcyukkuri.body.BodyFood;
import io.github.longshou.mcyukkuri.body.FillingResource;
import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.interaction.ItemContact;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.human.PicnicFood;
import io.github.longshou.mcyukkuri.motor.Braid;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.MouthOccupancy;
import io.github.longshou.mcyukkuri.motor.MouthOccupancy.Purpose;
import io.github.longshou.mcyukkuri.pet.PetConversation;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.pet.exam.ExamSession;
import io.github.longshou.mcyukkuri.social.EcologyPresentation;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import io.github.longshou.mcyukkuri.pet.social.BadgeSocial;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

/** Delegated life state; persistence stays in the original YukkuriLife compound. */
final class LifeMeal {
    private final YukkuriLife o;
    LifeMeal(YukkuriLife o) { this.o = o; }

    /** Project design: one portion of droppings feeds this much, and soils the eater this much hygiene. */
    static final float PIT_MEAL_NUTRITION = 20, PIT_MEAL_DIRT = 8;
    private int mealTicks;
    private record TuftMeal(MealRoute route, MealRoute.Serving source, long start) {}
    private TuftMeal tuftMeal;
    private BlockPos tuftReleaseRefusedAt;
    /** The take-food beat ends when chewing or spitting begins. */
    static final int TAKE_TICKS = 10, SWALLOW_TICKS = 8;
    private static final int SWALLOW_TICK = 45, SLOW_SWALLOW_TICK = 65;
    /** Poll every three ticks so a brief hop can end while the serving is still held. */
    private static final int MEAL_SIGHT_INTERVAL = 3;
    private Verdict mealVerdict = Verdict.PLAIN;
    private Vec3 refusedSpitAt;
    private final LinkedHashMap<Item, Long> complaints = new LinkedHashMap<>();
    private static final int COMPLAINT_TICKS = 2400;
    // A good meal keeps the feeder familiar through a short break in contact.
    private static final int RECENT_FEED_TICKS = 2400;
    private UUID feeder, lastFeeder;
    private long lastFedAt;
    private boolean sharedMeal, preparedMeal, bowlMeal;
    /** Nutrition above which it wants no more food: the bite that takes it past this leaves it full. */
    static final float FULL = 75;
    /** This meal left it full and it lets itself melt in contentment after the swallow (meal_content); transient, like the clock below. */
    private boolean content;
    /** The face this individual melts with and the one it stands up with (see {@link #sated}); null when this meal shows no melt face. */
    private String meltFace, meltTail;
    /** A composed adult never melts, but its meal finish keeps a restrained face instead of the ordinary finish that ends on a broad smile. */
    private String finishFace;
    /** The stand-up beat after the melt: one second of the face it leaves the melt with (project design, no clip authors it). */
    private static final int MELT_TAIL_TICKS = 20;
    /** No contented melt before this tick: a moment after a filling meal, not a habit. */
    private long contentAfter;
    private MouthOccupancy occupancy() { return o.entity().component(YukkuriComponents.MOUTH); }
    ItemStack mouth() { return occupancy().stack(Purpose.MEAL); }
    BodyFood.Bite mouthBodyFood() { return occupancy().bite(); }

    boolean hasMouthFood() { return occupancy().purpose() == Purpose.MEAL; }

    /** A blocked release keeps the item in its mouth but gives movement back to competing needs. */
    int mealTicks() { return playingTuftMeal() ? Math.max(1, mealTicks) : refusedSpitAt == null ? mealTicks : 0; }

    private boolean playingTuftMeal() { return tuftMeal != null && tuftReleaseRefusedAt == null; }
    boolean braidHasFood() { return tuftMeal != null && mealTicks == 0 && hasMouthFood(); }
    String braidClip() { return playingTuftMeal() ? tuftMeal.route().id() : ""; }
    long braidStart() { return tuftMeal.start(); }
    BlockPos braidMealBin() { return playingTuftMeal() ? tuftMeal.source().bin() : null; }

    boolean beginTuftMeal(MealRoute route, MealRoute.Serving source) {
        var body = o.entity();
        if (tuftMeal != null || !body.onGround() || body.motor().current() != null || !canEat() || !ExamSession.allowsPeerFeeding(body, null)
                || !Braid.mouthFree(body) || Braid.use(body) != Braid.Use.FREE || !route.reaches(body, source.contact().get())) return false;
        o.beginConsumption();
        SurfaceApproach.halt(body);
        tuftMeal = new TuftMeal(route, source, o.level().getGameTime());
        return true;
    }

    /** The source is checked and debited at the tuft, and the mouth clock begins at its own contact marker. */
    private boolean tickTuftMeal() {
        if (tuftMeal == null) return true;
        var body = o.entity();
        if (tuftReleaseRefusedAt != null) {
            if (!body.blockPosition().equals(tuftReleaseRefusedAt)) releaseForInterrupt();
            return false;
        }
        var route = tuftMeal.route();
        var source = tuftMeal.source();
        var clip = route.clip();
        long tick = o.level().getGameTime() - tuftMeal.start();
        if (mealTicks == 0 && (!MealRoute.braidIntact(body) || !body.onGround() || body.isPassenger() || body.motor().current() != null
                || body.distanceToSqr(route.stand()) > .12 * .12)) {
            if (releaseForInterrupt()) clearTransfer();
            return false;
        }
        if (tick == clip.ticks("pick")) {
            if (!route.reaches(body, source.contact().get()) || Braid.use(body) != Braid.Use.FREE
                    || !source.transfer().hand(one -> canStartSolid(one) && !refuseAtReach(one, null) && occupancy().acquireMeal(one))) {
                GameplayLog.event(body, "meal", "braid pickup refused clip={} contact={}", route.id(), source.contact().get());
                tuftMeal = null;
            }
        } else if (tick == clip.ticks("mouth")) {
            startItemMeal(mouth(), null, false, verdict(mouth(), null));
            if (source.bowl()) bowlMeal();
            GameplayLog.event(body, "meal", "braid handover item={} clip={} tick={}", mouth().getItem(), route.id(), tick);
            return false;
        } else if (tick >= clip.length()) {
            tuftMeal = null;
        }
        return mealTicks > 0;
    }

    boolean fedBy(UUID actor) {
        if (actor.equals(feeder) && mealVerdict != Verdict.REFUSE && kindnessFood(mouth())) return true;
        return actor.equals(lastFeeder) && o.level().getGameTime() - lastFedAt < RECENT_FEED_TICKS
                && o.personality().fear(actor) < BondLevels.AFFECTION_FEAR_LIMIT;
    }

    /** Grief and fright spoil the appetite for a while (mind/EmotionCourse); real hunger below the urgent line still eats. */
    boolean needsFood() {
        return o.nutrition() <= Math.max(30, FULL - 20 * o.entity().leafSwaddle().swelling() - o.entity().mind().feeling().appetiteLoss())
                && !hasMouthFood() && mealTicks == 0;
    }

    boolean edible(ItemStack food){return food.is(YukkuriEntity.FOOD)||o.entity().profile().diet().eats(food)||Toxins.dose(food)>0&&o.nutrition()<Toxins.STARVING||Consumables.kind(food)==Consumables.Kind.SPICY
            ||FillingResource.isFilling(food)||o.entity().profile().houseBody()&&food.is(ModItems.FOOD_SCRAP.get())
            ||food.is(ModItems.WASTE.get())&&o.entity().settlement().duties().pit().eatsWaste();}

    boolean acceptsFood(ItemStack food){return edible(food)&&(!FillingResource.isFilling(food)||FillingResource.acceptable(o.entity(),food));}

    private int tasteFor(UUID giver) {
        return Taste.effective(o.pickiness(), SocialDisposition.selfEsteem(o.entity()), o.personality().trust(giver));
    }

    private Verdict verdict(ItemStack food, UUID giver) {
        var eater = o.entity();
        if (eater.profile().dangerToYukkuri() || eater.settlement().society().slave() || eater.family().needsMash()
                || !(eater.family().adult() || eater.family().subadult()) || InfantFood.isMash(food)
                || Consumables.kind(food) == Consumables.Kind.SPICY) return Verdict.PLAIN;
        return Taste.judge(tasteFor(giver), FoodTaste.grade(food), o.nutrition());
    }

    boolean seeksFood(ItemStack food) {
        return seeksFood(food, null);
    }

    boolean seeksFood(ItemStack food, UUID giver) {
        return acceptsFood(food) && verdict(food, giver) != Verdict.REFUSE;
    }

    /** Automatic offers only show the complaint at contact, once per item type in the refusal window. */
    boolean refuseAtReach(ItemStack food, UUID giver) {
        if (verdict(food, giver) != Verdict.REFUSE) return false;
        Item item = food.getItem();
        if (showComplaint(item)) {
            GameplayLog.event(o.entity(), "meal", "food_complain item={} grade={} verdict=REFUSE nutrition={} pickiness={} taste={} giver={}",
                    item, FoodTaste.grade(food).id(), o.nutrition(), o.pickiness(), tasteFor(giver), giver);
        }
        return true;
    }

    private void rememberComplaint(Item item) {
        complaints.remove(item);
        if (complaints.size() == 16) complaints.pollFirstEntry();
        complaints.put(item, o.level().getGameTime() + COMPLAINT_TICKS);
    }

    private boolean showComplaint(Item item) {
        if (o.level().getGameTime() < complaints.getOrDefault(item, 0L)) return false;
        rememberComplaint(item);
        o.entity().showReactionCue(YukkuriAction.IGNORE, "", "food_complain");
        return true;
    }

    private boolean mouthForMeal() {
        return !occupancy().kitInMouth() && !o.entity().family().carry().mouthBusy();
    }

    boolean readyForFood() { return needsFood() && mouthForMeal() && !o.entity().body().dying() && !o.entity().family().awaitingLanding() && !o.entity().isPanicking() && !o.entity().isCommandPlaying() && o.entity().isAlive(); }

    boolean canEat() { return readyForFood() && o.entity().family().canEatIndependently() && o.entity().childhood().canEatSolid(); }

    boolean canTakeSweet(ItemStack food) {
        return Sweets.is(food) && o.nutrition() <= Sweets.FULL && mouthForMeal() && !hasMouthFood() && mealTicks == 0 && !o.entity().family().awaitingLanding() && !o.entity().isPanicking()
                && !o.entity().isCommandPlaying() && o.entity().isAlive() && o.entity().family().canEatIndependently() && o.entity().childhood().canEatSolid();
    }

    boolean canReceiveCareFood() { return readyForFood(); }

    boolean canReceiveCareFood(ItemStack food) { return canReceiveCareFood() && acceptsFood(food); }

    boolean beginMeal(ItemStack transferred, UUID fromPlayer, boolean alreadyShared) {
        return canStartSolid(transferred) && !refuseAtReach(transferred, fromPlayer)
                && startMeal(transferred, fromPlayer, alreadyShared);
    }

    /** The bin clip already plays the take-food beat. Its last pose leads straight into chewing. */
    boolean beginBinMeal(ItemStack transferred) {
        if (!beginMeal(transferred, null, false)) return false;
        mealTicks = TAKE_TICKS;
        return true;
    }

    boolean beginOfferedMeal(ItemStack food, UUID giver, boolean transfersItem) {
        if (!canStartSolid(food)) return false;
        Verdict offered = verdict(food, giver);
        if (offered == Verdict.REFUSE) {
            GameplayLog.limited(o.entity(), "meal", "taste_refused/" + food.getItem().getDescriptionId(), 400,
                    "declined serving item={} grade={} pickiness={} taste={} nutrition={} giver={} reason=taste",
                    food.getItem(), FoodTaste.grade(food).id(), o.pickiness(), tasteFor(giver), o.nutrition(), giver);
            if (!transfersItem || !Taste.spitsBack(o.personality().selfControl(), SocialDisposition.selfEsteem(o.entity()),
                    o.personality().trust(giver))
                    || !ItemContact.hasReleaseSupport(o.entity(), MouthPlacement.position(o.entity()))) {
                showComplaint(food.getItem());
                return false;
            }
        }
        if (!startMeal(food, giver, false, offered)) {
            o.entity().showReaction("cheek_puff", "refuse", 60);
            return false;
        }
        return true;
    }

    private boolean canStartSolid(ItemStack food) {
        return food.getCount() == 1 && acceptsFood(food) && (canEat() || canTakeSweet(food));
    }

    /** A crop and its serving commit together. A refused world change leaves the mouth empty. */
    boolean beginHarvestMeal(ItemStack serving, BooleanSupplier harvest) {
        if (tuftMeal != null || !canEat() || !acceptsFood(serving) || refuseAtReach(serving, null) || !ExamSession.allowsPeerFeeding(o.entity(), null)
                || !occupancy().freeForExternalWork() || !occupancy().acquireMeal(serving)) return false;
        if (!harvest.getAsBoolean()) {
            occupancy().clear(Purpose.MEAL);
            return false;
        }
        startItemMeal(serving, null, false, verdict(serving, null));
        return true;
    }

    /** A caller checks this before consuming a body's one-shot resource. */
    boolean canAcceptBodyFood() { return tuftMeal == null && canEat() && occupancy().canHold(Purpose.MEAL); }

    boolean canAcceptBodyFood(BodyFood.Bite bite) { return bite != null && !bite.isEmpty() && canAcceptBodyFood(); }

    /** The bite stays in the mouth until the ordinary swallow tick; nutrition is not granted here. */
    boolean acceptBodyFood(BodyFood.Bite bite) {
        if (!canAcceptBodyFood(bite)) return false;
        o.temperature().stop();
        o.entity().dailyLife().stop();
        if (!occupancy().acquireMeal(bite)) return false;
        bowlMeal = false;
        syncBodyFood();
        feeder = null;
        sharedMeal = true;
        preparedMeal = false;
        content = false; meltFace = meltTail = finishFace = null;
        mealTicks = 1;
        o.beginConsumption();
        o.entity().getNavigation().stop();
        o.entity().setPersistenceRequired();
        o.entity().showReaction("happy", "take_food", 60);
        return true;
    }

    boolean beginPreparedMeal(ItemStack prepared, UUID fromPlayer) {
        var player = fromPlayer == null ? null : o.level().getPlayerByUUID(fromPlayer);
        if ((o.entity().family().stage()==FamilyLife.Stage.BABY && o.entity().childhood().isNewborn())
                || player == null || !player.isAlive() || player.distanceToSqr(o.entity()) > 9 || !player.hasLineOfSight(o.entity())
                || !o.entity().family().needsMash() || !canReceiveCareFood(prepared) || !InfantFood.isMash(prepared) || prepared.getCount() != 1) return false;
        return startMeal(prepared, fromPlayer, true);
    }

    boolean beginStaffMeal(ItemStack portion, UUID staff) {
        return canReceiveCareFood(portion) && startMeal(InfantFood.mash(portion, staff), staff, true);
    }

    boolean beginParentMeal(YukkuriEntity caregiver, ItemStack prepared) {
        if (caregiver == null || caregiver.level() != o.entity().level() || !caregiver.family().caresFor(o.entity())
                || !FamilyFeedingContact.mouthsMeet(caregiver, o.entity())) return false;
        return o.entity().family().needsMash()
                && canReceiveCareFood(prepared) && InfantFood.isMash(prepared) && prepared.getCount() == 1
                && startMeal(prepared, caregiver.getUUID(), true);
    }

    boolean beginKindnessMeal(YukkuriEntity giver, ItemStack item) {
        if (!FamilyFeedingContact.mouthsMeet(giver, o.entity())
                || refusesWhileCarryingStallFood() || !occupancy().canHold(Purpose.MEAL)
                || !canReceiveCareFood(item) || item.getCount() != 1) return false;
        boolean mash = o.entity().family().needsMash();
        return (mash || canEat() && !refuseAtReach(item, giver.getUUID())) && startMeal(mash ? InfantFood.mash(item, giver.getUUID()) : item,
                giver.getUUID(), true);
    }

    boolean prepareKindnessFood() {
        if (!canOfferMeal() || !occupancy().prepareKindnessFood()) return false;
        clearTransfer();
        o.finishConsumption();
        return true;
    }

    boolean canOfferMeal() {
        var item = mouth();
        return tuftMeal == null && mealVerdict != Verdict.REFUSE && !sharedMeal && !preparedMeal && !bowlMeal && feeder == null
                && mealTicks > 0 && mealTicks < TAKE_TICKS && kindnessFood(item);
    }

    static boolean kindnessFood(ItemStack item) {
        return !item.isEmpty() && !Consumables.oral(item) && !item.is(ModItems.WASTE.get())
                && Consumables.kind(item) != Consumables.Kind.SPICY && Toxins.dose(item) == 0;
    }

    private boolean startMeal(ItemStack transferred, UUID fromPlayer, boolean alreadyShared) {
        return startMeal(transferred, fromPlayer, alreadyShared, verdict(transferred, fromPlayer));
    }

    private boolean startMeal(ItemStack transferred, UUID fromPlayer, boolean alreadyShared, Verdict verdict) {
        if (tuftMeal != null || !ExamSession.allowsPeerFeeding(o.entity(), fromPlayer)) return false;
        if (!makeRoom("meal") || !occupancy().acquireMeal(transferred.copy())) return false;
        startItemMeal(transferred, fromPlayer, alreadyShared, verdict);
        return true;
    }

    boolean beginCarriedMeal(Purpose source) {
        return beginCarriedMeal(source, null, false);
    }

    boolean beginCarriedMeal(Purpose source, UUID giver, boolean shared) {
        var stack = occupancy().stack(source);
        if (tuftMeal != null || !canEat() || stack.isEmpty() || !acceptsFood(stack) || refuseAtReach(stack, giver) || !occupancy().chewCarried(source)) return false;
        startItemMeal(stack, giver, shared, verdict(stack, giver));
        return true;
    }

    boolean passCarriedMeal(YukkuriEntity peer, boolean share) {
        var food = occupancy().stack(Purpose.KINDNESS);
        UUID giver = share ? o.entity().getUUID() : null;
        var receiver = peer.component(YukkuriComponents.MOUTH);
        Predicate<ItemStack> accept = portion -> {
            if (!peer.life().canEat() || !receiver.freeForExternalWork() || !peer.life().seeksFood(portion, giver)) return false;
            receiver.set(Purpose.KINDNESS, portion);
            return true;
        };
        boolean passed = share && food.is(Items.APPLE)
                ? occupancy().splitKindnessFood(PicnicFood.split(food), accept)
                : occupancy().offerKindnessFood(accept);
        if (passed && share) {
            if (food.is(Items.APPLE))
                GameplayLog.event(o.entity(), "meal", "serving split input={} input_count=1 output={} output_count=2 recipient={}",
                        food.getItem(), ModItems.APPLE_HALF.get(), peer.getUUID());
            o.personality().experienced(Trait.FRIENDLINESS, 1);
            o.personality().decided("share_food");
        }
        return passed;
    }

    private boolean makeRoom(String cause) {
        var purpose = occupancy().purpose();
        if (purpose == Purpose.CARE) return o.entity().family().releaseCareFood(cause);
        if (purpose == Purpose.DUTY) return o.entity().settlement().duties().putDownLoad();
        if (purpose == Purpose.SWADDLE) return o.entity().leafSwaddle().putDownLeaves();
        return !refusesWhileCarryingStallFood() && occupancy().canHold(Purpose.MEAL);
    }

    private boolean refusesWhileCarryingStallFood() {
        if (occupancy().purpose() != Purpose.STALL_FOOD) return false;
        o.entity().showReaction("cheek_puff", "refuse", 60);
        return true;
    }

    private void startItemMeal(ItemStack transferred, UUID fromPlayer, boolean alreadyShared, Verdict verdict) {
        mealVerdict = verdict;
        o.temperature().stop();
        o.entity().dailyLife().stop();
        bowlMeal = false;
        syncBodyFood();
        feeder = fromPlayer; sharedMeal = alreadyShared; preparedMeal = InfantFood.isMash(transferred); content = false;
        meltFace = meltTail = finishFace = null; mealTicks = 1;
        o.entity().social().mealStarted();
        o.beginConsumption(); o.entity().getNavigation().stop(); o.entity().setPersistenceRequired();
        if (preparedMeal) o.entity().showReaction("happy", "receive_mash", 60);
        else switch (mealVerdict) {
            case SAVOUR, PLAIN -> o.entity().showReaction("happy", "take_food", TAKE_TICKS);
            case REFUSE, GRUMBLE -> o.entity().showReaction("pursed", "", TAKE_TICKS);
            case RELUCTANT -> o.entity().showReaction("teary_frown", "", TAKE_TICKS);
        }
        GameplayLog.event(o.entity(), "meal", "received item={} count={} holder=mouth feeder={} prepared={} shared={}",
                transferred.getItem(), transferred.getCount(), feeder, preparedMeal, sharedMeal);
    }

    boolean snatchedBy(YukkuriEntity thief) {
        if (!hasMouthFood() || thief == o.entity() || !thief.isAlive()) return false;
        // A body bite cannot become an item or a second eater's serving. Losing it is only a short spit-out performance.
        if (!mouthBodyFood().isEmpty()) {
            loseBodyFood();
            clearTransfer();
            o.finishConsumption();
            return true;
        }
        if (!transferTo(thief, null)) o.interrupt();
        return true;
    }

    boolean handOverMouth(YukkuriEntity peer, String event) {
        if (mealVerdict == Verdict.REFUSE || mouth().isEmpty() || mealTicks == 0 || mealTicks >= swallowTick()
                || peer == o.entity() || !peer.isAlive()) return false;
        if (!transferTo(peer, o.entity().getUUID())) return false;
        o.personality().experienced(Trait.FRIENDLINESS, 1); o.entity().showReaction("happy", event, 100);
        o.personality().decided("share_food");
        return true;
    }

    private boolean transferTo(YukkuriEntity peer, UUID from) {
        if (!occupancy().releaseMeal(item -> peer.life().beginMeal(item, from, true), bite -> false)) return false;
        clearTransfer();
        return true;
    }

    private void clearTransfer() {
        tuftMeal = null; tuftReleaseRefusedAt = null;
        feeder = null; mealTicks = 0; mealVerdict = Verdict.PLAIN; refusedSpitAt = null; sharedMeal = false; preparedMeal = false;
    }

    void tryShare() {
        var donor = o.entity();
        var requested = ExamSession.sharingTarget(donor);
        var peer = o.level().getEntitiesOfClass(YukkuriEntity.class, o.entity().getBoundingBox().inflate(1.5), other ->
                other != o.entity() && other.isAlive() && other.life().canEat() && other.life().nutrition() < o.nutrition() - 15
                        && (requested == null ? Bonds.careKin(donor, other) : other == requested)
                        && (o.canRecognize(other) || other == requested) && o.entity().hasLineOfSight(other)
                        && !ExamSession.running(other) && ItemContact.mouthsMeet(o.entity(), other)).stream()
                .min(Comparator.comparingDouble(donor::distanceToSqr)).orElse(null);
        if (peer == null) {
            if (requested == null && canOfferMeal()) donor.kindness().offerMeal();
            return;
        }
        if (mouth().isEmpty()) return;
        if (requested != null) {
            if (PetConversation.sharingDanger(donor, peer)) {
                UUID owner = donor.pet().owner();
                if (owner != null) PetConversation.callOwnerForDanger(donor, peer, o.level().getPlayerByUUID(owner));
                return;
            }
            if (o.nutrition() >= 60 && peer.life().nutrition() < 40 && !bowlMeal && feeder == null
                    && !donor.dailyLife().sharedFoodToday(peer.getUUID())
                    && handOverMouth(peer, "share"))
                donor.dailyLife().sharedFoodWith(peer.getUUID());
            return;
        }
        int score=o.personality().shareScore(o.nutrition(),peer.life().nutrition(),o.entity().family().caresFor(peer),YukkuriWorldData.get(o.level()).affinity(o.entity().getUUID(),peer.getUUID()));
        if(o.entity().getRandom().nextInt(100)>=score){o.personality().decided("keep_food");return;}
        if (transferTo(peer, o.entity().getUUID())) {
            o.personality().experienced(Trait.FRIENDLINESS, 1); o.entity().showReaction("happy", "share", 100);
            o.personality().decided("share_food");
        }
    }
    /** False preserves the old interrupt-and-return from the entire life tick. */
    boolean tickChew() {
        if (!tickTuftMeal()) return true;
        if (mealTicks > 0) {
            ItemStack mouth = mouth();
            if (!mouth.isEmpty() && !Consumables.oral(mouth) && !acceptsFood(mouth)) { o.interrupt(); return false; }
            if (mealVerdict == null) restoreVerdict(mouth);
            int swallowAt = swallowTick();
            if (mealVerdict == Verdict.REFUSE && mealTicks >= TAKE_TICKS - 1) {
                mealTicks = TAKE_TICKS;
                Vec3 at = MouthPlacement.position(o.entity());
                if (at.equals(refusedSpitAt)) return true;
                if (!occupancy().releaseMeal(this::spit, bite -> false)) refusedSpitAt = at;
                else o.interrupt();
                return false;
            }
            o.entity().getNavigation().stop();
            mealTicks++;
            if (mealTicks % MEAL_SIGHT_INTERVAL == 0 && canOfferMeal() && BadgeSocial.mealSeen(o.entity())) return true;
            if (mealVerdict != Verdict.REFUSE && mealTicks == 6 && !sharedMeal && o.nutrition() >= 35) tryShare();
            if (mealTicks == swallowAt && !mouthBodyFood().isEmpty()) {
                swallowBodyFood();
            } else if (mealTicks == swallowAt && !mouth.isEmpty()) {
                if(Consumables.oral(mouth)){
                    boolean applied=Consumables.swallow(o.entity(),mouth);
                    var returned=applied?o.doses().returnBottle()?new ItemStack(Items.GLASS_BOTTLE):ItemStack.EMPTY:mouth.copy();
                    GameplayLog.event(o.entity(),"meal","oral dose resolved item={} applied={} feeder={} return_item={} count={}",
                            mouth.getItem(),applied,feeder,returned.getItem(),returned.getCount());
                    occupancy().clear(Purpose.MEAL);
                    if(!returned.isEmpty()){
                        var player=feeder==null?null:o.level().getPlayerByUUID(feeder);
                        if(player==null||!player.isAlive()||player.distanceToSqr(o.entity())>16||!player.getInventory().add(returned))o.entity().spawnAtLocation(returned);
                    }
                    o.doses().clearReturnBottle();feeder=null;o.entity().gameEvent(GameEvent.DRINK);
                }else{
                boolean scrap=mouth.is(ModItems.FOOD_SCRAP.get());
                boolean filling=FillingResource.isFilling(mouth);
                boolean spicy=Consumables.kind(mouth)==Consumables.Kind.SPICY;
                boolean sweet=Sweets.is(mouth);
                boolean grass=FoodTaste.grass(mouth);
                // A slave kept in the waste pit eats the droppings lying there (settlement/PitSlave): poor food, foul to the body.
                boolean waste=mouth.is(ModItems.WASTE.get());
                var grade = FoodTaste.grade(mouth);
                boolean liked = mealVerdict == Verdict.SAVOUR;
                boolean grudging = mealVerdict == Verdict.GRUMBLE;
                boolean reluctant = mealVerdict == Verdict.RELUCTANT;
                boolean unhappy = grudging || reluctant;
                ItemStack swallowed=mouth.copy();
                float nutritionBefore=o.nutrition();
                float gain=FoodTaste.serving(mouth);
                int toxic=Toxins.dose(mouth);
                boolean pleasant = !spicy && toxic == 0 && !waste && (!grass || liked);
                if(spicy){gain=12;o.doses().spicyTicks(240);o.increaseDryness(45);}
                else if(sweet)o.increaseDryness(Sweets.dryness(mouth));
                else if(mouth.is(ModItems.GRAIN_BUDGET.get())||mouth.is(ModItems.GRAIN_REGULAR.get()))o.increaseDryness(15); // premium grain never leaves the pet thirsty
                if(filling)FillingResource.swallowed(o.entity(),mouth);
                // The held item is retired at the same server step that grants nutrition.
                occupancy().clear(Purpose.MEAL);
                complaints.remove(mouth.getItem());
                o.entity().species().ate(o.nutrition(), waste);
                o.addNutrition(gain); o.addBladder(gain * .5f);
                o.adjustPickiness(Taste.afterMeal(grade, nutritionBefore));
                GameplayLog.event(o.entity(),"meal","swallowed item={} count={} nutrition={} -> {} feeder={} prepared={} shared={} grade={} verdict={} pickiness={}",
                        swallowed.getItem(),swallowed.getCount(),nutritionBefore,o.nutrition(),feeder,preparedMeal,sharedMeal,
                        grade.id(),mealVerdict.name(),o.pickiness());
                o.entity().childhood().firstMealSwallowed();
                var eater = o.entity();
                UUID bondTarget = feeder;
                var bond = o.personality().relation(bondTarget);
                double esteem = SocialDisposition.selfEsteem(eater);
                double wariness = SocialDisposition.wariness(eater, bond);
                boolean ordinaryAdult = eater.family().adult() && !preparedMeal && !filling && !scrap && !sweet;
                boolean premium = mouth.is(ModItems.GRAIN_PREMIUM.get());
                boolean guarded = ordinaryAdult && pleasant && wariness >= .4;
                boolean defiant = ordinaryAdult && pleasant && feeder != null && !guarded
                        && SocialDisposition.insistence(o.personality(), esteem, wariness, bond) >= .52;
                if (!unhappy) finishFace = guarded ? "teary" : defiant ? "smug" : null;
                sated(nutritionBefore, pleasant, unhappy, guarded, defiant);
                if(spicy)o.entity().body().poisoned(Toxins.SPICY_DOSE);
                else if(toxic>0)o.entity().body().poisoned(toxic);
                if(waste)o.changeHygiene(-PIT_MEAL_DIRT);
                if(!filling&&!spicy&&toxic==0&&!waste)o.entity().heal(2);
                if (!spicy) { o.personality().fed(feeder, pleasant); WildEvents.fed(o.entity(),feeder); }
                if (sharedMeal && pleasant) eater.kindness().swallowed(feeder);
                o.entity().pet().contact(feeder, !spicy);
                String treat = Sweets.eaten(o.entity(), swallowed, feeder);
                o.entity().pet().lessons().swallowed(feeder, swallowed);
                if (feeder != null) ExamSession.fed(o.entity(), feeder);
                boolean answered = eater.pet().care().requests().swallowed(feeder, pleasant && !sweet);
                boolean grateful = ordinaryAdult && feeder != null && (bond.closeness() >= 10
                        || premium && o.personality().temperament().is(Gate.WARM)) && !guarded && !defiant;
                String fedLine = answered ? "request_thanks" : grateful
                        ? (o.level().getEntity(bondTarget) instanceof HumanNpc ? "badge_thanks" : "request_thanks") : "fed";
                if (pleasant && feeder != null) { lastFeeder = feeder; lastFedAt = o.level().getGameTime(); }
                o.entity().mind().ate(swallowed, treat != null); feeder = null;
                if (bowlMeal) { o.entity().pet().routineCompleted(0); bowlMeal = false; }
                eater.gameEvent(GameEvent.EAT);
                boolean harmful = spicy || toxic > 0;
                String face = harmful ? "surprise" : waste ? "teary" : reluctant ? "squeezed_cry_tears" : grudging ? "pursed"
                        : guarded ? "teary" : grass && !liked ? "teary"
                        : defiant ? "smug" : grateful ? "closed_smile" : liked ? "sparkle" : "happy";
                String line = harmful ? "hurt" : waste ? "bitter" : reluctant ? "food_reluctant" : grudging ? "food_grudge"
                        : guarded || defiant ? "" : grass && !liked ? "bitter" : treat != null ? treat
                        : liked && fedLine.equals("fed") ? "food_delight" : fedLine;
                if (!coughing() && (!eater.body().sick() || !harmful)
                        && eater.showReaction(face, harmful ? line : "",
                            harmful || waste || grass && !liked && !unhappy || preparedMeal ? 100 : SWALLOW_TICKS)
                        && !harmful && !line.isEmpty()) {
                    eater.speakCue(line, line.equals("fed") ? "plain" : null);
                }
                if (!harmful && !grass && !waste && !unhappy)
                    o.level().sendParticles(ParticleTypes.HEART, eater.getX(), eater.getY()+eater.getBbHeight(), eater.getZ(), 3, .1, .05, .1, 0);
                }
            }
            if (mealTicks == swallowAt + SWALLOW_TICKS && !coughing()) {
                // The after-meal clip now carries the face: this individual's melt, or the composed adult's restraint over the finish.
                if (meltFace != null) o.entity().showReaction(meltFace, "", EcologyPresentation.duration(Behaviors.MEAL_CONTENT));
                else if (finishFace != null) o.entity().showReaction(finishFace, "", mealEnd() - mealTicks);
            }
            if (mealTicks >= mealEnd()) {
                clearTransfer(); content = false;
                if (meltTail != null && !coughing()) o.entity().showReaction(meltTail, "", MELT_TAIL_TICKS);
                meltFace = meltTail = finishFace = null;
                o.finishConsumption();
            }
        }
        return true;
    }

    /** Restore an interrupted chewing phase after all saved traits, custody and life stages have loaded. */
    private void restoreVerdict(ItemStack held) {
        Verdict restored = verdict(held, feeder);
        mealVerdict = restored != Verdict.REFUSE ? restored : o.nutrition() <= 30 ? Verdict.RELUCTANT : Verdict.GRUMBLE;
        mealTicks = Math.min(mealTicks, swallowTick() - 1);
    }

    int swallowTick() {
        return mealVerdict == Verdict.GRUMBLE || mealVerdict == Verdict.RELUCTANT ? SLOW_SWALLOW_TICK : SWALLOW_TICK;
    }

    String chewActivity() {
        return switch (mealVerdict) {
            case GRUMBLE -> Behaviors.CHEW_GRUDGE;
            case RELUCTANT -> Behaviors.CHEW_RELUCTANT;
            case null, default -> Behaviors.CHEW;
        };
    }

    String afterActivity() {
        if (content) return Behaviors.MEAL_CONTENT;
        if (finishFace != null) return Behaviors.MEAL_FINISH;
        return switch (mealVerdict) {
            case SAVOUR -> Behaviors.MEAL_DELIGHT;
            case GRUMBLE -> Behaviors.MEAL_GRUDGE;
            case RELUCTANT -> Behaviors.MEAL_RELUCTANT;
            case null, default -> Behaviors.MEAL_FINISH;
        };
    }

    private int mealEnd() {
        String after = afterActivity();
        return swallowTick() + SWALLOW_TICKS + (after.equals(Behaviors.MEAL_FINISH) ? 11 : EcologyPresentation.duration(after));
    }

    private boolean coughing() { return o.entity().reactionAction() == YukkuriAction.COUGH_MUKYU; }

    /**
     * The bite that takes it past {@link #FULL}: it melts in place a moment (しあわせー, project design), unless the food was unpleasant,
     * it rides a host, it is a {@link Gate#COMPOSED} adult, or it melted not long ago (a young one sooner again).
     */
    private void sated(float before, boolean pleasant, boolean unhappy, boolean guarded, boolean defiant) {
        if (before > FULL || o.nutrition() <= FULL) return;
        var e = o.entity();
        long now = o.level().getGameTime();
        boolean young = !e.family().adult();
        var t = o.personality().temperament();
        int composure = t.selfControl();
        String reason = !pleasant ? "unpleasant" : unhappy ? "reluctant" : guarded ? "guarded" : e.isPassenger() ? "aboard"
                : !young && t.is(Gate.COMPOSED) ? "composed" : now < contentAfter ? "recent" : null;
        content = reason == null;
        if (content) {
            contentAfter = now + 1200 + (young ? 0 : 24 * composure);
            // The melt face is this body's own, from values it already has (bands, not scene literals):
            // an overshoot or a body under the tired line (25, the same line EcologyPresentation draws the tired face at) drowses off;
            // one that was starving before the bite is moved by it; greedy young ones drool; a bold loner is pleased with itself.
            if (o.nutrition() >= 95 || o.energy() < 25) { meltFace = "sleepy"; meltTail = "yawn"; }
            else if (before < 25) { meltFace = "teary_smile"; meltTail = "satisfied"; }
            else if (young) { meltFace = "drool"; meltTail = "open_smile"; }
            else if (t.is(Gate.IMPULSIVE)) { meltFace = "open_drool"; meltTail = "open_smile"; }
            else if (defiant) { meltFace = "smug"; meltTail = "closed_smile"; }
            else { meltFace = "satisfied"; meltTail = "closed_smile"; }
        } else if (reason.equals("composed")) finishFace = "closed_smile";
        GameplayLog.event(e, "meal", "full nutrition={} -> {} content={} reason={} selfControl={} face={} patient={}", before, o.nutrition(), content,
                reason == null ? "melt" : reason, composure, meltFace != null ? meltFace : finishFace != null ? finishFace : "-",
                String.format(Locale.ROOT, "%.2f", t.patient()));
    }

    private void swallowBodyFood() {
        BodyFood.Bite swallowed = mouthBodyFood();
        float nutritionBefore=o.nutrition();
        occupancy().clear(Purpose.MEAL);
        syncBodyFood();
        float gain = swallowed.nutrition();
        o.entity().species().ate(o.nutrition(), false);
        o.addNutrition(gain);
        o.addBladder(gain * .5f);
        GameplayLog.event(o.entity(),"meal","swallowed bodyFood portions={} odor={} nutrition={} -> {}",swallowed.portions(),swallowed.odor(),nutritionBefore,o.nutrition());
        o.entity().childhood().firstMealSwallowed();
        sated(nutritionBefore, swallowed.odor() < 40, false, false, false);
        if (swallowed.odor() >= 40) o.entity().body().contaminated();
        o.entity().pet().contact(null, true);
        WildEvents.fed(o.entity(), null);
        o.entity().mind().ate(swallowed);
        if (bowlMeal) { o.entity().pet().routineCompleted(0); bowlMeal = false; }
        // Same rule as an item meal: a melting body keeps the swallow face only to the end of the swallow.
        o.entity().gameEvent(GameEvent.EAT);
        if (!coughing()) o.entity().showReaction(swallowed.odor() >= 40 ? "teary" : "happy",
                swallowed.odor() >= 40 ? "hurt" : "fed", content ? SWALLOW_TICKS : 100);
    }
    boolean preparedMeal() { return preparedMeal; }
    void bowlMeal() { bowlMeal = true; }
    boolean beginDose(ItemStack one, UUID player, boolean returnBottle) {
        if (!mouthForMeal() || !makeRoom("dose") || !occupancy().acquireMeal(one.copy())) return false;
        o.temperature().stop();
        syncBodyFood();
        mealVerdict = Verdict.PLAIN;
        feeder=player;sharedMeal=true;content=false; meltFace = meltTail = finishFace = null; o.doses().returnBottle(returnBottle);mealTicks=1;
        return true;
    }
    private boolean spit(ItemStack item) {
        var dropped = ItemContact.releaseSupported(o.entity(), item, 60);
        if (dropped == null) {
            GameplayLog.limited(o.entity(), "meal", "spit_refused", 200, "spit release refused item={} count={}", item.getItem(), item.getCount());
            return false;
        }
        rememberComplaint(item.getItem());
        o.entity().showReactionCue(YukkuriAction.REJECT_OR_SPIT, "bad_taste_small", "food_spit");
        GameplayLog.event(o.entity(), "meal", "spat item={} grade={} pickiness={} taste={} nutrition={} verdict=REFUSE feeder={} tick={} drop={}",
                item.getItem(), FoodTaste.grade(item).id(), o.pickiness(), tasteFor(feeder), o.nutrition(), feeder, mealTicks, dropped.getUUID());
        return true;
    }
    boolean releaseForInterrupt() {
        if (braidHasFood()) {
            var body = o.entity();
            var route = tuftMeal.route();
            var tip = tuftReleaseRefusedAt == null
                    ? route.tip(body, (o.level().getGameTime() - tuftMeal.start()) / 20f) : ContactGeometry.braidTip(body);
            boolean released = occupancy().releaseMeal(one -> {
                if (tuftMeal.source().restore().test(one)) {
                    GameplayLog.event(body, "meal", "braid returned item={} clip={}", one.getItem(), route.id());
                    return true;
                }
                var drop = ItemContact.hasReleaseSupport(body, tip) ? ItemContact.release(body, one, tip, Vec3.ZERO) : null;
                if (drop == null) return false;
                GameplayLog.event(body, "meal", "braid interrupted item={} drop={} clip={}", one.getItem(), drop.getUUID(), route.id());
                return true;
            }, bite -> false);
            if (released) clearTransfer();
            else {
                tuftReleaseRefusedAt = body.blockPosition();
                GameplayLog.limited(body, "meal", "braid_release_refused", 200, "braid release refused item={} tip={}", mouth().getItem(), tip);
            }
            return released;
        }
        return !hasMouthFood() || occupancy().releaseMeal(mouth -> {
            ItemEntity dropped = ItemContact.releaseSupported(o.entity(), mouth, 60);
            if (dropped == null) {
                GameplayLog.limited(o.entity(),"meal","release_refused",200,"interrupt release refused item={} count={} phaseTick={}",mouth.getItem(),mouth.getCount(),mealTicks);
                return false; // Retain the owned serving if spawning was rejected.
            }
            GameplayLog.event(o.entity(),"meal","interrupted item={} count={} phaseTick={} drop={}",mouth.getItem(),mouth.getCount(),mealTicks,dropped.getUUID());
            return true;
        }, bite -> {
            BodyEffects.emit(o.entity(), "spit", bite.portions(), MouthPlacement.position(o.entity()));
            GameplayLog.event(o.entity(),"meal","interrupted bodyFood portions={} phaseTick={}",bite.portions(),mealTicks);
            return true;
        });
    }
    void clearInterrupted() {
        syncBodyFood();
        clearTransfer();
        content = false; meltFace = meltTail = finishFace = null;
    }
    void save(CompoundTag tag) {
        tag.putInt("MealTicks", mealTicks); tag.putBoolean("SharedMeal", sharedMeal); tag.putBoolean("PreparedMeal",preparedMeal); tag.putBoolean("BowlMeal",
                bowlMeal); if (feeder != null) tag.putUUID("Feeder", feeder);
        if (lastFeeder != null && o.level().getGameTime() - lastFedAt < RECENT_FEED_TICKS) {
            tag.putUUID("LastFeeder", lastFeeder);
            tag.putLong("LastFedAt", lastFedAt);
        }
        if (mealVerdict == Verdict.REFUSE) tag.putBoolean("SpitMeal", true);
    }
    void load(CompoundTag tag) {
        ItemStack mouth = tag.contains("Mouth",Tag.TAG_COMPOUND) ? ItemStack.parseOptional(o.entity().registryAccess(),tag.getCompound("Mouth")) : ItemStack.EMPTY;
        BodyFood.Bite bodyFood = tag.contains("BodyFood", Tag.TAG_COMPOUND) ? BodyFood.Bite.load(tag.getCompound("BodyFood")) : BodyFood.Bite.EMPTY;
        occupancy().importMeal(mouth, bodyFood, edible(mouth) || Consumables.oral(mouth) || mouth.is(ModItems.WASTE.get()));
        ItemStack held = mouth();
        mealTicks = hasMouthFood() ? Math.clamp(tag.getInt("MealTicks"), 1, SLOW_SWALLOW_TICK - 1) : 0;
        if (held.isEmpty() || Consumables.oral(held)) {
            mealVerdict = Verdict.PLAIN;
            mealTicks = Math.min(mealTicks, SWALLOW_TICK - 1);
        } else {
            mealVerdict = tag.getBoolean("SpitMeal") ? Verdict.REFUSE : null;
        }
        content = false; meltFace = meltTail = finishFace = null;
        refusedSpitAt = null;
        sharedMeal=tag.getBoolean("SharedMeal"); preparedMeal=tag.getBoolean("PreparedMeal")
                || InfantFood.isMash(mouth()); bowlMeal=tag.getBoolean("BowlMeal"); feeder=tag.hasUUID("Feeder")?tag.getUUID("Feeder"):null;
        lastFeeder = tag.hasUUID("LastFeeder") ? tag.getUUID("LastFeeder") : null;
        lastFedAt = tag.getLong("LastFedAt");
        syncBodyFood();
    }

    private void loseBodyFood() {
        BodyFood.Bite bodyFood = mouthBodyFood();
        if (bodyFood.isEmpty()) return;
        BodyEffects.emit(o.entity(), "spit", bodyFood.portions(), MouthPlacement.position(o.entity()));
        occupancy().clear(Purpose.MEAL);
        syncBodyFood();
    }

    private void syncBodyFood() { o.entity().syncMouthBodyFood(mouthBodyFood()); }

}
