package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.entity.presentation.FaceTier;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.life.Duty;
import io.github.longshou.mcyukkuri.life.Sweets;
import io.github.longshou.mcyukkuri.life.YukkuriLife;
import io.github.longshou.mcyukkuri.life.NeedWarnings;
import io.github.longshou.mcyukkuri.perception.Belief;
import io.github.longshou.mcyukkuri.perception.Knowledge;
import io.github.longshou.mcyukkuri.perception.Senses;
import io.github.longshou.mcyukkuri.pet.rules.RulesMath;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import java.util.Locale;

/**
 * A pet that wants something tells its owner (docs/FEATURES.md): near the owner it shows an icon card above its head,
 * voices the need and waits. An answer raises trust and lowers stress; ignoring it repeatedly makes the pet sulk. Server-authoritative and
 * driven by game ticks only. All numbers are project-original.
 */
public final class PetRequests {
    public enum Kind { FOOD, WARM, NUZZLE, PLAY, SNACK, WALK, WATER }
    private static final int REQUEST_MASK = (1 << Kind.values().length) - 1;
    /** Ticks between looks for a need, between two requests of one pet, a request's lifetime; requests per pet and per owner and game day. */
    public static final int INTERVAL = 100, COOLDOWN = 2400, LIFETIME = 1200, PER_DAY = 6, PER_OWNER = 12;
    /** Unanswered requests (owner close by) in one day before the pet sulks; metres of walk that answer a walk request; the owner's reach. */
    public static final int SULK_AT = 3, WALK_METERS = 40, RANGE = 16;
    private final YukkuriEntity animal;
    private Kind pending;
    private int left, today, answered, ignoredNear, hinted, expiredNeeds, baseAffection, baseBoredom;
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
    public static String key(Kind kind) { return kind.name().toLowerCase(Locale.ROOT); }

    /** PetCare.rollDay resets the request budget and yesterday's sulk state. */
    void newDay(boolean continued) {
        today = answered = ignoredNear = hinted = 0; sulked = false;
    }

    public void tick() {
        if (pending != null) {
            if (!animal.isAlive() || !animal.pet().isPet()) {
                GameplayLog.event(animal,"request","cancelled kind={} owner={} alive={} remainingTicks={}",
                        pending,animal.pet().owner(),animal.isAlive(),left);
                clear(); return;
            }
            if (animal.tickCount % 10 == 3) {
                var how = satisfied();
                if (how != null) { resolve(how, how == Closure.FOUND ? "discovered" : "own"); return; }
            }
            if (--left <= 0) expire();
            return;
        }
        if (animal.tickCount % INTERVAL == 37) consider();
    }

