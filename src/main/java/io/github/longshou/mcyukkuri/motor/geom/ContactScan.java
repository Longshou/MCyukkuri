package io.github.longshou.mcyukkuri.motor.geom;

import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Neighbours and the last tested footing for one move, through vanilla's collision resolution. */
public final class ContactScan<T> implements Consumer<T> {
    @FunctionalInterface
    public interface Footing { boolean allows(double x, double y, double z); }

    public final ArrayList<T> neighbours = new ArrayList<>();
    private final Predicate<T> include;
    private final Footing footing;
    private boolean guarded, tested, allowed;
    private double x, y, z;

    public ContactScan(Predicate<T> include, Footing footing) {
        this.include = include;
        this.footing = footing;
    }

    @Override public void accept(T entity) {
        if (include.test(entity)) neighbours.add(entity);
    }

    public void guard() { guarded = true; }

    public boolean test(double dx, double dy, double dz) {
        if (!tested || dx != x || dy != y || dz != z) {
            x = dx;
            y = dy;
            z = dz;
            allowed = footing.allows(x, y, z);
            tested = true;
        }
        return allowed;
    }

    public boolean permitsResolved(double dx, double dy, double dz) {
        return !guarded || dx == 0 && dz == 0 || test(dx, dy, dz);
    }

    public void clear() {
        neighbours.clear();
        guarded = tested = false;
    }
}
