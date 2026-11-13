package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.Entity;

/** Stable appearance IDs. Each occupied head or remains record represents one generic item. */
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

    public static boolean fits(YukkuriEntity animal) {
        var head = animal.life().custody();
        return head.lost() && head.junk() == null && !animal.headwear().reassembling();
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

    public static double chance(SocialDisposition.Contact contact) {
        return (1 - SocialDisposition.ramp(contact.esteem(), 22, 44))
                * (.80 + .20 * SocialDisposition.ramp(contact.closeness(), 0, 20))
                * (1 - SocialDisposition.ramp(contact.fear(), 15, 60));
    }

    public static boolean accepts(YukkuriEntity animal, SocialDisposition.Contact contact) {
        var id = animal.getUUID();
        double preference = ((id.getMostSignificantBits() ^ id.getLeastSignificantBits()) >>> 11) * 0x1.0p-53;
        return preference < chance(contact);
    }

    /** Caller has checked the source and debits exactly one item after this server-side commit. */
    public static boolean equip(YukkuriEntity animal, Entity giver, SocialDisposition.Contact contact) {
        if (!animal.life().custody().equipJunk()) return false;
        Sanction.covered(animal);
        var response = contact.response(true);
        if (response == SocialDisposition.Response.GRATEFUL) animal.showReaction(YukkuriAction.BOW, "closed_smile", "request_thanks");
        else animal.showReaction(contact.esteem() <= 22 ? "happy" : "teary", "", 60);
        GameplayLog.event(animal, "headwear", "junk equipped variant={} source={} player={} esteem={} response={} item={}",
                animal.life().custody().junk().id(), giver == null ? "ground" : "offer",
                giver == null ? null : giver.getUUID(), contact.esteem(), response.id(), ModItems.JUNK_HEADWEAR.get());
        return true;
    }

    public static void refused(YukkuriEntity animal, Entity giver, SocialDisposition.Contact contact) {
        var response = contact.response(false);
        if (response == SocialDisposition.Response.GUARDED) animal.showReaction(YukkuriAction.TREMBLE, "teary", "");
        else animal.showReaction(contact.esteem() >= 44 ? "cheek_puff" : "look_down", contact.esteem() >= 44 ? "puff" : "", 60);
        GameplayLog.limited(animal, "headwear", "junk_refusal", 40,
                "junk refused player={} esteem={} chance={} response={}", giver.getUUID(), contact.esteem(), chance(contact), response.id());
    }

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
