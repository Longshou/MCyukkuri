package io.github.longshou.mcyukkuri.motor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/**
 * Where the one stack a body holds (her hat's kit) physically is: the single record the server writes and the client draws
 * (project design, user 2026-11-06: an item never vanishes or appears; the braid has one authority). {@link Place#GRIP} is her own
 * hold, a Marisa's braid tuft or the mouth of a body without a braid; {@link Place#MOUTH} a Marisa's mouth while her braid works;
 * {@link Place#HAT} lying in her own set-down hat at {@code spot} (hat-local units) with its short end toward {@code dir};
 * {@link Place#GROUND} only names where a beat lays it down as a real dropped item. While a {@link Beat} moves it, the stack
 * leaves {@code from} at the beat's handover marker and is in {@code to} from then on.
 */
public record Held(Place place, int hat, Vec3 spot, Vec3 dir, Beat beat) {
    public enum Place { GRIP, MOUTH, HAT, GROUND }

    /** One visible move, by the braid or ({@code mouth}) by the lips: it reaches the place {@code handover} ticks after {@code start}
     *  and is back at rest by {@code length}; {@code ground} is the drop point of a move to the ground. */
    public record Beat(Place from, Place to, long start, int handover, int length, boolean mouth, Vec3 ground) {
        /** Where the stack is at {@code tick}: the source until the handover, the destination from it. */
        public Place at(double tick) { return tick - start < handover ? from : to; }
    }

    public static final Held NONE = new Held(Place.GRIP, -1, null, null, null);

    /** Where it is at {@code tick}, the running beat included. */
    public Place at(double tick) { return beat == null ? place : beat.at(tick); }

    public CompoundTag write() {
        var tag = new CompoundTag();
        if (place != Place.GRIP) tag.putByte("P", (byte) place.ordinal());
        if (hat >= 0 && spot != null && dir != null) {
            tag.putInt("H", hat);
            tag.putFloat("X", (float) spot.x); tag.putFloat("Y", (float) spot.y); tag.putFloat("Z", (float) spot.z);
            tag.putFloat("DX", (float) dir.x); tag.putFloat("DZ", (float) dir.z);
        }
        if (beat != null) {
            var b = new CompoundTag();
            b.putByte("F", (byte) beat.from.ordinal()); b.putByte("T", (byte) beat.to.ordinal());
            b.putLong("S", beat.start); b.putInt("A", beat.handover); b.putInt("L", beat.length); if (beat.mouth) b.putBoolean("M", true);
            if (beat.ground != null) { b.putDouble("GX", beat.ground.x); b.putDouble("GY", beat.ground.y); b.putDouble("GZ", beat.ground.z); }
            tag.put("B", b);
        }
        return tag;
    }

    public static Held read(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) return NONE;
        var places = Place.values();
        Place place = places[Math.clamp(tag.getByte("P"), 0, places.length - 1)];
        boolean spotted = tag.contains("H");
        Beat beat = null;
        if (tag.contains("B")) {
            var b = tag.getCompound("B");
            beat = new Beat(places[Math.clamp(b.getByte("F"), 0, places.length - 1)], places[Math.clamp(b.getByte("T"), 0, places.length - 1)],
                    b.getLong("S"), b.getInt("A"), b.getInt("L"), b.getBoolean("M"),
                    b.contains("GX") ? new Vec3(b.getDouble("GX"), b.getDouble("GY"), b.getDouble("GZ")) : null);
        }
        return new Held(place, spotted ? tag.getInt("H") : -1, spotted ? new Vec3(tag.getFloat("X"), tag.getFloat("Y"), tag.getFloat("Z")) : null,
                spotted ? new Vec3(tag.getFloat("DX"), 0, tag.getFloat("DZ")) : null, beat);
    }
}
