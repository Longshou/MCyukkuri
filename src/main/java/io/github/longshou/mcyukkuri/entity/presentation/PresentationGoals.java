package io.github.longshou.mcyukkuri.entity.presentation;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.RoamingGoal;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.component.ComponentGoal;
import io.github.longshou.mcyukkuri.event.HomeClaimGoal;
import io.github.longshou.mcyukkuri.family.CareApproach;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.human.FactoryWorkGoal;
import io.github.longshou.mcyukkuri.settlement.ForageGoal;
import io.github.longshou.mcyukkuri.settlement.ForageTrip;
import io.github.longshou.mcyukkuri.settlement.Judgement;
import io.github.longshou.mcyukkuri.social.Cannibalism;
import io.github.longshou.mcyukkuri.social.GroupInteractionGoal;
import io.github.longshou.mcyukkuri.social.SelfOffering;
import io.github.longshou.mcyukkuri.social.TreasureLife;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;

/** Presentation bindings to existing Goal identities. Never starts, stops or probes eligibility. */
public final class PresentationGoals {
    public enum Actions { ORDINARY, GROUP, PREDATOR }
    public record Source<G extends Goal>(String id, BiFunction<YukkuriEntity, G, String> activity, Actions actions) {}
    /** Typed activity mapping for a component-owned Goal. It is converted to a bridge source only after READY. */
    public record ComponentSource<C>(String id, BiFunction<YukkuriEntity, C, String> activity, Actions actions) {
        public ComponentSource {
            Objects.requireNonNull(id);
            Objects.requireNonNull(activity);
            Objects.requireNonNull(actions);
        }
        public Source<ComponentGoal> forComponent(C component) {
            return new Source<>(id, (owner, bridge) -> activity.apply(owner, component), actions);
        }
    }

    // Priority and tie order come from the real selector, not this declaration order.
    public static final Source<RoamingGoal> ROAMING = observed("roaming", RoamingGoal::activity);
    public static final Source<Goal> PROTECTION = state("protection", e -> e.grief().activity());
    public static final Source<Goal> MIND = state("mind", e -> e.mind().active() ? e.mind().activity() : null);
    public static final Source<Goal> THREAT = state("threat", e -> switch (e.status().reactions().threat().stage()) {
        case WATCH -> Behaviors.THREAT_WATCH;
        case RETREAT -> Behaviors.THREAT_RETREAT;
        case HIDE -> Behaviors.THREAT_HIDE;
        default -> null;
    });
    public static final Source<Goal> WEATHER = state("weather", e -> switch (e.status().reactions().weather().stage()) {
        case RUN, SEARCH -> Behaviors.RAIN_RUN;
        case WAIT -> Behaviors.RAIN_WAIT;
        case SHAKE -> Behaviors.RAIN_SHAKE;
        default -> null;
    });
    public static final Source<Goal> SANCTION = state("sanction", e -> e.sanction().active() ? e.sanction().activity() : null);
    public static final ComponentSource<CareApproach> CARE_RECEIVER = component("care_receiver",
            (e, care) -> care.waiting() ? care.activity() : null);
    public static final ComponentSource<Judgement> JUDGEMENT = component("judgement", (e, judgement) -> judgement.activity());
    public static final Source<Goal> COMFORT_RECEIVER = state("comfort_receiver", e -> e.woundComfort().receiving() ? e.woundComfort().activity() : null);
    public static final Source<Cannibalism> CANNIBALISM = observed("cannibalism", Cannibalism::activity);
    public static final Source<SelfOffering> SELF_OFFERING = observed("self_offering", SelfOffering::activity);
    public static final Source<Goal> HEADWEAR = state("headwear", e -> e.headwear().active() ? e.headwear().activity() : null);
    public static final Source<Goal> PREDATOR = new Source<>("predator",
            (e, g) -> e.predator().active() ? e.predator().activity() : null, Actions.PREDATOR);
    public static final Source<Goal> AQUATIC = state("aquatic", e -> e.aquaticLife().active() ? e.aquaticLife().activity() : null);
    public static final Source<Goal> CHILDHOOD = state("childhood", e -> nonIdle(e.childhood().activity()));
    public static final Source<Goal> LIFE = state("life", e -> nonIdle(e.life().activity()));
    public static final Source<HomeClaimGoal> HOME_CLAIM = observed("home_claim", HomeClaimGoal::activity);
    public static final Source<FactoryWorkGoal> FACTORY = observed("factory", FactoryWorkGoal::activity);
    public static final Source<Goal> HOUSE = state("house", e -> e.house().active() ? e.house().activity() : null);
    public static final Source<ForageTrip> FORAGE_TRIP = observed("forage_trip", ForageTrip::activity);
    public static final Source<ForageGoal> FORAGE = observed("forage", ForageGoal::activity);
    public static final Source<TreasureLife> TREASURE = observed("treasure", TreasureLife::activity);
    public static final Source<Goal> HABITAT = state("habitat", e -> e.habitat().active() ? e.habitat().activity() : null);
    public static final Source<Goal> INDEPENDENCE = state("independence", e -> e.independence().active() ? e.independence().activity() : null);
    public static final Source<Goal> MIGRATION = state("migration", e -> e.settlement().active() ? e.settlement().activity() : null);
    public static final Source<Goal> COMFORT_GIVER = state("comfort_giver", e -> e.woundComfort().licking() ? e.woundComfort().activity() : null);
    public static final Source<Goal> FAMILY = state("family", e -> e.family().active() ? e.family().activity() : null);
    public static final Source<Goal> WILD_EVENT = state("wild_event", e -> WildEvents.reserved(e) ? WildEvents.activity(e) : null);
    public static final Source<Goal> DAILY = state("daily", e -> e.dailyLife().active() ? e.dailyLife().activity() : null);
    public static final Source<Goal> GROUP = new Source<>("group",
            (e, g) -> e.groupInteraction().active() ? e.groupInteraction().activity() : null, Actions.GROUP);
    public static final ComponentSource<GroupInteractionGoal> SELF_DEFENSE = new ComponentSource<>("self_defense",
            (e, group) -> group.defense().activity(), Actions.GROUP);
    public static final Source<Goal> DUTIES = state("duties", e -> e.settlement().duties().active() ? e.settlement().duties().activity() : null);
    public static final Source<Goal> SETTLEMENT = state("settlement", e -> e.settlement().active() ? e.settlement().activity() : null);
    public static final Source<Goal> SOCIAL = state("social", e -> e.social().active() ? e.social().activity() : null);
    public static final Source<Goal> PLAY = state("play", e -> e.playLife().active() ? e.playLife().activity() : null);
    public static final Source<Goal> WINTERING = state("wintering", e -> e.wintering().active() ? e.wintering().activity() : null);

