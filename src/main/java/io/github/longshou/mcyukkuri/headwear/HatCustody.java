package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/**
 * Where one individual's hat is: the single record that replaces the four places (accessory custody index, subspecies boat id,
 * first-voyage retained token, the boat's own token) that used to be kept in step by hand. Every read is a pure function of this
 * record; every change is one of the named transitions, each logged once as {@code [headwear] custody A -> B}.
 *
 * <pre>
 *  WORN            on the head (the token of its last voyage is kept so the same hat keeps its identity)
 *  DEPLOYED        the hat is a hull the owner sails or has moored; boat + token
 *  HELD_BY_PARENT  on the head again but carrying the token and provisions of its last voyage (a child's hat taken in by the
 *                  parent's hull, any captain's hat stowed ashore); they go back into the hull when it is set down again
 *  ADRIFT          the owner is not aboard her own afloat hull (thrown out, or not yet back); boat + since
 *  LOST            no longer in her custody; cause says why. A hat lost by drifting keeps its token and hull as a way back in:
 *                  only a real boarding reopens it.
 * </pre>
 */
public final class HatCustody {
    public enum State { WORN, DEPLOYED, HELD_BY_PARENT, ADRIFT, LOST }

    private final YukkuriEntity owner;
    private State state = State.WORN;
    private UUID token, boat;
    private long since;
    private String cause = "";
    private CompoundTag cargo;
    /** Project balance: an observed, empty, free-floating hat that stays 96 blocks away for one minute is no longer custody. */
    private int awayTicks;

    public HatCustody(YukkuriEntity owner) { this.owner = owner; }

