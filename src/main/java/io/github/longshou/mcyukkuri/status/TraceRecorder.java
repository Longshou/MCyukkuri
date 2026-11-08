package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.random.ModRandom;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.presentation.PresentationArbiter;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Conductor;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.BodyDriver;
import io.github.longshou.mcyukkuri.motor.Braid;
import io.github.longshou.mcyukkuri.motor.surface.HostFrame;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Structured, observation-only server records. No method here starts a Goal or changes gameplay state. */
public final class TraceRecorder {
    public enum Mode {
        OFF("off"), LIGHT("light"), DENSE("dense");
        public final String id;
        Mode(String id) { this.id = id; }
        public static Mode parse(String id) {
            for (Mode mode : values()) if (mode.id.equals(id)) return mode;
            throw new IllegalArgumentException("trace mode must be off, light or dense");
        }
    }

    private static final int SCHEMA = 1;
    private static final int MAX_ACTORS = 512;
    private static final int LIGHT_POSITION_TICKS = 40;
    private static final double JUMP_SUSPECT = .25;
    private static final double GROUNDED_VERTICAL_SLACK = .6;
    /** Existing local-interaction range; scope only, never a gameplay decision. */
    private static final double NEAR_ITEM_RANGE_SQR = 16 * 16;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final Object LOG_LOCK = new Object();
    private static final Map<UUID, ActorCache> ACTORS = new HashMap<>();
    private static final Map<ResourceKey<Level>, SceneWindow> WINDOWS = new HashMap<>();

    private static Mode mode = Mode.OFF;
    private static String run = "unstarted";
    private static long sequence;
    private static Thread serverThread;
    private static TraceWatchdog watchdog;
    private static long tickStartNanos;
    private static volatile long lastTickEndNanos;
    private static volatile ServerLevel lastTickLevel;
    private static volatile long lastTick;
    private static boolean stallPending;
    private static StackTraceElement[] stallStack;
    /** UUIDs seeded in this run only. The trace actor bound also bounds this smoke-only run state. */
    private static final Set<UUID> RANDOM_SEEDED = new HashSet<>();

    private static long modeStartedNanos;
    private static long modeTicks;
    private static long modeWorkNanos;
    private static long modeRecorderNanos;
    private static long modeBytes;

    private record SceneWindow(String scene, String field, long start, AABB box) {}

    private static final class ActorCache {
        private final ServerLevel level;
        private final UUID uuid;
        private final SceneWindow window;
        private JsonObject emitted;
        private Vec3 lastPosition;
        private Vec3 lastVelocity;
        private String attachment;
        private long lastPositionTick = Long.MIN_VALUE;
        private JsonObject previousLight;
        private long previousLightTick;
        private boolean lifeKnown;

        private ActorCache(Entity entity, SceneWindow window) {
            this.level = (ServerLevel) entity.level();
            this.uuid = entity.getUUID();
            this.window = window;
        }
    }

    public static Mode mode() { return mode; }

    /** Returns the current run seed once per actor. Reloads in the same run keep the actor's random stream. */
    public static Long runSeedForFirstAdd(UUID id) {
        if (ModRandom.runSeed() == null || RANDOM_SEEDED.contains(id)) return null;
        if (RANDOM_SEEDED.size() >= MAX_ACTORS) throw new IllegalStateException("Run-seeded actor limit: " + MAX_ACTORS);
        RANDOM_SEEDED.add(id);
        return ModRandom.runSeed();
    }

    /** {@code /yukkuri status seed}: every actor first added from now on mixes this seed with its UUID, and the run's
     * proven coverage is recorded once for the scene. */
    public static void setRunSeed(ServerLevel level, long seed) {
        ModRandom.seed(seed);
        RANDOM_SEEDED.clear();
        GameplayLog.world(level, "seed",
                "run={} covered=entity_random,mod_world_random,mod_identity,newborn_identity,pre_join,speech uncovered=vanilla_random,pre_run_world_generation,tick_order,world_generation_order", seed);
    }

    public static void setMode(Mode next) {
        if (mode == next) return;
        costSummary();
        mode = next;
        ACTORS.clear();
        modeStartedNanos = System.nanoTime();
        modeTicks = modeWorkNanos = modeRecorderNanos = modeBytes = 0;
        lastTickEndNanos = mode == Mode.OFF ? 0 : System.nanoTime();
        if (mode == Mode.OFF) stopWatchdog();
        else startWatchdog();
    }