    // choosing a need
    private boolean ownerHas(Player owner, ItemStack match) {
        var inv = owner.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(match.getItem())) return true;
        return false;
    }
    private Kind pick(Player owner) {
        var life = animal.life(); var daily = animal.dailyLife(); var pet = animal.pet();
        if (ready(Kind.FOOD) && life.nutrition() < 45
                && animal.family().canEatIndependently() && animal.childhood().canEatSolid() && !life.hasBowlFood(Knowledge.WORTH_A_LOOK)) return Kind.FOOD;
        // A body checks its own fixtures before it begs: a bowl or waterer it believes stocked, or has not looked at, is worth a walk first.
        if (ready(Kind.WATER) && life.dryness() >= YukkuriLife.THIRST_THRESHOLD && !life.hasDrink(Knowledge.WORTH_A_LOOK)) return Kind.WATER;
        if (ready(Kind.WARM) && life.temperature().cold() && !pet.care().swaddled()) return Kind.WARM;
        if (ready(Kind.NUZZLE) && daily.affectionNeed() >= 65) return Kind.NUZZLE;
        if (ready(Kind.PLAY) && daily.boredom() >= 65) return Kind.PLAY;
        if (ready(Kind.SNACK) && life.personality().temperament().is(Gate.SELFISH) && RulesMath.demandsSnack(pet.obedience())
                && life.nutrition() <= Sweets.FULL
                && pet.care().snacksToday() < Sweets.DAILY_FREE) return Kind.SNACK;
        if (ready(Kind.WALK) && daily.boredom() >= 50 && level().isDay() && !level().isRaining()
                && animal.family().adult() && !animal.isLeashed()
                && ownerHas(owner, new ItemStack(Items.LEAD))) return Kind.WALK;
        return null;
    }
    private boolean ready(Kind kind) { return (expiredNeeds & 1 << kind.ordinal()) == 0; }
    private void relieved(Kind kind) { expiredNeeds &= ~(1 << kind.ordinal()); }
    private static boolean waterAnswered(YukkuriLife life) { return life.dryness() <= 25 || life.hasDrink(Knowledge.KNOWN_STOCKED); }

    /** Sample the existing need values at the request cadence, even during cooldown or while the owner is away. */
    private void observeRelief() {
        if (expiredNeeds == 0) return;
        var life = animal.life();
        var daily = animal.dailyLife();
        if (life.nutrition() >= 55) relieved(Kind.FOOD);
        if (!ready(Kind.WATER) && waterAnswered(life)) relieved(Kind.WATER);
        if (!life.temperature().cold() || animal.pet().care().swaddled()) relieved(Kind.WARM);
        if (daily.affectionNeed() <= 50) relieved(Kind.NUZZLE);
        if (daily.boredom() <= 50) relieved(Kind.PLAY);
        if (life.nutrition() > Sweets.FULL) relieved(Kind.SNACK);
        if (daily.boredom() <= 40 || animal.isLeashed()) relieved(Kind.WALK);
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
        if (!pet.isPet()) { expiredNeeds = 0; return; }
        observeRelief();
        if (pet.busy() || !pet.calm() || pet.lessons().awaitingFeedback() || pet.care().habitWindow()
                || pet.conscience().answeringRule() || animal.duty().holds(Duty.Kind.RULES)) return;
        long now = animal.level().getGameTime();
        int cooldown = COOLDOWN * (sulked ? 3 : 1);
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
        if (kind == Kind.WARM) animal.mind().felt(EmotionEvent.CHILLED);
        voice(kind);
        if ((hinted & 1 << kind.ordinal()) == 0) {
            hinted |= 1 << kind.ordinal();
            owner.displayClientMessage(Component.translatable("pet.mcyukkuri.request." + key(kind), animal.getDisplayName()), true);
        }
        GameplayLog.event(animal,"request","started kind={} owner={} today={} nutrition={} affectionNeed={} boredom={} dryness={}",
                kind,owner.getUUID(),today,animal.life().nutrition(),baseAffection,baseBoredom,animal.life().dryness());
    }
    /** Action, face, speech and the icon card of the pending request (also repeated by "ask"). */
    private void voice(Kind kind) {
        String event = switch (kind) {
            case FOOD -> "ask_food";
            case WATER -> "ask_water";
            case WARM -> "cold";
            case NUZZLE -> "want_nuzzle";
            case PLAY -> "ask_play";
            case SNACK -> "ask_snack";
            case WALK -> "ask_walk";
        };
        var personality = animal.life().personality();
        var bond = personality.relation(animal.pet().owner());
        double esteem = SocialDisposition.selfEsteem(animal);
        double wary = SocialDisposition.wariness(animal, bond);
        String face = kind == Kind.WATER ? animal.life().dryness() >= 70 ? "teary_pant" : "pursed"
                : wary >= .5 ? "teary"
                : SocialDisposition.insistence(personality, esteem, wary, bond) >= .52 ? "cheek_puff"
                : switch (kind) { case FOOD, WARM -> "teary"; case WALK -> "happy"; default -> "open_smile"; };
        boolean demand = (kind == Kind.FOOD || kind == Kind.SNACK) && wary < .5
                && NeedWarnings.insistence(personality.temperament()) >= .75;
        animal.showReaction(demand ? YukkuriAction.STAMP : YukkuriAction.BEG, demand ? "cheek_puff" : face,
                event, 80, FaceTier.Tier.MEDIUM); // the insulting demand line is for strays facing strangers; a pet asks its keeper
        GameplayLog.event(animal,"request","beat=ask face={} line={} kind={}",demand ? "cheek_puff" : face,event,kind);
        dropCard();
        var at = markPos();
        card = switch (kind) {
            case FOOD -> Staging.itemCard(level(), at, new ItemStack(ModItems.FOOD_BOWL.get()), 100, .55f);
            case WATER -> Staging.itemCard(level(), at, new ItemStack(ModItems.WATERER.get()), 100, .55f);
            case WARM -> Staging.itemCard(level(), at, animal.family().adult() ? new ItemStack(Items.TORCH) : new ItemStack(ModItems.SWADDLE_CLOTH.get()), 100, .55f);
            case NUZZLE -> Staging.card(level(), at, Component.literal("♥"), 100, 1.5f);
            case PLAY -> Staging.itemCard(level(), at, new ItemStack(ModItems.TOY.get()), 100, .55f);
            case SNACK -> Staging.itemCard(level(), at, new ItemStack(ModItems.SWEET_CANDY.get()), 100, .55f);
            case WALK -> Staging.itemCard(level(), at, new ItemStack(Items.LEAD), 100, .55f);
        };
    }
    private void dropCard() { if (card != null) Staging.removeCard(level(), card); card = null; }
    private void clear() { pending = null; left = 0; dropCard(); }

    /**
     * How the request ended, if it has: food or water the body now knows is in reach (it saw the fixture stocked), warmth, comfort, play,
     * a treat or the walk. Null while it is still open.
     */
    private Closure satisfied() {
        var life = animal.life(); var daily = animal.dailyLife();
        return switch (pending) {
            case FOOD -> life.nutrition() >= 60 ? Closure.MET : life.hasBowlFood(Knowledge.KNOWN_STOCKED) ? Closure.FOUND : null;
            case WATER -> life.dryness() <= 25 ? Closure.MET : life.hasDrink(Knowledge.KNOWN_STOCKED) ? Closure.FOUND : null;
            case WARM -> !life.temperature().cold() || animal.pet().care().swaddled() ? Closure.GIVEN : null;
            case NUZZLE -> daily.affectionNeed() <= baseAffection - 15 ? Closure.GIVEN : null;
            case PLAY -> daily.boredom() <= baseBoredom - 15 ? Closure.GIVEN : null;
            case WALK -> walked >= WALK_METERS ? Closure.GIVEN : null;
            case SNACK -> null; // answered by the sweet itself (PetCare.snackEaten)
        };
    }

    /** Whether the need behind a water or food request is met in the world, seen or not: scores the owner, never moves the body. */
    private boolean metUnseen(Kind kind) {
        var life = animal.life();
        return switch (kind) {
            case FOOD -> life.nutrition() >= 60 || life.hasBowlFood(Knowledge.ACTUAL);
            case WATER -> life.dryness() <= 25 || life.hasDrink(Knowledge.ACTUAL);
            default -> false;
        };
    }
    /** The body has just seen a fixture stocked that it did not know, or thought empty, for a need it feels. An open request of that kind closes through {@link #satisfied()}; with none open, only the beat. */
    public void found(Kind kind) {
        if (animal.pet().isPet() && pending != kind) pleased(kind, "discovered");
    }
    /** Another source answered the pending request of this kind (a sweet, a gentle word, ...). */
    public void answer(Kind kind) {
        relieved(kind);
        if (pending == kind) resolve(Closure.GIVEN, "own");
    }
    /**
     * The body saw or heard its water or food being put out (CareBlockEntity). Heard counts as seen: the owner is thanked as if it had
     * been in view; a pour by someone else, or none, is only the body's own pleasure at the supply.
     */
    public void witnessed(Kind kind, Entity actor, Senses.Via via) {
        if (pending != kind) return;
        boolean owner = actor != null && animal.pet().ownedBy(actor.getUUID());
        resolve(owner ? Closure.THANKED : Closure.FOUND, via.name().toLowerCase(Locale.ROOT));
    }
    /** Mouth occupancy and nutrition have already committed; only that meal writes its feeder's relationship. */
    public boolean swallowed(UUID feeder, boolean ordinaryFood) {
        if (!ordinaryFood || !animal.pet().isPet()) return false;
        if (animal.life().nutrition() >= 55) relieved(Kind.FOOD);
        boolean ownerFed = animal.pet().ownedBy(feeder);
        if (pending != Kind.FOOD || !ownerFed && animal.life().nutrition() < 60) return false;
        resolve(Closure.GIVEN, "own");
        return ownerFed;
    }
    /** The owner walked the pet on the lead for this many blocks. */
    public void walked(double metres) { if (pending == Kind.WALK) walked += metres; }

    /** How a request ended. Only the owner's own answer (given, or perceived being done) is credited to the owner. */
    private enum Closure {
        /** The owner answered it. */ GIVEN,
        /** The body saw or heard the owner put the water or food out. */ THANKED,
        /** The body found the supply stocked (or heard it filled by no one it knows): pleased at the supply, nothing owed. */ FOUND,
        /** The need was met some other way (it drank, it ate, rain): quiet relief. */ MET
    }

    private void resolve(Closure how, String via) {
        var owner = animal.pet().owner();
        var kind=pending; int remaining=left,stressBefore=animal.pet().stress();
        clear(); answered++;
        var pet = animal.pet();
        boolean meal = kind == Kind.FOOD || kind == Kind.SNACK, credited = how == Closure.GIVEN || how == Closure.THANKED;
        if (credited && owner != null && !meal) { animal.life().personality().answeredRequest(owner); pet.contact(owner, true); }
        pet.addStress(-2);
        if (credited && !meal) {
            animal.showReaction("happy", "request_thanks", FaceTier.Tier.MEDIUM);
            GameplayLog.event(animal,"request","beat=thanks face=happy line=request_thanks kind={} via={}",kind,via);
        }
        if (credited) Staging.sparkle(level(), markPos());
        if (how == Closure.FOUND) pleased(kind, via);
        GameplayLog.event(animal,"request","satisfied kind={} owner={} remainingTicks={} walked={} answeredToday={} stress={} -> {} via={}",
                kind,owner,remaining,walked,answered,stressBefore,pet.stress(),via);
    }

    /**
     * The pleased beat of a body that found its water or food stocked: about the supply, not about the owner. A hungry body drools
     * and cheers the food; a mild thirst is content, a parched one only sighs with relief (no existing water line fits it).
     */
    private void pleased(Kind kind, String via) {
        var life = animal.life();
        boolean food = kind == Kind.FOOD;
        boolean pressing = (food ? Belief.foodUrgency(life.nutrition()) : Belief.waterUrgency(life.dryness())) >= .5;
        String face = food ? pressing ? "drool" : "happy" : pressing ? "closed_smile" : "happy";
        String line = food ? "food_found" : pressing ? "" : "mood_good";
        animal.showReaction(face, line, FaceTier.Tier.SHORT);
        GameplayLog.event(animal,"request","beat=discovered face={} line={} kind={} via={}",face,line.isEmpty() ? "-" : line,kind,via);
    }
    private void expire() {
        var kind=pending; int stressBefore=animal.pet().stress();
        if (metUnseen(kind)) {
            // Filled behind its back: the owner did answer, so no ignored count, no sulk and no stress.
            clear();
            GameplayLog.event(animal,"request","expired kind={} owner={} outcome=answered_unseen stress={}",kind,animal.pet().owner(),stressBefore);
            return;
        }
        clear();
        expiredNeeds |= 1 << kind.ordinal();
        var pet = animal.pet();
        pet.addStress(1);
        var nearbyOwner=ownerNear();
        if (nearbyOwner == null) {
            GameplayLog.event(animal,"request","expired kind={} owner={} ownerNearby=false stress={} -> {}",
                    kind,pet.owner(),stressBefore,pet.stress());
            return; // nobody was there to hear it
        }
        if (++ignoredNear >= SULK_AT && !sulked) {
            sulked = true;
            animal.showReaction(YukkuriAction.SULK, "puff_open", "request_sulk", 100, FaceTier.Tier.MEDIUM);
        }
        GameplayLog.event(animal,"request","expired kind={} owner={} ownerNearby=true ignoredToday={} sulking={} stress={} -> {}",
                kind,nearbyOwner.getUUID(),ignoredNear,sulked,stressBefore,pet.stress());
    }

    /** "Ask how it is" with a request open: the pet says it again. Returns whether there was one. */
    public boolean repeat() {
        if (pending == null) return false;
        voice(pending);
        return true;
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("Today", today); tag.putInt("Answered", answered); tag.putInt("Ignored", ignoredNear); tag.putInt("Hinted", hinted);
        tag.putLong("LastAt", lastAt); tag.putBoolean("Sulked", sulked); tag.putInt("ExpiredNeeds", expiredNeeds);
        return tag;
    }
    public void load(CompoundTag tag) {
        clearQuiet();
        today = Math.clamp(tag.getInt("Today"), 0, 99); answered = Math.clamp(tag.getInt("Answered"), 0, 99);
        ignoredNear = Math.clamp(tag.getInt("Ignored"), 0, 99); hinted = tag.getInt("Hinted") & REQUEST_MASK;
        expiredNeeds = tag.getInt("ExpiredNeeds") & REQUEST_MASK;
        lastAt = tag.contains("LastAt") ? tag.getLong("LastAt") : Long.MIN_VALUE / 2; sulked = tag.getBoolean("Sulked");
    }
    private void clearQuiet() { pending = null; left = 0; card = null; }
}
