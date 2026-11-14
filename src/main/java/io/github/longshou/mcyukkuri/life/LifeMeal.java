package io.github.longshou.mcyukkuri.life;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.body.BodyEffects;
import io.github.longshou.mcyukkuri.body.BodyFood;
import io.github.longshou.mcyukkuri.body.FillingResource;
import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.interaction.ItemContact;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.motor.MouthOccupancy;
import io.github.longshou.mcyukkuri.motor.MouthOccupancy.Purpose;
import io.github.longshou.mcyukkuri.pet.PetConversation;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.pet.exam.ExamSession;
import io.github.longshou.mcyukkuri.social.EcologyPresentation;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import io.github.longshou.mcyukkuri.pet.social.BadgeSocial;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
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
    /** The acquired serving is spat at the end of the take-food beat, including across unload. */
    private static final int SPIT_TICK = 10;
    private boolean spitMeal;
    private Vec3 refusedSpitAt;
    private final LinkedHashMap<Item, Long> complaints = new LinkedHashMap<>();
    private UUID feeder;
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

    int mealTicks() { return mealTicks; }

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

    private Taste.Verdict verdict(ItemStack food, UUID giver) {
        var eater = o.entity();
        if (eater.profile().dangerToYukkuri() || eater.settlement().society().slave() || eater.family().needsMash()
                || !(eater.family().adult() || eater.family().subadult()) || InfantFood.isMash(food)
                || Consumables.kind(food) == Consumables.Kind.SPICY) return Taste.Verdict.PLAIN;
        return Taste.judge(tasteFor(giver), FoodTaste.grade(food), o.nutrition());
    }

    boolean seeksFood(ItemStack food) {
        return seeksFood(food, null);
    }

    boolean seeksFood(ItemStack food, UUID giver) {
        return acceptsFood(food) && verdict(food, giver) != Taste.Verdict.REFUSE;
    }

    /** Automatic offers only show the complaint at contact, once per item type in the refusal window. */
    boolean refuseAtReach(ItemStack food, UUID giver) {
        if (verdict(food, giver) != Taste.Verdict.REFUSE) return false;
        long now = o.level().getGameTime();
        Item item = food.getItem();
        if (now >= complaints.getOrDefault(item, 0L)) {
            complaints.remove(item);
            if (complaints.size() == 16) complaints.pollFirstEntry();
            complaints.put(item, now + 2400);
            showComplaint();
            GameplayLog.event(o.entity(), "meal", "food_complain item={} grade={} verdict=REFUSE nutrition={} pickiness={} taste={} giver={}",
                    item, FoodTaste.grade(food).id(), o.nutrition(), o.pickiness(), tasteFor(giver), giver);
        }
        return true;
    }

    private void showComplaint() {
        if (o.entity().showReaction("pursed", "", 60)) o.entity().speakCue("food_complain");
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
                && startMeal(transferred, fromPlayer, alreadyShared, "take_food");
    }

    boolean beginOfferedMeal(ItemStack food, UUID giver, boolean transfersItem) {
        if (!canStartSolid(food)) return false;
        boolean spit = verdict(food, giver) == Taste.Verdict.REFUSE;
        if (spit) {
            GameplayLog.limited(o.entity(), "meal", "taste_refused/" + food.getItem().getDescriptionId(), 400,
                    "declined serving item={} grade={} pickiness={} taste={} nutrition={} giver={} reason=taste",
                    food.getItem(), FoodTaste.grade(food).id(), o.pickiness(), tasteFor(giver), o.nutrition(), giver);
            if (o.personality().selfControl() >= 60 || !transfersItem
                    || !ItemContact.hasReleaseSupport(o.entity(), MouthPlacement.position(o.entity()))) {
                showComplaint();
                return false;
            }
        }
        if (!startMeal(food, giver, false, spit ? "" : "take_food")) {
            o.entity().showReaction("cheek_puff", "refuse", 60);
            return false;
        }
        spitMeal = spit;
        return true;
    }

    private boolean canStartSolid(ItemStack food) {
        return food.getCount() == 1 && acceptsFood(food) && (canEat() || canTakeSweet(food));
    }

    /** A crop and its serving commit together. A refused world change leaves the mouth empty. */
    boolean beginHarvestMeal(ItemStack serving, BooleanSupplier harvest) {
        if (!canEat() || !acceptsFood(serving) || refuseAtReach(serving, null) || !ExamSession.allowsPeerFeeding(o.entity(), null)
                || !occupancy().freeForExternalWork() || !occupancy().acquireMeal(serving)) return false;
        if (!harvest.getAsBoolean()) {
            occupancy().clear(Purpose.MEAL);
            return false;
        }
        startItemMeal(serving, null, false, "take_food");
        return true;
    }

    /** A caller checks this before consuming a body's one-shot resource. */
    boolean canAcceptBodyFood() { return canEat() && occupancy().canHold(Purpose.MEAL); }

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
        return startMeal(prepared, fromPlayer, true, "receive_mash");
    }

    boolean beginStaffMeal(ItemStack portion, UUID staff) {
        return canReceiveCareFood(portion) && startMeal(InfantFood.mash(portion, staff), staff, true, "receive_mash");
    }

    boolean beginParentMeal(YukkuriEntity caregiver, ItemStack prepared) {
        if (caregiver == null || caregiver.level() != o.entity().level() || !caregiver.family().caresFor(o.entity())
                || !FamilyFeedingContact.mouthsMeet(caregiver, o.entity())) return false;
        return o.entity().family().needsMash()
                && canReceiveCareFood(prepared) && InfantFood.isMash(prepared) && prepared.getCount() == 1
                && startMeal(prepared, caregiver.getUUID(), true, "receive_mash");
    }

    boolean beginKindnessMeal(YukkuriEntity giver, ItemStack item) {
        if (!occupancy().canHold(Purpose.MEAL) || !FamilyFeedingContact.mouthsMeet(giver, o.entity())
                || !canReceiveCareFood(item) || item.getCount() != 1) return false;
        boolean mash = o.entity().family().needsMash();
        return (mash || canEat() && !refuseAtReach(item, giver.getUUID())) && startMeal(mash ? InfantFood.mash(item, giver.getUUID()) : item,
                giver.getUUID(), true, mash ? "receive_mash" : "take_food");
    }

    boolean prepareKindnessFood() {
        if (!canOfferMeal() || !occupancy().prepareKindnessFood()) return false;
        clearTransfer();
        o.finishConsumption();
        return true;
    }

    boolean canOfferMeal() {
        var item = mouth();
        return !spitMeal && !sharedMeal && !preparedMeal && !bowlMeal && feeder == null && mealTicks > 0
                && mealTicks < 40 && kindnessFood(item);
    }

    static boolean kindnessFood(ItemStack item) {
        return !item.isEmpty() && !Consumables.oral(item) && !item.is(ModItems.WASTE.get())
                && Consumables.kind(item) != Consumables.Kind.SPICY && Toxins.dose(item) == 0;
    }

    private boolean startMeal(ItemStack transferred, UUID fromPlayer, boolean alreadyShared, String reaction) {
        if (!ExamSession.allowsPeerFeeding(o.entity(), fromPlayer)) return false;
        if (!makeRoom("meal") || !occupancy().acquireMeal(transferred.copy())) return false;
        startItemMeal(transferred, fromPlayer, alreadyShared, reaction);
        return true;
    }

    boolean beginCarriedMeal(Purpose source) {
        return beginCarriedMeal(source, null, false);
    }

    boolean beginCarriedMeal(Purpose source, UUID giver, boolean shared) {
        var stack = occupancy().stack(source);
        if (!canEat() || stack.isEmpty() || !acceptsFood(stack) || refuseAtReach(stack, giver) || !occupancy().chewCarried(source)) return false;
        startItemMeal(stack, giver, shared, "take_food");
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
                ? occupancy().splitKindnessFood(new ItemStack(ModItems.APPLE_HALF.get()), accept)
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
        return occupancy().canHold(Purpose.MEAL);
    }

    private void startItemMeal(ItemStack transferred, UUID fromPlayer, boolean alreadyShared, String reaction) {
        o.temperature().stop();
        o.entity().dailyLife().stop();
        bowlMeal = false;
        syncBodyFood();
        feeder = fromPlayer; sharedMeal = alreadyShared; preparedMeal = InfantFood.isMash(transferred); content = false;
        meltFace = meltTail = finishFace = null; mealTicks = 1;
        o.entity().social().mealStarted();
        o.beginConsumption(); o.entity().getNavigation().stop(); o.entity().setPersistenceRequired();
        o.entity().showReaction("happy", reaction, 60);
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
        if (spitMeal || mouth().isEmpty() || mealTicks == 0 || mealTicks >= 45 || peer == o.entity() || !peer.isAlive()) return false;
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
        feeder = null; mealTicks = 0; spitMeal = false; refusedSpitAt = null; sharedMeal = false; preparedMeal = false;
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
        if (mealTicks > 0) {
            ItemStack mouth = mouth();
            if (!mouth.isEmpty() && !Consumables.oral(mouth) && !acceptsFood(mouth)) { o.interrupt(); return false; }
            o.entity().getNavigation().stop();
            if (spitMeal && mealTicks >= SPIT_TICK) {
                Vec3 at = MouthPlacement.position(o.entity());
                if (at.equals(refusedSpitAt)) return false;
                if (!occupancy().releaseMeal(this::spit, bite -> false)) refusedSpitAt = at;
                else o.interrupt();
                return false;
            }
            mealTicks++;
            // A hungry free onlooker can ask about an actual serving the pet has just picked up.
            if (mealTicks == 3 && canOfferMeal() && BadgeSocial.mealSeen(o.entity())) return true;
            if (!spitMeal && mealTicks == 6 && !sharedMeal && o.nutrition() >= 35) tryShare();
            if (mealTicks == 45 && !mouthBodyFood().isEmpty()) {
                swallowBodyFood();
            } else if (mealTicks == 45 && !mouth.isEmpty()) {
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
                var verdict = verdict(mouth, feeder);
                if (verdict == Taste.Verdict.REFUSE) verdict = Taste.Verdict.GRUMBLE;
                var grade = FoodTaste.grade(mouth);
                boolean liked = verdict == Taste.Verdict.SAVOUR;
                boolean grudging = verdict == Taste.Verdict.GRUMBLE;
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
                        grade.id(),verdict.name(),o.pickiness());
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
                sated(nutritionBefore, pleasant, grudging, guarded, defiant);
                if(spicy)o.entity().body().poisoned(Toxins.SPICY_DOSE);
                else if(toxic>0)o.entity().body().poisoned(toxic);
                if(waste)o.changeHygiene(-PIT_MEAL_DIRT);
                if(!filling&&!spicy&&toxic==0&&!waste)o.entity().heal(2);
                if (!spicy) { o.personality().fed(feeder); WildEvents.fed(o.entity(),feeder); }
                if (sharedMeal && pleasant) eater.kindness().swallowed(feeder);
                o.entity().pet().contact(feeder, !spicy);
                String treat = Sweets.eaten(o.entity(), swallowed, feeder);
                o.entity().pet().lessons().swallowed(feeder, swallowed);
                if (feeder != null) ExamSession.fed(o.entity(), feeder);
                boolean answered = eater.pet().care().requests().swallowed(feeder, pleasant && !sweet);
                boolean grateful = ordinaryAdult && feeder != null && (bond.closeness() >= 10
                        || premium && o.personality().friendliness() >= 55) && !guarded && !defiant;
                String fedLine = answered ? "request_thanks" : grateful
                        ? (o.level().getEntity(bondTarget) instanceof HumanNpc ? "badge_thanks" : "request_thanks") : "fed";
                o.entity().mind().ate(swallowed, treat != null); feeder = null;
                if (bowlMeal) { o.entity().pet().routineCompleted(0); bowlMeal = false; }
                // A melting meal keeps the swallow face only to the end of the swallow (tick 53 starts the melt), so it cannot cover the after-meal clip.
                eater.gameEvent(GameEvent.EAT);
                if (!eater.body().sick() || !(spicy || toxic > 0)) eater.showReaction(
                        spicy || toxic > 0 ? "surprise" : waste ? "teary" : grudging ? "pursed" : guarded ? "teary"
                                : grass ? liked ? "happy" : "teary"
                                : defiant ? "smug" : grateful ? "closed_smile" : liked ? "open_smile" : "happy",
                        spicy || toxic > 0 ? "hurt" : waste ? "bitter" : grudging ? "" : guarded ? ""
                                : grass ? liked ? fedLine : "bitter"
                                : treat != null ? treat : defiant ? "" : fedLine, content ? 8 : 100);
                if (grudging && !waste && !spicy && toxic == 0) eater.speakCue("food_complain");
                if(!spicy&&toxic==0&&!grass&&!waste&&!grudging)o.level().sendParticles(ParticleTypes.HEART, o.entity().getX(), o.entity().getY()+o.entity().getBbHeight(), o.entity().getZ(), 3, .1, .05, .1, 0);
                }
            }
            if (mealTicks == 53) {
                // The after-meal clip now carries the face: this individual's melt, or the composed adult's restraint over the finish.
                if (meltFace != null) o.entity().showReaction(meltFace, "", EcologyPresentation.duration(Behaviors.MEAL_CONTENT));
                else if (finishFace != null) o.entity().showReaction(finishFace, "", EcologyPresentation.duration(Behaviors.MEAL_FINISH));
            }
            if (mealTicks >= mealEnd()) {
                mealTicks = 0; sharedMeal = false; preparedMeal = false; content = false;
                if (meltTail != null) o.entity().showReaction(meltTail, "", MELT_TAIL_TICKS);
                meltFace = meltTail = finishFace = null;
                o.finishConsumption();
            }
        }
        return true;
    }

    /** After the swallow (tick 53) a full meal plays its contented melt for the clip's length; any other meal ends at tick 64. */
    private int mealEnd() { return content ? 53 + EcologyPresentation.duration(Behaviors.MEAL_CONTENT) : 64; }
    boolean content() { return content; }

    /**
     * The bite that takes it past {@link #FULL}: it melts in place a moment (しあわせー, project design), unless the food was unpleasant,
     * it rides a host, it is a composed adult (self-control 90 and above), or it melted not long ago (a young one sooner again).
     */
    private void sated(float before, boolean pleasant, boolean grudging, boolean guarded, boolean defiant) {
        if (before > FULL || o.nutrition() <= FULL) return;
        var e = o.entity();
        long now = o.level().getGameTime();
        boolean young = !e.family().adult();
        int composure = o.personality().selfControl();
        String reason = !pleasant ? "unpleasant" : grudging ? "reluctant" : guarded ? "guarded" : e.isPassenger() ? "aboard"
                : !young && composure >= 90 ? "composed" : now < contentAfter ? "recent" : null;
        content = reason == null;
        meltFace = null; meltTail = null; finishFace = null;
        if (content) {
            contentAfter = now + 1200 + (young ? 0 : 24 * composure);
            // The melt face is this body's own, from values it already has (bands, not scene literals):
            // an overshoot or a body under the tired line (25, the same line EcologyPresentation draws the tired face at) drowses off;
            // one that was starving before the bite is moved by it; greedy young ones drool; a bold loner is pleased with itself.
            if (o.nutrition() >= 95 || o.energy() < 25) { meltFace = "sleepy"; meltTail = "yawn"; }
            else if (before < 25) { meltFace = "teary_smile"; meltTail = "satisfied"; }
            else if (young) { meltFace = "drool"; meltTail = "open_smile"; }
            else if (composure < 40) { meltFace = "open_drool"; meltTail = "open_smile"; }
            else if (defiant) { meltFace = "smug"; meltTail = "closed_smile"; }
            else { meltFace = "satisfied"; meltTail = "closed_smile"; }
        } else if (grudging) finishFace = "pursed";
        else if (guarded) finishFace = "teary";
        else if (reason.equals("composed")) finishFace = "closed_smile";
        GameplayLog.event(e, "meal", "full nutrition={} -> {} content={} reason={} selfControl={} face={}", before, o.nutrition(), content,
                reason == null ? "melt" : reason, composure, meltFace != null ? meltFace : finishFace != null ? finishFace : "-");
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
        o.personality().fed(null);
        o.entity().pet().contact(null, true);
        WildEvents.fed(o.entity(), null);
        o.entity().mind().ate(swallowed);
        if (bowlMeal) { o.entity().pet().routineCompleted(0); bowlMeal = false; }
        // Same rule as an item meal: a melting body keeps the swallow face only to the end of the swallow.
        o.entity().gameEvent(GameEvent.EAT);
        o.entity().showReaction(swallowed.odor() >= 40 ? "teary" : "happy", swallowed.odor() >= 40 ? "hurt" : "fed", content ? 8 : 100);
    }
    boolean preparedMeal() { return preparedMeal; }
    void bowlMeal() { bowlMeal = true; }
    boolean beginDose(ItemStack one, UUID player, boolean returnBottle) {
        if (!mouthForMeal() || !makeRoom("dose") || !occupancy().acquireMeal(one.copy())) return false;
        o.temperature().stop();
        syncBodyFood();
        feeder=player;sharedMeal=true;content=false; meltFace = meltTail = finishFace = null; o.doses().returnBottle(returnBottle);mealTicks=1;
        return true;
    }
    private boolean spit(ItemStack item) {
        var dropped = ItemContact.releaseSupported(o.entity(), item, 60);
        if (dropped == null) {
            GameplayLog.limited(o.entity(), "meal", "spit_refused", 200, "spit release refused item={} count={}", item.getItem(), item.getCount());
            return false;
        }
        showComplaint();
        GameplayLog.event(o.entity(), "meal", "spat item={} grade={} pickiness={} taste={} nutrition={} verdict=REFUSE feeder={} tick={} drop={}",
                item.getItem(), FoodTaste.grade(item).id(), o.pickiness(), tasteFor(feeder), o.nutrition(), feeder, mealTicks, dropped.getUUID());
        return true;
    }
    boolean releaseForInterrupt() {
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
        if (spitMeal) tag.putBoolean("SpitMeal", true);
    }
    void load(CompoundTag tag) {
        ItemStack mouth = tag.contains("Mouth",Tag.TAG_COMPOUND) ? ItemStack.parseOptional(o.entity().registryAccess(),tag.getCompound("Mouth")) : ItemStack.EMPTY;
        BodyFood.Bite bodyFood = tag.contains("BodyFood", Tag.TAG_COMPOUND) ? BodyFood.Bite.load(tag.getCompound("BodyFood")) : BodyFood.Bite.EMPTY;
        occupancy().importMeal(mouth, bodyFood, edible(mouth) || Consumables.oral(mouth) || mouth.is(ModItems.WASTE.get()));
        mealTicks = Math.clamp(tag.getInt("MealTicks"),0,63);
        if (hasMouthFood()) mealTicks = Math.clamp(mealTicks,1,44);
        else if (mealTicks < 45) mealTicks=0;
        spitMeal = !mouth().isEmpty() && tag.getBoolean("SpitMeal");
        refusedSpitAt = null;
        sharedMeal=tag.getBoolean("SharedMeal"); preparedMeal=tag.getBoolean("PreparedMeal")
                || InfantFood.isMash(mouth()); bowlMeal=tag.getBoolean("BowlMeal"); feeder=tag.hasUUID("Feeder")?tag.getUUID("Feeder"):null;
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