    public static void started(ServerStartingEvent event) {
        synchronized (LOG_LOCK) {
            run = UUID.randomUUID().toString();
            sequence = 0;
        }
        serverThread = Thread.currentThread();
        lastTickLevel = event.getServer().overworld();
        lastTick = lastTickLevel.getGameTime();
        ACTORS.clear();
        WINDOWS.clear();
        RANDOM_SEEDED.clear();
        modeStartedNanos = System.nanoTime();
        modeTicks = modeWorkNanos = modeRecorderNanos = modeBytes = 0;
        if (mode != Mode.OFF) startWatchdog();
    }

    public static void stopping(ServerStoppingEvent event) {
        costSummary();
        stopWatchdog();
        mode = Mode.OFF;
        ACTORS.clear();
        WINDOWS.clear();
        ModRandom.clear();
        RANDOM_SEEDED.clear();
        serverThread = null;
        lastTickEndNanos = 0;
    }

    private static void startWatchdog() {
        if (serverThread == null || watchdog != null || mode == Mode.OFF) return;
        lastTickEndNanos = System.nanoTime();
        watchdog = new TraceWatchdog(serverThread, TraceRecorder::lastTickEndNanos, TraceRecorder::stalled);
    }

    private static void stopWatchdog() {
        if (watchdog == null) return;
        watchdog.close();
        watchdog = null;
    }

    private static long lastTickEndNanos() { return lastTickEndNanos; }

    public static void tickPre(ServerTickEvent.Pre event) {
        if (mode == Mode.OFF) return;
        tickStartNanos = System.nanoTime();
    }

    public static void tickPost(ServerTickEvent.Post event) {
        if (mode == Mode.OFF) return;
        long work = System.nanoTime() - tickStartNanos;
        long recorderStart = System.nanoTime();
        MinecraftServer server = event.getServer();
        for (ServerLevel level : server.getAllLevels()) sampleLevel(level);
        lastTickLevel = server.overworld();
        lastTick = lastTickLevel.getGameTime();
        long recorder = System.nanoTime() - recorderStart;
        if (stallPending) {
            JsonObject lag = new JsonObject();
            lag.addProperty("stall_ms", nanosToMillis(System.nanoTime() - lastTickEndNanos));
            lag.add("stack", stack(stallStack));
            emit(lastTickLevel, currentWindow(lastTickLevel), null, "lag", lag, lastTick);
            stallPending = false;
            stallStack = null;
        }
        if (mode == Mode.DENSE || work > 50_000_000L) {
            JsonObject lag = new JsonObject();
            lag.addProperty("ms", nanosToMillis(work));
            lag.addProperty("rec_ms", nanosToMillis(recorder));
            emit(lastTickLevel, currentWindow(lastTickLevel), null, "lag", lag, lastTick);
        }
        modeTicks++;
        modeWorkNanos += work;
        modeRecorderNanos += recorder;
        lastTickEndNanos = System.nanoTime();
    }

    private static void stalled(TraceWatchdog.Stall stall) {
        ServerLevel level = lastTickLevel;
        if (level == null || mode == Mode.OFF) return;
        JsonObject lag = new JsonObject();
        lag.addProperty("stall_ms", nanosToMillis(stall.elapsedNanos()));
        lag.add("stack", stack(stall.stack()));
        emit(level, currentWindow(level), null, "lag", lag, lastTick);
        stallPending = true;
        stallStack = stall.stack();
    }

    private static JsonArray stack(StackTraceElement[] stack) {
        var out = new JsonArray();
        if (stack != null) for (StackTraceElement frame : stack) out.add(frame.toString());
        return out;
    }

    private static double nanosToMillis(long nanos) { return nanos / 1_000_000.0; }

    private static void costSummary() {
        if (mode == Mode.OFF) return;
        ServerLevel level = lastTickLevel;
        if (level != null && modeTicks > 0) {
            JsonObject lag = new JsonObject();
            lag.addProperty("summary", true);
            lag.addProperty("ticks", modeTicks);
            lag.addProperty("ms", nanosToMillis(modeWorkNanos) / modeTicks);
            lag.addProperty("rec_ms", nanosToMillis(modeRecorderNanos) / modeTicks);
            lag.addProperty("bytes", modeBytes);
            lag.addProperty("wall_ms", nanosToMillis(System.nanoTime() - modeStartedNanos));
            emit(level, currentWindow(level), null, "lag", lag, lastTick);
        }
        modeTicks = modeWorkNanos = modeRecorderNanos = modeBytes = 0;
    }

    /** Starts a scene window and invalidates every delta, so each actor's next sample is full. */
    public static void window(ServerLevel level, String scene, String field) {
        window(level, scene, field, null);
    }

