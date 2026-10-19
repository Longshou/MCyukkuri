package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/** Delegated life state; persistence stays in the original YukkuriLife compound. */
final class LifeAccessory {
    private final YukkuriLife o;
    LifeAccessory(YukkuriLife o) { this.o = o; }

    private boolean accessory = true;
    private UUID accessoryToken;
    /**
     * An index to the one physical hat while it is a boat. The owner's token remains the capability;
     * this UUID is only used to find that holder again and never authorises a return by itself.
     */
    private UUID custodyBoat;
    /** A positive loss result. It prevents old boat hints from silently turning loss back into custody. */
    private boolean custodyEnded;
    /** A pre-index save named a boat in SubspeciesLife; keep that unresolved custody without inventing one for every off-hat. */
    private boolean provisionalCustody;
    /** Project balance: an observed, empty, free-floating hat that remains 96 blocks away for one minute is no longer custody. */
    private int driftingAwayTicks;
    boolean hasAccessory() { return accessory; }
    boolean custodied() { return !accessory && accessoryToken != null && !custodyEnded && (custodyBoat != null || provisionalCustody); }
    UUID custodyBoat() { return custodyBoat; }
    boolean custodyEnded() { return custodyEnded; }
    String custodyDetail() {
        String state = custodyEnded ? "lost" : custodyBoat != null ? "boat" : provisionalCustody ? "provisional" : "none";
        return "custody=" + state + " drifting=" + driftingAwayTicks;
    }

    void removeAccessory() { accessory = false; accessoryToken = custodyBoat = null; custodyEnded = provisionalCustody = false; driftingAwayTicks = 0; clearConfiscation(); o.entity().subspecies().boatId(null); }

    UUID takeAccessory(UUID player) {
        return takeAccessory(player,UUID.randomUUID());
    }

    UUID stowAccessory() {
        return stowAccessory(null, null);
    }

    UUID stowAccessory(UUID retainedToken) {
        return stowAccessory(retainedToken, null);
    }

    UUID stowAccessory(UUID retainedToken, UUID boat) {
        if (!accessory) return null;
        accessory = false; accessoryToken = retainedToken == null ? UUID.randomUUID() : retainedToken;
        custodyBoat = boat; custodyEnded = provisionalCustody = false; driftingAwayTicks = 0; return accessoryToken;
    }

    UUID takeAccessory(UUID player,UUID token) {
        if (!accessory) return null;
        o.entity().aquaticLife().firstVoyage().dropStoredCargo();
        accessory = false; accessoryToken = token; custodyBoat = null; custodyEnded = true; provisionalCustody = false; driftingAwayTicks = 0; clearConfiscation(); o.entity().subspecies().boatId(null); o.personality().accessoryTaken(player);
        o.entity().mind().felt(EmotionEvent.ACCESSORY_LOST); o.entity().showReaction("surprise", "lost_accessory", 100); return accessoryToken;
    }

    boolean returnAccessory(UUID owner, UUID token, UUID player) {
        if(!matchesAccessory(owner,token))return false;
        accessory = true; accessoryToken = custodyBoat = null; custodyEnded = provisionalCustody = false; driftingAwayTicks = 0; clearConfiscation(); o.entity().subspecies().boatId(null); o.personality().accessoryReturned(player);
        o.entity().settlement().society().restored();
        o.entity().mind().felt(EmotionEvent.ACCESSORY_RETURNED); o.entity().showReaction("happy", "returned_accessory", 100); return true;
    }

    void wearAccessory() {
        if (accessory) return;
        accessory = true; accessoryToken = custodyBoat = null; custodyEnded = provisionalCustody = false; driftingAwayTicks = 0; clearConfiscation(); o.entity().subspecies().boatId(null);
        o.entity().settlement().society().restored();
    }

