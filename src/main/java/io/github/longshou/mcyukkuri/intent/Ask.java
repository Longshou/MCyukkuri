package io.github.longshou.mcyukkuri.intent;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * One request for help inside one incident (spec 11.3): {@code requester} wants {@code helper} to run the intent the factory
 * builds for the helper's own body. {@link AskLedger} draws the one chance that decides it for the pair
 * {@code (incidentId, helper)}; the helper's domain goal runs the intent when it accepted, the requester waits with a bounded
 * wait in its own intent.
 */
public record Ask(UUID requester, UUID helper, Function<YukkuriEntity, Intent> intentFactory, String incidentId) {
    public Ask {
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(helper, "helper");
        Objects.requireNonNull(intentFactory, "intentFactory");
        if (incidentId == null || incidentId.isBlank()) throw new IllegalArgumentException("An ask needs an incident id");
    }

    /** The intent the helper runs once it accepted, built for its own body. */
    public Intent intent(YukkuriEntity helperBody) { return intentFactory.apply(helperBody); }
}
