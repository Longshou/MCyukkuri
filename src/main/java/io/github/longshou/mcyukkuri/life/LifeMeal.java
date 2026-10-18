package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.body.FillingResource;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import io.github.longshou.mcyukkuri.interaction.ItemContact;
import io.github.longshou.mcyukkuri.pet.social.BadgeSocial;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Disposition;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gameevent.GameEvent;

/** Delegated life state; persistence stays in the original YukkuriLife compound. */
final class LifeMeal {
    private final YukkuriLife o;
    LifeMeal(YukkuriLife o) { this.o = o; }

    private ItemStack mouth = ItemStack.EMPTY;
    private int mealTicks, mealHold;
    private UUID feeder;
    private boolean sharedMeal, preparedMeal, bowlMeal;
    ItemStack mouth() { return mouth; }

    int mealTicks() { return mealTicks; }

    boolean needsFood() { return o.nutrition() <= 75 && mouth.isEmpty() && mealTicks == 0; }

    boolean edible(ItemStack food){return food.is(YukkuriEntity.FOOD)||Toxins.dose(food)>0&&o.nutrition()<Toxins.STARVING||Consumables.kind(food)==Consumables.Kind.SPICY||FillingResource.isFilling(food)||o.entity().profile().houseBody()&&food.is(ModItems.FOOD_SCRAP.get());}

    boolean acceptsFood(ItemStack food){return edible(food)&&(!FillingResource.isFilling(food)||FillingResource.acceptable(o.entity(),food));}

    boolean readyForFood() { return needsFood() && !o.entity().family().awaitingLanding() && !o.entity().isPanicking() && !o.entity().isCommandPlaying() && o.entity().isAlive(); }

    boolean canEat() { return readyForFood() && o.entity().family().canEatIndependently() && o.entity().childhood().canEatSolid(); }

    boolean canTakeSweet(ItemStack food) {
        return Sweets.is(food) && o.nutrition() <= Sweets.FULL && mouth.isEmpty() && mealTicks == 0 && !o.entity().family().awaitingLanding() && !o.entity().isPanicking()
                && !o.entity().isCommandPlaying() && o.entity().isAlive() && o.entity().family().canEatIndependently() && o.entity().childhood().canEatSolid();
    }

    boolean canReceiveCareFood() { return readyForFood(); }

    boolean canReceiveCareFood(ItemStack food) { return canReceiveCareFood() && acceptsFood(food); }

    boolean beginMeal(ItemStack transferred, UUID fromPlayer, boolean alreadyShared) {
        if (!(canEat() || canTakeSweet(transferred)) || transferred.isEmpty() || !acceptsFood(transferred) || transferred.getCount() != 1) return false;
        return startMeal(transferred, fromPlayer, alreadyShared, "take_food");
    }

    boolean beginPreparedMeal(ItemStack prepared, UUID fromPlayer) {
        var player = fromPlayer == null ? null : o.level().getPlayerByUUID(fromPlayer);
        if (player == null || !player.isAlive() || player.distanceToSqr(o.entity()) > 9 || !player.hasLineOfSight(o.entity())
                || !o.entity().family().needsMash() || !canReceiveCareFood(prepared) || !InfantFood.isMash(prepared) || prepared.getCount() != 1) return false;
        return startMeal(prepared, fromPlayer, true, "receive_mash");
    }

    boolean beginParentMeal(YukkuriEntity caregiver, ItemStack prepared) {
        if (caregiver == null || caregiver.level() != o.entity().level() || !caregiver.family().caresFor(o.entity())
                || !FamilyFeedingContact.mouthsMeet(caregiver, o.entity())) return false;
        return o.entity().family().needsMash()
                && canReceiveCareFood(prepared) && InfantFood.isMash(prepared) && prepared.getCount() == 1
                && startMeal(prepared, null, true, "receive_mash");
    }

    boolean beginBraidMeal(YukkuriEntity caregiver, ItemStack prepared) {
        if (caregiver == null || caregiver.level() != o.entity().level() || !caregiver.family().caresFor(o.entity())
                || !(caregiver.getVehicle() instanceof HatBoatEntity boat)
                || o.entity().getVehicle() != boat || !boat.protects(caregiver) || !boat.protects(o.entity())
                || !FamilyFeedingContact.mouthsMeet(caregiver, o.entity())) return false;
        return o.entity().family().needsMash() && canReceiveCareFood(prepared) && InfantFood.isMash(prepared) && prepared.getCount() == 1
                && startMeal(prepared, null, true, "receive_mash");
    }

    boolean startMeal(ItemStack transferred, UUID fromPlayer, boolean alreadyShared, String reaction) {
        o.temperature().stop();
        o.entity().dailyLife().stop();
        bowlMeal = false; mouth = transferred.copy(); mealHold = 0; feeder = fromPlayer; sharedMeal = alreadyShared; preparedMeal = InfantFood.isMash(transferred); mealTicks = 1;
        o.beginConsumption(); o.entity().getNavigation().stop(); o.entity().setPersistenceRequired();
        o.entity().showReaction("happy", reaction, 60); return true;
    }

