package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.BirthCabin;
import io.github.longshou.mcyukkuri.family.BirthLaunch;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.GrowthSize;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.headwear.HatScale;
import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.headwear.HullSurface;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.geom.BirthFlight;
import io.github.longshou.mcyukkuri.motor.geom.BodyFootprint;
import io.github.longshou.mcyukkuri.motor.geom.Disc;
import io.github.longshou.mcyukkuri.motor.geom.FloorSearch;
import io.github.longshou.mcyukkuri.motor.geom.PlaceGeometry;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import io.github.longshou.mcyukkuri.motor.surface.Floor;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.StatusEvents;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** The captain's measured outlet and newborn floor space, without a berth or a seating operation. */
record HatBirth(PlacedHatEntity hat, YukkuriEntity mother) implements BirthCabin {
    @Override public String clip() { return HullBounds.DATA.string("hat_birth.clip"); }
    @Override public boolean ready() {
        return AquaticLife.afloat(hat) && mother.getUUID().equals(hat.ownerYukkuri())
                && mother.getVehicle() == hat && Floor.standing(mother) && HullSurface.captainAtRest(hat,mother) && !mother.isVehicle();
    }
    @Override public double underside() {
        return (mother.family().laboring()?HullBounds.birthUnderside(mother.family().liveBelly()):HullBounds.HOME.y()) / HullBounds.UNIT;
    }
    private FloorSearch.Placed maternal(float belly) {
        var size = LiveBirthGeometry.bodyDimensions(mother, mother.growthScale(), belly).scale(mother.getScale());
        var at=hat.riders().local(mother.getUUID());
        return new FloorSearch.Placed(BodyFootprint.envelope(mother.assetId(), size.width() / hat.unit(), size.height() / hat.unit(), 0),
                new Vec(at.x,HullBounds.birthUnderside(belly)/HullBounds.UNIT,at.z));
    }
    private BodyFootprint shape(FamilyData.Child child) {
        var size = LiveBirthGeometry.childDimensions(child, mother.getScale());
        return BodyFootprint.envelope(SubspeciesLife.assetId(child.variant, child.subspeciesFlags),
                size.width() / hat.unit(), size.height() / hat.unit(), 0);
    }
    private Vec spot(FamilyData.Brood brood, int index) {
        var p = HullBounds.birthSpots(brood.children.size()).get(index).scale(1 / HullBounds.UNIT);
        return new Vec(p.x(), hat.profile().support(p.x(), p.z(), shape(brood.children.get(index))), p.z());
    }
    private Disc headwear(FamilyData.Child child, Vec at) {
        double growth = GrowthSize.physicalBirth(FamilyData.BirthKind.LIVE, child) * mother.getScale();
        double size = HatScale.of(GrowthSize.physicalBirth(FamilyData.BirthKind.LIVE, child), child.subspeciesFlags) * mother.getScale();
        double y = at.y() + HatGeometry.anchor().y * growth / 16 / hat.unit();
        return new Disc(at.x(), at.z(), HullBounds.RADIUS * size / HullBounds.SCALE / hat.unit(), y,
                y + .3 * growth / 16 / hat.unit());
    }
    private Disc headwear(YukkuriEntity rider) {
        var at=hat.riders().local(rider.getUUID());
        double growth=rider.growthScale()*rider.getScale(),y=at.y+HatGeometry.anchor().y*growth/16/hat.unit();
        return new Disc(at.x,at.z,ParentCarry.headwearRadius(rider)/hat.unit(),y,y+.3*growth/16/hat.unit());
    }
    @Override public boolean fits(FamilyData.Brood brood) {
        int count = brood.children.size();
        var sizes = brood.children.stream().map(c -> GrowthSize.physicalBirth(brood.kind, c)).toList();
        float limit = count <= 2 ? (float) HullBounds.birthLimit(count) : 0;
        String reason = !ready() ? "not_own_captain" : count > 2 ? "brood_count"
                : sizes.stream().anyMatch(size -> size > limit) ? "birth_size" : "fit";
        if (reason.equals("fit")) for (var rider : hat.getPassengers()) if (rider != mother) {
            var at = hat.riders().local(rider.getUUID());
            var full = maternal(1);
            if (hat.profile().inOpening(at.x,at.z)) { reason = "basin_occupied"; break; }
            if (!FloorSearch.clear(Floor.shape(hat, (YukkuriEntity) rider), Floor.vec(at), List.of(full), hat.roomGap())
                    || full.body().overlaps(full.at().x(), full.at().y(), full.at().z(), headwear((YukkuriEntity) rider), hat.roomGap())) {
                reason = "full_term_envelope"; break;
            }
        }
        if (reason.equals("fit")) for (int i = 0; i < count; i++) if (!clear(brood, i)) { reason = "spot_occupied"; break; }
        GameplayLog.event(mother, "aquatic", "birth route={} reason={} brood={} count={} growth={} growth_max={} basin={} head={} exit_marker={} land_marker={}",
                reason.equals("fit") ? "own_hat" : "shore", reason, brood.id, count, sizes, limit,
                hat.getPassengers().stream().filter(p -> p != mother).filter(p->{var at=hat.riders().local(p.getUUID());return hat.profile().inOpening(at.x,at.z);}).count(),
                mother.getPassengers().size(), ClipMarkers.of(clip()).markers().get("exit"), ClipMarkers.of(clip()).markers().get("land"));
        return reason.equals("fit");
    }
    @Override public boolean clear(FamilyData.Brood brood, int index) {
        var child = brood.children.get(index);
        var at = spot(brood, index);
        var others = new ArrayList<>(Floor.others(hat, mother));
        others.add(maternal(LiveBirthGeometry.belly(1, index + 1, brood.children.size())));
        var hats = new ArrayList<>(hat.getPassengers().stream().filter(p->p!=mother).map(p->headwear((YukkuriEntity)p)).toList());
        for (int i = 0; i < index; i++) {
            var earlier = brood.children.get(i);
            if (!earlier.born) others.add(new FloorSearch.Placed(shape(earlier), spot(brood, i)));
            if (!earlier.born) hats.add(headwear(earlier, spot(brood, i)));
        }
        var disc = headwear(child, at);
        return Double.isFinite(at.y()) && FloorSearch.clear(shape(child), at, others, hat.roomGap())
                && PlaceGeometry.cabin(disc, HullBounds.innerRadius(disc.bottom() * HullBounds.UNIT) / HullBounds.UNIT,
                        others, hats, .3 / HullBounds.SCALE);
    }
    @Override public Vec3 exit(int index) {
        var at = hat.riders().local(mother.getUUID());
        return hat.frame().toWorld(at.add(LiveBirthGeometry.cabinExit(mother, index).scale(mother.getScale() / hat.unit())));
    }
    /** The ordinary impulse from her outlet into the hollow. It is never withheld: a newborn may miss the cloth and land in the water, exactly
     *  like an uncaught land birth (the landing records {@code outcome=WATER}), and the shared rescue takes it from there. */
    @Override public BirthLaunch.Nominal launch(FamilyData.Brood brood, int index) {
        var start = exit(index);
        var local = spot(brood, index);
        var child = brood.children.get(index);
        var size = LiveBirthGeometry.childDimensions(child, mother.getScale());
        var body = BodyFootprint.of(SubspeciesLife.assetId(child.variant, child.subspeciesFlags),
                size.width() / hat.unit(), size.height() / hat.unit(), Math.toRadians(mother.yBodyRot - hat.getYRot()));
        var floor = new Vec3(local.x(), hat.profile().support(local.x(), local.z(), body), local.z());
        var plan = BirthFlight.drop(Floor.vec(start), tick -> Floor.vec(hat.frameAfter(tick).toWorld(floor)));
        if (plan == null) return null;
        if (StatusEvents.trace()) GameplayLog.event(mother, "family",
                "hat birth geometry index={} frame={} drift={} startLocal={} targetLocal={} ticks={} velocity={}",
                index, hat.frame(), hat.getDeltaMovement(), hat.frame().toLocal(start), floor, plan.ticks(), plan.velocity());
        return new BirthLaunch.Nominal(Floor.point(plan.aim()), Floor.point(plan.velocity()), plan.ticks());
    }
}
