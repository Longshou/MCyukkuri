package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.dialogue.NativeDialogue;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Comparator;
import java.util.Locale;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Stable appearance IDs. Each occupied head or remains record represents one generic item.
 *
 * <p>The wearer answers in its own voice, split by self-esteem ({@link SocialDisposition#selfEsteem}, ramped across the same
 * band the acceptance roll uses): a lowly one is relieved and thankful, a middling one hesitates, a haughty one is indignant
 * and shakes the thing off. A pit slave is grateful but cowers. All fitting entry points (player offer, street bin, dropped
 * piece) go through {@link #equip}/{@link #refused}, so one split covers every path.
 */
public enum JunkHeadwear {
    PAPER_BOX(1, "paper_box"),
    BOTTLE_CAP(2, "bottle_cap"),
    ICE_CREAM_CONE(3, "ice_cream_cone");

    public static final String KEY = "JunkHeadwear";
    private final int visualId;
    private final String id;

    JunkHeadwear(int visualId, String id) {
        this.visualId = visualId;
        this.id = id;
    }

    public int visualId() { return visualId; }
    public String id() { return id; }
    public static ItemStack item() { return new ItemStack(ModItems.JUNK_HEADWEAR.get()); }

    public static boolean isItem(ItemStack stack) { return stack.is(ModItems.JUNK_HEADWEAR.get()); }

    /** A bare head big enough for it: a baby or juvenile is not fitted, and a predator never wears junk. */
    public static boolean fits(YukkuriEntity animal) {
        var head = animal.life().custody();
        return head.lost() && head.junk() == null && !animal.headwear().reassembling()
                && canWear(animal);
    }

    private static boolean canWear(YukkuriEntity animal) {
        return (animal.family().adult() || animal.family().subadult())
                && !animal.profile().dangerToYukkuri() && !animal.profile().aquatic();
    }

    public static boolean onDryGround(YukkuriEntity animal) {
        return animal.onGround() && !animal.isInWaterOrBubble() && !animal.isPassenger() && !animal.isVehicle()
                && SurfaceApproach.supportedGround(animal, animal.position());
    }

    public static boolean available(YukkuriEntity animal) {
        return !animal.operations().active() && !animal.family().awaitingLanding() && !animal.family().active()
                && !animal.isPanicking() && !animal.isOnFire() && !animal.body().dying() && !animal.life().isSleeping()
                && animal.life().mealTicks() == 0 && !animal.status().reactions().active();
    }

    /** The two esteem edges the acceptance roll and the wearer's answer share. */
    static final double ACCEPT_LOW = 22, ACCEPT_HIGH = 44;

    /** Low esteem is the first third of the ramp. High esteem is where the acceptance ramp reaches zero. */
    public enum Band {
        MEEK, HESITANT, HAUGHTY;

        public String id() { return name().toLowerCase(Locale.ROOT); }
    }

    public static Band band(SocialDisposition.Contact contact) {
        double esteem = SocialDisposition.ramp(contact.esteem(), ACCEPT_LOW, ACCEPT_HIGH);
        return esteem < 1 / 3. ? Band.MEEK : esteem < 1 ? Band.HESITANT : Band.HAUGHTY;
    }

    public static double chance(SocialDisposition.Contact contact) {
        return (1 - SocialDisposition.ramp(contact.esteem(), ACCEPT_LOW, ACCEPT_HIGH))
                * (.80 + .20 * SocialDisposition.ramp(contact.closeness(), 0, 20))
                * (1 - SocialDisposition.ramp(contact.fear(), 15, 60));
    }

    public static boolean accepts(YukkuriEntity animal, SocialDisposition.Contact contact) {
        var id = animal.getUUID();
        double preference = ((id.getMostSignificantBits() ^ id.getLeastSignificantBits()) >>> 11) * 0x1.0p-53;
        return preference < chance(contact);
    }

    /** Caller checks acceptance and debits exactly one item after this server-side commit. */
    public static boolean equip(YukkuriEntity animal, Entity giver, SocialDisposition.Contact contact) {
        if (!canWear(animal) || !animal.life().custody().equipJunk()) return false;
        Sanction.covered(animal);
        var band = band(contact);
        if (slave(animal)) {
            animal.showReaction(YukkuriAction.TREMBLE, "teary", line(animal, "junk_thanks", "request_thanks"));
        } else if (band == Band.MEEK) {
            animal.showReaction(YukkuriAction.BOW, "closed_smile", line(animal, "junk_thanks", "request_thanks"));
        } else {
            animal.showReaction(YukkuriAction.THINK, "look_down", line(animal, "junk_unsure", "shy"));
        }
        GameplayLog.event(animal, "headwear", "junk equipped variant={} source={} player={} esteem={} band={} item={}",
                animal.life().custody().junk().id(), giver == null ? "ground" : "offer",
                giver == null ? null : giver.getUUID(), contact.esteem(), band.id(), ModItems.JUNK_HEADWEAR.get());
        return true;
    }

    /** A refused fit: a haughty one puffs and retorts, a lowly or middling one only trembles and tears up. */
    public static void refused(YukkuriEntity animal, Entity giver, SocialDisposition.Contact contact) {
        var band = band(contact);
        if (band == Band.HAUGHTY) animal.showReaction(YukkuriAction.PUFF, "cheek_puff", line(animal, "junk_indignant", "request_sulk"));
        else animal.showReaction(YukkuriAction.TREMBLE, "teary", line(animal, "junk_afraid", "afraid"));
        GameplayLog.limited(animal, "headwear", "junk_refusal", 40,
                "junk refused player={} esteem={} chance={} band={}", giver == null ? null : giver.getUUID(),
                contact.esteem(), chance(contact), band.id());
    }

    /** The player takes it off again: the lowly one struggles not to lose it, the haughty one says it never suited it. */
    public static void removed(YukkuriEntity animal, SocialDisposition.Contact contact) {
        switch (band(contact)) {
            case HAUGHTY -> animal.showReaction(YukkuriAction.PUFF, "cheek_puff", line(animal, "junk_indignant", "request_sulk"));
            case MEEK -> animal.showReaction(YukkuriAction.TREMBLE, "teary", line(animal, "junk_afraid", "afraid"));
            case HESITANT -> lowerEyes(animal);
        }
    }

    /**
     * One lasting beat of a wearer, off the wearer's own clock; every value split, nothing per tick:
     * a lowly one shows the new cover off to whoever is in sight, and a haughty one (uneasy or just mocked) shakes
     * it off for real. The middle band lowers its eyes when mocked. Returns the ticks until the next look.
     */
    public static int beat(YukkuriEntity animal, boolean mocked, boolean lowMood) {
        if (animal.life().custody().junk() == null || owned(animal) || !canWear(animal)
                || !available(animal) || !onDryGround(animal)) return 200;
        return switch (band(SocialDisposition.contact(animal, null))) {
            case MEEK -> {
                if (mocked) yield wince(animal) ? 1200 : 300;
                yield !slave(animal) && showOff(animal) ? 1200 + animal.getRandom().nextInt(1200) : 300;
            }
            case HAUGHTY -> {
                if (!mocked && !lowMood) yield 600;
                yield shakeOff(animal, mocked) ? 2400 : 300;
            }
            case HESITANT -> {
                if (mocked) lowerEyes(animal);
                yield 600;
            }
        };
    }

    /** The lowly one is mocked: it keeps the cover and only winces, it does not answer back. */
    private static boolean wince(YukkuriEntity animal) {
        if (!animal.showReaction("teary", line(animal, "junk_afraid", "afraid"), 60)) return false;
        GameplayLog.limited(animal, "headwear", "junk_mock_wince", 600,
                "junk mock wince variant={} esteem={}", animal.life().custody().junk().id(), SocialDisposition.selfEsteem(animal));
        return true;
    }

    private static void lowerEyes(YukkuriEntity animal) {
        animal.showReaction(YukkuriAction.THINK, "look_down", "shy");
    }

    private static boolean showOff(YukkuriEntity animal) {
        var watcher = animal.level().getEntitiesOfClass(LivingEntity.class, animal.getBoundingBox().inflate(6, 2, 6),
                        y -> y != animal && watchedPerson(y) && animal.hasLineOfSight(y))
                .stream().min(Comparator.comparingDouble(animal::distanceToSqr)).orElse(null);
        if (watcher == null) return false;
        animal.getLookControl().setLookAt(watcher, 30, 30);
        animal.showReaction(YukkuriAction.PROUD, "happy", "");
        GameplayLog.limited(animal, "headwear", "junk_shown_off", 1200,
                "junk shown off variant={} esteem={} to={}",
                animal.life().custody().junk().id(), SocialDisposition.selfEsteem(animal), watcher.getUUID());
        return true;
    }

    /** Shakes it off for real: the item lands on the ground as the generic junk item, then the wearer sulks. */
    private static boolean shakeOff(YukkuriEntity animal, boolean mocked) {
        var variant = animal.life().custody().junk();
        if (!animal.life().custody().putDownJunk()) return false;
        animal.showReaction(YukkuriAction.SHAKE, "angry", line(animal, "junk_indignant", "request_sulk"));
        GameplayLog.event(animal, "headwear", "junk shaken_off variant={} esteem={} reason={} destination=ground item={}",
                variant.id(), SocialDisposition.selfEsteem(animal), mocked ? "mocked" : "low_mood",
                ModItems.JUNK_HEADWEAR.get());
        return true;
    }

    private static boolean watchedPerson(LivingEntity looker) {
        return looker instanceof Player player ? !player.isSpectator()
                : looker instanceof YukkuriEntity peer && !peer.profile().dangerToYukkuri();
    }

    /** Somebody's property (a pet, or an individual a person owns): what it wears is the owner's call, not its own. */
    private static boolean owned(YukkuriEntity animal) {
        var custody = Custody.view(animal);
        return animal.pet().isPet() || custody.owner() != null || custody.hasPendingStaffRole() || custody.role() == Custody.Role.STOCK;
    }

    private static String line(YukkuriEntity animal, String specific, String shared) {
        var species = animal.getVariant().dialogueSpecies();
        var tier = animal.family().subadult() ? "young" : animal.speechTier();
        return NativeDialogue.has(species, tier, animal.myon().quirk(), specific) ? specific : shared;
    }

    private static boolean slave(YukkuriEntity animal) { return animal.settlement().society().slave(); }

    public static JunkHeadwear fromVisual(int visualId) {
        return switch (visualId) {
            case 1 -> PAPER_BOX;
            case 2 -> BOTTLE_CAP;
            case 3 -> ICE_CREAM_CONE;
            default -> null;
        };
    }

    public static JunkHeadwear read(CompoundTag tag) {
        return switch (tag.getString(KEY)) {
            case "paper_box" -> PAPER_BOX;
            case "bottle_cap" -> BOTTLE_CAP;
            case "ice_cream_cone" -> ICE_CREAM_CONE;
            default -> null;
        };
    }
}