    boolean holdMeal(int ticks) {
        if (mouth.isEmpty() || mealTicks < 1 || mealTicks > 36 || o.entity().isPanicking()) return false;
        mealHold = Math.max(mealHold, ticks); return true;
    }

    void releaseMeal() { mealHold = 0; }

    boolean mealHeld() { return mealHold > 0 && !mouth.isEmpty(); }

    boolean snatchedBy(YukkuriEntity thief) {
        if (mouth.isEmpty() || thief == o.entity() || !thief.isAlive()) return false;
        if (!thief.life().beginMeal(mouth.copy(), null, true)) { o.interrupt(); return true; }
        mouth = ItemStack.EMPTY; feeder = null; mealTicks = 0; mealHold = 0; sharedMeal = false; preparedMeal = false;
        return true;
    }

    boolean handOverMouth(YukkuriEntity peer, String event) {
        if (mouth.isEmpty() || mealTicks == 0 || mealTicks >= 45 || peer == o.entity() || !peer.isAlive()) return false;
        ItemStack transferred = mouth.copy();
        if (!peer.life().beginMeal(transferred, feeder, true)) return false;
        mouth = ItemStack.EMPTY; feeder = null; mealTicks = 0; mealHold = 0; sharedMeal = false; preparedMeal = false;
        YukkuriWorldData.get(o.level()).relate(peer.getUUID(), o.entity().getUUID(), 8);
        o.personality().experienced(Disposition.SELFISHNESS, -1); o.entity().showReaction("happy", event, 100);
        o.personality().decided("share_food"); peer.life().personality().environment().record(o.entity().getUUID(), "shared_food", 4);
        return true;
    }

