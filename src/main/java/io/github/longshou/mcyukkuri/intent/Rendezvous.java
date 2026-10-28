package io.github.longshou.mcyukkuri.intent;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.action.Await;
import net.minecraft.world.phys.Vec3;

/**
 * The meeting of two bodies on one deadline (spec 14 WP3b): each gets a {@link GoTo} to its own stand and yaw, around the
 * other, and waits there for the partner (walking back first if pushed off). Satisfied when both stand at their stands,
 * turned to their yaws, and see each other; the domain chose the stands. The pair is a value built when the beat starts;
 * nothing is stored on either body.
 */
public final class Rendezvous {
    /** Where the two bodies stand and which way each faces. */
    public record Site(Vec3 a, float yawA, Vec3 b, float yawB) {}

    private final YukkuriEntity a;
    private final YukkuriEntity b;
    private final Site site;
    private final long deadline;

    public Rendezvous(YukkuriEntity a, YukkuriEntity b, Site site, long deadline) {
        this.a = a;
        this.b = b;
        this.site = site;
        this.deadline = deadline;
    }

    public Site site() { return site; }

    public Intent forA() { return side(a, b, site.a(), site.yawA()); }

    public Intent forB() { return side(b, a, site.b(), site.yawB()); }

    public boolean satisfied() {
        return GoTo.at(a, site.a(), site.yawA()) && GoTo.at(b, site.b(), site.yawB()) && a.hasLineOfSight(b) && b.hasLineOfSight(a);
    }

    private Intent side(YukkuriEntity self, YukkuriEntity other, Vec3 stand, float yaw) {
        return new GoTo("rendezvous", self, stand, deadline).around(other).facing(yaw).until(this::satisfied)
                .then(() -> new Await(() -> satisfied() || !GoTo.at(self, stand, yaw), (int) Math.max(1, deadline - self.level().getGameTime()), ""));
    }
}
