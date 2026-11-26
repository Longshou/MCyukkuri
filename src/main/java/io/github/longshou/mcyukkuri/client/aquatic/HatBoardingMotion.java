package io.github.longshou.mcyukkuri.client.aquatic;

import io.github.longshou.mcyukkuri.client.PlacedHatRenderer;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatView;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Client-side visual response of a floating hat when a yukkuri climbs aboard from the water
 * ({@code water_hat_climb}) or lands on the moored brim/crown from shore: dips into the water,
 * tilts toward the climber/landing edge under her weight, and rocks smoothly to settle.
 * Pure presentation; never alters server state, admission or physics.
 */
public final class HatBoardingMotion {
    private static final String CLIMB_CLIP = "water_hat_climb";
    private static final double CLIMB_DURATION = 1.8;
    private static final double LANDING_DURATION = 1.25;
    private static final double CLIMB_DIP_MAX = 0.065;
    private static final double LANDING_DIP_MAX = 0.055;
    private static final double LANDING_TILT_MAX = 8.5;

    private record Landing(long startTick, double dirX, double dirZ, double dip, double tilt) {}

    private static final Map<Integer, Set<Integer>> KNOWN_RIDERS = new HashMap<>();
    private static final Map<Integer, Landing> LANDINGS = new HashMap<>();

    private HatBoardingMotion() {}