    /** The generated scene supplies its real field box; manual one-argument windows retain tag/proximity scope. */
    public static void window(ServerLevel level, String scene, String field, AABB box) {
        String actualField = field == null ? inferField(level) : field;
        SceneWindow window = new SceneWindow(scene, actualField, level.getGameTime(), box);
        WINDOWS.put(level.dimension(), window);
        ACTORS.clear();
        JsonObject body = new JsonObject();
        body.addProperty("scene", scene);
        body.addProperty("field", actualField);
        body.addProperty("start", window.start());
        if (box != null) body.add("box", vec(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ));
        emit(level, window, null, "win", body, window.start());
    }

    private static String inferField(ServerLevel level) {
        for (Entity entity : level.getAllEntities()) for (String tag : entity.getTags())
            if (tag.startsWith("fld_")) return tag.substring(4);
        return "unknown";
    }

    private static SceneWindow currentWindow(ServerLevel level) { return WINDOWS.get(level.dimension()); }

    /** Light StatusLife.watch and GameplayLog use the same scope as sampling. */
    public static boolean recorded(Entity entity) {
        if (mode == Mode.OFF || entity == null || entity.level().isClientSide) return false;
        return inScope(entity, new HashSet<>());
    }

    private static boolean inScope(Entity entity, Set<UUID> visiting) {
        if (!visiting.add(entity.getUUID())) return false;
        if (ACTORS.containsKey(entity.getUUID()) || direct(entity)) return true;
        if (entity.getVehicle() != null && inScope(entity.getVehicle(), visiting)) return true;
        for (Entity passenger : entity.getPassengers()) if (inScope(passenger, visiting)) return true;
        if (entity instanceof YukkuriEntity y) {
            for (UUID parent : new UUID[]{y.family().mother(), y.family().father()})
                if (parent != null && find(entity, parent) instanceof Entity found && inScope(found, visiting)) return true;
        }
        if (entity instanceof PlacedHatEntity hat && hat.ownerYukkuri() != null
                && find(hat, hat.ownerYukkuri()) instanceof Entity owner && inScope(owner, visiting)) return true;
        return false;
    }

    private static Entity find(Entity context, UUID id) {
        return context.level() instanceof ServerLevel level ? level.getEntity(id) : null;
    }

    private static boolean direct(Entity entity) {
        for (String tag : entity.getTags())
            if (tag.equals("scn") || tag.startsWith("scn_") || tag.startsWith("fld_")) return true;
        return false;
    }

    public static void joined(EntityJoinLevelEvent event) {
        if (mode == Mode.OFF || event.getLevel().isClientSide()) return;
        Entity entity = event.getEntity();
        UUID parent = recordedParent(entity);
        if (!inScope(entity, new HashSet<>()) && parent == null) return;
        SceneWindow window = windowFor(entity);
        ActorCache cache = cacheFor(entity, window);
        cache.lifeKnown = true;
        String reason = parent != null && entity instanceof YukkuriEntity y && y.family().birthId() != null ? "birth"
                : cache.emitted != null ? "scope_enter" : event.loadedFromDisk() ? "chunk_load" : "spawn";
        JsonObject life = new JsonObject();
        life.addProperty("in", reason);
        if (parent != null) life.addProperty("parent", parent.toString());
        emit(entity, cache.window, "life", life);
    }

    public static void leaving(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity entity = event.getEntity();
        ActorCache cache = ACTORS.remove(entity.getUUID());
        if (cache == null || mode == Mode.OFF) return;
        Entity.RemovalReason reason = entity.getRemovalReason();
        String out = reason == null ? "unloaded" : switch (reason) {
            case KILLED -> "killed";
            case DISCARDED -> "discarded";
            case UNLOADED_TO_CHUNK, UNLOADED_WITH_PLAYER -> "unloaded";
            case CHANGED_DIMENSION -> "changed_dimension";
        };
        JsonObject life = new JsonObject();
        life.addProperty("out", out);
        if (entity instanceof ItemEntity item) {
            life.addProperty("item", BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString());
            life.addProperty("age", item.getAge());
            life.addProperty("lifespan", item.lifespan);
        }
        emit(entity, cache.window, "life", life);
    }

    private static UUID recordedParent(Entity entity) {
        if (!(entity instanceof YukkuriEntity y)) return null;
        for (UUID parent : new UUID[]{y.family().mother(), y.family().father()})
            if (parent != null && find(y, parent) instanceof Entity found && inScope(found, new HashSet<>())) return parent;
        return null;
    }

    private static SceneWindow windowFor(Entity entity) {
        ActorCache cache = ACTORS.get(entity.getUUID());
        if (cache != null) return cache.window;
        if (entity instanceof YukkuriEntity y) for (UUID parentId : new UUID[]{y.family().mother(), y.family().father()}) {
            ActorCache parent = ACTORS.get(parentId);
            if (parent != null) return parent.window;
        }
        return entity.level() instanceof ServerLevel level ? currentWindow(level) : null;
    }

