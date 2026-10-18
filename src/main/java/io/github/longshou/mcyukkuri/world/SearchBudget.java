package io.github.longshou.mcyukkuri.world;

import com.mojang.logging.LogUtils;
import java.lang.ref.WeakReference;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/** Server-thread admission only: tickets contain identities, never searches or world-edit continuations. */
public final class SearchBudget {
    public static final long PER_TICK = 12288, LEGACY_COST = 4096;
    private static final long HEAD_WINDOW = 120, IDLE_TTL = 240;
    private static final long SURVEY_UNITS = 121, PATH_UNITS = 256;
    public enum Result { FOUND, NOT_FOUND, DEFERRED;
        public static Result of(boolean found) { return found ? FOUND : NOT_FOUND; }
    }
    public enum Purpose { FIND_HOME, SETTLE, RELOCATE, PICK_SITE, NAVIGATE, CLAIM_SETTLE,
        FORAGE, FORAGE_TRIP, DIG, GRANARY, BEDDING, LEGACY_ANONYMOUS }
    public record Cost(long probes, long surveys, long paths) {
        public Cost {
            if (probes < 0 || surveys < 0 || paths < 0) throw new IllegalArgumentException("Negative search cost");
        }
        public long units() { return add(probes, add(multiply(surveys, SURVEY_UNITS), multiply(paths, PATH_UNITS))); }
        public Cost plus(Cost other) { return new Cost(add(probes, other.probes), add(surveys, other.surveys), add(paths, other.paths)); }
        public Cost times(long n) { return new Cost(multiply(probes, n), multiply(surveys, n), multiply(paths, n)); }
    }
    public static long add(long a, long b) { return a >= Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }
    public static long multiply(long a, long b) {
        if (a < 0 || b < 0) throw new IllegalArgumentException("Negative search factor");
        return a == 0 || b == 0 ? 0 : a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b;
    }
    private record Key(ResourceKey<Level> dimension, UUID owner, Purpose purpose) {}
    private record Identity(WeakReference<Entity> entity, long generation) {
        boolean same(Identity other) {
            return generation == other.generation && (entity == null ? other.entity == null
                    : other.entity != null && entity.get() != null && entity.get() == other.entity.get());
        }
        boolean valid(MinecraftServer server, Key key) {
            var level = server.getLevel(key.dimension);
            if (level == null) return false;
            if (entity == null) return true;
            var value = entity.get();
            return value != null && value.isAlive() && !value.isRemoved() && value.level() == level && level.getEntity(key.owner) == value;
        }
    }
    private static final class Ticket {
        final Identity identity;
        long lastRequested, headSince = -1, upper;
        Ticket(Identity identity, long now, long upper) { this.identity = identity; lastRequested = now; this.upper = upper; }
    }
    private static final class Ledger {
        final LinkedHashMap<Key, Ticket> waiting = new LinkedHashMap<>();
        int stamp;
        boolean initialized, exclusive;
        long now, used;
        Lease active;
        void begin(MinecraftServer server) {
            if (!server.isSameThread()) throw new IllegalStateException("Search budget requires the server thread");
            int tick = server.getTickCount();
            if (!initialized || stamp != tick) {
                if (active != null) throw new IllegalStateException("Search lease crossed a server tick");
                if (initialized) now = add(now, Integer.toUnsignedLong(tick - stamp));
                initialized = true; stamp = tick; used = 0; exclusive = false;
            }
            waiting.entrySet().removeIf(e -> !e.getValue().identity.valid(server, e.getKey())
                    || now - e.getValue().lastRequested >= IDLE_TTL
                    || e.getValue().headSince >= 0 && now - e.getValue().headSince >= HEAD_WINDOW);
            head();
        }
        void head() { if (!waiting.isEmpty()) {
            var first = waiting.firstEntry().getValue();
            if (first.headSince < 0) first.headSince = now;
        } }
    }
    private static final Map<MinecraftServer, Ledger> SERVERS = new IdentityHashMap<>();
    private static final AtomicLong GENERATIONS = new AtomicLong();
    private static final UUID ANONYMOUS = new UUID(0, 0);
    private record Frame(Lease lease, Entity owner) {}
    private static final ThreadLocal<Frame> FRAME = new ThreadLocal<>();
    public static long generation() { return GENERATIONS.incrementAndGet(); }
    public static void beginTick(MinecraftServer server) { ledger(server).begin(server); }
    private static Ledger ledger(MinecraftServer server) { return SERVERS.computeIfAbsent(server, ignored -> new Ledger()); }

