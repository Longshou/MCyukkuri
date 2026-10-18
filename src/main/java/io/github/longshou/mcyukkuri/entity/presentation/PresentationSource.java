package io.github.longshou.mcyukkuri.entity.presentation;

import javax.annotation.Nullable;

/**
 * Queried in catalogue order; null means no takeover. Do not pre-evaluate or query sources for debug:
 * even a declining source can have effects (CareApproach.waiting cancels an invalid incoming lease).
 */
@FunctionalInterface
public interface PresentationSource {
    @Nullable Presentation present(PresentationAccess state, Motion motion);
}