    private static ActorCache cacheFor(Entity entity, SceneWindow window) {
        ActorCache existing = ACTORS.get(entity.getUUID());
        if (existing != null) return existing;
        if (ACTORS.size() >= MAX_ACTORS) ACTORS.clear();
        ActorCache made = new ActorCache(entity, window);
        ACTORS.put(entity.getUUID(), made);
        return made;
    }

    private static void sampleLevel(ServerLevel level) {
        List<Entity> entities = new ArrayList<>();
        level.getAllEntities().forEach(entities::add);
        Map<UUID, SceneWindow> selected = new HashMap<>();
        SceneWindow current = currentWindow(level);
        for (Entity entity : entities) if (direct(entity)) selected.put(entity.getUUID(), current);

        boolean changed = true;
        while (changed) {
            changed = false;
            for (Entity entity : entities) {
                UUID id = entity.getUUID();
                if (selected.containsKey(id)) {
                    changed |= include(entity.getVehicle(), selected, current);
                    for (Entity passenger : entity.getPassengers()) changed |= include(passenger, selected, current);
                    continue;
                }
                if (entity.getVehicle() != null && selected.containsKey(entity.getVehicle().getUUID())) {
                    selected.put(id, current);
                    changed = true;
                    continue;
                }
                boolean carrier = false;
                for (Entity passenger : entity.getPassengers()) carrier |= selected.containsKey(passenger.getUUID());
                if (carrier || recordedParent(entity) != null) {
                    selected.put(id, current);
                    changed = true;
                    continue;
                }
                if (entity instanceof PlacedHatEntity hat && hat.ownerYukkuri() != null && selected.containsKey(hat.ownerYukkuri())) {
                    selected.put(id, current);
                    changed = true;
                }
            }
        }
        for (Entity item : entities) {
            if (!(item instanceof ItemEntity) || selected.containsKey(item.getUUID())) continue;
            if (current != null && current.box() != null && current.box().contains(item.position())) {
                selected.put(item.getUUID(), current);
                continue;
            }
            for (Entity actor : entities) if (selected.containsKey(actor.getUUID()) && item.distanceToSqr(actor) <= NEAR_ITEM_RANGE_SQR) {
                selected.put(item.getUUID(), current);
                break;
            }
        }

        List<Entity> sampled = new ArrayList<>();
        for (Entity entity : entities) if (selected.containsKey(entity.getUUID())) sampled.add(entity);
        sampled.sort(Comparator.comparing(Entity::getUUID));
        for (Entity entity : sampled) {
            ActorCache cache = cacheFor(entity, selected.get(entity.getUUID()));
            if (!cache.lifeKnown) {
                JsonObject life = new JsonObject();
                life.addProperty("in", "scope_enter");
                emit(entity, cache.window, "life", life);
                cache.lifeKnown = true;
            }
            if (entity instanceof YukkuriEntity y) sampleYukkuri(y, cache);
            else if (entity instanceof PlacedHatEntity hat) sampleSimple(hat, cache, placedHatState(hat));
            else if (entity instanceof ItemEntity item) sampleSimple(item, cache, itemState(item));
        }

        var iterator = ACTORS.entrySet().iterator();
        while (iterator.hasNext()) {
            ActorCache cache = iterator.next().getValue();
            if (cache.level != level || selected.containsKey(cache.uuid)) continue;
            JsonObject life = new JsonObject();
            life.addProperty("out", "scope_exit");
            emit(level, cache.window, cache.uuid.toString(), "life", life, level.getGameTime());
            iterator.remove();
        }
    }

    private static boolean include(Entity entity, Map<UUID, SceneWindow> selected, SceneWindow window) {
        if (entity == null || selected.containsKey(entity.getUUID())) return false;
        selected.put(entity.getUUID(), window);
        return true;
    }

