package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.Sweets;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Disposition;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * A pet that wants something tells its owner (docs/PET_SHOP.md section 12): near the owner it shows an icon card above its head,
 * voices the need and waits. An answer raises trust and lowers stress; ignoring it repeatedly makes the pet sulk. Server-authoritative and
 * driven by game ticks only. All numbers are project-original.
 */
public final class PetRequests {
    public enum Kind { FOOD, WARM, NUZZLE, PLAY, SNACK, WALK }
    /** Ticks between looks for a need, between two requests of one pet, a request's lifetime; requests per pet and per owner and game day. */
    public static final int INTERVAL = 100, COOLDOWN = 2400, LIFETIME = 1200, PER_DAY = 6, PER_OWNER = 12;
    /** Unanswered requests (owner close by) in one day before the pet sulks; metres of walk that answer a walk request; the owner's reach. */
    public static final int SULK_AT = 3, WALK_METERS = 40, RANGE = 16;
    private final YukkuriEntity animal;
    private Kind pending;
    private int left, today, answered, ignoredNear, hinted, baseAffection, baseBoredom;
    private long lastAt = Long.MIN_VALUE / 2;
    private boolean sulked;
    private double walked;
    private UUID card;

    public PetRequests(YukkuriEntity animal) { this.animal = animal; }

    public Kind pending() { return pending; }
    public int today() { return today; }
    public int answeredToday() { return answered; }
    public int ignoredToday() { return ignoredNear; }
    public boolean sulking() { return sulked; }
    private ServerLevel level() { return (ServerLevel) animal.level(); }
    private Vec3 markPos() { return animal.position().add(0, animal.getBbHeight() + .75, 0); }
    public static String key(Kind kind) { return kind.name().toLowerCase(java.util.Locale.ROOT); }

    /** PetCare.rollDay: a new game day. A pet that sulked yesterday is a little less attached. */
    void newDay(boolean continued) {
        if (continued && sulked && animal.pet().isPet()) animal.life().personality().experienced(Disposition.ATTACHMENT, -1);
        today = answered = ignoredNear = hinted = 0; sulked = false;
    }

    public void tick() {
        if (pending != null) {
            if (!animal.isAlive() || !animal.pet().isPet()) { clear(); return; }
            if (animal.tickCount % 10 == 3 && satisfied()) { resolve(); return; }
            if (--left <= 0) expire();
            return;
        }
        if (animal.tickCount % INTERVAL == 37) consider();
    }