    public static void unload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) return;
        KNOWN_RIDERS.clear();
        LANDINGS.clear();
    }

    public static void tick(ClientTickEvent.Post event) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            KNOWN_RIDERS.clear();
            LANDINGS.clear();
            return;
        }
        long now = level.getGameTime();
        Set<Integer> seen = new HashSet<>();
        for (var entity : level.entitiesForRendering()) {
            if (!(entity instanceof PlacedHatEntity hat) || hat.isRemoved()) continue;
            PlacedHatView view = hat;
            if (!view.profile().seated() || view.upsideDown() || view.state() == PlacedHatEntity.State.RESCUED) continue;
            int hatId = hat.getId();
            seen.add(hatId);
            Set<Integer> previous = KNOWN_RIDERS.get(hatId);
            Set<Integer> current = new HashSet<>();
            for (var passenger : hat.getPassengers()) {
                if (!(passenger instanceof YukkuriEntity y)) continue;
                current.add(y.getId());
                if (previous != null && !previous.contains(y.getId())) {
                    recordLanding(hat, y, now);
                }
            }
            KNOWN_RIDERS.put(hatId, current);
        }
        KNOWN_RIDERS.keySet().retainAll(seen);
        Iterator<Map.Entry<Integer, Landing>> it = LANDINGS.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (!seen.contains(entry.getKey()) || (now - entry.getValue().startTick()) / 20.0 > LANDING_DURATION) {
                it.remove();
            }
        }
    }

    private static void recordLanding(PlacedHatEntity hat, YukkuriEntity rider, long now) {
        YukkuriView view = rider;
        double scale = hat.hullScale() / HullBounds.SCALE;
        double weight = Mth.clamp(view.growthScale() * view.getScale(), 0.25, 1.25);
        double[] local = toHatLocal(hat, 1f, rider.getX() - hat.getX(), rider.getZ() - hat.getZ());
        double dist = Math.hypot(local[0], local[1]);
        double dirX = dist > 1e-4 ? local[0] / dist : 0;
        double dirZ = dist > 1e-4 ? local[1] / dist : -1;
        double radial = Mth.clamp(dist / Math.max(0.1, HullBounds.RADIUS * scale), 0.25, 1.0);
        boolean fromClimb = CLIMB_CLIP.equals(view.getAnimationId());
        double scaleFactor = fromClimb ? 0.7 : 1.0;
        double dip = LANDING_DIP_MAX * weight * scale * scaleFactor;
        double tilt = LANDING_TILT_MAX * weight * radial * scaleFactor;
        LANDINGS.put(hat.getId(), new Landing(now, dirX, dirZ, dip, tilt));
    }

    public static PlacedHatRenderer.VisualOffset sample(PlacedHatView hat, float partial) {
        if (!hat.profile().seated() || hat.upsideDown() || hat.state() == PlacedHatEntity.State.RESCUED) {
            return PlacedHatRenderer.VisualOffset.ZERO;
        }
        var level = hat.level();
        long now = level.getGameTime();
        double dipY = 0;
        double rollDeg = 0;
        double pitchDeg = 0;

        Landing landing = LANDINGS.get(hat.getId());
        if (landing != null) {
            double age = (now - landing.startTick() + partial) / 20.0;
            if (age >= 0 && age <= LANDING_DURATION) {
                double ramp = Mth.clamp(age / 0.06, 0, 1);
                double env = Math.exp(-3.4 * age) * ramp;
                dipY += -landing.dip() * env * Math.cos(2 * Math.PI * 1.35 * age);
                double tilt = landing.tilt() * env * Math.cos(2 * Math.PI * 1.15 * age);
                rollDeg += -tilt * landing.dirX();
                pitchDeg += tilt * landing.dirZ();
            }
        }

        double scale = hat.hullScale() / HullBounds.SCALE;
        double search = HullBounds.RADIUS * scale + 1.4;
        var hatPos = hat.getPosition(partial);
        AABB box = new AABB(hatPos.x - search, hatPos.y - 1.2, hatPos.z - search,
                hatPos.x + search, hatPos.y + 1.5, hatPos.z + search);
        for (var entity : level.getEntitiesOfClass(YukkuriEntity.class, box)) {
            YukkuriView climber = entity;
            if (climber.isPassenger() || !CLIMB_CLIP.equals(climber.getAnimationId())) continue;
            double t = (now - climber.getAnimationStartTick() + partial) / 20.0;
            if (t < 0 || t > CLIMB_DURATION + 0.05) continue;
            var pos = climber.getPosition(partial);
            double[] local = toHatLocal(hat, partial, pos.x - hatPos.x, pos.z - hatPos.z);
            double dist = Math.hypot(local[0], local[1]);
            if (dist > search || dist < 1e-4) continue;
            double dirX = local[0] / dist;
            double dirZ = local[1] / dist;
            double weight = Mth.clamp(climber.growthScale() * climber.getScale(), 0.25, 1.25);
            double tilt = climbTilt(t) * weight;
            double dip = climbDip(t) * weight * scale;
            dipY += dip;
            rollDeg += -tilt * dirX;
            pitchDeg += tilt * dirZ;
        }

        if (Math.abs(dipY) < 1e-5 && Math.abs(rollDeg) < 1e-4 && Math.abs(pitchDeg) < 1e-4) {
            return PlacedHatRenderer.VisualOffset.ZERO;
        }
        return new PlacedHatRenderer.VisualOffset(dipY, (float) Mth.clamp(rollDeg, -14, 14), (float) Mth.clamp(pitchDeg, -14, 14));
    }

    /** Tilt toward the climber (degrees, positive dips the climber's side), synced to water_hat_climb markers. */
    private static double climbTilt(double t) {
        return keyed(t,
                0.00, 0.0,
                0.35, 3.5,   // bite_contact
                0.75, 8.0,   // hook_contact
                0.98, 11.5,  // peak weight as she coils and heaves up
                1.28, -3.0,  // crests over brim -> rebound tilt opposite
                1.55, 1.5,   // pull_end -> drops into crown
                1.80, 0.0);
    }

    /** Vertical dip into the water (blocks, negative is down), synced to water_hat_climb markers. */
    private static double climbDip(double t) {
        return -CLIMB_DIP_MAX * keyed(t,
                0.00, 0.0,
                0.35, 0.30,  // bite_contact
                0.75, 0.72,  // hook_contact
                0.98, 1.00,  // deepest dip under heave
                1.30, 0.22,  // unloading as she crests the brim
                1.52, 0.85,  // drop into the crown
                1.68, -0.12, // gentle buoy rebound
                1.80, 0.0);
    }

    private static double keyed(double t, double... pairs) {
        if (t <= pairs[0]) return pairs[1];
        for (int i = 0; i < pairs.length - 2; i += 2) {
            double a = pairs[i], va = pairs[i + 1], b = pairs[i + 2], vb = pairs[i + 3];
            if (t <= b) {
                double q = Mth.clamp((t - a) / (b - a), 0, 1);
                return va + (vb - va) * q * q * (3 - 2 * q);
            }
        }
        return pairs[pairs.length - 1];
    }

    private static double[] toHatLocal(PlacedHatView hat, float partial, double dx, double dz) {
        double theta = Math.toRadians(180 - Mth.rotLerp(partial, hat.previousYaw(), hat.getYRot()));
        double c = Math.cos(theta), s = Math.sin(theta);
        return new double[]{dx * c - dz * s, dx * s + dz * c};
    }
}