    private static void sampleYukkuri(YukkuriEntity entity, ActorCache cache) {
        long now = entity.level().getGameTime();
        JsonObject full = yukkuriState(entity);
        JsonObject cheap = cheapState(full);
        Vec3 position = entity.position(), velocity = entity.getDeltaMovement();
        String attachment = attachmentKey(entity);
        boolean jump = cache.lastPosition != null && jump(entity, position, velocity, attachment, cache);
        if (mode == Mode.DENSE) {
            emit(entity, cache.window, "st", full);
            cache.emitted = full;
        } else if (cache.emitted == null) {
            emit(entity, cache.window, "st", full);
            cache.emitted = full;
            cache.lastPositionTick = now;
        } else {
            boolean positionDue = cache.lastPositionTick == Long.MIN_VALUE || now - cache.lastPositionTick >= LIGHT_POSITION_TICKS;
            JsonObject observed = positionDue ? full : cheap.deepCopy();
            if (!positionDue) {
                JsonObject att = observed.getAsJsonObject("att");
                JsonObject prior = cache.emitted.getAsJsonObject("att");
                if (Objects.equals(att.get("host"), prior.get("host"))) {
                    for (String key : new String[]{"frame", "spot"})
                        if (prior.has(key)) att.add(key, prior.get(key));
                }
                JsonElement act = observed.get("act"), previousAct = cache.emitted.get("act");
                if (act.isJsonObject() && previousAct.isJsonObject()
                        && act.getAsJsonObject().get("id").equals(previousAct.getAsJsonObject().get("id"))
                        && act.getAsJsonObject().get("owner").equals(previousAct.getAsJsonObject().get("owner"))) {
                    act.getAsJsonObject().add("metric", previousAct.getAsJsonObject().get("metric"));
                }
            }
            JsonObject delta = changedKeys(cache.emitted, observed);
            if (jump) {
                if (cache.previousLight != null) emit(entity, cache.window, "st", cache.previousLight, cache.previousLightTick);
                emit(entity, cache.window, "st", full);
                cache.emitted = full;
                cache.lastPositionTick = now;
            } else {
                if (!delta.isEmpty()) emit(entity, cache.window, "st", delta);
                for (String key : delta.keySet()) cache.emitted.add(key, delta.get(key));
                if (positionDue) cache.lastPositionTick = now;
            }
        }
        cache.previousLight = cheap.deepCopy();
        cache.previousLight.add("p", vec(position));
        cache.previousLight.add("v", vec(velocity));
        cache.previousLightTick = now;
        cache.lastPosition = position;
        cache.lastVelocity = velocity;
        cache.attachment = attachment;
    }

    private static boolean jump(YukkuriEntity entity, Vec3 position, Vec3 velocity, String attachment, ActorCache cache) {
        if (!Objects.equals(attachment, cache.attachment)) return true;
        Vec3 residual = position.subtract(cache.lastPosition).subtract(cache.lastVelocity);
        double vertical = Math.abs(residual.y);
        if (entity.onGround()) vertical = Math.max(0, vertical - GROUNDED_VERTICAL_SLACK);
        return Math.hypot(Math.hypot(residual.x, residual.z), vertical) > JUMP_SUSPECT;
    }

    private static String attachmentKey(YukkuriEntity entity) {
        AttachState state = Attach.of(entity);
        if (state instanceof AttachState.OnHost host) return "host:" + host.host().entity().getUUID();
        if (state instanceof AttachState.Held held) return "held:" + held.carrier().getUUID();
        if (state instanceof AttachState.Airborne air) return "air:" + air.action();
        return "free";
    }

    private static void sampleSimple(Entity entity, ActorCache cache, JsonObject full) {
        long now = entity.level().getGameTime();
        if (mode == Mode.DENSE || cache.emitted == null) {
            emit(entity, cache.window, "st", full);
            cache.emitted = full;
            cache.lastPositionTick = now;
        } else {
            boolean positionDue = now - cache.lastPositionTick >= LIGHT_POSITION_TICKS;
            JsonObject observed = full.deepCopy();
            if (!positionDue) for (String key : new String[]{"p", "v", "frame"}) observed.remove(key);
            JsonObject delta = changedKeys(cache.emitted, observed);
            if (positionDue) cache.lastPositionTick = now;
            if (!delta.isEmpty()) emit(entity, cache.window, "st", delta);
            for (String key : delta.keySet()) cache.emitted.add(key, delta.get(key));
        }
    }

    private static JsonObject changedKeys(JsonObject before, JsonObject after) {
        JsonObject out = new JsonObject();
        for (String key : after.keySet()) if (!after.get(key).equals(before.get(key))) out.add(key, after.get(key));
        return out;
    }

    private static JsonObject cheapState(JsonObject full) {
        JsonObject out = new JsonObject();
        for (String key : new String[]{"att", "veh", "hold", "dep", "distress", "g", "w", "wr", "iw", "iwr", "hp", "phase", "int", "goals", "stage", "parents"}) out.add(key, full.get(key));
        JsonElement action = full.get("act");
        if (action == null || action.isJsonNull()) out.add("act", JsonNull.INSTANCE);
        else {
            out.add("act", action);
        }
        return out;
    }

