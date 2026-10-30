package io.github.longshou.mcyukkuri.intent;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** One request for help in one incident: {@code requester} wants {@code helper} to run the intent built for its body; {@link AskLedger} draws the one chance that decides the pair. */
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
