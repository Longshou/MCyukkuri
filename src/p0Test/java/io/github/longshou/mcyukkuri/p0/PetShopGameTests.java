package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.human.HumanWorld;
import io.github.longshou.mcyukkuri.interaction.CareAction;
import io.github.longshou.mcyukkuri.item.CarrierItem;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.pet.Ability;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.ExamRecord;
import io.github.longshou.mcyukkuri.pet.PetLessons;
import io.github.longshou.mcyukkuri.pet.exam.ExamHall;
import io.github.longshou.mcyukkuri.pet.exam.ExamMenu;
import io.github.longshou.mcyukkuri.pet.exam.ExamSession;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.shop.KeeperMenu;
import io.github.longshou.mcyukkuri.shop.ShopMenu;
import io.github.longshou.mcyukkuri.shop.ShopPens;
import io.github.longshou.mcyukkuri.shop.ShopPricing;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Pet shop redesign (docs/PET_SHOP.md): template binding, shop counter, exams, lessons, breeding and the pet core.
 * Every test is a sequence of steps driven once per tick; each step has its own timeout, every check logs one line
 * ("[pet_shop/label] PASS|FAIL name :: observed values") so the CI log alone explains a failure.
 * Each test owns its arena (160 blocks apart) and its own FakePlayer, so the batch is safe to run in parallel;
 * only the exam test touches the global ExamSession.forceOutcomes switch.
 */
@PrefixGameTestTemplate(false)
public final class PetShopGameTests {
    private record Check(String name, boolean pass, String detail) {}
    private interface Body { boolean run() throws Exception; }
    private record Step(String name, int timeout, boolean critical, Body body) {}

    private static final int BASE_X = 9000, SPACING = 160, BASE_Z = 64;

    private final GameTestHelper h;
    private final ServerLevel level;
    private final String label;
    private final int ox, oz, minCx, maxCx, minCz, maxCz;
    private final AABB area;
    private final List<Check> checks = new ArrayList<>();
    private final List<Step> steps = new ArrayList<>();
    private final List<FakePlayer> players = new ArrayList<>();
    private int cursor;
    private long stepStart = -1;
    private boolean finished, forced, examTouched;
    private String note = "";
    private Entity follow;

    private HumanNpc shop, examiner, keeper;
    private FakePlayer player;

    private PetShopGameTests(GameTestHelper h, String label, int slot, int width, int depth) {
        this.h = h; this.level = h.getLevel(); this.label = label;
        ox = BASE_X + SPACING * slot; oz = BASE_Z;
        minCx = (ox >> 4) - 1; maxCx = ((ox + width) >> 4) + 1; minCz = (oz >> 4) - 1; maxCz = ((oz + depth) >> 4) + 1;
        area = new AABB(ox - 2, 62, oz - 2, ox + width + 2, 92, oz + depth + 2);
    }

    // ------------------------------------------------------------------ driver

    private void check(String name, boolean pass) { check(name, pass, ""); }
    private void check(String name, boolean pass, String detail) {
        checks.add(new Check(name, pass, detail));
        MCYukkuri.LOGGER.info("[pet_shop/{}] {} {}{}", label, pass ? "PASS" : "FAIL", name, detail.isEmpty() ? "" : " :: " + detail);
    }
    private void step(String name, int timeout, Body body) { steps.add(new Step(name, timeout, false, body)); }
    private void critical(String name, int timeout, Body body) { steps.add(new Step(name, timeout, true, body)); }
    private void begin() { h.onEachTick(this::drive); }

    private void keepDay() {
        if (level.getDayTime() % 24000L > 10000L || !level.isDay()) { level.setDayTime(6000); level.updateSkyBrightness(); }
    }

    private void drive() {
        if (finished || cursor >= steps.size()) return;
        keepDay();
        if (follow != null && follow.isAlive() && player != null) player.moveTo(follow.getX() + 1.5, follow.getY(), follow.getZ(), 0, 0);
        var s = steps.get(cursor);
        long now = h.getTick();
        if (stepStart < 0) { stepStart = now; note = ""; }
        boolean done, bad = false;
        try { done = s.body().run(); }
        catch (Throwable e) {
            MCYukkuri.LOGGER.warn("[pet_shop/" + label + "] step '" + s.name() + "' threw", e);
            check("step '" + s.name() + "' completes without exception", false, e + " at " + where(e));
            done = true; bad = true;
        }
        if (!done && now - stepStart > s.timeout()) {
            check("step '" + s.name() + "' finishes within " + s.timeout() + " ticks", false, note.isEmpty() ? "no progress note" : note);
            done = true; bad = true;
        }
        if (!done) return;
        cursor++; stepStart = -1;
        if (bad && s.critical()) { MCYukkuri.LOGGER.warn("[pet_shop/{}] critical step '{}' failed, aborting the sequence", label, s.name()); finish(); }
        else if (cursor >= steps.size()) finish();
    }

