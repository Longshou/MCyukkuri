package io.github.longshou.mcyukkuri.entity.presentation;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;

/** Narrow bridge to the entity's existing transient controls and synchronized presentation setters. */
public interface PresentationAccess {
    YukkuriEntity owner();
    YukkuriAction requestedAction();
    void clearRequestedAction();
    int panicTicks();
    void decrementPanic();
    void stopNavigation();
    int reactionTicks();
    String reactionEvent();
    String reactionExpression();
    void behavior(String behavior);
    void animation(YukkuriAction action);
    void animationId(String animation);
    void animationStart(long tick);
    void expression(String expression);
}
