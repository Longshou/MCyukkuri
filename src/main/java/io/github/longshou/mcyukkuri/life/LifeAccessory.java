package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;

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
        accessory = false; accessoryToken = retainedToken == null ? accessoryToken == null ? UUID.randomUUID() : accessoryToken : retainedToken;
        custodyBoat = boat; custodyEnded = provisionalCustody = false; driftingAwayTicks = 0;
        GameplayLog.event(o.entity(), "headwear", "stowed token={} boat={}", accessoryToken, boat);
        return accessoryToken;
    }

    UUID takeAccessory(UUID player,UUID token) {
        if (!accessory && !o.entity().headwear().reassembling()) {
            GameplayLog.limited(o.entity(), "headwear", "take.no_accessory", 20,
                    "take refused reason=no_removable_accessory state={} player={}", o.entity().headwear().stateId(), player);
            return null;
        }
        o.entity().headwear().detachReassembly();
        o.entity().aquaticLife().firstVoyage().dropStoredCargo();
        accessory = false; accessoryToken = custodyBoat = null; custodyEnded = true; provisionalCustody = false; driftingAwayTicks = 0; clearConfiscation(); o.entity().subspecies().boatId(null); o.personality().accessoryTaken(player);
        GameplayLog.event(o.entity(), "headwear", "taken player={} token={}", player, token);
        o.entity().mind().felt(EmotionEvent.ACCESSORY_LOST); o.entity().showReaction("surprise", "lost_accessory", 100); return token;
    }

    boolean returnAccessory(UUID owner, UUID token, UUID player) {
        if(!matchesAccessory(owner,token)){
            GameplayLog.limited(o.entity(), "headwear", "return.mismatch", 20,
                    "return refused reason=owner_or_token_mismatch owner={} token={} player={}", owner, token, player);
            return false;
        }
        accessory = true; accessoryToken = token; custodyBoat = null; custodyEnded = provisionalCustody = false; driftingAwayTicks = 0; clearConfiscation(); o.entity().subspecies().boatId(null); o.personality().accessoryReturned(player);
        o.entity().settlement().society().restored();
        GameplayLog.event(o.entity(), "headwear", "returned player={} token={}", player, token);
        o.entity().mind().felt(EmotionEvent.ACCESSORY_RETURNED); o.entity().showReaction("happy", "returned_accessory", 100); return true;
    }
    /** Transfer a compatible loose physical accessory; boat tokens never participate in this path. */
    boolean receiveAccessory(ItemStack stack,UUID player) {
        if (accessoryReceiptRefusalReason() != null || !(o.entity().level() instanceof ServerLevel)) return false;
        if (!o.entity().headwear().releaseHeldShardsForWholeAccessory()) return false;
        accessory=true;accessoryToken=custodyBoat=null;custodyEnded=provisionalCustody=false;driftingAwayTicks=0;
        clearConfiscation();o.entity().subspecies().boatId(null);o.personality().accessoryReturned(player);
        o.entity().headwear().receiveAccessoryState(stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag());
        if (accessory) o.entity().settlement().society().restored();
        GameplayLog.event(o.entity(),"headwear","received loose accessory item={} state={} damage={} player={}",
                stack.getItem(),o.entity().headwear().stateId(),o.entity().headwear().damage(),player);
        o.entity().mind().felt(EmotionEvent.ACCESSORY_RETURNED);o.entity().showReaction("happy","returned_accessory",100);
        return true;
    }

    /** Why a different intact accessory cannot be put on this entity at the moment. */
    String accessoryReceiptRefusalReason() {
        if (accessory) return "already_wearing";
        if (custodied()) return "still_custodied";
        return null;
    }

    /** Drop only the old capability after another individual accepted its whole accessory. */
    boolean retireTransferredAccessory(UUID token,UUID recipient) {
        if(accessory||accessoryToken==null||!accessoryToken.equals(token))return false;
        accessoryToken=custodyBoat=null;custodyEnded=provisionalCustody=false;driftingAwayTicks=0;
        clearConfiscation();o.entity().subspecies().boatId(null);
        GameplayLog.event(o.entity(),"headwear","transferred recipient={}",recipient);
        return true;
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
        if (boat != null && matchesAccessory(owner, token)) {
            boolean changed = custodyEnded || !boat.equals(custodyBoat);
            custodyBoat = boat; custodyEnded = provisionalCustody = false; driftingAwayTicks = 0;
            if (changed) GameplayLog.event(o.entity(), "headwear", "boat custody restored boat={} token={}", boat, token);
        }
    }
    /** A confirmed drift, destruction or legacy custody transfer uses the ordinary lost-accessory reaction. */
    boolean relinquishCustody(UUID boat, UUID owner, UUID token, UUID player) {
        // The unique physical hat remains recoverable after an earlier drift loss, without charging that loss twice.
        if (custodyEnded) return matchesAccessory(owner, token);
        if (!matchesCustody(boat, owner, token)) return false;
        // Keep the physical hull index as a search hint; ended custody grants no ownership or protection.
        custodyBoat = boat; custodyEnded = true; provisionalCustody = false; driftingAwayTicks = 0; o.entity().subspecies().boatId(null); o.personality().accessoryTaken(player);
        GameplayLog.event(o.entity(), "headwear", "boat custody ended boat={} token={} player={}", boat, token, player);
        o.entity().mind().felt(EmotionEvent.ACCESSORY_LOST); o.entity().showReaction("surprise", "lost_accessory", 100); return true;
    }
    /** Only positive evidence ends custody: a recorded confiscation or a currently visible boat drifting away. */
    void tick() {
        if (accessory || accessoryToken == null || !(o.entity().level() instanceof ServerLevel level)) return;
        var recipient=YukkuriWorldData.get(level).consumeAccessoryTransfer(o.entity().getUUID(),accessoryToken);
        if(recipient!=null){
            retireTransferredAccessory(accessoryToken,recipient);
            return;
        }
        var notice = YukkuriWorldData.get(level).consumeAccessoryLoss(o.entity().getUUID(), accessoryToken);
        if (notice != null) {
            if (!notice.destroyedBy().isEmpty()) o.entity().headwear().deployedDestroyed(o.entity().getUUID(), accessoryToken, notice.destroyedBy());
            else {
                UUID detachedToken=accessoryToken;
                if(relinquishCustody(custodyBoat, o.entity().getUUID(), detachedToken, notice.player()))
                    retireTransferredAccessory(detachedToken,notice.player());
            }
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