    // ---------------------------------------------------------------- pure reads
    public State state() { return state; }
    /** The hat is on the head (possibly with the provisions of its last voyage). */
    public boolean worn() { return state == State.WORN || state == State.HELD_BY_PARENT; }
    /** A physical hull is the hat: deployed or adrift. */
    public boolean custodied() { return state == State.DEPLOYED || state == State.ADRIFT; }
    public boolean adrift() { return state == State.ADRIFT; }
    public boolean lost() { return state == State.LOST; }
    public UUID token() { return token; }
    /** The hull while it is the hat, else null. */
    public UUID boat() { return custodied() ? boat : null; }
    /** The hull last known, also after the custody ended: where to look, never a grant of ownership. */
    public UUID boatHint() { return boat; }
    public long since() { return since; }
    public String cause() { return cause; }
    /** This owner and token name the hat that is off the head. */
    public boolean matches(UUID ownerId, UUID tokenId) { return !worn() && owner.getUUID().equals(ownerId) && token != null && token.equals(tokenId); }
    /** As {@link #matches} for a custody that has not ended and, when a hull is recorded, is that hull. */
    public boolean matchesBoat(UUID boatId, UUID ownerId, UUID tokenId) {
        return state != State.LOST && matches(ownerId, tokenId) && (boat == null || boat.equals(boatId));
    }
    public int retainedFoodCount() {
        if (cargo == null) return 0;
        int count = 0;
        for (var raw : cargo.getList("Food", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            var stack = net.minecraft.world.item.ItemStack.parseOptional(owner.registryAccess(), (CompoundTag) raw);
            count += stack.getCount();
        }
        return count;
    }
    /** The provisions of the last voyage, or null. */
    public CompoundTag retainedCargo() { return cargo; }
    public UUID retainedToken() { return state == State.HELD_BY_PARENT ? token : null; }
    public String detail() { return "custody=" + state.name().toLowerCase(java.util.Locale.ROOT) + (cause.isEmpty() ? "" : "(" + cause + ")") + " drifting=" + awayTicks; }

    // ---------------------------------------------------------------- transitions
    private void go(State next, String why) {
        if (next != state) GameplayLog.event(owner, "headwear", "custody {} -> {} why={} boat={} token={}", state, next, why, boat, token);
        state = next;
        since = owner.level().getGameTime();
    }

    /** The hat leaves the head to be this hull; returns the token it carries (the retained one, the last one, or a new one). */
    public UUID deploy(UUID hull) {
        if (!worn()) return null;
        if (token == null) token = UUID.randomUUID();
        boat = hull;
        cause = "";
        awayTicks = 0;
        go(State.DEPLOYED, "deploy");
        return token;
    }

    /** The hat is back on the head (taken in, landed, or handed back): it keeps the token of its voyage. */
    public void reclaim(UUID tokenId, String why) {
        token = tokenId;
        boat = null;
        cause = "";
        awayTicks = 0;
        go(cargo != null ? State.HELD_BY_PARENT : State.WORN, why);
    }

    /** The hat is worn as a new, plain hat (restored, replaced, handed over by a player). */
    public void wear(String why) {
        token = null;
        boat = null;
        cargo = null;
        cause = "";
        awayTicks = 0;
        go(State.WORN, why);
    }

    /** The provisions of the hat's last voyage ride with it until it is set down again. */
    public void retain(UUID tokenId, CompoundTag provisions) {
        if (!worn()) return;
        token = tokenId;
        cargo = provisions.copy();
        go(State.HELD_BY_PARENT, "retain");
    }

    /** Whether new provisions can be kept under this token: nothing is held, or it is the same hat. */
    public boolean canRetain(UUID tokenId) { return cargo == null || tokenId.equals(token); }

    /** The provisions go back into the hull the hat was just set down as. */
    public CompoundTag releaseCargo() {
        var kept = cargo;
        cargo = null;
        return kept;
    }

    /** The provisions are dropped (death, landing, the hat taken away). */
    public CompoundTag dropCargo() {
        var kept = cargo;
        cargo = null;
        if (state == State.HELD_BY_PARENT) go(State.WORN, "cargo_dropped");
        return kept;
    }

    /** The owner is not aboard her own afloat hull: thrown out, or fallen in. */
    public void adrift(UUID hull, String why) {
        if (state != State.DEPLOYED) return;
        boat = hull;
        go(State.ADRIFT, why);
    }

    /** A real boarding of the hull the token belongs to reopens the custody, also after a loss by drifting. */
    public void board(UUID hull, String why) {
        if (worn() || token == null || state == State.DEPLOYED && hull.equals(boat)) return;
        boat = hull;
        cause = "";
        awayTicks = 0;
        go(State.DEPLOYED, why);
    }

    /** The custody ends. {@code keepToken} leaves a way back (a hat lost by drifting can still be boarded); a taken or destroyed hat has none. */
    public void lose(String why, boolean keepToken) {
        cause = why;
        if (!keepToken) { token = null; boat = null; }
        awayTicks = 0;
        go(State.LOST, why);
    }

    // ---------------------------------------------------------------- drifting away
    /** Observed evidence only: an empty free-floating hull more than 96 blocks away for a minute. */
    public void away(boolean farAndEmpty, int ticks) {
        if (!farAndEmpty) { awayTicks = 0; return; }
        awayTicks += ticks;
        if (awayTicks >= 1200 && custodied()) lose("drifted_away", true);
    }

    // ---------------------------------------------------------------- persistence
    public void save(CompoundTag tag) {
        var t = new CompoundTag();
        t.putString("State", state.name());
        if (token != null) t.putUUID("Token", token);
        if (boat != null) t.putUUID("Boat", boat);
        t.putLong("Since", since);
        if (!cause.isEmpty()) t.putString("Cause", cause);
        if (cargo != null) t.put("Cargo", cargo.copy());
        if (awayTicks > 0) t.putInt("Away", awayTicks);
        tag.put("HatCustody", t);
    }

    public void load(CompoundTag tag, boolean wornFlag) {
        var t = tag.getCompound("HatCustody");
        try { state = State.valueOf(t.getString("State")); } catch (IllegalArgumentException ex) { state = wornFlag ? State.WORN : State.LOST; }
        token = t.hasUUID("Token") ? t.getUUID("Token") : null;
        boat = t.hasUUID("Boat") ? t.getUUID("Boat") : null;
        since = t.getLong("Since");
        cause = t.getString("Cause");
        cargo = t.contains("Cargo") ? t.getCompound("Cargo").copy() : null;
        awayTicks = Math.clamp(t.getInt("Away"), 0, 1200);
        if (wornFlag != worn()) state = wornFlag ? State.WORN : (custodiedFallback() ? State.DEPLOYED : State.LOST);
    }

    private boolean custodiedFallback() { return token != null && boat != null; }
}
