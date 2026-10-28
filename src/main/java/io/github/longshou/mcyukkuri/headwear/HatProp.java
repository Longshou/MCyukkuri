package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Prop;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * A Marisa's own hat as the motor's {@link Prop} (spec 9.4): PlaceProp sets it down as a {@link PlacedHatEntity} and
 * TakeProp puts it back on. Every read comes from the single owners (her {@code HatCustody}, the placed hat and its riders)
 * and the two irreversible moves are {@link PlacedHatEntity#setDown} and {@link PlacedHatEntity#retrieve}; nothing is kept here.
 */
public final class HatProp implements Prop {
    private final YukkuriEntity owner;
    /** Placed by the owner before her own outlet (the self clips and role) rather than by a helper beside it. */
    private final boolean self;

    public HatProp(YukkuriEntity owner, boolean self) {
        this.owner = owner;
        this.self = self;
    }

    /** A Marisa who wears her hat, or has it set down: the only bodies that catch with one (a kotatsu or a shell is no hat). */
    public static boolean usable(YukkuriEntity y) {
        return y.getVariant() == YukkuriVariant.MARISA && (y.life().hasAccessory() || PlacedHatEntity.find(y) != null)
                && y.subspecies().kind() != SubspeciesLife.Kind.SHELL_MARISA
                && y.subspecies().kind() != SubspeciesLife.Kind.KOTATSU_MARISA;
    }

    @Override public String id() { return "hat"; }

    @Override public boolean worn() { return owner.life().custody().worn(); }

    @Override public boolean deployed() { return entity() != null; }

    @Override public boolean riders() { return entity() instanceof PlacedHatEntity hat && !hat.getPassengers().isEmpty(); }

    @Override public boolean empty() { return deployed() && !riders(); }

    @Override public Entity entity() { return PlacedHatEntity.find(owner); }

    /** A dry spot on a sturdy floor: what {@link PlacedHatEntity#setDown} enforces at the release marker too. */
    @Override public Feasibility fits(Vec3 at, float yaw) {
        if (owner.getVariant() != YukkuriVariant.MARISA) return Feasibility.of(Reason.NO_TOOL, 0, "not_marisa");
        if (owner.isPassenger()) return Feasibility.of(Reason.NO_ROOM, 0, "passenger");
        if (!worn() || deployed()) return Feasibility.of(Reason.NO_TOOL, 0, "hat_not_worn");
        var level = owner.level();
        var spot = BlockPos.containing(at);
        if (!level.hasChunkAt(spot)) return Feasibility.of(Reason.NO_ROOM, 0, "chunk_unloaded");
        if (!level.getFluidState(spot).isEmpty()) return Feasibility.of(Reason.NO_ROOM, 0, "spot_in_fluid");
        if (!level.getBlockState(spot.below()).isFaceSturdy(level, spot.below(), Direction.UP))
            return Feasibility.of(Reason.NO_ROOM, 0, "no_sturdy_floor");
        return Feasibility.pass();
    }

    @Override public Entity setDown(Vec3 at, float yaw) {
        return PlacedHatEntity.setDown(owner, at, yaw, self ? HatGeometry.Role.SELF : HatGeometry.Role.HELPER);
    }

    @Override public boolean takeBack() { return entity() instanceof PlacedHatEntity hat && hat.retrieve(owner); }

    @Override public String clip(boolean on) {
        if (on) return self ? Behaviors.HAT_ON_SELF : Behaviors.HAT_ON;
        return self ? Behaviors.HAT_OFF_SELF : Behaviors.HAT_OFF;
    }
}
