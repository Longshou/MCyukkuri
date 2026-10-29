package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Paddle;
import io.github.longshou.mcyukkuri.motor.action.Pick;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Food on the water (refactor WP6b; design 8.3 补给; AQUATIC: 帽内家庭按正常进食结算，缺粮时先寻找可达漂浮食物和岸缘食源):
 * <ul>
 *   <li>{@code eat_aboard}: a hungry body riding a hat afloat walks on it within mouth reach of a stored portion it would eat, takes
 *       it with the mouth ({@code Pick} from the hat's hold) into its own meal and chews it where it stands ({@code boat_eat}). Done
 *       once it has swallowed.</li>
 *   <li>{@code forage}: an owner aboard with her stick paddles over the loaded calm water to a food item afloat or lying at the
 *       water's edge, walks on the hat within reach and picks it up: hungry she eats it, else it goes into the hollow (the hold's own
 *       store, which refuses with its real reason, and then the item stays where it lay). Done once taken.</li>
 * </ul>
 */
final class Provision implements Intent {
    /** The longest meal aboard, forage trip and chewing wait (ticks); blocks from the food's water a paddled hat counts as there. */
    private static final int EAT = 600, FORAGE = 1600, CHEW = 80;
    private static final double ARRIVE = .6;

    private final YukkuriEntity body;
    private final ItemEntity food;
    private final long started;
    private boolean taken;

    private Provision(YukkuriEntity body, ItemEntity food) {
        this.body = body;
        this.food = food;
        this.started = body.level().getGameTime();
    }

    static Provision eat(YukkuriEntity body) { return new Provision(body, null); }
    static Provision forage(YukkuriEntity owner, ItemEntity food) { return new Provision(owner, food); }

    @Override public String id() { return food == null ? "eat_aboard" : "forage"; }
    @Override public long deadline() { return started + (food == null ? EAT : FORAGE); }
    @Override public boolean satisfied() { return taken && (food != null || body.life().mealTicks() == 0); }
    /** A pick that failed (the portion or the item moved off) is tried once more from where things are then. */
    @Override public List<Method> methods() { return List.of(Plan.of(id(), this::derive), Plan.of(id() + "_again", this::derive)); }

    private static Step act(Action action) { return new Step.Do(action); }

    private List<Supplier<Step>> derive() {
        var hat = AquaticLife.riding(body);
        if (hat == null) return null;
        if (food != null) return fetch(hat);
        if (taken) return List.of(() -> new Step.Wait(() -> body.life().mealTicks() == 0, CHEW, Behaviors.BOAT_EAT));
        var larder = HullLarder.of(hat);
        var portion = larder == null ? null : larder.portion(hat, body.life()::acceptsFood, body.position(), false);
        return portion == null ? null : reach(body, hat, portion.at(), new Pick(portion, this::eatIt));
    }

    /** Paddle the hat to the food's water, then reach for it from the hat. Gone, the pick ends the intent ({@code TARGET_GONE}). */
    private List<Supplier<Step>> fetch(PlacedHatEntity hat) {
        var pick = new Pick(food, this::keep);
        var water = food.isAlive() ? Waters.foodCell(body.level(), food) : null;
        if (water == null || pick.check(body).ok()) return reach(body, hat, food.position(), pick);
        if (Waters.surface(body.level(), water).subtract(hat.position()).horizontalDistance() <= ARRIVE) return reach(body, hat, food.position(), pick);
        var route = AquaticLife.stick(body) ? Waters.route(hat, water) : List.<Vec3>of();
        Waters.log(hat, body, water, route, "forage");
        return route.isEmpty() ? null : List.of(() -> act(new Paddle(Waters.leg(route), ARRIVE)));
    }

    /** Walk on the hat to the spot nearest {@code at} (unless already in reach), face it, then {@code action}. */
    static List<Supplier<Step>> reach(YukkuriEntity body, PlacedHatEntity hat, Vec3 at, Action action) {
        return List.of(() -> {
                    if (action.check(body).ok()) return null;
                    var spot = AquaticLife.spot(hat, body, false, hat.frame().toLocal(at));
                    var here = hat.frame().toLocal(body.position());
                    return spot == null || spot.subtract(here).horizontalDistance() < .1 ? null : act(new Walk(hat, spot));
                },
                () -> act(new Face(SurfaceApproach.yaw(body.position(), at))),
                () -> act(action));
    }

    private boolean eatIt(ItemStack one) {
        if (!body.life().beginMeal(one, null, false)) return false;
        taken = true;
        GameplayLog.event(body, "aquatic", "eat_aboard item={} nutrition={}", one.getItem(), Math.round(body.life().nutrition()));
        return true;
    }

    /** Hungry, eat it; else into the hollow of the hat she rides. */
    private boolean keep(ItemStack one) {
        var hat = AquaticLife.riding(body);
        var larder = HullLarder.of(hat);
        boolean ate = body.life().canEat() && body.life().beginMeal(one, null, false);
        taken = ate || larder != null && larder.keep(hat, one, body);
        if (taken) GameplayLog.event(body, "aquatic", "forage taken item={} {} nutrition={}", one.getItem(), ate ? "eaten" : "stored",
                Math.round(body.life().nutrition()));
        return taken;
    }
}