    public record Candidate(Source<?> source, int priority, int ordinal, Supplier<String> activity) {}
    public record Running(List<Candidate> candidates) {
        public Running { candidates = List.copyOf(candidates); }
        public boolean contains(Source<?> source) {
            return candidates.stream().anyMatch(candidate -> candidate.source() == source);
        }
    }

    private record Binding(Source<?> source, Supplier<String> activity) {}
    private final YukkuriEntity owner;
    private final Map<Goal, Binding> bindings = new IdentityHashMap<>();

    /** Safe in Mob.registerGoals: retain the owner, but do not read any not-yet-created component. */
    public PresentationGoals(YukkuriEntity owner) { this.owner = owner; }

    /** Returns the exact original instance. Registration timing, flags and AI identity are unchanged. */
    public <G extends Goal> G bind(Source<? super G> source, G goal) {
        bindings.put(goal, new Binding(source, () -> source.activity().apply(owner, goal)));
        return goal;
    }

    /** One selector pass per ordinary arbitration. Sorting never invokes activity or eligibility. */
    public Running running(Iterable<WrappedGoal> available) {
        var candidates = new ArrayList<Candidate>();
        int ordinal = 0;
        for (var wrapped : available) {
            ordinal++;
            if (!wrapped.isRunning()) continue;
            var binding = bindings.get(wrapped.getGoal());
            if (binding != null) candidates.add(new Candidate(binding.source(), wrapped.getPriority(), ordinal, binding.activity()));
        }
        candidates.sort(Comparator.comparingInt(Candidate::priority).thenComparingInt(Candidate::ordinal));
        return new Running(candidates);
    }

    private static Source<Goal> state(String id, Function<YukkuriEntity, String> activity) {
        return new Source<>(id, (e, g) -> activity.apply(e), Actions.ORDINARY);
    }
    private static <C> ComponentSource<C> component(String id, BiFunction<YukkuriEntity, C, String> activity) {
        return new ComponentSource<>(id, activity, Actions.ORDINARY);
    }
    private static <G extends Goal> Source<G> observed(String id, Function<G, String> activity) {
        return new Source<>(id, (e, g) -> activity.apply(g), Actions.ORDINARY);
    }
    private static String nonIdle(String behavior) { return Behaviors.IDLE.equals(behavior) ? null : behavior; }
}
