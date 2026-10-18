package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/** Delegated life state; persistence stays in the original YukkuriLife compound. */
final class LifeAccessory {
    private final YukkuriLife o;
    LifeAccessory(YukkuriLife o) { this.o = o; }

    private boolean accessory = true;
    private UUID accessoryToken;
    boolean hasAccessory() { return accessory; }

    void removeAccessory() { accessory = false; accessoryToken = null; }

    UUID takeAccessory(UUID player) {
        return takeAccessory(player,UUID.randomUUID());
    }

    UUID stowAccessory() {
        return stowAccessory(null);
    }

    UUID stowAccessory(UUID retainedToken) {
        if (!accessory) return null;
        accessory = false; accessoryToken = retainedToken == null ? UUID.randomUUID() : retainedToken; return accessoryToken;
    }

    UUID takeAccessory(UUID player,UUID token) {
        if (!accessory) return null;
        o.entity().aquaticLife().firstVoyage().dropStoredCargo();
        accessory = false; accessoryToken = token; o.personality().accessoryTaken(player);
        o.entity().mind().felt(EmotionEvent.ACCESSORY_LOST); o.entity().showReaction("surprise", "lost_accessory", 100); return accessoryToken;
    }

    boolean returnAccessory(UUID owner, UUID token, UUID player) {
        if(!matchesAccessory(owner,token))return false;
        accessory = true; accessoryToken = null; o.personality().accessoryReturned(player);
        o.entity().settlement().society().restored();
        o.entity().mind().felt(EmotionEvent.ACCESSORY_RETURNED); o.entity().showReaction("happy", "returned_accessory", 100); return true;
    }

    void wearAccessory() {
        if (accessory) return;
        accessory = true; accessoryToken = null;
        o.entity().settlement().society().restored();
    }

    boolean matchesAccessory(UUID owner,UUID token){return !accessory&&o.entity().getUUID().equals(owner)&&accessoryToken!=null&&accessoryToken.equals(token);}
    void save(CompoundTag tag) {
        tag.putBoolean("Accessory", accessory); if (accessoryToken != null) tag.putUUID("AccessoryToken", accessoryToken);
    }
    void load(CompoundTag tag) {
        accessory=!tag.contains("Accessory")||tag.getBoolean("Accessory");
        accessoryToken=tag.hasUUID("AccessoryToken")?tag.getUUID("AccessoryToken"):null;
    }
}
