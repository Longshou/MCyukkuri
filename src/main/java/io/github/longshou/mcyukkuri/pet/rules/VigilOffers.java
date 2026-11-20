package io.github.longshou.mcyukkuri.pet.rules;

import java.util.HashMap;
import java.util.Map;

/** Answers to shelter offers made during one vigil, keyed by the place the guest was shown. */
public final class VigilOffers {
    public record Site(String dimension, long position) {}
    public record Evidence(double need, double bond) {}

    private final Map<Site, Evidence> answered = new HashMap<>();

    public boolean fresh(Site site, Evidence offer) {
        var previous = answered.get(site);
        // One need band or three earned closeness points can reopen the choice.
        return previous == null || offer.need() >= previous.need() + .15 || offer.bond() >= previous.bond() + .075;
    }

    public void answer(Site site, Evidence offer) { answered.put(site, offer); }
    public Map<Site, Evidence> snapshot() { return Map.copyOf(answered); }
    public boolean isEmpty() { return answered.isEmpty(); }
    public void clear() { answered.clear(); }
}
