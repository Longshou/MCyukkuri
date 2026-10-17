package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.interaction.CareAction;
import io.github.longshou.mcyukkuri.life.Sweets;
import io.github.longshou.mcyukkuri.pet.Ability;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.LeashGuard;
import io.github.longshou.mcyukkuri.pet.PetPrivileges;
import io.github.longshou.mcyukkuri.pet.PetRequests;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Everyday pet care rules (docs/PET_SHOP.md section 12): sweets, the habit praise window and manners fading, requests and "ask", the harness, the owner-only
 * lead and walks, the swaddle, ownership changes and saving. Every check is a synchronous rule check on server objects in one flat arena (no client, no
 * frame rate); each logs "[pet_care] PASS|FAIL name :: detail". Shop stock, orders, privileges and resale are in PetShopGameTests.shopEconomy.
 */
@PrefixGameTestTemplate(false)
public final class PetCareGameTests {
    private record Check(String name, boolean pass, String detail) {}
    private static final int X = 2600, Z = 0, WIDTH = 40, DEPTH = 26;
    private static final AABB AREA = new AABB(X - 2, 63, Z - 2, X + WIDTH + 2, 76, Z + DEPTH + 2);
    private final List<Check> checks = new ArrayList<>();
    private ServerLevel level;
    private FakePlayer player, stranger;

    private void check(String name, boolean pass) { check(name, pass, ""); }
    private void check(String name, boolean pass, String detail) {
        checks.add(new Check(name, pass, detail));
        MCYukkuri.LOGGER.info("[pet_care] {} {}{}", pass ? "PASS" : "FAIL", name, detail.isEmpty() ? "" : " :: " + detail);
    }

    @GameTest(templateNamespace = P0Mod.ID, template = "empty", timeoutTicks = 800, batch = "pet_shop")
    public static void petCareRules(GameTestHelper h) {
        var t = new PetCareGameTests(); t.level = h.getLevel();
        t.force(true);
        h.startSequence().thenWaitUntil(() -> GameTestChunks.assertTicking(h, X >> 4, (X + WIDTH) >> 4, Z >> 4, (Z + DEPTH) >> 4))
            .thenExecute(() -> {
                try {
                    t.setup(); t.ordinals(); t.sweets(); t.refusal(); t.habits(); t.decay(); t.requests(); t.ask(); t.harness(); t.walk(); t.swaddle(); t.ownership(); t.saving();
                } catch (Throwable e) { t.check("no exception in the pet care checks", false, e + " at " + where(e)); }
                t.finish(h);
            });
    }

    private static String where(Throwable e) {
        var trace = e.getStackTrace();
        return trace.length == 0 ? "" : trace[0].getClassName().replaceAll(".*\\.", "") + "." + trace[0].getMethodName() + ":" + trace[0].getLineNumber();
    }
    private void force(boolean on) { for (int x = X >> 4; x <= (X + WIDTH) >> 4; x++) for (int z = Z >> 4; z <= (Z + DEPTH) >> 4; z++) GameTestChunks.setForced(level, x, z, on); }
    private void finish(GameTestHelper h) {
        P0Control.write("pet-care", checks);
        level.getEntitiesOfClass(Entity.class, AREA, e -> !(e instanceof net.minecraft.world.entity.player.Player)).forEach(Entity::discard);
        if (player != null) ShopFixtures.release(level, player);
        if (stranger != null) ShopFixtures.release(level, stranger);
        force(false);
        var failed = checks.stream().filter(c -> !c.pass()).toList();
        if (failed.isEmpty()) h.succeed();
        else h.fail("pet_care: " + failed.size() + " of " + checks.size() + " checks failed. " + String.join(" | ", failed.stream().map(c -> c.name() + (c.detail().isEmpty() ? "" : " [" + c.detail() + "]")).toList()));
    }

