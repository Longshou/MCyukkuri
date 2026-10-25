package io.github.longshou.mcyukkuri.subspecies.crew;

import java.util.UUID;

/**
 * A temporary claim on a seat. {@code expiry} is a game tick ({@link Long#MAX_VALUE} for a retained voyage berth);
 * {@code returnTo} is the seat the body goes back to when a service lease ends ({@link DeckLayout#NONE} otherwise);
 * {@code caregiver} is the captain sheltering a refuge lease.
 */
public record Lease(long id, Purpose purpose, UUID holder, long expiry, int returnTo, UUID caregiver) {
    public boolean lapsed(long now) { return expiry != Long.MAX_VALUE && now >= expiry; }
}
