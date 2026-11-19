package io.github.longshou.mcyukkuri.badge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Cases are indexed under both biological parents. Only parents registered at conception have an owner on file. */
public final class BadgeConduct {
    public record Parent(UUID id, UUID owner) {}
    public record Verdict(String cause, long day, UUID judge) {}
    public static final class Case {
        public final UUID brood;
        public final List<Parent> parents;
        private Verdict verdict;

        public Case(UUID brood, Parent mother, Parent father, Verdict verdict) {
            this.brood = brood;
            parents = List.of(mother, father);
            this.verdict = verdict;
        }
        public Verdict verdict() { return verdict; }
        public boolean discover(Verdict proof) {
            if (verdict != null) return false;
            verdict = proof;
            return true;
        }
        public boolean judged(UUID pet) {
            return verdict != null && parents.stream().anyMatch(p -> p.id.equals(pet) && p.owner != null);
        }
    }

    private final Map<UUID, Case> broods = new HashMap<>();
    private final Map<UUID, List<Case>> byParent = new HashMap<>();

    public boolean open(UUID brood, Parent mother, long motherPermit, Parent father, long fatherPermit, long now) {
        if ((mother.owner == null || motherPermit >= now) && (father.owner == null || fatherPermit >= now)) return false;
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