    // choosing a need
    private boolean bowlHasFood() {
        var origin = animal.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-8, -2, -8), origin.offset(8, 2, 8)))
            if (level().hasChunkAt(pos) && level().getBlockEntity(pos) instanceof CareBlockEntity care && care.kind() == CareBlock.Kind.BOWL
                    && care.hasFood(animal.life()::acceptsFood)) return true;
        return false;
    }
    private boolean ownerHas(Player owner, ItemStack match) {
        var inv = owner.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(match.getItem())) return true;
        return false;
    }
    private Kind pick(Player owner) {
        var life = animal.life(); var daily = animal.dailyLife(); var pet = animal.pet();
        if (life.nutrition() < 45 && animal.family().canEatIndependently() && animal.childhood().canEatSolid() && !bowlHasFood()) return Kind.FOOD;
        if (life.temperature().cold() && !pet.care().swaddled()) return Kind.WARM;
        if (daily.affectionNeed() >= 65) return Kind.NUZZLE;
        if (daily.boredom() >= 65) return Kind.PLAY;
        if (pet.indulgence() >= 40 && life.personality().selfishness() >= Temperament.SELFISHNESS_AT_55 && life.nutrition() <= Sweets.FULL && pet.care().snacksToday() < Sweets.DAILY_FREE) return Kind.SNACK;
        if (daily.boredom() >= 50 && level().isDay() && !level().isRaining() && animal.family().adult() && !animal.isLeashed()
                && (pet.care().harnessed() || ownerHas(owner, new ItemStack(Items.LEAD)))) return Kind.WALK;
        return null;
    }
    private int ownerTotal(Player owner) {
        int sum = 0;
        for (var y : level().getEntitiesOfClass(YukkuriEntity.class, owner.getBoundingBox().inflate(48), o -> o.isAlive() && o.pet().ownedBy(owner.getUUID())))
            sum += y.pet().care().requests().today;
        return sum;
    }
    private Player ownerNear() {
        var pet = animal.pet(); var owner = pet.owner() == null ? null : animal.level().getPlayerByUUID(pet.owner());
        return owner != null && owner.isAlive() && !owner.isSpectator() && owner.distanceToSqr(animal) <= RANGE * RANGE && owner.hasLineOfSight(animal) ? owner : null;
    }
    private void consider() {
        var pet = animal.pet();
        if (!pet.isPet() || pet.busy() || !pet.calm() || pet.lessons().awaitingFeedback() || pet.care().habitWindow()) return;
        long now = animal.level().getGameTime();
        int cooldown = (pet.indulgence() > 60 ? COOLDOWN / 2 : COOLDOWN) * (sulked ? 3 : 1);
        if (now - lastAt < cooldown || today >= PER_DAY) return;
        var owner = ownerNear();
        if (owner == null || ownerTotal(owner) >= PER_OWNER) return;
        var kind = pick(owner);
        if (kind != null) start(kind, owner);
    }

    // the request itself
    private void start(Kind kind, Player owner) {
        pending = kind; left = LIFETIME; lastAt = animal.level().getGameTime(); today++; walked = 0;
        baseAffection = animal.dailyLife().affectionNeed(); baseBoredom = animal.dailyLife().boredom();
        voice(kind);
        if ((hinted & 1 << kind.ordinal()) == 0) {
            hinted |= 1 << kind.ordinal();
            owner.displayClientMessage(Component.translatable("pet.mcyukkuri.request." + key(kind), animal.getDisplayName()), true);
        }
    }
    /** Action, face, speech and the icon card of the pending request (also repeated by "ask"). */
    private void voice(Kind kind) {
        String event = switch (kind) { case FOOD -> "ask_food"; case WARM -> "cold"; case NUZZLE -> "want_nuzzle"; case PLAY -> "ask_play"; case SNACK -> "ask_snack"; case WALK -> "ask_walk"; };
        String face = switch (kind) { case FOOD, WARM -> "teary"; case WALK -> "happy"; default -> "open_smile"; };
        animal.playAction("beg"); animal.showReaction(face, event, 80);
        dropCard();
        var at = markPos();
        card = switch (kind) {
            case FOOD -> Staging.itemCard(level(), at, new ItemStack(ModItems.FOOD_BOWL.get()), 100, .55f);
            case WARM -> Staging.itemCard(level(), at, animal.family().adult() ? new ItemStack(Items.TORCH) : new ItemStack(ModItems.SWADDLE_CLOTH.get()), 100, .55f);
            case NUZZLE -> Staging.card(level(), at, Component.literal("♥"), 100, 1.5f);
            case PLAY -> Staging.itemCard(level(), at, new ItemStack(ModItems.TOY.get()), 100, .55f);
            case SNACK -> Staging.itemCard(level(), at, new ItemStack(ModItems.SWEET_CANDY.get()), 100, .55f);
            case WALK -> Staging.itemCard(level(), at, new ItemStack(Items.LEAD), 100, .55f);
        };
    }
    private void dropCard() { if (card != null) Staging.removeCard(level(), card); card = null; }
    private void clear() { pending = null; left = 0; dropCard(); }

    /** The state that ends the request: food in reach, warmth, comfort, play, a treat or the walk. */
    private boolean satisfied() {
        var life = animal.life(); var daily = animal.dailyLife();
        return switch (pending) {
            case FOOD -> life.nutrition() >= 60 || bowlHasFood();
            case WARM -> !life.temperature().cold() || animal.pet().care().swaddled();
            case NUZZLE -> daily.affectionNeed() <= baseAffection - 15;
            case PLAY -> daily.boredom() <= baseBoredom - 15;
            case WALK -> walked >= WALK_METERS;
            case SNACK -> false; // answered by the sweet itself (PetCare.snackEaten)
        };
    }
    /** Another source answered the pending request of this kind (a sweet, a gentle word, ...). */
    public void answer(Kind kind) { if (pending == kind) resolve(); }
    /** The owner walked the pet on its harness for this many blocks. */
    public void walked(double metres) { if (pending == Kind.WALK) walked += metres; }

    private void resolve() {
        var owner = animal.pet().owner();
        clear(); answered++;
        var pet = animal.pet();
        if (owner != null) { animal.life().personality().answeredRequest(owner); pet.contact(owner, true); }
        pet.addStress(-2);
        if (answered % 3 == 0) animal.life().personality().experienced(Disposition.ATTACHMENT, 1);
        animal.showReaction("happy", "request_thanks", 70);
        Staging.sparkle(level(), markPos());
    }
    private void expire() {
        clear();
        var pet = animal.pet();
        pet.addStress(1);
        if (ownerNear() == null) return; // nobody was there to hear it
        if (++ignoredNear >= SULK_AT && !sulked) {
            sulked = true;
            animal.playAction("sulk"); animal.showReaction("teary", "request_sulk", 100);
        }
    }

    /** "Ask how it is" with a request open: the pet says it again. Returns whether there was one. */
    public boolean repeat() {
        if (pending == null) return false;
        voice(pending); left = Math.max(left, LIFETIME / 2);
        return true;
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("Today", today); tag.putInt("Answered", answered); tag.putInt("Ignored", ignoredNear); tag.putInt("Hinted", hinted);
        tag.putLong("LastAt", lastAt); tag.putBoolean("Sulked", sulked);
        return tag;
    }
    public void load(CompoundTag tag) {
        clearQuiet();
        today = Math.clamp(tag.getInt("Today"), 0, 99); answered = Math.clamp(tag.getInt("Answered"), 0, 99);
        ignoredNear = Math.clamp(tag.getInt("Ignored"), 0, 99); hinted = tag.getInt("Hinted") & 63;
        lastAt = tag.contains("LastAt") ? tag.getLong("LastAt") : Long.MIN_VALUE / 2; sulked = tag.getBoolean("Sulked");
    }
    private void clearQuiet() { pending = null; left = 0; card = null; }
}