    boolean matchesAccessory(UUID owner,UUID token){return !accessory&&o.entity().getUUID().equals(owner)&&accessoryToken!=null&&accessoryToken.equals(token);}
    boolean matchesCustody(UUID boat, UUID owner, UUID token) {
        return !custodyEnded && matchesAccessory(owner, token) && (custodyBoat == null || custodyBoat.equals(boat));
    }
    /** Adopt a strict owner+token match from an older save that predates the boat index. */
    void confirmCustody(UUID boat, UUID owner, UUID token) {
        if (boat != null && matchesCustody(boat, owner, token)) { custodyBoat = boat; provisionalCustody = false; }
    }
    /** Only a verified physical return to its own boat can reopen a custody already declared lost. */
    void restoreCustody(UUID boat, UUID owner, UUID token) {
        if (boat != null && matchesAccessory(owner, token)) { custodyBoat = boat; custodyEnded = provisionalCustody = false; driftingAwayTicks = 0; }
    }
    /** A player taking the actual boat is the same existing lost-accessory reaction as taking the hat itself. */
    boolean relinquishCustody(UUID boat, UUID owner, UUID token, UUID player) {
        // The unique physical hat remains recoverable after an earlier drift loss, without charging that loss twice.
        if (custodyEnded) return matchesAccessory(owner, token);
        if (!matchesCustody(boat, owner, token)) return false;
        // Keep the physical hull index as a search hint; ended custody grants no ownership or protection.
        custodyBoat = boat; custodyEnded = true; provisionalCustody = false; driftingAwayTicks = 0; o.entity().subspecies().boatId(null); o.personality().accessoryTaken(player);
        o.entity().mind().felt(EmotionEvent.ACCESSORY_LOST); o.entity().showReaction("surprise", "lost_accessory", 100); return true;
    }
    /** Only positive evidence ends custody: a recorded confiscation or a currently visible boat drifting away. */
    void tick() {
        if (accessory || accessoryToken == null || !(o.entity().level() instanceof ServerLevel level)) return;
        var notice = YukkuriWorldData.get(level).consumeAccessoryLoss(o.entity().getUUID(), accessoryToken);
        if (notice != null) {
            if (!notice.destroyedBy().isEmpty()) o.entity().headwear().deployedDestroyed(o.entity().getUUID(), accessoryToken, notice.destroyedBy());
            else relinquishCustody(custodyBoat, o.entity().getUUID(), accessoryToken, notice.player());
            return;
        }
        // Legacy off-hats have no boat index. Keep the compatibility probe bounded; ordinary stolen hats need no per-tick scan.
        if (custodyEnded || custodyBoat == null && o.entity().tickCount % 20 != 0) return;
        var boat = HatBoatEntity.owned(o.entity());
        if (boat == null || !boat.isAfloat() || boat.isVehicle() || o.entity().getVehicle() == boat
                || o.entity().distanceToSqr(boat) <= 96 * 96) { driftingAwayTicks = 0; return; }
        if (++driftingAwayTicks >= 1200)
            relinquishCustody(boat.getUUID(), o.entity().getUUID(), accessoryToken, null);
    }
    private void clearConfiscation() {
        if (o.entity().level() instanceof ServerLevel level) YukkuriWorldData.get(level).clearAccessoryConfiscation(o.entity().getUUID());
    }
    void save(CompoundTag tag) {
        tag.putBoolean("Accessory", accessory); if (accessoryToken != null) tag.putUUID("AccessoryToken", accessoryToken);
        if (custodyBoat != null) tag.putUUID("AccessoryCustodyBoat", custodyBoat);
        if (custodyEnded) tag.putBoolean("AccessoryCustodyEnded", true);
        if (provisionalCustody) tag.putBoolean("AccessoryCustodyProvisional", true);
        if (driftingAwayTicks > 0) tag.putInt("AccessoryDriftingAwayTicks", driftingAwayTicks);
    }
    void load(CompoundTag tag) {
        accessory=!tag.contains("Accessory")||tag.getBoolean("Accessory");
        accessoryToken=tag.hasUUID("AccessoryToken")?tag.getUUID("AccessoryToken"):null;
        custodyBoat=tag.hasUUID("AccessoryCustodyBoat")?tag.getUUID("AccessoryCustodyBoat"):null;
        custodyEnded=tag.getBoolean("AccessoryCustodyEnded");
        provisionalCustody=tag.getBoolean("AccessoryCustodyProvisional") || !custodyEnded && !accessory && accessoryToken != null
                && custodyBoat == null && o.entity().subspecies().boatId() != null;
        driftingAwayTicks=Math.clamp(tag.getInt("AccessoryDriftingAwayTicks"),0,1200);
    }
}
