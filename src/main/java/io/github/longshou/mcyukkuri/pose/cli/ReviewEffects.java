package io.github.longshou.mcyukkuri.pose.cli;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.mind.ExcitedLeak;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import io.github.longshou.mcyukkuri.pose.BodyEffectLook;
import io.github.longshou.mcyukkuri.pose.BodyEffectLook.Kind;
import io.github.longshou.mcyukkuri.pose.EmoteCue;
import io.github.longshou.mcyukkuri.pose.check.PoseChecks;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class ReviewEffects {
    static JsonObject leak(JsonObject request) {
        var stage = PoseChecks.s(request, "stage").toUpperCase(Locale.ROOT);
        int control = Integer.parseInt(PoseChecks.s(request, "selfControl"));
        var stimulus = ExcitedLeak.Stimulus.valueOf(PoseChecks.s(request, "stimulus").toUpperCase(Locale.ROOT));
        long seed = Long.parseLong(PoseChecks.s(request, "seed"));
        float pressure = (float) PoseChecks.n(request, "pressure");
        float width = (float) PoseChecks.n(request, "bodyWidth");
        if (control < 0 || control > 100 || pressure < 0 || pressure > 100 || width <= 0 || !Float.isFinite(width))
            throw new IllegalArgumentException("Expected selfControl and pressure in [0,100] and positive bodyWidth");
        var result = ExcitedLeak.roll(stage, control, stimulus, seed, 0, pressure, width);
        var out = new JsonObject();
        out.addProperty("roll", result.roll());
        out.addProperty("chance", result.chance());
        var beat = result.beat();
        if (beat == null) return out;
        out.add("beat", new Gson().toJsonTree(beat));
        var faces = new JsonArray();
        for (long tick : new long[]{beat.start(), beat.flowAt(), beat.puddleAt()}) faces.add(beat.face(tick));
        out.add("faces", faces);
        out.addProperty("puddleReach", ExcitedLeak.puddleReach(beat.width()));
        out.addProperty("sweatUntil", beat.puddleAt() + EmoteCue.SWEAT_TICKS);
        var opacity = new JsonArray();
        for (int tick = 0; tick <= ExcitedLeak.TRACE_LIFETIME; tick++) opacity.add(ExcitedLeak.opacity(tick));
        out.add("traceOpacity", opacity);
        return out;
    }

    private record Deposit(Vec position, float size, long tick) {}
    private record Flight(float size, List<Vec> path) {}

    static JsonObject floor(JsonObject request) {
        String reason = PoseChecks.s(request, "reason");
        long tick = Long.parseLong(PoseChecks.s(request, "tick"));
        int source = Integer.parseInt(PoseChecks.s(request, "sourceId"));
        float amount = (float) PoseChecks.n(request, "amount"), scale = (float) PoseChecks.n(request, "scale");
        var position = vector(request, "position");
        var direction = vector(request, "direction");
        boolean puddle = reason.equals(ExcitedLeak.PUDDLE) || reason.equals("urine_puddle");
        var kind = puddle ? Kind.PUDDLE : Kind.FILLING;
        var flights = new ArrayList<Flight>();
        var deposits = new ArrayList<Deposit>();
        if (puddle) {
            if (position.y() >= -.01 && position.y() <= 4) deposits.add(new Deposit(onFloor(position), amount, tick));
        } else {
            for (var shard : BodyEffectLook.shards(source, tick, reason, amount, scale, direction, 48)) {
                var at = position;
                var velocity = shard.velocity();
                var path = new ArrayList<Vec>();
                path.add(at);
                for (int age = 1; age <= BodyEffectLook.AIR_TICKS; age++) {
                    var next = at.add(velocity);
                    if (at.y() >= 0 && next.y() <= 0) {
                        var hit = at.add(velocity.scale(at.y() / (at.y() - next.y())));
                        path.add(hit);
                        deposits.add(new Deposit(onFloor(hit), shard.size(), tick + age));
                        break;
                    }
                    path.add(next);
                    at = next;
                    velocity = BodyEffectLook.nextVelocity(velocity);
                    if (age == BodyEffectLook.AIR_TICKS && at.y() >= -.01 && at.y() <= 1.5)
                        deposits.add(new Deposit(onFloor(at), shard.size(), tick + age));
                }
                flights.add(new Flight(shard.size(), path));
            }
        }
        var gson = new Gson();
        var traces = new JsonArray();
        var active = new ArrayList<Deposit>();
        var activeJson = new ArrayList<JsonObject>();
        deposits.sort(Comparator.comparingLong(Deposit::tick));
        for (var deposit : deposits) {
            var at = deposit.position();
            float size = BodyEffectLook.traceSize(deposit.size());
            for (int i = 0; i < active.size(); i++) {
                var earlier = active.get(i);
                if (!BodyEffectLook.touches(earlier.position(), at)) continue;
                at = earlier.position().scale(.5).add(at.scale(.5));
                size = BodyEffectLook.mergedSize(earlier.size(), deposit.size());
                active.remove(i);
                activeJson.remove(i).addProperty("until", deposit.tick());
                break;
            }
            var trace = new JsonObject();
            trace.add("position", gson.toJsonTree(at));
            trace.addProperty("halfX", BodyEffectLook.halfX(size));
            trace.addProperty("halfZ", BodyEffectLook.halfZ(size));
            trace.addProperty("tick", deposit.tick());
            trace.addProperty("until", deposit.tick() + kind.lifetime);
            traces.add(trace);
            active.add(new Deposit(at, size, deposit.tick()));
            activeJson.add(trace);
        }
        var rim = new JsonArray();
        for (int i = 0; i <= 12; i++) rim.add(OfflineModel.array(new float[]{BodyEffectLook.rimX(i), BodyEffectLook.rimZ(i)}));
        var out = new JsonObject();
        out.addProperty("texture", kind.texture);
        out.addProperty("yaw", puddle ? Math.atan2(direction.x(), direction.z()) : 0);
        out.addProperty("lifetime", kind.lifetime);
        out.addProperty("fadeTicks", kind.fadeTicks);
        out.addProperty("tick", tick);
        out.add("rim", rim);
        out.add("flights", gson.toJsonTree(flights));
        out.add("traces", traces);
        return out;
    }

    private static Vec onFloor(Vec v) { return new Vec(v.x(), BodyEffectLook.FLOOR_LIFT, v.z()); }

    private static Vec vector(JsonObject request, String name) {
        var value = PoseChecks.obj(request, name);
        return new Vec(PoseChecks.n(value, "x"), PoseChecks.n(value, "y"), PoseChecks.n(value, "z"));
    }

    private ReviewEffects() {}
}
