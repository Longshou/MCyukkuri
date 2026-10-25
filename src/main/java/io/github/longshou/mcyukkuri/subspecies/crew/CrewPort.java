package io.github.longshou.mcyukkuri.subspecies.crew;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.entity.Entity;

/**
 * What one body or carrier knows of the transfer it takes part in. The ticket itself ({@link CrewTransfer}) is the only owner of
 * a transfer's state; the port is the entity's handle on it, plus the three small ledgers the caller-facing refusal classes need
 * (the last outcome, anchor pairs known to be impossible, and the slips of the current event). Nothing here is saved.
 */
public final class CrewPort {
    /** How a transfer ended, for the decision layer that asked for it. */
    public record Outcome(boolean success, CrewTransfer.Refusal refusal, String reason, CrewTransfer.Mode mode, long tick) {}

    public static final int MAX_SLIPS = 3, GEOMETRY_MEMORY = 600, SLIP_MEMORY = 1200;

    private final YukkuriEntity entity;
    private CrewTransfer ticket;
    private Outcome last;
    private final Map<String, Long> impossible = new HashMap<>();
    private WaterIncidents.Incident incident;
    private int slips;
    private long slipAt = Long.MIN_VALUE;

    public CrewPort(YukkuriEntity entity) { this.entity = entity; }

    // ---------------------------------------------------------------- the current ticket
    public CrewTransfer ticket() { return ticket; }
    void take(CrewTransfer next) { ticket = next; }
    void drop(CrewTransfer finished, Outcome outcome) {
        if (ticket == finished) ticket = null;
        last = outcome;
    }
    public boolean active() { return ticket != null && !ticket.finished(); }
    public boolean asBody() { return active() && ticket.bodyId().equals(entity.getUUID()); }
    public boolean asCarrier() { return active() && entity.getUUID().equals(ticket.carrierId()); }
    /** Replaces the old {@code braidRescueActive()}: this entity is part of a transfer, whichever side. */
    public boolean busy() { return active(); }
    /** The ticket has reached the clip stage (contact or later): the transfer's own clip is what this entity shows. */
    public boolean presenting() { return active() && ticket.phase().ordinal() >= CrewTransfer.Phase.CONTACT.ordinal(); }
    /** The body is out of its own control: from the contact until the carrier lets go. Normal travel and buoyancy stand aside. */
    public boolean holdsBody() { return asBody() && ticket.holdsBody(); }
    /** The carrier has the body in braid or mouth. */
    public boolean carrying() { return asCarrier() && ticket.holdsBody(); }
    /** A climb of the hull's rim is under way (the old self-climb). */
    public boolean climbing() { return asBody() && ticket.mode() == CrewTransfer.Mode.CLIMB; }
    /** The teeth are on the rim. */
    public boolean gripping() { return climbing() && ticket.holdsBody(); }
    /** Body in a HOP or climb that left the ground: it has no footing of its own. */
    public boolean flying() { return asBody() && ticket.mode() != CrewTransfer.Mode.LIFT && ticket.phase() == CrewTransfer.Phase.MOVE; }
    /** The entity's own ticket passes through this hull (the hull must not collide with it). */
    public boolean passesThrough(HatBoatEntity hat) {
        return active() && ticket.touches(hat) && (ticket.phase().ordinal() >= CrewTransfer.Phase.CONTACT.ordinal() || ticket.mode() == CrewTransfer.Mode.HOP);
    }
    public UUID partner() {
        if (!active()) return null;
        return asBody() ? ticket.carrierId() : ticket.bodyId();
    }
    public Outcome last() { return last; }
    /** The water incident this body is in, with every rescue decision of it (see {@link WaterIncidents}), or null. */
    public WaterIncidents.Incident incident() { return incident; }
    void incident(WaterIncidents.Incident next) { incident = next; }
    public String describe() {
        return "ticket=" + (active() ? ticket.mode() + "/" + ticket.phase() : "none") + " last="
                + (last == null ? "none" : (last.success() ? "ok" : last.refusal() + ":" + last.reason()) + "@" + last.tick());
    }
    /** The hull whose CrewState carries this entity's transfer (entity id), or -1. */
    public int boatId() { return active() ? ticket.anchorBoat() : -1; }
    /** End this entity's transfer, whichever side it is on; the ticket decides what becomes of the bodies. */
    public void cancel(String reason) { if (ticket != null) ticket.cancel(reason); }

    // ---------------------------------------------------------------- caller-facing ledgers
    boolean knownImpossible(String key, long now) {
        var until = impossible.get(key);
        if (until == null) return false;
        if (until <= now) { impossible.remove(key); return false; }
        return true;
    }
    void rememberImpossible(String key, long now) {
        if (impossible.size() > 16) impossible.values().removeIf(until -> until <= now);
        impossible.put(key, now + GEOMETRY_MEMORY);
    }
    /** Count a slip of the current event; false once the budget is spent. */
    boolean slip(long now) {
        if (now - slipAt > SLIP_MEMORY) slips = 0;
        slipAt = now;
        return ++slips <= MAX_SLIPS;
    }
    public int slips(long now) { return now - slipAt > SLIP_MEMORY ? 0 : slips; }
    /** The event is over (safe aboard or ashore): the budget starts afresh. */
    void settled() { slips = 0; impossible.clear(); }

    Entity entity() { return entity; }
}