    void tryShare() {
        var peer = o.level().getEntitiesOfClass(YukkuriEntity.class, o.entity().getBoundingBox().inflate(1.5), other ->
                other != o.entity() && other.isAlive() && other.life().canEat() && other.life().nutrition() < o.nutrition() - 15
                        && o.canRecognize(other) && o.entity().hasLineOfSight(other)
                        && ItemContact.mouthsMeet(o.entity(), other)).stream()
                .sorted(Comparator.comparingInt(other->o.entity().family().caresFor(other)?0:1)).findFirst().orElse(null);
        if (peer == null || mouth.isEmpty()) return;
        int score=o.personality().shareScore(o.nutrition(),peer.life().nutrition(),o.entity().family().caresFor(peer),YukkuriWorldData.get(o.level()).affinity(o.entity().getUUID(),peer.getUUID()));
        if(score<50){o.personality().decided("keep_food");return;}
        ItemStack transferred = mouth.copy();
        if (peer.life().beginMeal(transferred, feeder, true)) {
            mouth = ItemStack.EMPTY; feeder = null; mealTicks = 0; mealHold = 0; sharedMeal = false; preparedMeal = false;
            YukkuriWorldData.get(o.level()).relate(peer.getUUID(), o.entity().getUUID(), 8);
            o.personality().experienced(Disposition.SELFISHNESS, -1); o.entity().showReaction("happy", "share", 100);
            o.personality().decided("share_food");peer.life().personality().environment().record(o.entity().getUUID(),"shared_food",4);
        }
    }
    /** False preserves the old interrupt-and-return from the entire life tick. */
    boolean tickChew() {
        if (mealTicks > 0) {
            if (!mouth.isEmpty() && !Consumables.oral(mouth) && !acceptsFood(mouth)) { o.interrupt(); return false; }
            o.entity().getNavigation().stop();
            if (mealHold > 0 && mealTicks >= 8 && mealTicks < 40) mealHold--; else mealTicks++; // a begging peer made the bite wait (pet/social/BadgeArcs)
            // A hungry wild onlooker may beg for this very bite while it is still in the mouth (badge-holders only; pet/social).
            if (mealTicks == 3 && !sharedMeal && !preparedMeal && !mouth.isEmpty()) BadgeSocial.mealSeen(o.entity());
            if (mealTicks == 6 && !sharedMeal && o.nutrition() >= 35) tryShare();
            if (mealTicks == 45 && !mouth.isEmpty()) {
                if(Consumables.oral(mouth)){
                    var returned=Consumables.swallow(o.entity(),mouth)?o.doses().returnBottle()?new ItemStack(Items.GLASS_BOTTLE):ItemStack.EMPTY:mouth.copy();
                    mouth=ItemStack.EMPTY;
                    if(!returned.isEmpty()){
                        var player=feeder==null?null:o.level().getPlayerByUUID(feeder);
                        if(player==null||!player.isAlive()||player.distanceToSqr(o.entity())>16||!player.getInventory().add(returned))o.entity().spawnAtLocation(returned);
                    }
                    o.doses().clearReturnBottle();feeder=null;o.entity().gameEvent(GameEvent.DRINK);
                }else{
                var food = mouth.get(DataComponents.FOOD);
                boolean scrap=mouth.is(ModItems.FOOD_SCRAP.get());
                boolean filling=FillingResource.isFilling(mouth);
                boolean spicy=Consumables.kind(mouth)==Consumables.Kind.SPICY;
                boolean sweet=Sweets.is(mouth);
                ItemStack swallowed=mouth.copy();
                float gain = filling?FillingResource.nutrition(mouth):scrap?10:food == null ? 20 : Math.clamp(food.nutrition() * 5, 10, 35);
                if(mouth.is(ModItems.GRAIN_BUDGET.get()))gain=12;
                else if(mouth.is(ModItems.GRAIN_PREMIUM.get()))gain=30;
                if(sweet)gain=Sweets.nutrition(mouth);
                int toxic=Toxins.dose(mouth);
                if(spicy){gain=12;o.doses().spicyTicks(240);o.increaseDryness(45);}
                else if(sweet)o.increaseDryness(Sweets.dryness(mouth));
                else if(mouth.is(ModItems.GRAIN_BUDGET.get())||mouth.is(ModItems.GRAIN_REGULAR.get()))o.increaseDryness(15); // premium grain never leaves the pet thirsty
                if(filling)FillingResource.swallowed(o.entity(),mouth);
                // The held item is retired at the same server step that grants nutrition.
                mouth = ItemStack.EMPTY;
                o.entity().species().ate(o.nutrition());
                o.addNutrition(gain); o.addBladder(gain * .5f);
                if(spicy)o.entity().body().poisoned(Toxins.SPICY_DOSE);
                else if(toxic>0)o.entity().body().poisoned(toxic);
                if(!filling&&!spicy&&toxic==0)o.entity().heal(2); o.personality().fed(feeder); o.entity().pet().contact(feeder, true); WildEvents.fed(o.entity(),feeder); String treat = Sweets.eaten(o.entity(), swallowed, feeder); o.entity().mind().ate(swallowed, treat != null); feeder = null;
                if (bowlMeal) { o.entity().pet().routineCompleted(0); bowlMeal = false; }
                o.entity().gameEvent(GameEvent.EAT); if(!o.entity().body().sick()||!(spicy||toxic>0))o.entity().showReaction(spicy||toxic>0?"surprise":"happy", spicy||toxic>0?"hurt":treat!=null?treat:"fed", 100);
                if(!spicy&&toxic==0)o.level().sendParticles(ParticleTypes.HEART, o.entity().getX(), o.entity().getY()+o.entity().getBbHeight(), o.entity().getZ(), 3, .1, .05, .1, 0);
                }
            }
            if (mealTicks >= 64) { mealTicks = 0; sharedMeal = false; preparedMeal = false; o.finishConsumption(); }
        }
        return true;
    }
    boolean preparedMeal() { return preparedMeal; }
    void bowlMeal() { bowlMeal = true; }
    void beginDose(ItemStack one, UUID player, boolean returnBottle) {
        mouth=one.copy();mealHold=0;feeder=player;sharedMeal=true;o.doses().returnBottle(returnBottle);mealTicks=1;
    }
    boolean releaseForInterrupt() {
        if (!mouth.isEmpty()) {
            ItemEntity dropped = ItemContact.release(o.entity(), mouth, 60);
            if (dropped == null) return false; // Retain the owned serving if spawning was rejected.
        }
        return true;
    }
    void clearInterrupted() {
        mouth = ItemStack.EMPTY; mealTicks = 0; mealHold = 0; feeder = null; sharedMeal = false;preparedMeal=false;
    }
    void save(CompoundTag tag) {
        if (!mouth.isEmpty()) tag.put("Mouth", mouth.save(o.entity().registryAccess()));
        tag.putInt("MealTicks", mealTicks); tag.putBoolean("SharedMeal", sharedMeal); tag.putBoolean("PreparedMeal",preparedMeal); tag.putBoolean("BowlMeal", bowlMeal); if (feeder != null) tag.putUUID("Feeder", feeder);
    }
    void load(CompoundTag tag) {
        mealHold = 0;
        mouth = tag.contains("Mouth",Tag.TAG_COMPOUND) ? ItemStack.parseOptional(o.entity().registryAccess(),tag.getCompound("Mouth")) : ItemStack.EMPTY;
        if (!mouth.isEmpty() && ((!edible(mouth)&&!Consumables.oral(mouth)) || mouth.getCount()!=1)) mouth=ItemStack.EMPTY;
        mealTicks = Math.clamp(tag.getInt("MealTicks"),0,63);
        if (!mouth.isEmpty()) mealTicks = Math.clamp(mealTicks,1,44);
        else if (mealTicks < 45) mealTicks=0;
        sharedMeal=tag.getBoolean("SharedMeal"); preparedMeal=tag.getBoolean("PreparedMeal") || InfantFood.isMash(mouth); bowlMeal=tag.getBoolean("BowlMeal"); feeder=tag.hasUUID("Feeder")?tag.getUUID("Feeder"):null;
    }

}