    private static JsonObject yukkuriState(YukkuriEntity entity) {
        JsonObject out = new JsonObject();
        Vec3 position = entity.position();
        out.add("p", vec(position));
        out.add("v", vec(entity.getDeltaMovement()));
        out.addProperty("yaw", entity.getYRot());
        out.addProperty("by", entity.bodyYaw());
        out.addProperty("g", entity.onGround());
        out.addProperty("hc", entity.horizontalCollision);
        out.addProperty("vc", entity.verticalCollision);
        out.addProperty("step", entity.maxUpStep());
        out.addProperty("w", entity.isInWater());
        out.addProperty("wr", entity.rawInWater());
        Boolean rawWall = TraceBlocks.inWall(entity);
        out.addProperty("iw", entity.getVehicle() instanceof YukkuriEntity ? Boolean.FALSE : rawWall);
        out.addProperty("iwr", rawWall);
        out.addProperty("hp", entity.getHealth());
        out.add("att", attachment(entity));
        out.addProperty("veh", entity.getVehicle() == null ? null : entity.getVehicle().getUUID().toString());

        BodyDriver.ActionTrace action = entity.motor().actionTrace();
        IntentRunner runner = IntentRunner.running(entity);
        if (action == null) out.add("act", JsonNull.INSTANCE);
        else {
            JsonObject value = new JsonObject();
            value.addProperty("id", action.id());
            value.addProperty("owner", action.owner());
            if (Double.isFinite(action.metric())) value.addProperty("metric", action.metric());
            else value.add("metric", JsonNull.INSTANCE);
            value.addProperty("win", action.window());
            if (runner == null) value.add("deadline", JsonNull.INSTANCE);
            else value.addProperty("deadline", runner.intent().deadline());
            out.add("act", value);
        }
        if (runner == null) out.add("int", JsonNull.INSTANCE);
        else {
            JsonObject value = new JsonObject();
            value.addProperty("id", runner.intent().id());
            value.addProperty("method", runner.method() == null ? null : runner.method().id());
            value.addProperty("attempts", runner.attempts().used());
            out.add("int", value);
        }
        var goals = new JsonArray();
        entity.runningGoalIds().forEach(goals::add);
        out.add("goals", goals);
        out.addProperty("behavior", entity.getBehaviorId());
        Conductor.Role role = Conductor.roleOf(entity);
        out.addProperty("phase", role == null ? entity.family().taskId() : role.choreography().beat());
        Number waitElapsed;
        if (role == null) waitElapsed = entity.status().reactions().observedWaitElapsed();
        else waitElapsed = role.choreography().observedWaitElapsed();
        out.addProperty("waitElapsed", waitElapsed);
        out.addProperty("contactProgress", entity.groupInteraction().active()
                ? entity.groupInteraction().contactProgress()
                : Math.max(entity.childhood().careProgress(), entity.careApproach().observedContactProgress()));
        out.addProperty("careChewTicks", entity.family().careChewTicks());
        UUID careParent = entity.careApproach().observedParent();
        out.addProperty("careParent", careParent == null ? null : careParent.toString());
        UUID socialGreeting = entity.social().observedGreeting();
        out.addProperty("socialGreeting", socialGreeting == null ? null : socialGreeting.toString());
        out.addProperty("defenseWait", entity.groupInteraction().defense().observedStrikeWait());
        out.addProperty("dailyContactTicks", entity.dailyLife().observedContactTicks());
        out.addProperty("dailyGreetingWait", entity.dailyLife().observedGreetingWait());
        out.addProperty("thermalRestTicks", entity.life().temperature().observedRestTicks());
        out.addProperty("pitPhase", entity.settlement().duties().pit().observedPhase());
        out.add("hold", hold(entity));
        out.addProperty("dep", entity.childhood().shouldStayWithParents());
        out.addProperty("distress", entity.waterDistress().active());
        out.addProperty("waterPhase", entity.waterDistress().phase().name());
        out.addProperty("stage", entity.family().stage().name().toLowerCase(Locale.ROOT));
        JsonObject parents = new JsonObject();
        parents.addProperty("mother", entity.family().mother() == null ? null : entity.family().mother().toString());
        parents.addProperty("father", entity.family().father() == null ? null : entity.family().father().toString());
        out.add("parents", parents);
        BlockPos home = entity.life().homeStand();
        out.add("home", home == null ? JsonNull.INSTANCE : vec(home.getX(), home.getY(), home.getZ()));
        JsonObject needs = new JsonObject();
        needs.addProperty("nut", entity.life().nutrition());
        needs.addProperty("en", entity.life().energy());
        needs.addProperty("wet", entity.life().wetness());
        out.add("need", needs);
        out.addProperty("scale", entity.getScale() * entity.growthScale());
        return out;
    }