    private void setup() {
        level.setDayTime(6000); level.updateSkyBrightness(); level.setWeatherParameters(60000, 0, false, false);
        level.getEntitiesOfClass(Entity.class, AREA, e -> !(e instanceof net.minecraft.world.entity.player.Player)).forEach(Entity::discard);
        for (int x = X; x < X + WIDTH; x++) for (int z = Z; z < Z + DEPTH; z++) {
            level.setBlock(new BlockPos(x, 64, z), Blocks.STONE.defaultBlockState(), 2);
            for (int y = 65; y <= 74; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
        player = ShopFixtures.player(level, "PetCareOwner"); stranger = ShopFixtures.player(level, "PetCareStranger");
        player.moveTo(X + 2.5, 65, Z + 2.5, 0, 0); stranger.moveTo(X + 30.5, 65, Z + 20.5, 0, 0);
    }
    /** An adult reimu owned by the player. */
    private YukkuriEntity pet(double x, double z) {
        var y = ShopFixtures.spawn(level, ModEntities.REIMU, X + x, 65, Z + z);
        ShopFixtures.needs(y); ShopFixtures.adult(y);
        HumanCommerce.assign(y, player.getUUID(), "companion");
        return y;
    }
    private static void setNutrition(YukkuriEntity y, float value) { var tag = y.life().save(); tag.putFloat("Nutrition", value); y.life().load(tag); }
    private int count(Item item) { return player.getInventory().items.stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum(); }

    // ------------------------------------------------------------------ enum and tags

    private void ordinals() {
        check("the earlier care actions keep their ordinals", CareAction.RELEASE.ordinal() == 30 && CareAction.HAT_PUT_DOWN_CHILD.ordinal() == 43,
                "RELEASE=" + CareAction.RELEASE.ordinal() + " HAT_PUT_DOWN_CHILD=" + CareAction.HAT_PUT_DOWN_CHILD.ordinal());
        check("ASK, REMOVE_HARNESS and UNWRAP are appended in that order", CareAction.ASK.ordinal() == 44 && CareAction.REMOVE_HARNESS.ordinal() == 45 && CareAction.UNWRAP.ordinal() == 46,
                CareAction.ASK.ordinal() + "/" + CareAction.REMOVE_HARNESS.ordinal() + "/" + CareAction.UNWRAP.ordinal());
        check("candy, pudding and the cookie are sweets and food", Sweets.is(new ItemStack(ModItems.SWEET_CANDY.get())) && Sweets.is(new ItemStack(ModItems.CUSTARD_PUDDING.get()))
                && Sweets.is(new ItemStack(Items.COOKIE)) && new ItemStack(ModItems.SWEET_CANDY.get()).is(YukkuriEntity.FOOD), "tag mcyukkuri:yukkuri_sweets");
        check("plain grain is not a sweet", !Sweets.is(new ItemStack(ModItems.GRAIN_REGULAR.get())));
    }

    // ------------------------------------------------------------------ sweets

    private void sweets() {
        var y = pet(6.5, 6.5); var pet = y.pet(); var life = y.life();
        var candy = new ItemStack(ModItems.SWEET_CANDY.get(), 8);
        setNutrition(y, 90);
        check("a sweet is taken on top of a meal at nutrition 90, plain food is not", life.canTakeSweet(candy) && !life.canEat(), "canTakeSweet=" + life.canTakeSweet(candy) + " canEat=" + life.canEat());
        setNutrition(y, 96);
        check("a completely full pet takes no sweet", !life.canTakeSweet(candy), "nutrition=" + life.nutrition());
        pet.addStress(30); int stress = pet.stress(), spoiled = pet.indulgence(); y.dailyLife().entertained(0);
        var daily = y.dailyLife().save(); daily.putInt("Boredom", 50); y.dailyLife().load(daily);
        String first = Sweets.eaten(y, new ItemStack(ModItems.SWEET_CANDY.get()), player.getUUID());
        check("candy from the owner: stress -4, boredom -8, indulgence +3", pet.stress() == stress - 4 && y.dailyLife().boredom() == 42 && pet.indulgence() == spoiled + 3 && "snack_eat".equals(first),
                "stress " + stress + " -> " + pet.stress() + " boredom=" + y.dailyLife().boredom() + " indulgence " + spoiled + " -> " + pet.indulgence() + " event=" + first);
        stress = pet.stress(); spoiled = pet.indulgence();
        Sweets.eaten(y, new ItemStack(ModItems.CUSTARD_PUDDING.get()), player.getUUID());
        check("pudding: stress -12 and indulgence +5", pet.stress() == Math.max(0, stress - 12) && pet.indulgence() == spoiled + 5, "stress " + stress + " -> " + pet.stress() + " indulgence " + spoiled + " -> " + pet.indulgence());
        Sweets.eaten(y, new ItemStack(Items.COOKIE), player.getUUID());
        spoiled = pet.indulgence();
        String fourth = Sweets.eaten(y, new ItemStack(ModItems.SWEET_CANDY.get()), player.getUUID());
        check("the fourth sweet of a day is greed: indulgence +6 and its own line", pet.care().snacksToday() == 4 && pet.indulgence() == spoiled + 6 && "snack_greedy".equals(fourth),
                "snacks=" + pet.care().snacksToday() + " indulgence " + spoiled + " -> " + pet.indulgence() + " event=" + fourth);
        int snacks = pet.care().snacksToday(); stress = pet.stress(); spoiled = pet.indulgence();
        String other = Sweets.eaten(y, candy.copyWithCount(1), stranger.getUUID());
        check("a sweet from someone else counts for nothing", other == null && pet.care().snacksToday() == snacks && pet.stress() == stress && pet.indulgence() == spoiled, "event=" + other);
        pet.addStress(10); stress = pet.stress();
        Sweets.eaten(y, new ItemStack(ModItems.GRAIN_PREMIUM.get()), null);
        check("premium grain eases stress by 2", pet.stress() == stress - 2, "stress " + stress + " -> " + pet.stress());
        y.discard();

        // through the real interaction
        var eater = pet(10.5, 6.5); setNutrition(eater, 90);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.SWEET_CANDY.get(), 3));
        var result = eater.interact(player, InteractionHand.MAIN_HAND);
        check("right-clicking a sweet feeds it and uses one", result.consumesAction() && player.getMainHandItem().getCount() == (player.getAbilities().instabuild ? 3 : 2) && eater.life().mealTicks() > 0,
                "result=" + result + " held=" + player.getMainHandItem().getCount() + " mealTicks=" + eater.life().mealTicks());
        var full = pet(14.5, 6.5); setNutrition(full, 98);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.SWEET_CANDY.get(), 2));
        var refused = full.interact(player, InteractionHand.MAIN_HAND);
        check("a pet that is completely full refuses and keeps the sweet in the hand", refused.consumesAction() && player.getMainHandItem().getCount() == 2 && full.life().mealTicks() == 0,
                "result=" + refused + " held=" + player.getMainHandItem().getCount() + " mealTicks=" + full.life().mealTicks());
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        eater.discard(); full.discard();
    }

    /** Ordinary food is still only taken when hungry (the old rule stays). */
    private void refusal() {
        var y = pet(18.5, 6.5); setNutrition(y, 90);
        check("plain food is refused at nutrition 90", !y.life().canEat() && !y.life().canTakeSweet(new ItemStack(ModItems.GRAIN_REGULAR.get())), "canEat=" + y.life().canEat());
        y.discard();
    }

    // ------------------------------------------------------------------ habit praise and manners fading

    private void habits() {
        player.moveTo(X + 9.5, 65, Z + 12.5, 0, 0);
        var y = pet(12.5, 12.5); var pet = y.pet(); var care = pet.care();
        pet.setAbility(Ability.MANNERS, 20); pet.setPedigree(50);
        pet.routineCompleted(0);
        check("a routine with the owner watching opens the praise window", care.habitWindow(), "window=" + care.habitWindow());
        care.feedback(player, CareAction.GENTLE);
        check("gentle praise raises manners and closes the window", pet.ability(Ability.MANNERS) > 20 && !care.habitWindow() && care.habitsToday() == 1, "manners=" + pet.ability(Ability.MANNERS) + " habits=" + care.habitsToday());
        pet.routineCompleted(1); int manners = pet.ability(Ability.MANNERS), stress = pet.stress();
        care.feedback(player, CareAction.COLD);
        check("a reprimand teaches nothing and only adds stress", pet.ability(Ability.MANNERS) == manners && pet.stress() == stress + 2 && !care.habitWindow(), "manners=" + pet.ability(Ability.MANNERS) + " stress " + stress + " -> " + pet.stress());
        pet.routineCompleted(2); stress = pet.stress();
        care.feedback(player, CareAction.FLICK);
        check("a flick teaches nothing either", pet.ability(Ability.MANNERS) == manners && pet.stress() == stress + 4 && !care.habitWindow(), "stress " + stress + " -> " + pet.stress());
        pet.routineCompleted(0);
        care.feedback(stranger, CareAction.GENTLE);
        check("only the owner can praise", care.habitWindow() && pet.ability(Ability.MANNERS) == manners, "window=" + care.habitWindow());
        care.feedback(player, CareAction.GENTLE);
        for (int i = 0; i < 8; i++) { pet.routineCompleted(2); care.feedback(player, CareAction.GENTLE); }
        check("at most 6 praised habits count a game day", care.habitsToday() == 6, "habits=" + care.habitsToday());
        pet.routineCompleted(1);
        check("no window opens after the daily cap", !care.habitWindow(), "window=" + care.habitWindow());
        y.discard();
        var far = pet(30.5, 12.5); player.moveTo(X + 2.5, 65, Z + 2.5, 0, 0);
        far.pet().routineCompleted(1);
        check("no window opens when the owner is not near", !far.pet().care().habitWindow(), "distance=" + player.distanceTo(far));
        far.discard();
    }

    private void decay() {
        player.moveTo(X + 6.5, 65, Z + 14.5, 0, 0);
        var y = pet(8.5, 14.5); var pet = y.pet(); var care = pet.care();
        pet.setAbility(Ability.MANNERS, 40);
        var tag = care.save(); tag.putInt("Day", (int) (level.getGameTime() / 24000L) - 1); tag.putInt("IdleDays", 4); care.load(tag);
        care.tick();
        check("the fifth idle day costs one manners point", pet.ability(Ability.MANNERS) == 39 && care.idleDays() == 0, "manners=" + pet.ability(Ability.MANNERS) + " idleDays=" + care.idleDays());
        tag = care.save(); tag.putInt("Day", (int) (level.getGameTime() / 24000L) - 1); tag.putInt("IdleDays", 4); care.load(tag);
        pet.learn(Ability.MANNERS, 2);
        care.tick();
        check("a manners lesson (or praised habit) restarts the clock", pet.ability(Ability.MANNERS) > 39 && care.idleDays() == 1, "manners=" + pet.ability(Ability.MANNERS) + " idleDays=" + care.idleDays());
        y.discard();
    }

    // ------------------------------------------------------------------ requests

    private void requests() {
        player.moveTo(X + 15.5, 65, Z + 18.5, 0, 0);
        var y = pet(18.5, 18.5); var pet = y.pet(); var req = pet.care().requests();
        setNutrition(y, 40);
        y.tickCount = 37; req.tick();
        check("a hungry pet near its owner asks for food", req.pending() == PetRequests.Kind.FOOD && req.today() == 1, "pending=" + req.pending() + " today=" + req.today());
        y.tickCount = 137; req.tick();
        check("only one request is open at a time", req.pending() == PetRequests.Kind.FOOD && req.today() == 1, "today=" + req.today());
        int trust = y.life().personality().trust(player.getUUID()); pet.addStress(10); int stress = pet.stress();
        setNutrition(y, 80); y.tickCount = 3; req.tick();
        check("food answers it: trust up, stress down", req.pending() == null && req.answeredToday() == 1 && y.life().personality().trust(player.getUUID()) > trust && pet.stress() < stress,
                "pending=" + req.pending() + " answered=" + req.answeredToday() + " trust " + trust + " -> " + y.life().personality().trust(player.getUUID()) + " stress " + stress + " -> " + pet.stress());
        setNutrition(y, 40); y.tickCount = 37; req.tick();
        check("no new request within the cooldown of a request", req.pending() == null && req.today() == 1, "pending=" + req.pending());
        y.discard();

        var distant = pet(34.5, 5.5); setNutrition(distant, 40); distant.tickCount = 37; distant.pet().care().requests().tick();
        check("no request when the owner is far away", distant.pet().care().requests().pending() == null, "distance=" + player.distanceTo(distant));
        distant.discard();

        var busy = pet(22.5, 18.5); setNutrition(busy, 40); busy.pet().lock("test", player.getUUID());
        busy.tickCount = 37; busy.pet().care().requests().tick();
        check("no request while the pet is busy with a session", busy.pet().care().requests().pending() == null, "busyWith=" + busy.pet().busyWith());
        busy.pet().unlock("test"); busy.discard();

        var capped = pet(26.5, 18.5); setNutrition(capped, 40);
        var cap = new CompoundTag(); cap.putInt("Today", PetRequests.PER_DAY); cap.putLong("LastAt", Long.MIN_VALUE / 2); capped.pet().care().requests().load(cap);
        capped.tickCount = 37; capped.pet().care().requests().tick();
        check("no request after the daily limit of a pet", capped.pet().care().requests().pending() == null && capped.pet().care().requests().today() == PetRequests.PER_DAY, "today=" + capped.pet().care().requests().today());
        capped.discard();

        var sulker = pet(12.5, 20.5); setNutrition(sulker, 40);
        var tag = new CompoundTag(); tag.putInt("Ignored", PetRequests.SULK_AT - 1); tag.putLong("LastAt", Long.MIN_VALUE / 2); sulker.pet().care().requests().load(tag);
        var sreq = sulker.pet().care().requests();
        sulker.tickCount = 37; sreq.tick();
        boolean opened = sreq.pending() == PetRequests.Kind.FOOD;
        sulker.tickCount = 0; for (int i = 0; i < PetRequests.LIFETIME + 2; i++) sreq.tick();
        check("a request that nobody answers expires and the third ignored one makes the pet sulk", opened && sreq.pending() == null && sreq.sulking() && sreq.ignoredToday() >= PetRequests.SULK_AT,
                "opened=" + opened + " pending=" + sreq.pending() + " sulking=" + sreq.sulking() + " ignored=" + sreq.ignoredToday());
        sulker.discard();
    }

    private void ask() {
        player.moveTo(X + 4.5, 65, Z + 24.5, 0, 0);
        var y = pet(6.5, 24.5); var care = y.pet().care();
        check("a stranger cannot ask someone else's pet", !care.ask(stranger));
        boolean asked = y.operations().perform(player, CareAction.ASK);
        check("asking an unrequesting pet tells its mood", asked, "performed=" + asked);
        check("asking again at once is refused", !y.operations().perform(player, CareAction.ASK) && !care.ask(player), "cooldown");
        y.discard();
        var wants = pet(10.5, 24.5); setNutrition(wants, 40);
        var req = wants.pet().care().requests();
        wants.tickCount = 37; req.tick();
        boolean again = wants.pet().care().ask(player);
        check("asking while it wants something repeats the request", again && req.pending() == PetRequests.Kind.FOOD, "ask=" + again + " pending=" + req.pending());
        wants.discard();
    }

    // ------------------------------------------------------------------ harness and lead

    private int leads() { return count(Items.LEAD); }
    private boolean tryLead(net.minecraft.world.entity.player.Player who, YukkuriEntity target) {
        who.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LEAD));
        var event = new PlayerInteractEvent.EntityInteract(who, InteractionHand.MAIN_HAND, target);
        LeashGuard.interact(event);
        who.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        return !event.isCanceled();
    }

    private void harness() {
        player.moveTo(X + 14.5, 65, Z + 24.5, 0, 0);
        var y = pet(16, 24.5); var care = y.pet().care();
        var stack = new ItemStack(ModItems.PET_HARNESS.get(), 2);
        check("a stranger cannot fit the owner's pet with a harness", !care.putOnHarness(stranger, stack.copy()) && !care.harnessed());
        check("the owner fits the harness and one item is used", care.putOnHarness(player, stack) && care.harnessed() && stack.getCount() == 1 && (y.visibleWear() & 1) == 1 && y.canBeLeashed(),
                "harnessed=" + care.harnessed() + " held=" + stack.getCount() + " wear=" + y.visibleWear());
        check("it cannot be fitted twice", !care.putOnHarness(player, stack) && stack.getCount() == 1);
        check("the owner may lead the harnessed pet", tryLead(player, y));
        check("a stranger may not lead it", !tryLead(stranger, y));
        var bare = pet(20.5, 24.5);
        check("an owned pet without a harness cannot be led", !tryLead(player, bare) && !bare.canBeLeashed());
        var stock = pet(22.5, 24.5); HumanCommerce.assign(stock, UUID.randomUUID(), "stock");
        check("shop stock cannot be led", !tryLead(player, stock));
        var wild = ShopFixtures.spawn(level, ModEntities.REIMU, X + 24.5, 65, Z + 24.5);
        check("a wild yukkuri cannot be led", !tryLead(player, wild));
        bare.discard(); stock.discard(); wild.discard();

        y.setLeashedTo(player, true);
        check("vanilla ties the lead to the harnessed pet", y.isLeashed() && y.getLeashHolder() == player);
        int leads = leads();
        check("a session lock (exam, lesson, sale) ...", y.pet().lock("exam", player.getUUID()));
        check("... releases the lead and returns it to its holder", !y.isLeashed() && leads() == leads + 1, "leashed=" + y.isLeashed() + " leads " + leads + " -> " + leads());
        y.pet().unlock("exam");
        y.setLeashedTo(player, true); leads = leads();
        boolean removed = y.operations().perform(player, CareAction.REMOVE_HARNESS);
        check("taking the harness off releases the lead and returns both items", removed && !care.harnessed() && !y.isLeashed() && leads() == leads + 1 && count(ModItems.PET_HARNESS.get()) >= 1,
                "removed=" + removed + " leads " + leads + " -> " + leads() + " harness items=" + count(ModItems.PET_HARNESS.get()));
        y.discard();
    }

    // ------------------------------------------------------------------ walks

    private void walk() {
        var y = pet(10, 12); var care = y.pet().care(); var walk = care.walk();
        player.moveTo(X + 8.5, 65, Z + 12.5, 0, 0);
        care.putOnHarness(player, new ItemStack(ModItems.PET_HARNESS.get()));
        y.setLeashedTo(player, true);
        var daily = y.dailyLife().save(); daily.putInt("Boredom", 60); daily.putInt("Affection", 60); y.dailyLife().load(daily);
        y.pet().addStress(30); int stress = y.pet().stress(), nutrition = Math.round(y.life().nutrition());
        player.moveTo(X + 2.5, 65, Z + 4.5, 0, 0); walk.tick();
        for (int i = 1; i <= 230; i++) { walkTo(i); walk.tick(); }
        check("about 200 blocks of walking on the lead make three walk units", walk.unitsToday() == 3 && walk.walking(), "units=" + walk.unitsToday() + " carry=" + walk.carry());
        check("each unit lowers boredom (-6), the need for affection (-4) and stress (-2)", y.dailyLife().boredom() == 42 && y.dailyLife().affectionNeed() == 48 && y.pet().stress() == stress - 6,
                "boredom=" + y.dailyLife().boredom() + " affection=" + y.dailyLife().affectionNeed() + " stress " + stress + " -> " + y.pet().stress());
        for (int i = 231; i <= 1100; i++) { walkTo(i); walk.tick(); }
        check("at most 4 walk units count a game day", walk.unitsToday() == io.github.longshou.mcyukkuri.pet.PetWalk.UNITS_PER_DAY, "units=" + walk.unitsToday());
        double carry = walk.carry(); player.moveTo(player.getX() + 30, 65, player.getZ(), 0, 0); walk.tick();
        check("a jump of 30 blocks (teleport) is not walking", Math.abs(walk.carry() - carry) < 1e-6, "carry " + carry + " -> " + walk.carry());
        y.setLeashedTo(stranger, true); walk.tick();
        check("a pet led by someone else is not on a walk with its owner", !walk.walking(), "walking=" + walk.walking());
        y.dropLeash(true, false);
        y.discard();
    }
    /** A triangle wave of 0.95 blocks per step inside the arena. */
    private void walkTo(int step) {
        double d = (step * .95) % 56, x = X + 2.5 + (d <= 28 ? d : 56 - d);
        player.moveTo(x, 65, Z + 4.5, 0, 0);
    }

    // ------------------------------------------------------------------ swaddle

    private YukkuriEntity baby(double x, double z) {
        var y = pet(x, z);
        var family = y.family().save(); family.putString("Stage", "BABY"); family.putFloat("Scale", .35f); y.family().load(family);
        return y;
    }
    private void swaddle() {
        player.moveTo(X + 30.5, 65, Z + 5.5, 0, 0);
        var adult = pet(32, 5.5);
        var cloth = new ItemStack(ModItems.SWADDLE_CLOTH.get(), 3);
        check("an adult cannot be swaddled", !adult.pet().care().putOnSwaddle(player, cloth) && cloth.getCount() == 3 && !adult.pet().care().swaddled());
        var b = baby(28, 5.5); var care = b.pet().care();
        check("a baby is not able to live independently", !b.childhood().canLiveIndependently(), "stage=" + b.family().stage());
        check("a stranger cannot swaddle it", !care.putOnSwaddle(stranger, cloth.copy()));
        check("the owner swaddles a baby and one cloth is used", care.putOnSwaddle(player, cloth) && care.swaddled() && cloth.getCount() == 2 && (b.visibleWear() & 2) == 2, "swaddled=" + care.swaddled() + " held=" + cloth.getCount());
        check("it cannot be swaddled twice", !care.putOnSwaddle(player, cloth) && cloth.getCount() == 2);
        int before = count(ModItems.SWADDLE_CLOTH.get());
        boolean undone = b.operations().perform(player, CareAction.UNWRAP);
        check("UNWRAP takes it off and returns the cloth", undone && !care.swaddled() && count(ModItems.SWADDLE_CLOTH.get()) == before + 1, "undone=" + undone + " cloths " + before + " -> " + count(ModItems.SWADDLE_CLOTH.get()));
        adult.discard(); b.discard();
    }

    // ------------------------------------------------------------------ ownership and saving

    private void ownership() {
        player.moveTo(X + 30.5, 65, Z + 12.5, 0, 0);
        var b = baby(32, 12.5); var care = b.pet().care();
        player.getInventory().clearContent();
        care.putOnSwaddle(player, new ItemStack(ModItems.SWADDLE_CLOTH.get()));
        check("the swaddle is on before the sale", care.swaddled() && count(ModItems.SWADDLE_CLOTH.get()) == 0);
        HumanCommerce.assign(b, UUID.randomUUID(), "stock");
        check("a change of owner takes the gear off and gives it back to whoever fitted it", !care.swaddled() && care.wearBits() == 0 && count(ModItems.SWADDLE_CLOTH.get()) == 1,
                "swaddled=" + care.swaddled() + " cloths=" + count(ModItems.SWADDLE_CLOTH.get()));
        b.discard();
        var y = pet(36, 12.5); var yc = y.pet().care();
        player.moveTo(X + 34.5, 65, Z + 12.5, 0, 0);
        yc.putOnHarness(player, new ItemStack(ModItems.PET_HARNESS.get())); int items = count(ModItems.PET_HARNESS.get());
        HumanCommerce.assign(y, player.getUUID(), "companion");
        check("the same owner taking the pet again (a carrier trip) keeps the harness on", yc.harnessed() && count(ModItems.PET_HARNESS.get()) == items, "harnessed=" + yc.harnessed());
        y.pet().abandonedBy(player.getUUID());
        check("abandoning it takes the harness off", !yc.harnessed(), "harnessed=" + yc.harnessed());
        y.discard();
        var b2 = pet(30.5, 8.5);
        b2.pet().issue(BadgeGrade.BRONZE, 3, null);
        PetPrivileges.note(b2);
        b2.discard();
        check("a badge earned by a pet is remembered for its owner after the pet is gone", PetPrivileges.grade(player).id() >= BadgeGrade.BRONZE.id(), "grade=" + PetPrivileges.grade(player));
    }

    private void saving() {
        player.moveTo(X + 3.5, 65, Z + 20.5, 0, 0);
        var y = pet(5.5, 20.5); var care = y.pet().care();
        care.putOnHarness(player, new ItemStack(ModItems.PET_HARNESS.get()));
        Sweets.eaten(y, new ItemStack(ModItems.SWEET_CANDY.get()), player.getUUID()); Sweets.eaten(y, new ItemStack(Items.COOKIE), player.getUUID());
        var req = new CompoundTag(); req.putInt("Today", 3); req.putInt("Answered", 2); care.requests().load(req);
        var saved = y.pet().save();
        var copy = ShopFixtures.spawn(level, ModEntities.MARISA, X + 8.5, 65, Z + 20.5);
        copy.pet().load(saved);
        var cc = copy.pet().care();
        check("harness, today's sweets and request counters survive saving", cc.harnessed() && cc.snacksToday() == 2 && cc.requests().today() == 3 && cc.requests().answeredToday() == 2,
                "harnessed=" + cc.harnessed() + " snacks=" + cc.snacksToday() + " requests=" + cc.requests().today() + "/" + cc.requests().answeredToday());
        var old = ShopFixtures.spawn(level, ModEntities.MARISA, X + 11.5, 65, Z + 20.5);
        old.pet().load(new CompoundTag());
        var oc = old.pet().care();
        check("an old save without care data loads with defaults", !oc.harnessed() && !oc.swaddled() && oc.snacksToday() == 0 && oc.requests().today() == 0 && oc.requests().pending() == null);
        y.discard(); copy.discard(); old.discard();
    }
}
