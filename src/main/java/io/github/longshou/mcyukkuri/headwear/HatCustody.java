package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.random.ModRandom;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.interaction.ItemContact;
import io.github.longshou.mcyukkuri.motor.CarrierSlot;
import io.github.longshou.mcyukkuri.motor.Held;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Where one individual's hat is: the single record of the hat's own token while it is on the head, the real entity it became when
 * set down, and its end. Every read is a pure function of this record; every change is one of the named transitions, each logged
 * once as {@code [headwear] custody A -> B}.
 *
 * <pre>
 *  WORN            on the head; the token of the hat now set down is kept so the same physical hat keeps its identity
 *  DEPLOYED        the hat is the real entity the owner set down; placed entity + token
 *  LOST            no longer in her custody; cause says why.
 * </pre>
 * The worn hat also keeps her kit, the one stick tucked into it (project design: the paddle travels with the hat); set down,
 * the placed hat carries it instead ({@link PlacedHatEntity#kit()}), so the stick has one place at a time. Where it physically is
 * for her ({@link #kitPlace}: her grip, her mouth, or lying in her set-down hat at {@link #kitSpot}) is this record's too: only a
 * visible beat (motor Stow) moves it.
 */
public final class HatCustody {
    public enum State { WORN, DEPLOYED, LOST }

    private final YukkuriEntity owner;
    private State state = State.WORN;
    private UUID token, boat;
    private long since;
    private String cause = "";
    /** Finite claims of the existing physical hat; rebuilt from custody after loading, never a second hat ledger. */
    private UUID reclaimHat, reclaimAssignee;
    private long reclaimLease, reclaimRetry;
    private final CarrierSlot<ItemStack> kit = new CarrierSlot<>();
    private Held.Place kitPlace = Held.Place.GRIP;
    /** Where the kit lies in her set-down hat (hat-local units) and the way its short end points, while {@link #kitPlace} is HAT. */
    private Vec3 kitSpot, kitDir;

    public HatCustody(YukkuriEntity owner) { this.owner = Objects.requireNonNull(owner, "Hat custody requires its entity owner"); }

    // ---------------------------------------------------------------- pure reads
    public State state() { return state; }
    /** The hat is on the head. */
    public boolean worn() { return state == State.WORN; }
    /** The real placed hat is the hat. */
    public boolean custodied() { return state == State.DEPLOYED; }
    public boolean lost() { return state == State.LOST; }
    public UUID token() { return token; }
    /** The placed hat while it is the hat, else null. */
    public UUID boat() { return custodied() ? boat : null; }
    public long since() { return since; }
    public String cause() { return cause; }
    /** The stick the worn hat keeps, or empty. */
    public ItemStack kit() { return kit.contents().orElse(ItemStack.EMPTY); }
    /** Tucks one stick into the worn hat: true when it is kept (worn, nothing kept yet). */
    public boolean stow(ItemStack one) {
        if (!worn() || one.isEmpty() || !kit.acquire(one.copyWithCount(1))) return false;
        GameplayLog.event(owner, "headwear", "kit stowed item={} custody={}", one.getItem(), state);
        return true;
    }
    void deployKit(CarrierSlot<ItemStack> placed) { kit.transferTo(placed); }
    void reclaimKit(CarrierSlot<ItemStack> placed) {
        if (placed.transferTo(kit)) GameplayLog.event(owner, "headwear", "kit stowed item={} custody={}", kit().getItem(), state);
    }
    public boolean releaseKit(Vec3 at) {
        return kit.release(stack -> ItemContact.release(owner, stack, at, Vec3.ZERO) != null);
    }
    /** Where her kit is for her: in her grip, her mouth, or lying in her set-down hat. */
    public Held.Place kitPlace() { return kitPlace; }
    public Vec3 kitSpot() { return kitSpot; }
    public Vec3 kitDir() { return kitDir; }
    /** The one move of her kit, made at a beat's handover (or by a new stick taken into her grip); {@code spot}/{@code dir} for HAT. */
    public void placeKit(Held.Place next, Vec3 spot, Vec3 dir, String cause) {
        if (next == Held.Place.GROUND) throw new IllegalArgumentException("a kit on the ground is no longer her kit");
        if (next == Held.Place.HAT && (spot == null || dir == null)) throw new IllegalArgumentException("a kit in the hat lies somewhere");
        var before = kitPlace;
        kitPlace = next;
        kitSpot = next == Held.Place.HAT ? spot : null;
        kitDir = next == Held.Place.HAT ? dir : null;
        if (before != next) GameplayLog.event(owner, "headwear", "kit place {} -> {} cause={}{}", before, next, cause,
                next == Held.Place.HAT ? " spot=" + GameplayLog.at(spot) : "");
    }
    /** This owner and token name the hat that is off the head. */
    public boolean matches(UUID ownerId, UUID tokenId) { return !worn() && owner.getUUID().equals(ownerId) && token != null && token.equals(tokenId); }
    public String detail() { return "custody=" + state.name().toLowerCase(Locale.ROOT) + (cause.isEmpty() ? "" : "(" + cause + ")")
            + " reclaim=" + reclaimHat + " claimant=" + reclaimAssignee; }

    private void finishReclaim(String result, String why) {
        if (reclaimHat != null) GameplayLog.event(owner, "headwear", "reclaim {} hat={} owner={} claimant={} reason={}",
                result, reclaimHat, owner.getUUID(), reclaimAssignee, why);
        reclaimHat = reclaimAssignee = null; reclaimLease = reclaimRetry = 0;
    }

    // ---------------------------------------------------------------- transitions
    private void go(State next, String why) {
        if (next != state) GameplayLog.event(owner, "headwear", "custody {} -> {} why={} boat={} token={}", state, next, why, boat, token);
        state = next;
        since = owner.level().getGameTime();
    }

    /** The hat leaves the head to be this hull; returns the token it carries (the retained one, the last one, or a new one). */
    public UUID deploy(UUID hull) {
        if (!worn()) return null;
        if (token == null) token = ModRandom.identity(owner.getRandom());
        boat = hull;
        cause = "";
        go(State.DEPLOYED, "deploy");
        return token;
    }

    /** The hat is back on the head (taken in, landed, or handed back): it keeps the token of its voyage. */
    public void reclaim(UUID tokenId, String why) {
        finishReclaim("done", why);
        token = tokenId;
        boat = null;
        cause = "";
        go(State.WORN, why);
    }

    /** The hat is worn as a new, plain hat (restored, replaced, handed over by a player). */
    public void wear(String why) {
        finishReclaim("expired", why);
        token = null;
        boat = null;
        cause = "";
        go(State.WORN, why);
    }

    /** The custody ends. {@code keepToken} leaves a way back through the same physical hat; a taken or destroyed hat has none. */
    public void lose(String why, boolean keepToken) {
        finishReclaim("expired", why);
        cause = why;
        if (!keepToken) { token = null; boat = null; }
        go(State.LOST, why);
    }

    // ---------------------------------------------------------------- persistence
    public void save(CompoundTag tag) {
        var t = new CompoundTag();
        t.putString("State", state.name());
        if (token != null) t.putUUID("Token", token);
        if (boat != null) t.putUUID("Boat", boat);
        t.putLong("Since", since);
        if (!cause.isEmpty()) t.putString("Cause", cause);
        if (!kit.isEmpty()) t.put("Kit", kit().save(owner.registryAccess()));
        if (kitPlace != Held.Place.GRIP) t.putString("KitPlace", kitPlace.name());
        if (kitSpot != null && kitDir != null) {
            t.putDouble("KitX", kitSpot.x); t.putDouble("KitY", kitSpot.y); t.putDouble("KitZ", kitSpot.z);
            t.putDouble("KitDX", kitDir.x); t.putDouble("KitDZ", kitDir.z);
        }
        tag.put("HatCustody", t);
    }

    public void load(CompoundTag tag, boolean wornFlag) {
        var t = tag.getCompound("HatCustody");
        try { state = State.valueOf(t.getString("State")); } catch (IllegalArgumentException ex) { state = wornFlag ? State.WORN : State.LOST; }
        token = t.hasUUID("Token") ? t.getUUID("Token") : null;
        boat = t.hasUUID("Boat") ? t.getUUID("Boat") : null;
        since = t.getLong("Since");
        cause = t.getString("Cause");
        var savedKit = ItemStack.parseOptional(owner.registryAccess(), t.getCompound("Kit"));
        kit.restore(savedKit.isEmpty() ? null : savedKit);
        try { kitPlace = t.contains("KitPlace") ? Held.Place.valueOf(t.getString("KitPlace")) : Held.Place.GRIP; }
        catch (IllegalArgumentException ex) { kitPlace = Held.Place.GRIP; }
        boolean spotted = t.contains("KitX");
        kitSpot = spotted ? new Vec3(t.getDouble("KitX"), t.getDouble("KitY"), t.getDouble("KitZ")) : null;
        kitDir = spotted ? new Vec3(t.getDouble("KitDX"), 0, t.getDouble("KitDZ")) : null;
        if (wornFlag != worn()) state = wornFlag ? State.WORN : (custodiedFallback() ? State.DEPLOYED : State.LOST);
        reclaimHat = reclaimAssignee = null; reclaimLease = reclaimRetry = 0;
    }

    private boolean custodiedFallback() { return token != null && boat != null; }
}
