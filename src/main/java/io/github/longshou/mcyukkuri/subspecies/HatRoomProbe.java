package io.github.longshou.mcyukkuri.subspecies;

/** Diagnostic: why a hat boat reports no room for a newborn (family/FamilyLife logs this while a water birth is held up). */
public final class HatRoomProbe {
    private HatRoomProbe() {}

    public static String explain(HatBoatEntity boat) {
        return "afloat=" + boat.isAfloat() + " state=" + boat.state() + " catches=" + boat.catches() + " young=" + boat.youngAboard()
                + " incoming=" + boat.hasIncoming() + " room=" + boat.canAdmitNewborn() + " | " + boat.newbornRoomDebug();
    }
}
