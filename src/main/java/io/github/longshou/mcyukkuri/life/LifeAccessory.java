package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.headwear.HatCustody;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.status.GameplayLog;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;

/**
 * Delegated life state; persistence stays in the original YukkuriLife compound. Where the hat is lives in one {@link HatCustody}
 * record; this class only turns the player- and owner-facing events into its transitions and the owner's reactions.
 */
final class LifeAccessory {
    private final YukkuriLife o;
    private final HatCustody custody;
    LifeAccessory(YukkuriLife o) { this.o = o; custody = new HatCustody(o.entity()); }

    HatCustody custody() { return custody; }
    boolean hasAccessory() { return custody.worn(); }
    boolean custodied() { return custody.custodied(); }

    void removeAccessory(String cause) { custody.lose(cause, false); clearConfiscation(); }

    /** The worn hat becomes the placed hat {@code hat}; returns the custody token it carries. */
    UUID stowAccessory(UUID hat) {
        if (!custody.worn()) return null;
        UUID token = custody.deploy(hat);
        GameplayLog.event(o.entity(), "headwear", "stowed token={} hat={}", token, hat);
        return token;
    }

    UUID takeAccessory(UUID player) {
        return takeAccessory(player,UUID.randomUUID());
    }

    UUID takeAccessory(UUID player,UUID token) {
        if (!custody.worn() && !o.entity().headwear().reassembling()) {
            GameplayLog.limited(o.entity(), "headwear", "take.no_accessory", 20,
                    "take refused reason=no_removable_accessory state={} player={}", o.entity().headwear().stateId(), player);
            return null;
        }
        o.entity().headwear().detachReassembly();
        custody.lose("taken", false); clearConfiscation(); o.personality().accessoryTaken(player);
        GameplayLog.event(o.entity(), "headwear", "taken player={} token={}", player, token);
        o.entity().mind().felt(EmotionEvent.ACCESSORY_LOST); o.entity().showReaction("surprise", "lost_accessory", 100); return token;
    }

    boolean returnAccessory(UUID owner, UUID token, UUID player) {
        if(!matchesAccessory(owner,token)){
            GameplayLog.limited(o.entity(), "headwear", "return.mismatch", 20,
                    "return refused reason=owner_or_token_mismatch owner={} token={} player={}", owner, token, player);
            return false;
        }
        custody.reclaim(token, "returned"); clearConfiscation(); o.personality().accessoryReturned(player);
        o.entity().settlement().society().restored();
        GameplayLog.event(o.entity(), "headwear", "returned player={} token={}", player, token);
        o.entity().mind().felt(EmotionEvent.ACCESSORY_RETURNED); o.entity().showReaction("happy", "returned_accessory", 100); return true;
    }
    /** Transfer a compatible loose physical accessory. */
    boolean receiveAccessory(ItemStack stack,UUID player) {
        if (accessoryReceiptRefusalReason() != null || !(o.entity().level() instanceof ServerLevel)) return false;
        if (!o.entity().headwear().releaseHeldShardsForWholeAccessory()) return false;
        custody.wear("received");
        clearConfiscation();o.personality().accessoryReturned(player);
        o.entity().headwear().receiveAccessoryState(stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag());
        if (custody.worn()) o.entity().settlement().society().restored();
        GameplayLog.event(o.entity(),"headwear","received loose accessory item={} state={} damage={} player={}",
                stack.getItem(),o.entity().headwear().stateId(),o.entity().headwear().damage(),player);
        o.entity().mind().felt(EmotionEvent.ACCESSORY_RETURNED);o.entity().showReaction("happy","returned_accessory",100);
        return true;
    }

    /** Why a different intact accessory cannot be put on this entity at the moment. */
    String accessoryReceiptRefusalReason() {
        if (custody.worn()) return "already_wearing";
        if (custody.custodied()) return "still_custodied";
        return null;
    }

    /** Drop only the old capability after another individual accepted its whole accessory. */
    boolean retireTransferredAccessory(UUID token,UUID recipient) {
        if(custody.worn()||custody.token()==null||!custody.token().equals(token))return false;
        custody.lose("transferred", false);
        clearConfiscation();
        GameplayLog.event(o.entity(),"headwear","transferred recipient={}",recipient);
        return true;
    }

    void wearAccessory() {
        if (custody.worn()) return;
        custody.wear("worn");
        clearConfiscation();
        o.entity().settlement().society().restored();
    }

    boolean matchesAccessory(UUID owner,UUID token){return custody.matches(owner,token);}
    /** A confirmed drift, destruction or confiscation uses the ordinary lost-accessory reaction. */
    boolean relinquishCustody(UUID owner, UUID token, UUID player, String cause) {
        // The unique physical hat remains recoverable after an earlier drift loss, without charging that loss twice.
        if (custody.lost()) return matchesAccessory(owner, token);
        if (!custody.matches(owner, token)) return false;
        custody.lose(cause, true); o.personality().accessoryTaken(player);
        o.entity().mind().felt(EmotionEvent.ACCESSORY_LOST); o.entity().showReaction("surprise", "lost_accessory", 100); return true;
    }
    /** Only positive evidence ends custody: a recorded confiscation. */
    void tick() {
        if (custody.worn() || custody.token() == null || !(o.entity().level() instanceof ServerLevel level)) return;
        var recipient=YukkuriWorldData.get(level).consumeAccessoryTransfer(o.entity().getUUID(),custody.token());
        if(recipient!=null){
            retireTransferredAccessory(custody.token(),recipient);
            return;
        }
        var notice = YukkuriWorldData.get(level).consumeAccessoryLoss(o.entity().getUUID(), custody.token());
        if (notice != null) {
            if (!notice.destroyedBy().isEmpty()) o.entity().headwear().deployedDestroyed(o.entity().getUUID(), custody.token(), notice.destroyedBy());
            else {
                UUID detachedToken=custody.token();
                if(relinquishCustody(o.entity().getUUID(), detachedToken, notice.player(), "confiscated"))
                    retireTransferredAccessory(detachedToken,notice.player());
            }
            return;
        }
    }
    private void clearConfiscation() {
        if (o.entity().level() instanceof ServerLevel level) YukkuriWorldData.get(level).clearAccessoryConfiscation(o.entity().getUUID());
    }
    void save(CompoundTag tag) {
        tag.putBoolean("Accessory", custody.worn());
        custody.save(tag);
    }
    void load(CompoundTag tag) {
        custody.load(tag, !tag.contains("Accessory") || tag.getBoolean("Accessory"));
    }
}