    private static String where(Throwable e) {
        var trace = e.getStackTrace();
        var sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, trace.length); i++) sb.append(i == 0 ? "" : " < ").append(trace[i].getClassName().replaceAll(".*\\.", "")).append('.').append(trace[i].getMethodName()).append(':').append(trace[i].getLineNumber());
        return sb.toString();
    }

    private void cleanup() {
        if (examTouched) ExamSession.forceOutcomes(null);
        follow = null;
        level.getEntitiesOfClass(Entity.class, area, e -> !(e instanceof net.minecraft.world.entity.player.Player)).forEach(Entity::discard);
        for (var p : players) ShopFixtures.release(level, p);
        players.clear();
        if (forced) for (int x = minCx; x <= maxCx; x++) for (int z = minCz; z <= maxCz; z++) GameTestChunks.setForced(level, x, z, false);
        forced = false;
    }

    private void finish() {
        if (finished) return;
        finished = true;
        cleanup();
        var failed = checks.stream().filter(c -> !c.pass()).toList();
        MCYukkuri.LOGGER.info("[pet_shop/{}] SUMMARY {} checks, {} failed{}", label, checks.size(), failed.size(),
                failed.isEmpty() ? "" : ": " + failed.stream().map(Check::name).toList());
        P0Control.write("pet-shop-" + label, checks);
        if (failed.isEmpty()) h.succeed();
        else h.fail("pet_shop/" + label + ": " + failed.size() + " of " + checks.size() + " checks failed. " + String.join(" | ",
                failed.stream().map(c -> c.name() + (c.detail().isEmpty() ? "" : " [" + c.detail() + "]")).toList()));
    }

    // ------------------------------------------------------------------ shared steps and helpers

    private boolean chunksStep() {
        if (!forced) {
            for (int x = minCx; x <= maxCx; x++) for (int z = minCz; z <= maxCz; z++) GameTestChunks.setForced(level, x, z, true);
            forced = true;
        }
        for (int x = minCx; x <= maxCx; x++) for (int z = minCz; z <= maxCz; z++)
            if (!level.isPositionEntityTicking(new BlockPos(x * 16 + 8, 65, z * 16 + 8))) { note = "chunk " + x + "," + z + " is not entity ticking"; return false; }
        return true;
    }

    private void clearEntities() {
        level.getEntitiesOfClass(Entity.class, area, e -> !(e instanceof net.minecraft.world.entity.player.Player)).forEach(Entity::discard);
    }

    /** Shared prologue of every template test: chunks, daylight, placement of the real yukkuri_shop template, stock custody binding. */
    private void shopSteps() {
        critical("fixture chunks become entity ticking", 800, this::chunksStep);
        critical("place yukkuri_shop through StructureTemplate", 5, () -> {
            level.setDayTime(6000); level.updateSkyBrightness(); level.setWeatherParameters(60000, 0, false, false);
            clearEntities();
            var template = level.getStructureManager().get(MCYukkuri.id("yukkuri_shop")).orElseThrow();
            check("shop template has its authored 41x16x33 footprint", template.getSize().equals(new BlockPos(41, 16, 33)), "size=" + template.getSize());
            var origin = new BlockPos(ox, 64, oz);
            boolean ok = template.placeInWorld(level, origin, origin, new StructurePlaceSettings().setIgnoreEntities(false).setFinalizeEntities(true), level.random, 2);
            check("shop template places with its residents", ok, "origin=" + origin);
            if (!ok) throw new IllegalStateException("placeInWorld returned false");
            level.getEntitiesOfClass(HumanNpc.class, area).forEach(n -> n.aiStep());
            findCast();
            return true;
        });
        critical("stock custody binds (PendingRole to NPC)", 800, () -> {
            var animals = level.getEntitiesOfClass(YukkuriEntity.class, area);
            long pending = animals.stream().filter(y -> HumanCommerce.data(y).contains("PendingRole")).count();
            note = animals.size() + " animals in the arena, " + pending + " still pending";
            return animals.size() >= 14 && pending == 0;
        });
        critical("residents are discoverable", 2, () -> { findCast(); return shop != null && examiner != null && keeper != null; });
    }

    private void findCast() {
        for (var n : level.getEntitiesOfClass(HumanNpc.class, area)) {
            switch (n.job()) {
                case SHOPKEEPER -> shop = n;
                case EXAMINER -> examiner = n;
                case KEEPER -> keeper = n;
                default -> { }
            }
        }
    }

    private static String use(YukkuriEntity y) { return HumanCommerce.data(y).getString("Use"); }
    private static String ownerOf(YukkuriEntity y) { var d = HumanCommerce.data(y); return d.hasUUID("Owner") ? d.getUUID("Owner").toString() : "none"; }
    private FakePlayer newPlayer(String name) {
        var p = ShopFixtures.player(level, name);
        players.add(p);
        check("FakePlayer " + name + " is findable through PlayerList", level.getServer().getPlayerList().getPlayer(p.getUUID()) == p, "uuid=" + p.getUUID());
        return p;
    }
    private YukkuriEntity ownedPet(Supplier<net.minecraft.world.entity.EntityType<YukkuriEntity>> type, Vec3 at) {
        var y = ShopFixtures.spawn(level, type, at.x, at.y, at.z);
        ShopFixtures.needs(y); ShopFixtures.adult(y);
        HumanCommerce.assign(y, player.getUUID(), "companion");
        return y;
    }
    private void freezeShop() {
        for (var n : level.getEntitiesOfClass(HumanNpc.class, area)) n.setNoAi(true);
        for (var y : level.getEntitiesOfClass(YukkuriEntity.class, area)) y.setNoAi(true);
    }
    private static String describe(ExamRecord r) {
        return r == null ? "no record" : "type=" + r.type() + " outcome=" + r.outcome() + " result=" + r.result() + " scores=" + Arrays.toString(r.scores());
    }
    private static ExamRecord last(YukkuriEntity y) {
        var all = y.pet().history();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }
    private void flatFloor(int width, int depth) {
        for (int x = ox; x < ox + width; x++) for (int z = oz; z < oz + depth; z++) {
            level.setBlock(new BlockPos(x, 64, z), Blocks.STONE.defaultBlockState(), 2);
            for (int y = 65; y <= 72; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
    }

    // ------------------------------------------------------------------ a. template binding

    @GameTest(templateNamespace = P0Mod.ID, template = "empty", timeoutTicks = 2400, batch = "pet_shop")
    public static void templateBinding(GameTestHelper h) {
        var t = new PetShopGameTests(h, "binding", 0, 41, 33);
        t.shopSteps();
        t.step("authored cast and hall", 5, t::bindingChecks);
        t.begin();
    }

    private boolean bindingChecks() {
        var npcs = level.getEntitiesOfClass(HumanNpc.class, area);
        long shopkeepers = npcs.stream().filter(n -> n.job() == HumanNpc.Job.SHOPKEEPER).count();
        long examiners = npcs.stream().filter(n -> n.job() == HumanNpc.Job.EXAMINER).count();
        long keepers = npcs.stream().filter(n -> n.job() == HumanNpc.Job.KEEPER).count();
        check("one shopkeeper, one examiner and one keeper exist", shopkeepers == 1 && examiners == 1 && keepers == 1,
                "npcs=" + npcs.size() + " shopkeepers=" + shopkeepers + " examiners=" + examiners + " keepers=" + keepers);
        check("residents carry their site roles", "shopkeeper".equals(shop.siteRole()) && "shop_examiner".equals(examiner.siteRole()) && "shop_keeper".equals(keeper.siteRole()),
                shop.siteRole() + "/" + examiner.siteRole() + "/" + keeper.siteRole());
        var all = level.getEntitiesOfClass(YukkuriEntity.class, area);
        check("the template spawns exactly fourteen animals", all.size() == 14, "animals=" + all.size());

        var stock = shop.animals("stock");
        var grades = new TreeMap<Integer, Integer>();
        for (var y : stock) grades.merge(y.pet().grade().id(), 1, Integer::sum);
        check("shopkeeper owns eight stock animals", stock.size() == 8, "stock=" + stock.size());
        check("stock grades are as authored (4 none, 3 silver, 1 gold)", grades.equals(Map.of(0, 4, 2, 3, 3, 1)), "grades=" + grades);
        var pedigrees = stock.stream().map(y -> y.pet().pedigree()).sorted().toList();
        check("stock pedigrees are as authored", pedigrees.equals(List.of(25, 30, 35, 40, 70, 72, 75, 85)), "pedigrees=" + pedigrees);
        check("badged stock wears its badge with an issuer", stock.stream().filter(y -> y.pet().grade() != BadgeGrade.NONE)
                .allMatch(y -> y.pet().visibleGrade() == y.pet().grade() && shop.getUUID().equals(y.pet().issuer())), "badged=" + stock.stream().map(y -> y.pet().grade() + "/" + y.pet().visibleGrade()).toList());

        var studs = keeper.animals("stud");
        check("keeper owns two studs", studs.size() == 2, "studs=" + studs.size());
        check("studs are silver with pedigree 80", studs.stream().allMatch(y -> y.pet().grade() == BadgeGrade.SILVER && y.pet().pedigree() == 80),
                studs.stream().map(y -> y.pet().grade() + "/" + y.pet().pedigree()).toList().toString());
        check("studs are not for sale (not stock of the shopkeeper)", studs.stream().noneMatch(y -> HumanCommerce.ownedBy(y, shop.getUUID())), "");

        var trainees = examiner.animals("trainee");
        var actor = examiner.animals("exam_actor");
        check("examiner owns three trainees and one actor", trainees.size() == 3 && actor.size() == 1, "trainees=" + trainees.size() + " actors=" + actor.size());

        var hall = ExamHall.find(level, examiner.home());
        check("ExamHall.find from the examiner returns a hall", hall != null, "home=" + examiner.home());
        if (hall != null) {
            var detail = "mask=" + hall.supportMask() + " room=" + hall.room + " podium=" + hall.podium + " hide=" + hall.hideBox + " gold=" + hall.gold + " actor=" + hall.actor
                    + " promise=" + hall.promise.size() + " trainee=" + hall.trainee.size() + " bowls=" + hall.bowls.size() + " toilets=" + hall.toilets.size() + " nests=" + hall.nests.size();
            for (var a : Ability.values()) check("exam hall supports " + a.key(), hall.supports(a), detail);
            check("hall mask covers all four sections", hall.supportMask() == 15, detail);
        }
        var cached = ExamHall.of(examiner);
        check("ExamHall.of agrees with a fresh scan", cached != null && hall != null && cached.supportMask() == hall.supportMask(), "cached=" + (cached == null ? "null" : cached.supportMask()));

        int nests = 0;
        for (var pos : BlockPos.betweenClosed(shop.blockPosition().offset(-24, -6, -24), shop.blockPosition().offset(24, 6, 24)))
            if (level.getBlockState(pos).getBlock() instanceof CareBlock care && care.kind() == CareBlock.Kind.NEST) nests++;
        check("shop has at least nine nests for buyback pens", nests >= 9, "nests within reach=" + nests);
        var pen = ShopPens.freePen(level, shop, stock.isEmpty() ? null : stock.get(0));
        check("ShopPens.freePen finds a pen", pen != null, "pen=" + pen + " population=" + ShopPens.population(level, shop));
        return true;
    }

    // ------------------------------------------------------------------ b. shop counter

    private ShopMenu shopMenu;
    private YukkuriEntity subject;
    private UUID subjectId;
    private int cash, price;
    private long clickTick;
    private BlockPos expectedPen;

    private YukkuriEntity liveRow(int row) {
        return shop.animals("stock").stream().sorted(Comparator.comparingInt((YukkuriEntity y) -> -y.pet().grade().id())
                .thenComparingInt(y -> y.getVariant().ordinal()).thenComparing(YukkuriEntity::getUUID)).skip(row).findFirst().orElse(null);
    }
    private ItemStack findCarrier() {
        for (var s : player.getInventory().items) if (s.is(ModItems.CARRIER.get())) return s;
        return null;
    }

    @GameTest(templateNamespace = P0Mod.ID, template = "empty", timeoutTicks = 4000, batch = "pet_shop")
    public static void shopCounter(GameTestHelper h) {
        var t = new PetShopGameTests(h, "counter", 1, 41, 33);
        t.shopSteps();
        t.counterSteps();
        t.begin();
    }

    private void counterSteps() {
        step("counter setup", 5, () -> {
            freezeShop();
            player = newPlayer("PetShopBuyer");
            check("player stands in reach of the shopkeeper with line of sight", ShopFixtures.placeFor(level, player, shop, 1.5), "player=" + player.blockPosition() + " shopkeeper=" + shop.blockPosition());
            ShopFixtures.giveEmeralds(player, 200);
            return true;
        });
        step("live: insufficient funds", 5, () -> {
            subject = liveRow(0);
            if (subject == null) throw new IllegalStateException("shop has no stock");
            int kept = ShopFixtures.emeralds(player); ShopFixtures.clearEmeralds(player);
            shopMenu = new ShopMenu(41, player.getInventory(), shop.getId()); player.containerMenu = shopMenu;
            boolean clicked = shopMenu.clickMenuButton(player, 4);
            check("live purchase without emeralds is refused and the animal stays", !clicked && subject.isAlive() && !subject.pet().busy() && HumanCommerce.ownedBy(subject, shop.getUUID()),
                    "clicked=" + clicked + " alive=" + subject.isAlive() + " busy=" + subject.pet().busyWith());
            ShopFixtures.giveEmeralds(player, kept);
            return true;
        });
        step("live: buy row 0", 5, () -> {
            subject = liveRow(0); subjectId = subject.getUUID(); price = ShopPricing.price(subject); cash = ShopFixtures.emeralds(player);
            shopMenu = new ShopMenu(42, player.getInventory(), shop.getId()); player.containerMenu = shopMenu;
            check("live row 0 quote shows the species and the ShopPricing price", shopMenu.value(8) == subject.getVariant().ordinal() + 1 && shopMenu.value(19) == price,
                    "species slot=" + shopMenu.value(8) + " expected " + (subject.getVariant().ordinal() + 1) + ", price slot=" + shopMenu.value(19) + " expected " + price);
            check("live row 0 is the highest grade on offer", (shopMenu.value(10) & 3) == subject.pet().grade().id() && subject.pet().grade() == BadgeGrade.GOLD,
                    "flags=" + shopMenu.value(10) + " grade=" + subject.pet().grade());
            boolean clicked = shopMenu.clickMenuButton(player, 4);
            clickTick = h.getTick();
            check("live buy click is accepted", clicked, "price=" + price + " cash=" + cash);
            check("emeralds are debited by the quoted price at click time", ShopFixtures.emeralds(player) == cash - price, "before=" + cash + " after=" + ShopFixtures.emeralds(player) + " price=" + price);
            check("the sold animal is locked for the sale scene", "sale".equals(subject.pet().busyWith()), "busyWith=" + subject.pet().busyWith());
            return true;
        });
        step("live: carrier is delivered", 400, () -> {
            var carrier = findCarrier();
            note = "carrier=" + (carrier != null) + " animalStillInWorld=" + (level.getEntity(subjectId) != null) + " elapsed=" + (h.getTick() - clickTick);
            boolean inInventory = carrier != null;
            if (carrier == null && level.getEntity(subjectId) == null) {
                var dropped = level.getEntitiesOfClass(ItemEntity.class, area, i -> i.getItem().is(ModItems.CARRIER.get()));
                if (!dropped.isEmpty()) carrier = dropped.get(0).getItem();
            }
            if (carrier == null) return false;
            check("carrier lands in the buyer's inventory", inInventory, "found as dropped item instead");
            var token = CarrierItem.token(carrier);
            var snapshot = HumanCommerce.heldData(level, carrier);
            var custody = snapshot.getCompound("NeoForgeData").getCompound("HumanCommerce");
            check("carrier holds the bought individual (UUID kept)", snapshot.hasUUID("UUID") && snapshot.getUUID("UUID").equals(subjectId), "snapshot UUID=" + (snapshot.hasUUID("UUID") ? snapshot.getUUID("UUID") : "none") + " expected " + subjectId);
            check("bought animal is owned by the buyer as companion", custody.hasUUID("Owner") && custody.getUUID("Owner").equals(player.getUUID()) && custody.getString("Use").equals("companion"),
                    "owner=" + (custody.hasUUID("Owner") ? custody.getUUID("Owner") : "none") + " use=" + custody.getString("Use"));
            check("bought animal is captured and gone from the world", level.getEntity(subjectId) == null && token != null && YukkuriWorldData.get(level).hasToken(token), "inWorld=" + (level.getEntity(subjectId) != null) + " token=" + token);
            check("emeralds are reduced by exactly the quoted price", ShopFixtures.emeralds(player) == cash - price, "cash " + cash + " -> " + ShopFixtures.emeralds(player) + ", price " + price);
            return true;
        });
        step("live: stale quote", 5, () -> {
            int before = ShopFixtures.emeralds(player);
            boolean clicked = shopMenu.clickMenuButton(player, 4);
            check("a repeated purchase of the sold row cannot charge or clone", !clicked && ShopFixtures.emeralds(player) == before, "clicked=" + clicked + " cash " + before + " -> " + ShopFixtures.emeralds(player));
            return true;
        });
        step("goods: buy one item", 5, () -> {
            check("goods tab opens", shopMenu.clickMenuButton(player, 1), "");
            var stack = ShopMenu.goodsStack(0, 0); int cost = ShopMenu.goodsPrice(0, 0); int before = ShopFixtures.emeralds(player); int trades = shop.remainingTrades();
            check("goods page 0 row 0 is a priced item", !stack.isEmpty() && cost > 0 && trades > 0, "stack=" + stack + " price=" + cost + " tradesLeft=" + trades);
            level.setDayTime(18000); level.updateSkyBrightness();
            boolean night = shopMenu.clickMenuButton(player, 14);
            level.setDayTime(6000); level.updateSkyBrightness();
            check("goods cannot be bought at night", !night && ShopFixtures.emeralds(player) == before, "clicked=" + night + " cash " + before + " -> " + ShopFixtures.emeralds(player));
            boolean clicked = shopMenu.clickMenuButton(player, 14);
            int owned = player.getInventory().items.stream().filter(s -> s.is(stack.getItem())).mapToInt(ItemStack::getCount).sum();
            check("goods purchase succeeds, debits the price and gives the item", clicked && ShopFixtures.emeralds(player) == before - cost && owned >= stack.getCount(),
                    "clicked=" + clicked + " cash " + before + " -> " + ShopFixtures.emeralds(player) + " price " + cost + " owned " + stack.getItem() + "=" + owned);
            check("goods purchase uses up one daily trade", shop.remainingTrades() == trades - 1, "trades " + trades + " -> " + shop.remainingTrades());
            return true;
        });
        step("buyback: sell an owned reimu", 5, () -> {
            subject = ownedPet(ModEntities.REIMU, player.position().add(1, 0, 0));
            subject.setNoAi(true);
            check("sold animal can be placed beside the player in sight", ShopFixtures.placeNear(level, subject, player, 1.0), "player=" + player.blockPosition() + " pet=" + subject.blockPosition());
            subject.setNoAi(true);
            subjectId = subject.getUUID();
            expectedPen = ShopPens.freePen(level, shop, subject);
            int offer = ShopPricing.buyback(subject); cash = ShopFixtures.emeralds(player);
            check("a free pen exists for the sale", expectedPen != null, "pen=" + expectedPen + " population=" + ShopPens.population(level, shop));
            check("buyback tab opens", shopMenu.clickMenuButton(player, 2), "");
            check("buyback lists the owned animal as the subject", shopMenu.value(6) == 1 && shopMenu.value(34) == 0, "subjects=" + shopMenu.value(6) + " refusal slot=" + shopMenu.value(34) + " offer slot=" + shopMenu.value(29) + " expected offer " + offer);
            check("buyback offer slot equals ShopPricing.buyback", shopMenu.value(29) == offer, "slot=" + shopMenu.value(29) + " offer=" + offer);
            boolean clicked = shopMenu.clickMenuButton(player, 18);
            check("sell click is accepted", clicked, "");
            check("emeralds received equal the buyback offer", ShopFixtures.emeralds(player) == cash + offer, "cash " + cash + " -> " + ShopFixtures.emeralds(player) + " offer " + offer);
            check("sold animal now belongs to the shopkeeper as stock", HumanCommerce.ownedBy(subject, shop.getUUID()) && use(subject).equals("stock"),
                    "owner=" + ownerOf(subject) + " use=" + use(subject));
            clickTick = h.getTick();
            return true;
        });
        step("buyback: animal walks into a pen", 400, () -> {
            note = "busyWith=" + subject.pet().busyWith() + " elapsed=" + (h.getTick() - clickTick) + " pos=" + subject.position();
            if (subject.pet().busy()) return false;
            int nearest = nestDistance(subject);
            check("after the farewell scene the animal stands beside a nest", nearest >= 0 && nearest <= 2, "pos=" + subject.position() + " nestDistance=" + nearest + " plannedPen=" + expectedPen);
            check("the planned pen is exactly where the animal stands", expectedPen != null && subject.position().distanceToSqr(expectedPen.getX() + .5, expectedPen.getY() + .12, expectedPen.getZ() + .5) < 1.0,
                    "pos=" + subject.position() + " pen=" + expectedPen);
            check("sold animal is listed in the shop stock", shop.animals("stock").contains(subject), "stock size=" + shop.animals("stock").size());
            return true;
        });
        step("consult: forecast debits one emerald", 5, () -> {
            subject = ownedPet(ModEntities.MARISA, player.position().add(1, 0, 0));
            subject.setNoAi(true);
            ShopFixtures.placeNear(level, subject, player, 1.0);
            check("consult tab opens", shopMenu.clickMenuButton(player, 3), "");
            check("consult lists the owned animal with no forecast yet", shopMenu.value(6) == 1 && shopMenu.value(33) == 0, "subjects=" + shopMenu.value(6) + " forecastBought=" + shopMenu.value(33));
            int before = ShopFixtures.emeralds(player);
            boolean clicked = shopMenu.clickMenuButton(player, 19);
            check("forecast click is accepted and costs 1 emerald", clicked && ShopFixtures.emeralds(player) == before - 1, "clicked=" + clicked + " cash " + before + " -> " + ShopFixtures.emeralds(player));
            check("forecast slots hold one of high, mid or low for bronze, silver and gold", shopMenu.value(33) == 1
                    && inRange13(shopMenu.value(30), shopMenu.value(31), shopMenu.value(32)), "bought=" + shopMenu.value(33) + " bronze/silver/gold=" + shopMenu.value(30) + "/" + shopMenu.value(31) + "/" + shopMenu.value(32));
            before = ShopFixtures.emeralds(player);
            boolean again = shopMenu.clickMenuButton(player, 19);
            check("a second forecast for the same animal is refused without charge", !again && ShopFixtures.emeralds(player) == before, "clicked=" + again + " cash " + before + " -> " + ShopFixtures.emeralds(player));
            return true;
        });
    }

    private static boolean inRange13(int a, int b, int c) { return a >= 1 && a <= 3 && b >= 1 && b <= 3 && c >= 1 && c <= 3; }

    /** Horizontal-ish distance in blocks to the nearest NEST block around the animal, or -1. */
    private int nestDistance(YukkuriEntity y) {
        int best = -1;
        var at = y.blockPosition();
        for (var pos : BlockPos.betweenClosed(at.offset(-3, -1, -3), at.offset(3, 1, 3))) {
            if (level.getBlockState(pos).getBlock() instanceof CareBlock care && care.kind() == CareBlock.Kind.NEST) {
                int d = Math.max(Math.abs(pos.getX() - at.getX()), Math.abs(pos.getZ() - at.getZ()));
                if (best < 0 || d < best) best = d;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ c. exams

    private static final class ExamRun {
        YukkuriEntity candidate; String refusal; boolean started; int cash, stress; long t0; int expiryBefore;
    }

    @GameTest(templateNamespace = P0Mod.ID, template = "empty", timeoutTicks = 52000, batch = "pet_shop")
    public static void examinations(GameTestHelper h) {
        var t = new PetShopGameTests(h, "exams", 2, 41, 33);
        t.shopSteps();
        t.examSteps();
        t.begin();
    }

    private YukkuriEntity candidateNearExaminer(boolean owned) {
        var spots = ShopFixtures.spots(level, examiner.position(), 1.5, 5);
        var at = spots.isEmpty() ? examiner.position() : Vec3.atBottomCenterOf(spots.get(0));
        var y = ShopFixtures.spawn(level, ModEntities.REIMU, at.x, at.y, at.z);
        ShopFixtures.needs(y); ShopFixtures.adult(y);
        if (owned) HumanCommerce.assign(y, player.getUUID(), "companion");
        player.moveTo(at.x + 1.5, at.y, at.z, 0, 0);
        return y;
    }

    /** One exam as three steps: start, wait for the session to end, verify. */
    private void exam(String tag, int type, Boolean outcome, Supplier<YukkuriEntity> prepare, int timeout, java.util.function.Consumer<ExamRun> verify) {
        var run = new ExamRun();
        step(tag + ": start", 5, () -> {
            run.candidate = prepare.get();
            ExamSession.forceOutcomes(outcome); examTouched = true;
            run.cash = ShopFixtures.emeralds(player); run.stress = run.candidate.pet().stress(); run.t0 = h.getTick();
            run.expiryBefore = (int)Math.min(Integer.MAX_VALUE, run.candidate.pet().expiresAt());
            run.refusal = ExamSession.start(examiner, player, run.candidate, type, true);
            run.started = run.refusal == null;
            check(tag + ": session starts", run.started, "refusal=" + run.refusal + " distance=" + Math.sqrt(run.candidate.distanceToSqr(player)) + " examinerBusy=" + ExamSession.active(examiner));
            follow = run.started ? run.candidate : null;
            return true;
        });
        step(tag + ": wait for the session to end", timeout, () -> {
            if (!run.started) return true;
            var pet = run.candidate.pet();
            note = "elapsed=" + (h.getTick() - run.t0) + " active=" + ExamSession.active(examiner) + " busyWith=" + pet.busyWith() + " pos=" + run.candidate.position() + " lastRecord=" + describe(last(run.candidate));
            return !ExamSession.active(examiner) && !pet.busy();
        });
        step(tag + ": verify", 5, () -> {
            follow = null;
            if (run.started) {
                check(tag + ": session ran without interruption", last(run.candidate) != null && last(run.candidate).outcome() != 4, "ticks=" + (h.getTick() - run.t0) + " " + describe(last(run.candidate)));
                check(tag + ": candidate is released with a cooldown", !run.candidate.pet().busy() && run.candidate.pet().cooldown() > 0, "busy=" + run.candidate.pet().busyWith() + " cooldown=" + run.candidate.pet().cooldown());
                verify.accept(run);
            }
            ExamSession.forceOutcomes(null);
            if (run.candidate != null) run.candidate.discard();
            return true;
        });
    }

    private void examSteps() {
        step("exam setup", 5, () -> {
            for (var n : level.getEntitiesOfClass(HumanNpc.class, area)) if (n != examiner) n.setNoAi(true);
            for (var y : level.getEntitiesOfClass(YukkuriEntity.class, area)) if (!use(y).equals("trainee") && !use(y).equals("exam_actor")) y.setNoAi(true);
            player = newPlayer("PetShopOwner");
            ShopFixtures.giveEmeralds(player, 400);
            check("player stands in reach of the examiner", ShopFixtures.placeFor(level, player, examiner, 2.0), "player=" + player.blockPosition() + " examiner=" + examiner.blockPosition());
            var menu = new ExamMenu(51, player.getInventory(), examiner.getId());
            check("ExamMenu reports a hall with all four sections", (menu.value(14) & 16) != 0 && (menu.value(14) & 15) == 15, "hall slot=" + menu.value(14));
            check("ExamMenu daytime and idle examiner", menu.value(2) == 1 && menu.value(3) == 0, "day=" + menu.value(2) + " busy=" + menu.value(3));
            return true;
        });

        // 1. simple exam on an unowned tame reimu: registration plus bronze
        exam("simple exam", ExamSession.SIMPLE, true, () -> {
            var y = candidateNearExaminer(false);
            y.life().personality().fed(player.getUUID()); y.life().personality().fed(player.getUUID());
            check("wild candidate is unowned with trust >= 10", y.pet().owner() == null && y.life().personality().trust(player.getUUID()) >= 10, "owner=" + y.pet().owner() + " trust=" + y.life().personality().trust(player.getUUID()));
            check("a wild candidate may only sit the simple exam", "wild_simple_only".equals(ExamSession.refusal(examiner, player, y, ExamSession.PROMOTION)) && ExamSession.refusal(examiner, player, y, ExamSession.SIMPLE) == null,
                    "promotion=" + ExamSession.refusal(examiner, player, y, ExamSession.PROMOTION) + " simple=" + ExamSession.refusal(examiner, player, y, ExamSession.SIMPLE));
            return y;
        }, 2000, r -> {
            var pet = r.candidate.pet(); var rec = last(r.candidate);
            check("simple exam registers the wild animal as the player's companion", pet.ownedBy(player.getUUID()) && pet.isPet(), "owner=" + pet.owner() + " use=" + use(r.candidate));
            check("simple exam issues a bronze badge", pet.grade() == BadgeGrade.BRONZE, "grade=" + pet.grade());
            check("simple exam leaves a history record", rec != null && rec.type() == ExamSession.SIMPLE && rec.outcome() == 0 && rec.result() == BadgeGrade.BRONZE && examiner.getUUID().equals(rec.examiner()), describe(rec));
            check("simple exam is free", ShopFixtures.emeralds(player) == r.cash, "cash " + r.cash + " -> " + ShopFixtures.emeralds(player));
            check("a second exam right away is refused for the cooldown", "cooldown".equals(ExamSession.refusal(examiner, player, r.candidate, ExamSession.JUDGMENT)), "refusal=" + ExamSession.refusal(examiner, player, r.candidate, ExamSession.JUDGMENT) + " cooldown=" + pet.cooldown());
        });

        // 2. judgment, forced success: gold and the refund
        exam("judgment pass", ExamSession.JUDGMENT, true, () -> {
            var y = candidateNearExaminer(true);
            for (var a : Ability.values()) y.pet().setAbility(a, 90);
            y.pet().setPedigree(60);
            return y;
        }, 14000, r -> {
            var pet = r.candidate.pet(); var rec = last(r.candidate);
            boolean poise = ExamHall.of(examiner) != null && ExamHall.of(examiner).supports(Ability.POISE);
            check("forced judgment success awards gold", pet.grade() == BadgeGrade.GOLD, "grade=" + pet.grade() + " poiseSupported=" + poise);
            check("judgment record: type 4, passed, gold, this examiner", rec != null && rec.type() == ExamSession.JUDGMENT && rec.outcome() == 0 && rec.result() == BadgeGrade.GOLD && examiner.getUUID().equals(rec.examiner()), describe(rec));
            check("judgment scores clear the gold pass marks in every section", rec != null && Arrays.stream(rec.scores()).allMatch(v -> v >= 70), describe(rec));
            check("judgment fee 9 is charged and 3 refunded (net 6)", r.cash - ShopFixtures.emeralds(player) == 6, "cash " + r.cash + " -> " + ShopFixtures.emeralds(player));
            check("gold badge is issued by the examiner and worn", examiner.getUUID().equals(pet.issuer()) && pet.serial() != null && pet.visibleGrade() == BadgeGrade.GOLD, "issuer=" + pet.issuer() + " serial=" + pet.serial() + " visible=" + pet.visibleGrade());
            int days; try { days = HumanWorld.BADGE_VALIDITY_DAYS.get(); } catch (RuntimeException e) { days = 20; }
            check("gold badge expiry follows the configured validity", days <= 0 ? pet.expiresAt() == -1 : pet.expiresAt() > level.getGameTime(), "days=" + days + " expiresAt=" + pet.expiresAt() + " now=" + level.getGameTime());
            check("a passed judgment adds the pressure of harder questions", pet.stress() > r.stress, "stress " + r.stress + " -> " + pet.stress());
        });

        // 3. judgment, forced failure
        exam("judgment fail", ExamSession.JUDGMENT, false, () -> {
            var y = candidateNearExaminer(true);
            for (var a : Ability.values()) y.pet().setAbility(a, 90);
            return y;
        }, 14000, r -> {
            var pet = r.candidate.pet(); var rec = last(r.candidate);
            check("forced judgment failure leaves no badge", pet.grade() == BadgeGrade.NONE, "grade=" + pet.grade());
            check("failure raises the stress", pet.stress() > r.stress, "stress " + r.stress + " -> " + pet.stress());
            check("failure record: type 4, outcome 2, result none", rec != null && rec.type() == ExamSession.JUDGMENT && rec.outcome() == 2 && rec.result() == BadgeGrade.NONE, describe(rec));
            check("failed judgment is not refunded (fee 9 stays spent)", r.cash - ShopFixtures.emeralds(player) == 9, "cash " + r.cash + " -> " + ShopFixtures.emeralds(player));
        });

        // 4. promotion bronze -> silver
        exam("promotion", ExamSession.PROMOTION, true, () -> {
            var y = candidateNearExaminer(true);
            for (var a : Ability.values()) y.pet().setAbility(a, 90);
            y.pet().issue(BadgeGrade.BRONZE, 3, examiner.getUUID());
            check("promotion candidate starts with a bronze badge", y.pet().grade() == BadgeGrade.BRONZE && ExamSession.refusal(examiner, player, y, ExamSession.PROMOTION) == null,
                    "grade=" + y.pet().grade() + " refusal=" + ExamSession.refusal(examiner, player, y, ExamSession.PROMOTION));
            return y;
        }, 11000, r -> {
            var pet = r.candidate.pet(); var rec = last(r.candidate);
            check("forced promotion success raises bronze to silver", pet.grade() == BadgeGrade.SILVER, "grade=" + pet.grade());
            check("promotion record: type 5, outcome 0, silver", rec != null && rec.type() == ExamSession.PROMOTION && rec.outcome() == 0 && rec.result() == BadgeGrade.SILVER, describe(rec));
            check("promotion fee 4 is charged", r.cash - ShopFixtures.emeralds(player) == 4, "cash " + r.cash + " -> " + ShopFixtures.emeralds(player));
            check("silver badge has an expiry in the future (or none if validity is 0)", pet.expiresAt() == -1 || pet.expiresAt() > level.getGameTime(), "expiresAt=" + pet.expiresAt() + " now=" + level.getGameTime());
            var goldRefusal = refusalForGold();
            check("a gold animal cannot be promoted further", "max_grade".equals(goldRefusal), "refusal=" + goldRefusal);
        });

        // 5. renewal of an expired silver badge
        exam("renewal", ExamSession.RENEWAL, true, () -> {
            var y = candidateNearExaminer(true);
            for (var a : Ability.values()) y.pet().setAbility(a, 90);
            y.pet().issue(BadgeGrade.SILVER, 3, examiner.getUUID());
            var tag = y.pet().save(); tag.putLong("ExpiresAt", Math.max(0, level.getGameTime() - 100)); y.pet().load(tag);
            check("renewal candidate is an expired silver", y.pet().grade() == BadgeGrade.SILVER && y.pet().expired() && y.pet().effectiveGrade() == BadgeGrade.BRONZE,
                    "grade=" + y.pet().grade() + " expired=" + y.pet().expired() + " effective=" + y.pet().effectiveGrade() + " expiresAt=" + y.pet().expiresAt() + " now=" + level.getGameTime());
            check("renewal is allowed for the expired silver", ExamSession.refusal(examiner, player, y, ExamSession.RENEWAL) == null, "refusal=" + ExamSession.refusal(examiner, player, y, ExamSession.RENEWAL));
            return y;
        }, 6000, r -> {
            var pet = r.candidate.pet(); var rec = last(r.candidate);
            check("renewal pushes the expiry forward", !pet.expired() && pet.expiresAt() > level.getGameTime(), "expiresAt=" + pet.expiresAt() + " before=" + r.expiryBefore + " now=" + level.getGameTime());
            check("renewal keeps the silver grade", pet.grade() == BadgeGrade.SILVER && pet.effectiveGrade() == BadgeGrade.SILVER, "grade=" + pet.grade());
            check("renewal record: type 6, outcome 1 (kept)", rec != null && rec.type() == ExamSession.RENEWAL && rec.outcome() == 1 && rec.result() == BadgeGrade.SILVER, describe(rec));
            check("renewal fee 2 is charged", r.cash - ShopFixtures.emeralds(player) == 2, "cash " + r.cash + " -> " + ShopFixtures.emeralds(player));
        });
    }

    private String refusalForGold() {
        var y = ShopFixtures.spawn(level, ModEntities.REIMU, player.getX(), player.getY(), player.getZ() + 1);
        ShopFixtures.needs(y); HumanCommerce.assign(y, player.getUUID(), "companion");
        y.pet().issue(BadgeGrade.GOLD, 3, examiner.getUUID());
        var why = ExamSession.refusal(examiner, player, y, ExamSession.PROMOTION);
        y.discard();
        return why;
    }

    // ------------------------------------------------------------------ d. lessons

    @GameTest(templateNamespace = P0Mod.ID, template = "empty", timeoutTicks = 2400, batch = "pet_shop")
    public static void homeLessons(GameTestHelper h) {
        var t = new PetShopGameTests(h, "lessons", 3, 24, 24);
        t.critical("fixture chunks become entity ticking", 800, t::chunksStep);
        t.step("lesson: academics with owner feedback", 5, () -> {
            t.clearEntities(); t.flatFloor(24, 24);
            t.level.setDayTime(6000); t.level.updateSkyBrightness();
            t.player = t.newPlayer("PetShopTutor");
            t.player.moveTo(t.ox + 8.5, 65, t.oz + 10.5, 0, 0);
            t.subject = t.ownedPet(ModEntities.REIMU, new Vec3(t.ox + 11.5, 65, t.oz + 10.5));
            t.subject.pet().setAbility(Ability.ACADEMICS, 10); t.subject.pet().setPedigree(50);
            t.cash = t.subject.pet().ability(Ability.ACADEMICS);
            return true;
        });
        t.step("lesson: start", 120, () -> {
            var lessons = t.subject.pet().lessons();
            var why = lessons.refusal(t.player, Ability.ACADEMICS);
            t.note = "refusal=" + why + " pos=" + t.subject.position() + " player=" + t.player.position();
            if (why != null) return false;
            boolean started = lessons.start(t.player, Ability.ACADEMICS);
            t.check("PetLessons.start(ACADEMICS) is accepted", started, "refusal=" + why);
            t.check("lesson is running and the pet is locked", lessons.state() == PetLessons.State.RUNNING && t.subject.pet().busyWith().equals(PetLessons.LOCK),
                    "state=" + lessons.state() + " busyWith=" + t.subject.pet().busyWith());
            t.clickTick = t.h.getTick();
            return true;
        });
        t.step("lesson: scene ends in the feedback window", 700, () -> {
            var lessons = t.subject.pet().lessons();
            t.note = "state=" + lessons.state() + " awaiting=" + lessons.awaitingFeedback() + " elapsed=" + (t.h.getTick() - t.clickTick) + " busyWith=" + t.subject.pet().busyWith() + " pos=" + t.subject.position();
            if (!lessons.awaitingFeedback()) return false;
            t.check("after the scene the lesson awaits owner feedback", lessons.state() == PetLessons.State.AWAITING && lessons.kind() == Ability.ACADEMICS, "state=" + lessons.state() + " kind=" + lessons.kind());
            t.check("the lesson cooldown is set and today's count is 1", t.subject.pet().cooldown() > 0 && lessons.today() == 1, "cooldown=" + t.subject.pet().cooldown() + " today=" + lessons.today());
            lessons.feedback(t.player, CareAction.GENTLE);
            int now = t.subject.pet().ability(Ability.ACADEMICS);
            t.check("gentle feedback raises the ACADEMICS ability", now > t.cash, "ACADEMICS " + t.cash + " -> " + now);
            t.check("feedback closes the lesson", lessons.state() == PetLessons.State.IDLE && !lessons.awaitingFeedback(), "state=" + lessons.state());
            var again = lessons.refusal(t.player, Ability.ACADEMICS);
            t.check("a new lesson is refused during the cooldown", again != null && !lessons.start(t.player, Ability.ACADEMICS), "refusal=" + again + " cooldown=" + t.subject.pet().cooldown());
            return true;
        });
        t.begin();
    }

    // ------------------------------------------------------------------ e. breeding

    private YukkuriEntity mate, spare;

    @GameTest(templateNamespace = P0Mod.ID, template = "empty", timeoutTicks = 4500, batch = "pet_shop")
    public static void breeding(GameTestHelper h) {
        var t = new PetShopGameTests(h, "breeding", 4, 41, 33);
        t.shopSteps();
        t.breedingSteps();
        t.begin();
    }

    private void breedingSteps() {
        step("breeding setup", 5, () -> {
            freezeShop();
            player = newPlayer("PetShopBreeder");
            check("player stands in reach of the keeper", ShopFixtures.placeFor(level, player, keeper, 2.0), "player=" + player.blockPosition() + " keeper=" + keeper.blockPosition());
            ShopFixtures.giveEmeralds(player, 100);
            subject = ownedPet(ModEntities.REIMU, player.position().add(1, 0, 0));
            mate = ownedPet(ModEntities.MARISA, player.position().add(-1, 0, 0));
            check("both pets fit beside the player in sight", ShopFixtures.placeNear(level, subject, player, 1.0) && ShopFixtures.placeNear(level, mate, player, 1.0),
                    "player=" + player.blockPosition() + " a=" + subject.blockPosition() + " b=" + mate.blockPosition());
            subject.setNoAi(true); mate.setNoAi(true);
            check("both pets are adult companions of the player", subject.pet().isPet() && mate.pet().isPet() && subject.family().adult() && mate.family().adult(),
                    "a=" + use(subject) + "/" + subject.family().stage() + " b=" + use(mate) + "/" + mate.family().stage());
            check("two ready adults pass arrangedMatch and compatibleWith", FamilyLife.arrangedMatch(subject, mate) && subject.family().compatibleWith(mate),
                    "arranged=" + FamilyLife.arrangedMatch(subject, mate) + " compatible=" + subject.family().compatibleWith(mate));
            return true;
        });
        step("courtship gate and permits", 5, () -> {
            long now = level.getGameTime();
            int[] none = new int[3];
            for (int i = 0; i < 40; i++) { none[subject.pet().courtshipGate(mate)]++; none[mate.pet().courtshipGate(subject)]++; }
            check("without a permit every courtship roll is 1 (proceeds) or 2 (backs off)", none[0] == 0 && none[1] + none[2] == 80, "gate counts 0/1/2=" + Arrays.toString(none));
            subject.pet().permitBreeding(mate.getUUID(), now + 24000);
            int[] one = new int[3];
            for (int i = 0; i < 40; i++) { one[subject.pet().courtshipGate(mate)]++; }
            int back = 0; for (int i = 0; i < 40; i++) if (mate.pet().courtshipGate(subject) != 0) back++;
            check("a one-way permit opens only that pet's gate", one[0] == 40 && back == 40, "permitted side gate counts=" + Arrays.toString(one) + " other side nonzero=" + back + "/40");
            mate.pet().permitBreeding(subject.getUUID(), now + 24000);
            int open = 0; for (int i = 0; i < 40; i++) if (subject.pet().courtshipGate(mate) == 0 && mate.pet().courtshipGate(subject) == 0) open++;
            check("permits in both directions make the gate 0", open == 40 && subject.pet().breedingPermitted(mate.getUUID()) && mate.pet().breedingPermitted(subject.getUUID()), "open=" + open + "/40");
            check("the permit lasts about a day", subject.pet().permitUntil(mate.getUUID()) >= now + 23000, "until=" + subject.pet().permitUntil(mate.getUUID()) + " now=" + now);
            subject.pet().permitBreeding(mate.getUUID(), now - 1);
            check("an expired permit no longer opens the gate", !subject.pet().breedingPermitted(mate.getUUID()) && subject.pet().courtshipGate(mate) != 0, "permitted=" + subject.pet().breedingPermitted(mate.getUUID()));
            subject.pet().revokeBreeding(mate.getUUID()); mate.pet().revokeBreeding(subject.getUUID());
            return true;
        });
        step("inheritPet gives owner, pedigree and manners", 5, () -> {
            subject.pet().setPedigree(60); subject.pet().setAbility(Ability.MANNERS, 50);
            mate.pet().setPedigree(80); mate.pet().setAbility(Ability.MANNERS, 30);
            int lo = 200, hi = -1; boolean owners = true, uses = true, manners = true, noBadge = true;
            var made = new ArrayList<YukkuriEntity>();
            for (int i = 0; i < 8; i++) {
                var child = ShopFixtures.spawn(level, ModEntities.REIMU, player.getX() + 3, player.getY(), player.getZ() + 3);
                child.setNoAi(true);
                HumanCommerce.inheritPet(child, subject, mate);
                owners &= HumanCommerce.ownedBy(child, player.getUUID()); uses &= use(child).equals("companion");
                manners &= child.pet().ability(Ability.MANNERS) == 8; noBadge &= child.pet().grade() == BadgeGrade.NONE;
                lo = Math.min(lo, child.pet().pedigree()); hi = Math.max(hi, child.pet().pedigree());
                made.add(child);
            }
            made.forEach(Entity::discard);
            check("child inherits the mother's owner and the companion use", owners && uses, "owners=" + owners + " uses=" + uses);
            check("child pedigree is the parents' average (70) within +-10", lo >= 60 && hi <= 80, "pedigree range " + lo + ".." + hi);
            check("child manners start at 20% of the parents' average (8)", manners, "manners ok=" + manners);
            check("badges are never inherited", noBadge, "noBadge=" + noBadge);
            mate.pet().issue(BadgeGrade.SILVER, 3, null);
            var child = ShopFixtures.spawn(level, ModEntities.REIMU, player.getX() + 3, player.getY(), player.getZ() + 3);
            child.setNoAi(true);
            HumanCommerce.inheritPet(child, subject, mate);
            check("a silver parent adds 5 to the child's pedigree", child.pet().pedigree() >= 65 && child.pet().pedigree() <= 85 && child.pet().grade() == BadgeGrade.NONE, "pedigree=" + child.pet().pedigree() + " grade=" + child.pet().grade());
            child.discard();
            var stud = keeper.animals("stud").get(0);
            var nursery = ShopFixtures.spawn(level, ModEntities.REIMU, player.getX() + 3, player.getY(), player.getZ() + 3);
            nursery.setNoAi(true);
            HumanCommerce.inheritPet(nursery, stud, null);
            check("a stud mother's young join the keeper's nursery", HumanCommerce.ownedBy(nursery, keeper.getUUID()) && use(nursery).equals("shop_nursery"), "owner=" + ownerOf(nursery) + " use=" + use(nursery));
            check("nursery young take the stud's pedigree (+5 for silver) and 20% of its manners",
                    nursery.pet().pedigree() >= 75 && nursery.pet().pedigree() <= 95 && nursery.pet().ability(Ability.MANNERS) == Math.round(stud.pet().ability(Ability.MANNERS) / 1f * .2f),
                    "pedigree=" + nursery.pet().pedigree() + " manners=" + nursery.pet().ability(Ability.MANNERS) + " stud manners=" + stud.pet().ability(Ability.MANNERS));
            nursery.discard();
            return true;
        });
        step("keeper matchmaking: book a date", 5, () -> {
            mate.discard();
            for (var extra : level.getEntitiesOfClass(YukkuriEntity.class, area, e -> e.pet().ownedBy(player.getUUID()) && e != subject)) extra.discard();
            for (var stud : keeper.animals("stud")) { ShopFixtures.needs(stud); ShopFixtures.adult(stud); }
            ShopFixtures.needs(subject); subject.setNoAi(true);
            ShopFixtures.placeFor(level, player, keeper, 2.0);
            check("pet stands beside the player in sight", ShopFixtures.placeNear(level, subject, player, 1.0), "player=" + player.blockPosition() + " pet=" + subject.blockPosition());
            subject.setNoAi(true);
            spare = keeper.animals("stud").get(0);
            var menu = new KeeperMenu(61, player.getInventory(), keeper.getId()); player.containerMenu = menu;
            cash = ShopFixtures.emeralds(player);
            boolean match = FamilyLife.arrangedMatch(subject, spare);
            check("the booking screen says the date is possible", menu.value(9) == 0 && menu.value(10) == KeeperMenu.FEE && menu.value(7) == 1,
                    "state=" + menu.value(9) + " (0 ok,1 night,2 emeralds,3 no animal,4 no stud,5 cannot mate) fee=" + menu.value(10) + " subject index=" + menu.value(7) + " arrangedMatch=" + match);
            boolean clicked = menu.clickMenuButton(player, 2);
            clickTick = h.getTick();
            check("the date is booked", clicked, "arrangedMatch=" + match + " stud=" + spare.getUUID());
            check("the fee of 6 emeralds is taken", !clicked || ShopFixtures.emeralds(player) == cash - KeeperMenu.FEE, "cash " + cash + " -> " + ShopFixtures.emeralds(player));
            if (!clicked) subjectId = null; else subjectId = spare.getUUID();
            return true;
        });
        step("keeper matchmaking: outcome", 600, () -> {
            if (subjectId == null) return true;
            note = "elapsed=" + (h.getTick() - clickTick) + " petBusy=" + subject.pet().busyWith() + " studBusy=" + spare.pet().busyWith();
            if (subject.pet().busy() || spare.pet().busy()) return false;
            boolean pregnant = subject.family().brood() != null;
            boolean permit = subject.pet().breedingPermitted(subjectId) && spare.pet().breedingPermitted(subject.getUUID());
            int cashNow = ShopFixtures.emeralds(player);
            MCYukkuri.LOGGER.info("[pet_shop/{}] matchmaking outcome: {}", label, pregnant ? "CONCEIVED (brood exists)" : "REFUNDED (conceive returned null)");
            if (pregnant) check("matchmaking conceived: brood exists, fee kept and permits recorded both ways", cashNow == cash - KeeperMenu.FEE && permit,
                    "cash " + cash + " -> " + cashNow + " permit=" + permit + " mother conceptions=" + subject.family().conceptions());
            else check("matchmaking found no conception: fee refunded and no permit", cashNow == cash && !permit && !FamilyData.get(level).busyParent(subject.getUUID()),
                    "cash " + cash + " -> " + cashNow + " permit=" + permit + " (conceive returned null, e.g. population budget)");
            return true;
        });
        step("keeper night refusal", 5, () -> {
            var menu = new KeeperMenu(62, player.getInventory(), keeper.getId());
            level.setDayTime(18000); level.updateSkyBrightness();
            int before = ShopFixtures.emeralds(player);
            boolean clicked = menu.clickMenuButton(player, 2);
            int state = menu.value(9);
            level.setDayTime(6000); level.updateSkyBrightness();
            check("matchmaking is refused at night without charge", !clicked && state == 1 && ShopFixtures.emeralds(player) == before, "clicked=" + clicked + " state slot=" + state + " (1 = night) cash " + before + " -> " + ShopFixtures.emeralds(player));
            return true;
        });
    }

    // ------------------------------------------------------------------ f. pet core

    @GameTest(templateNamespace = P0Mod.ID, template = "empty", timeoutTicks = 1500, batch = "pet_shop")
    public static void petCore(GameTestHelper h) {
        var t = new PetShopGameTests(h, "core", 5, 24, 24);
        t.critical("fixture chunks become entity ticking", 800, t::chunksStep);
        t.step("expiry and effective grade", 5, () -> {
            t.clearEntities(); t.flatFloor(24, 24);
            t.level.setDayTime(6000); t.level.updateSkyBrightness();
            var y = ShopFixtures.spawn(t.level, ModEntities.REIMU, t.ox + 5.5, 65, t.oz + 5.5);
            var pet = y.pet(); var examinerId = UUID.randomUUID();
            long now = t.level.getGameTime();
            int days; try { days = HumanWorld.BADGE_VALIDITY_DAYS.get(); } catch (RuntimeException e) { days = 20; }
            pet.setPedigree(50);
            pet.issue(BadgeGrade.SILVER, 3, examinerId);
            t.check("fresh silver expires after the configured validity", pet.expiresAt() == (days > 0 ? now + days * 24000L : -1), "days=" + days + " expiresAt=" + pet.expiresAt() + " now=" + now);
            t.check("fresh silver is valid and effective silver", !pet.expired() && pet.effectiveGrade() == BadgeGrade.SILVER, "expired=" + pet.expired() + " effective=" + pet.effectiveGrade());
            int validPrice = ShopPricing.price(y);
            var tag = pet.save(); tag.putLong("ExpiresAt", Math.max(0, now - 1)); pet.load(tag);
            t.check("expired silver is still silver but effectively bronze", pet.grade() == BadgeGrade.SILVER && pet.expired() && pet.effectiveGrade() == BadgeGrade.BRONZE,
                    "grade=" + pet.grade() + " expired=" + pet.expired() + " effective=" + pet.effectiveGrade());
            t.check("expired badge is quoted one grade lower", ShopPricing.price(y) < validPrice, "price valid=" + validPrice + " expired=" + ShopPricing.price(y));
            t.check("an expired badge stays worn with its printed grade (only the effective grade drops)", pet.visibleGrade() == BadgeGrade.SILVER && pet.worn(), "visible=" + pet.visibleGrade());
            long oldExpiry = pet.expiresAt();
            pet.renew(examinerId);
            t.check("renew restores validity, forward in time", !pet.expired() && pet.expiresAt() > oldExpiry && pet.effectiveGrade() == BadgeGrade.SILVER && pet.source() == 6,
                    "expiresAt " + oldExpiry + " -> " + pet.expiresAt() + " source=" + pet.source());
            pet.issue(BadgeGrade.GOLD, 3, examinerId);
            tag = pet.save(); tag.putLong("ExpiresAt", Math.max(0, now - 1)); pet.load(tag);
            t.check("expired gold counts as silver", pet.effectiveGrade() == BadgeGrade.SILVER, "effective=" + pet.effectiveGrade());
            pet.issue(BadgeGrade.BRONZE, 3, examinerId);
            t.check("bronze never expires", pet.expiresAt() == -1 && !pet.expired() && pet.effectiveGrade() == BadgeGrade.BRONZE, "expiresAt=" + pet.expiresAt());
            pet.issue(BadgeGrade.NONE, 0, null);
            t.check("issuing NONE revokes the badge", pet.grade() == BadgeGrade.NONE && pet.serial() == null && pet.effectiveGrade() == BadgeGrade.NONE, "grade=" + pet.grade() + " serial=" + pet.serial());
            y.discard();
            return true;
        });
        t.step("records, permits and clamps", 5, () -> {
            var y = ShopFixtures.spawn(t.level, ModEntities.REIMU, t.ox + 5.5, 65, t.oz + 5.5);
            var pet = y.pet(); var examinerId = UUID.randomUUID(); var partner = UUID.randomUUID();
            pet.setPedigree(70); pet.issue(BadgeGrade.SILVER, 3, examinerId); pet.setAbility(Ability.WITS, 64); pet.addStress(20);
            pet.record(new ExamRecord(4, 1234, new int[] {80, 70, 60, 50}, BadgeGrade.SILVER, 0, examinerId));
            pet.permitBreeding(partner, t.level.getGameTime() + 5000);
            var saved = pet.save();
            var other = ShopFixtures.spawn(t.level, ModEntities.MARISA, t.ox + 9.5, 65, t.oz + 5.5);
            other.pet().load(saved);
            var op = other.pet(); var rec = op.history().isEmpty() ? null : op.history().get(0);
            t.check("save and load keep grade, serial, expiry and issuer", op.grade() == BadgeGrade.SILVER && java.util.Objects.equals(op.serial(), pet.serial()) && op.expiresAt() == pet.expiresAt() && examinerId.equals(op.issuer()),
                    "grade=" + op.grade() + " expiresAt=" + op.expiresAt() + "/" + pet.expiresAt());
            t.check("save and load keep abilities, pedigree and stress", op.ability(Ability.WITS) == 64 && op.pedigree() == 70 && op.stress() == 20, "wits=" + op.ability(Ability.WITS) + " pedigree=" + op.pedigree() + " stress=" + op.stress());
            t.check("save and load keep the exam history", rec != null && rec.type() == 4 && rec.gameTime() == 1234 && Arrays.equals(rec.scores(), new int[] {80, 70, 60, 50}) && rec.result() == BadgeGrade.SILVER, describe(rec));
            t.check("save and load keep the breeding permit", op.breedingPermitted(partner), "permitted=" + op.breedingPermitted(partner));
            for (int i = 0; i < 20; i++) pet.record(new ExamRecord(3, i, null, BadgeGrade.NONE, 2, null));
            t.check("history keeps at most 16 records, newest last", pet.history().size() == 16 && pet.history().get(15).gameTime() == 19, "size=" + pet.history().size());
            pet.addStress(500); int high = pet.stress(); pet.addStress(-500); int low = pet.stress();
            t.check("stress is clamped to 0..100", high == 100 && low == 0, "high=" + high + " low=" + low);
            pet.setPedigree(100); float top = pet.learningMultiplier(); pet.setPedigree(0); float bottom = pet.learningMultiplier();
            t.check("pedigree scales learning between 0.7 and 1.3", Math.abs(top - 1.3f) < .01f && Math.abs(bottom - .7f) < .01f, "top=" + top + " bottom=" + bottom);
            pet.setAbility(Ability.ACADEMICS, 0); double worst = pet.successChance(Ability.ACADEMICS, BadgeGrade.GOLD);
            pet.setAbility(Ability.ACADEMICS, 100); double best = pet.successChance(Ability.ACADEMICS, BadgeGrade.BRONZE);
            t.check("success chance stays within 0.05..0.95", worst >= .05 - 1e-9 && best <= .95 + 1e-9 && best > worst, "worst=" + worst + " best=" + best);
            y.discard(); other.discard();
            return true;
        });
        t.begin();
    }
}
