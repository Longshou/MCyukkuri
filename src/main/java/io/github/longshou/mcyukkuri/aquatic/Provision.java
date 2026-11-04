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
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Food on the water (补给; AQUATIC: 帽内家庭按正常进食结算，缺粮时先寻找可达漂浮食物和岸缘食源):
 * <ul>
 *   <li>{@code eat_aboard}: a hungry body riding a hat afloat reaches down in place to take stored food
 *       with the mouth ({@code Pick} from the hat's hold) into its own meal and chews it where it stands ({@code boat_eat}). Done
 *       once it has swallowed.</li>
 *   <li>{@code forage}: an owner aboard with her stick paddles over the loaded calm water to a food item afloat or lying at the
 *       water's edge, walks within reach and picks it up: hungry she eats it, else it goes into the hollow (the hold's own
 *       store, which refuses with its real reason, and then the item stays where it lay). Done once taken.</li>
 *   <li>{@code fetch_stick}: the same trip for a stick afloat or lying at the water's edge when she has none (never had one, or
 *       lost it at a capsize: one state); it goes into the hat's kit. Done once stowed.</li>
 * </ul>
 */
final class Provision implements Intent {
    /** The longest meal aboard, forage trip and chewing wait (ticks); blocks from the food's water a paddled hat counts as there. */
    private static final int EAT = 600, FORAGE = 1600, CHEW = 80;
    private static final double ARRIVE = .6;

    private final YukkuriEntity body;
    private final ItemEntity food;
    private final boolean stick;
    private final long started;
    private boolean taken;

    private Provision(YukkuriEntity body, ItemEntity food, boolean stick) {
        this.body = body;
        this.food = food;
        this.stick = stick;
        this.started = body.level().getGameTime();
    }

    static Provision eat(YukkuriEntity body) { return new Provision(body, null, false); }
    static Provision forage(YukkuriEntity owner, ItemEntity food) { return new Provision(owner, food, false); }
    static Provision stick(YukkuriEntity owner, ItemEntity stick) {
        GameplayLog.event(owner, "aquatic", "stick target chosen item={} at={} afloat={}", stick.getUUID(), GameplayLog.at(stick.position()), stick.isInWater());
        return new Provision(owner, stick, true);
    }

    @Override public String id() { return stick ? "fetch_stick" : food == null ? "eat_aboard" : "forage"; }
    @Override public long deadline() { return started + (food == null ? EAT : FORAGE); }
    @Override public boolean satisfied() { return taken && (food != null || body.life().mealTicks() == 0); }
    @Override public List<Method> methods() { return List.of(Plan.of(id(), this::derive)); }

    private static Step act(Action action) { return new Step.Do(action); }

    private List<Supplier<Step>> derive() {
        var hat = AquaticLife.riding(body);
        if (hat == null) return null;
        if (food != null) return fetch(hat);
        if (taken) return List.of(() -> new Step.Wait(() -> body.life().mealTicks() == 0, CHEW, Behaviors.BOAT_EAT));
        var larder = HullLarder.of(hat);
        var portion = larder == null ? null : larder.source(hat, body, body.life()::acceptsFood, "eat");
        return portion == null ? null : reach(new Pick(portion, this::eatIt));
    }

    /** Paddle the hat to the food's water, then reach for it from the hat. Gone, the pick ends the intent ({@code TARGET_GONE}). */
    private List<Supplier<Step>> fetch(PlacedHatEntity hat) {
        var pick = new Pick(food, this::keep);
        var water = food.isAlive() ? Waters.foodCell(body.level(), food) : null;
        if (water == null || pick.check(body).ok()) return reachLoose(hat, pick);
        if (Waters.surface(body.level(), water).subtract(hat.position()).horizontalDistance() <= ARRIVE) return reachLoose(hat, pick);
        var route = Waters.route(hat, water);
        Waters.log(hat, body, water, route, "forage");
        return route.isEmpty() ? null : List.of(() -> act(new Paddle(Waters.leg(route), ARRIVE)));
    }

    /** Approach a loose item on the hat's surface before the mouth takes it. */
    private List<Supplier<Step>> reachLoose(PlacedHatEntity hat, Pick pick) {
        return List.of(() -> act(new Face(SurfaceApproach.yaw(body.position(), food.position()))),
                () -> act(pick));
    }

    /** Stored food already exposes its mouth-reachable contact; taking it does not turn the seated body. */
    static List<Supplier<Step>> reach(Action action) {
        return List.of(() -> act(action));
    }

    private boolean eatIt(ItemStack one) {
        if (!body.life().beginMeal(one, null, false)) return false;
        taken = true;
        GameplayLog.event(body, "aquatic", "eat_aboard item={} nutrition={}", one.getItem(), Math.round(body.life().nutrition()));
        return true;
    }

    /** Hungry, eat it; else into the hollow of the hat she rides. A stick goes into the hat's kit. */
    private boolean keep(ItemStack one) {
        if (stick) {
            taken = AquaticLife.stow(body, one);
            if (taken) GameplayLog.event(body, "aquatic", "stick taken item={} stowed_in=hat", one.getItem());
            return taken;
        }
        var hat = AquaticLife.riding(body);
        var larder = HullLarder.of(hat);
        boolean ate = body.life().canEat() && body.life().beginMeal(one, null, false);
        taken = ate || larder != null && larder.keep(hat, one, body);
        if (taken) GameplayLog.event(body, "aquatic", "forage taken item={} {} nutrition={}", one.getItem(), ate ? "eaten" : "stored",
                Math.round(body.life().nutrition()));
        return taken;
    }
}