    private static JsonObject attachment(YukkuriEntity entity) {
        JsonObject out = new JsonObject();
        AttachState state = Attach.of(entity);
        if (state instanceof AttachState.OnHost ride) {
            SurfaceHost host = ride.host();
            out.addProperty("kind", "on_host");
            out.addProperty("host", host.entity().getUUID().toString());
            out.add("spot", vec(ride.local()));
            out.addProperty("place", place(host, entity, ride.local()));
            out.add("frame", frame(host.frame()));
        } else if (state instanceof AttachState.Held held) {
            out.addProperty("kind", "held");
            out.addProperty("host", held.carrier().getUUID().toString());
            ParentCarry.Hold hold = ParentCarry.hold(held.carrier(), entity);
            out.addProperty("place", hold == ParentCarry.Hold.HEAD ? "head" : hold == ParentCarry.Hold.BRIM ? "brim" : "ground");
            out.add("spot", JsonNull.INSTANCE);
        } else {
            out.addProperty("kind", state instanceof AttachState.Airborne ? "airborne" : "free");
            out.add("host", JsonNull.INSTANCE);
            out.add("spot", JsonNull.INSTANCE);
            out.addProperty("place", "ground");
        }
        return out;
    }

    private static String place(SurfaceHost host, YukkuriEntity body, Vec3 local) {
        if (host.entity() instanceof PlacedHatEntity hat) {
            if (PresentationArbiter.onBrim(hat, body)) return "brim";
            if (Occupancy.of(body, host.container()) == Occupancy.INSIDE_SUPPORTED) return "cabin";
        }
        return "ground";
    }

    private static JsonObject hold(YukkuriEntity entity) {
        JsonObject out = new JsonObject();
        out.addProperty("braid", Braid.use(entity).name().toLowerCase(Locale.ROOT));
        var mouth = new JsonArray();
        ItemStack mouthItem = entity.getMouthItem();
        if (!mouthItem.isEmpty()) mouth.add(item(mouthItem));
        var bite = entity.getMouthBodyFood();
        if (!bite.isEmpty()) {
            JsonObject value = new JsonObject();
            value.addProperty("item", "body_food:" + bite.taste());
            value.addProperty("n", bite.portions());
            mouth.add(value);
        }
        ItemStack carried = entity.getCarriedItem();
        if (!carried.isEmpty()) mouth.add(item(carried));
        out.add("mouth", mouth);
        // Observe this holder's authority only; hatKit() also aliases a deployed hat's stack.
        ItemStack kitStack = entity.life().custody().kit();
        if (kitStack.isEmpty()) out.add("kit", JsonNull.INSTANCE);
        else {
            JsonObject kit = item(kitStack);
            kit.addProperty("place", entity.heldRecord().at(entity.level().getGameTime()).name().toLowerCase(Locale.ROOT));
            out.add("kit", kit);
        }
        JsonObject hat = new JsonObject();
        hat.addProperty("state", entity.headwear().stateId());
        hat.addProperty("placed", PlacedHatEntity.find(entity) != null);
        out.add("hat", hat);
        var carry = new JsonArray();
        for (Entity passenger : entity.getPassengers()) if (passenger instanceof YukkuriEntity child) {
            JsonObject value = new JsonObject();
            value.addProperty("child", child.getUUID().toString());
            value.addProperty("hold", ParentCarry.hold(entity, child).key());
            carry.add(value);
        }
        out.add("carry", carry);
        return out;
    }

    private static JsonObject placedHatState(PlacedHatEntity hat) {
        JsonObject out = new JsonObject();
        out.add("p", vec(hat.position()));
        out.add("v", vec(hat.getDeltaMovement()));
        out.add("frame", frame(hat.frame()));
        var riders = new JsonArray();
        for (Entity passenger : hat.getPassengers()) {
            JsonObject value = new JsonObject();
            value.addProperty("u", passenger.getUUID().toString());
            value.add("spot", vec(hat.riders().local(passenger.getUUID())));
            value.addProperty("place", passenger instanceof YukkuriEntity body ? place(hat, body, hat.riders().local(passenger.getUUID())) : "ground");
            riders.add(value);
        }
        out.add("riders", riders);
        out.add("kit", hat.kit().isEmpty() ? JsonNull.INSTANCE : item(hat.kit()));
        return out;
    }

    private static JsonObject itemState(ItemEntity entity) {
        JsonObject out = new JsonObject();
        out.add("p", vec(entity.position()));
        out.addProperty("item", BuiltInRegistries.ITEM.getKey(entity.getItem().getItem()).toString());
        out.addProperty("n", entity.getItem().getCount());
        return out;
    }

