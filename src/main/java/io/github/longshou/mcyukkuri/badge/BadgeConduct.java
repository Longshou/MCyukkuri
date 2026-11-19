package io.github.longshou.mcyukkuri.badge;

import io.github.longshou.mcyukkuri.pet.rules.ArcStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Cases are indexed under both biological parents. Only parents registered at conception have an owner on file. */
public final class BadgeConduct {
    public record Parent(UUID id, UUID owner, ArcStyle style) {}
    public record Verdict(String cause, long day, UUID judge) {}
    public record Evidence(Verdict verdict, UUID parent, UUID subject, String clue) {}
    public static final class Case {
        public final UUID brood;
        public final List<Parent> parents;
        private Verdict verdict;
        private final Map<UUID, Long> exchanges = new HashMap<>();
        private Evidence heldSight;

        public Case(UUID brood, Parent mother, Parent father, Verdict verdict) {
            this.brood = brood;
            parents = List.of(mother, father);
            this.verdict = verdict;
        }
        public Verdict verdict() { return verdict; }
        public boolean discover(Verdict proof) {
            if (verdict != null) return false;
            verdict = proof;
            exchanges.clear();
            heldSight = null;
            return true;
        }

        /** Transient exchange windows from the parents' running approaches and actual speech. */
        public void exchange(UUID parent, long until) { exchanges.put(parent, until); }

        public Evidence sight(Evidence proof, long now) {
            if (verdict != null) return null;
            // Keep the first completed observation, including its witness, across both parents' exchanges.
            if (heldSight == null) heldSight = proof;
            return expireExchanges(now);
        }

        public Evidence spoken(Evidence proof, long now) {
            var until = exchanges.get(proof.parent());
            if (until == null || now >= until) return expireExchanges(now);
            return discover(proof.verdict()) ? proof : null;
        }

        public Evidence abandonExchange(UUID parent, long now) {
            exchanges.remove(parent);
            return expireExchanges(now);
        }

        public Evidence expireExchanges(long now) {
            exchanges.values().removeIf(until -> now >= until);
            if (!exchanges.isEmpty() || heldSight == null) return null;
            var proof = heldSight;
            return discover(proof.verdict()) ? proof : null;
        }
        public boolean judged(UUID pet) {
            return verdict != null && parents.stream().anyMatch(p -> p.id.equals(pet) && p.owner != null);
        }
    }

    private final Map<UUID, Case> broods = new HashMap<>();
    private final Map<UUID, List<Case>> byParent = new HashMap<>();
    private final Map<UUID, Long> elopements = new HashMap<>();

    /** Arrival consumes the old custody once. A pet without a badge still leaves. */
    public boolean eloped(UUID pet, boolean stillOwned, boolean registered, long day, Runnable release) {
        if (!stillOwned) return false;
        release.run();
        if (!registered || disqualified(pet)) return false;
        elopements.put(pet, day);
        return true;
    }

    public Map<UUID, Long> elopements() { return Collections.unmodifiableMap(elopements); }
    public void loadElopement(UUID pet, long day) { elopements.put(pet, day); }
    public void forgetElopements(Collection<UUID> pets) { elopements.keySet().removeAll(pets); }

    public boolean open(UUID brood, Parent mother, long motherPermit, Parent father, long fatherPermit, long now, boolean brokeHouseRule) {
        if (!brokeHouseRule && (mother.owner == null || motherPermit >= now) && (father.owner == null || fatherPermit >= now)) return false;
        add(new Case(brood, mother, father, null));
        return true;
    }
    public void add(Case entry) {
        broods.put(entry.brood, entry);
        for (Parent p : entry.parents) byParent.computeIfAbsent(p.id, id -> new ArrayList<>()).add(entry);
    }
    public void remove(Case entry) {
        broods.remove(entry.brood);
        for (Parent p : entry.parents) {
            var cases = byParent.get(p.id);
            cases.remove(entry);
            if (cases.isEmpty()) byParent.remove(p.id);
        }
    }
    public Collection<Case> cases() { return broods.values(); }
    public List<Case> cases(UUID parent) { return byParent.getOrDefault(parent, List.of()); }
    public boolean disqualified(UUID pet) {
        if (elopements.containsKey(pet)) return true;
        for (Case entry : cases(pet)) if (entry.judged(pet)) return true;
        return false;
    }
    public int standing(Map<UUID, Integer> contributions) {
        int best = 0;
        for (var pet : contributions.entrySet())
            if (!disqualified(pet.getKey())) best = Math.max(best, pet.getValue());
        return best;
    }
}