    public static Lease request(ServerLevel level, Entity owner, Purpose purpose, long generation, Cost upper) {
        if (owner.level() != level || !owner.isAlive() || owner.isRemoved()) return null;
        return request(level, new Key(level.dimension(), owner.getUUID(), purpose), new Identity(new WeakReference<>(owner), generation), upper.units());
    }
    public static Lease request(ServerLevel level, UUID virtualOwner, Purpose purpose, long generation, Cost upper) {
        return request(level, new Key(level.dimension(), virtualOwner, purpose), new Identity(null, generation), upper.units());
    }
    private static Lease request(ServerLevel level, Key key, Identity identity, long upper) {
        var server = level.getServer(); var ledger = ledger(server); ledger.begin(server);
        var ticket = ledger.waiting.get(key);
        if (ticket != null && !ticket.identity.same(identity)) { ledger.waiting.remove(key); ledger.head(); ticket = null; }
        if (ticket != null) { ticket.lastRequested = ledger.now; ticket.upper = upper; }
        boolean first = ledger.waiting.isEmpty() || ledger.waiting.firstEntry().getKey().equals(key);
        boolean fits = upper <= PER_TICK && upper <= PER_TICK - Math.min(PER_TICK, ledger.used);
        boolean oversized = upper > PER_TICK && ledger.used == 0;
        if (ledger.active != null || ledger.exclusive || !first || !(fits || oversized)) {
            if (ticket == null) ledger.waiting.put(key, new Ticket(identity, ledger.now, upper));
            ledger.head(); return null;
        }
        ledger.waiting.remove(key); ledger.head();
        ledger.used = add(ledger.used, upper);
        if (oversized) ledger.exclusive = true;
        var lease = new Lease(server, ledger, key, upper);
        ledger.active = lease;
        return lease;
    }
    /** Fixed, non-refundable compatibility admission. Anonymous callers share one bucket per dimension. */
    public static boolean take(ServerLevel level) { return take(level, null, Purpose.LEGACY_ANONYMOUS); }
    public static boolean take(ServerLevel level, Entity owner, Purpose purpose) {
        var cost = new Cost(LEGACY_COST, 0, 0);
        var lease = owner == null ? request(level, ANONYMOUS, Purpose.LEGACY_ANONYMOUS, 0, cost)
                : request(level, owner, purpose, 0, cost);
        if (lease == null) return false;
        try (lease) { lease.actual = LEGACY_COST; }
        return true;
    }
    public static final class Lease implements AutoCloseable {
        private final MinecraftServer server;
        private final Ledger ledger;
        private final Key key;
        private final long reserved;
        private long actual;
        private boolean closed;
        private Lease(MinecraftServer server, Ledger ledger, Key key, long reserved) {
            this.server = server; this.ledger = ledger; this.key = key; this.reserved = reserved;
        }
        /** Explicit delegation, including a claim's members / a relocating household; never inferred by owner equality. */
        public Scope bind(Entity owner) {
            if (closed || ledger.active != this || !server.isSameThread() || owner.level().getServer() != server || !owner.level().dimension().equals(key.dimension)) throw new IllegalStateException("Invalid nested search lease");
            var previous = FRAME.get();
            if (previous != null && previous.lease != this) throw new IllegalStateException("Foreign search frame");
            FRAME.set(new Frame(this, owner)); return new Scope(previous);
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (ledger.active != this || FRAME.get() != null && FRAME.get().lease == this)
                throw new IllegalStateException("Unbalanced search lease");
            ledger.active = null;
            ledger.used = add(ledger.used >= reserved ? ledger.used - reserved : 0, actual);
            if (actual > reserved) {
                ledger.exclusive = true;
                LogUtils.getLogger().error("Search cost underestimated: {} {} reserved={} actual={}; tick sealed", key.dimension, key.purpose, reserved, actual);
            }
        }
    }
    public static final class Scope implements AutoCloseable {
        private final Frame previous;
        private Scope(Frame previous) { this.previous = previous; }
        @Override public void close() { if (previous == null) FRAME.remove(); else FRAME.set(previous); }
    }
    private static void charge(Level level, Entity owner, long units) {
        var frame = FRAME.get();
        if (frame != null && frame.owner.level() == level && level.getServer() == frame.lease.server && (owner == null || owner == frame.owner))
            frame.lease.actual = add(frame.lease.actual, units);
    }
    /** Logical probes: enclosed / stand entries and explicitly marked candidate or external dry-stand checks. */
    public static void probe(Level level) { charge(level, null, 1); }
    public static void survey(Level level) { charge(level, null, SURVEY_UNITS); }
    /** A path-capable API attempt, including assignNaturalHome's possible early return; not an A* count. */
    public static void path(Entity owner) { charge(owner.level(), owner, PATH_UNITS); }
    public static void cancel(ServerLevel level, Entity owner, Purpose purpose, long generation) {
        cancel(level.getServer(), new Key(level.dimension(), owner.getUUID(), purpose), new Identity(new WeakReference<>(owner), generation));
    }
    public static void cancel(ServerLevel level, UUID owner, Purpose purpose, long generation) {
        cancel(level.getServer(), new Key(level.dimension(), owner, purpose), new Identity(null, generation));
    }
    private static void cancel(MinecraftServer server, Key key, Identity identity) {
        var ledger = SERVERS.get(server); if (ledger == null) return;
        var ticket = ledger.waiting.get(key);
        if (ticket != null && ticket.identity.same(identity)) { ledger.waiting.remove(key); ledger.head(); }
    }
    public static void leaving(ServerLevel level, Entity owner) {
        var ledger = SERVERS.get(level.getServer()); if (ledger == null) return;
        ledger.waiting.entrySet().removeIf(e -> e.getKey().dimension.equals(level.dimension()) && e.getValue().identity.entity != null
                && e.getValue().identity.entity.get() == owner);
        ledger.head();
    }
    public static void clear(ServerLevel level) {
        var ledger = SERVERS.get(level.getServer()); if (ledger == null) return;
        ledger.waiting.keySet().removeIf(k -> k.dimension.equals(level.dimension())); ledger.head();
    }
    public static void clear(MinecraftServer server) { SERVERS.remove(server); }
    private SearchBudget() {}
}
