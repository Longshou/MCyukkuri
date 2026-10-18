package io.github.longshou.mcyukkuri.entity.presentation;

/**
 * A selected, deferred presentation. The arbiter publishes it once, before querying another source.
 * Values are deliberately evaluated at their original write sites: expressions can read the newly
 * written behavior/start, and shell poses must keep the animation already sampled by their controller.
 * An eager tuple followed by a uniform batch of writes would change those semantics.
 */
public record Presentation(Runnable display) {
    public void publish() { display.run(); }
}