    private static JsonObject item(ItemStack stack) {
        JsonObject out = new JsonObject();
        out.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        out.addProperty("n", stack.getCount());
        return out;
    }

    private static JsonObject frame(HostFrame frame) {
        JsonObject out = new JsonObject();
        out.add("origin", vec(frame.origin()));
        out.addProperty("unit", frame.unit());
        out.addProperty("yaw", frame.yawRad());
        out.addProperty("roll", frame.rollDeg());
        out.addProperty("pitch", frame.pitchDeg());
        return out;
    }

    private static JsonArray vec(Vec3 value) { return vec(value.x, value.y, value.z); }

    private static JsonArray block(BlockPos value) {
        var out = new JsonArray();
        out.add(value.getX());
        out.add(value.getY());
        out.add(value.getZ());
        return out;
    }

    private static JsonArray vec(double x, double y, double z) {
        var out = new JsonArray();
        out.add(x);
        out.add(y);
        out.add(z);
        return out;
    }

    private static JsonArray vec(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        var out = new JsonArray();
        out.add(minX);
        out.add(minY);
        out.add(minZ);
        out.add(maxX);
        out.add(maxY);
        out.add(maxZ);
        return out;
    }

    public static void event(Entity source, String category, String message, int suppressed) {
        if (!recorded(source)) return;
        JsonObject body = new JsonObject();
        body.addProperty("cat", category);
        body.addProperty("msg", message);
        body.add("pos", block(source.blockPosition()));
        if (suppressed > 0) body.addProperty("sup", suppressed);
        emit(source, windowFor(source), "ev", body);
    }

    public static void world(ServerLevel level, String category, String message, int suppressed) {
        SceneWindow window = currentWindow(level);
        // SceneGeometry emits its dedicated geo row; duplicating the human-readable MCYGEOMETRY payload as ev breaks its reader.
        if (category.equals("layout") && message.startsWith("MCYGEOMETRY")) return;
        if (mode == Mode.OFF || window == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("cat", category);
        body.addProperty("msg", message);
        body.add("pos", JsonNull.INSTANCE);
        if (suppressed > 0) body.addProperty("sup", suppressed);
        emit(level, window, null, "ev", body, level.getGameTime());
    }

    static void changed(YukkuriEntity entity, String key, String before, String after) {
        if (!recorded(entity)) return;
        JsonObject body = new JsonObject();
        body.addProperty("key", key);
        body.addProperty("a", before);
        body.addProperty("b", after);
        emit(entity, windowFor(entity), "ch", body);
    }

    static void relationChanged(UUID from, UUID to, int before, int after) {
        if (mode == Mode.DENSE) MCYukkuri.LOGGER.info("[status] relation {} -> {}: {} -> {}", from, to, before, after);
    }

    /** Geometry rows retain their existing payload and gain the same envelope as other records. */
    public static void geometry(ServerLevel level, JsonObject row) {
        SceneWindow window = currentWindow(level);
        if (mode == Mode.OFF || window == null) return;
        String uuid = row.has("uuid") ? row.get("uuid").getAsString() : null;
        emit(level, window, uuid, "geo", row, level.getGameTime());
    }

    private static void emit(Entity entity, SceneWindow window, String kind, JsonObject body) {
        emit(entity, window, kind, body, entity.level().getGameTime());
    }

    private static void emit(Entity entity, SceneWindow window, String kind, JsonObject body, long tick) {
        emit((ServerLevel) entity.level(), window, entity.getUUID().toString(), kind, body, tick);
    }

    private static void emit(ServerLevel level, SceneWindow window, String uuid, String kind, JsonObject body, long tick) {
        if (mode == Mode.OFF) return;
        JsonObject envelope = new JsonObject();
        envelope.addProperty("v", SCHEMA);
        long seq;
        synchronized (LOG_LOCK) {
            envelope.addProperty("run", run);
            seq = ++sequence;
        }
        envelope.addProperty("seq", seq);
        envelope.addProperty("t", tick);
        envelope.addProperty("dim", level.dimension().location().toString());
        envelope.addProperty("win", window == null ? null : window.scene());
        envelope.addProperty("u", uuid);
        envelope.addProperty("k", kind);
        envelope.addProperty("src", "server");
        if (kind.equals("st")) envelope.add("st", body); // The state velocity key v cannot replace the envelope's schema v.
        else for (String key : body.keySet()) envelope.add(key, body.get(key));
        String line = GSON.toJson(envelope);
        MCYukkuri.LOGGER.info("[trace] {}", line);
        modeBytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
    }

    private TraceRecorder() {}
}
