package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.motor.geom.OccupancyMath;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** A sleeper's square body and the circle swept by its headwear when turning. */
public final class NestLayout {
    public record Place(double x, double z, float width, double reach) {
        Place at(double x, double z) { return new Place(x, z, width, reach); }
        double gap(Place other) {
            double dx=Math.abs(x-other.x), dz=Math.abs(z-other.z);
            return Math.min(Math.max(dx,dz)-(width+other.width)*.5, Math.hypot(dx,dz)-reach-other.reach);
        }
    }
    private NestLayout() {}

    /** Whether the empty bed's floor and the residents' turning clearance can accommodate the group. */
    public static boolean fits(NestResting.Bed bed, List<Place> bodies, double arrival) {
        var places = new LinkedHashMap<UUID, Place>();
        var floor = bed.floor();
        for (var body : bodies) {
            var id = new UUID(0, places.size());
            var place = choose(id, body, bed.levelHalf(), places,
                    p -> OccupancyMath.classify(p.x(), p.z(), 0, p.width() * .5 + arrival, floor, 0, 0, true)
                            == OccupancyMath.Result.INSIDE_SUPPORTED, false, bed.centreFirst(), arrival);
            if (place == null) return false;
            places.put(id, place);
        }
        return true;
    }

    static Place choose(UUID id, Place body, double half, Map<UUID,Place> places, Predicate<Place> supported,
                        boolean sleeping, boolean centre, double arrival) {
        var previous=places.get(id);
        if(previous!=null) {
            var kept=body.at(previous.x,previous.z);
            if(sleeping)return free(id,kept,places,arrival) && supported.test(kept) ? kept : null;
            if ((!centre || !free(id,body,places,arrival) || !supported.test(body))
                    && free(id,kept,places,arrival) && supported.test(kept)) return kept;
        }
        return find(id,body,half,places,supported,centre,arrival);
    }

    private static boolean free(UUID id,Place p,Map<UUID,Place> others,double arrival) {
        return others.entrySet().stream().allMatch(e->e.getKey().equals(id)||p.gap(e.getValue())>=2*arrival);
    }

    private static Place find(UUID id,Place body,double half,Map<UUID,Place> places,Predicate<Place> supported,
                              boolean centre,double arrival) {
        var middle=body.at(0,0);
        if(centre && free(id,middle,places,arrival) && supported.test(middle))return middle;
        double limit=half-body.width*.5-arrival;
        if(limit<0)return null;
        // Include both extremes and the centre. Each grid step is at most one model pixel.
        int steps=Math.max(1,(int)Math.ceil(limit*16));
        Place best=null;
        double score=Double.NEGATIVE_INFINITY;
        for(int i=-steps;i<=steps;i++)for(int j=-steps;j<=steps;j++){
            var p=body.at(i*limit/steps,j*limit/steps);
            double space=Double.POSITIVE_INFINITY;
            for(var e:places.entrySet())if(!e.getKey().equals(id))space=Math.min(space,p.gap(e.getValue()));
            if(space<2*arrival)continue;
            double rank=centre?-Math.hypot(p.x,p.z):Double.isInfinite(space)?Math.hypot(p.x,p.z):space;
            if(rank>score && supported.test(p)){best=p;score=rank;}
        }
        return best;
    }
}
