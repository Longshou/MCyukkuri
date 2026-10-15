package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.registry.ModCreativeTabs;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Assertions use actual server ticks, entity goals, inventories and native save/load APIs. */
public final class P0Server {
    private static final AABB ARENA = new AABB(-16, 60, -16, 48, 85, 48);
    private static final List<Supplier<EntityType<YukkuriEntity>>> TYPES = List.of(
            ModEntities.REIMU, ModEntities.MARISA, ModEntities.HOUSE_REIMU, ModEntities.HOUSE_MARISA,
            ModEntities.CHEN, ModEntities.ALICE, ModEntities.PATCHOULI);
    private record Step(int delay, String name, Runnable run) {}
    private record Check(String name, boolean pass, String detail) {}
    private final Queue<Step> steps = new ArrayDeque<>();
    private final List<Check> checks = new ArrayList<>();
    private final List<UUID> displayIds = new ArrayList<>();
    private MinecraftServer server;
    private ServerLevel level;
    private JsonObject activeRequest;
    private int nextTick;
    private YukkuriEntity subject;
    private ItemEntity food;
    private FakePlayer player;
    private Vec3 initialPosition;
    private long actionStart;
    private int restoreAt = -1;

    public static void preview(MinecraftServer integrated, JsonObject request) {
        try {
            if (integrated.isDedicatedServer() || !integrated.getWorldPath(LevelResource.ROOT).normalize()
                    .getFileName().toString().equals("p0-preview")) throw new IllegalStateException("Not the isolated P0 preview world");
            String operation = request.get("command").getAsString();
            if(operation.startsWith("hat-")){HatPreview.command(integrated,request);return;}
            if(operation.startsWith("rounded-")){RoundedPreview.command(integrated,request);return;}
            if(operation.startsWith("subspecies-")){SubspeciesPreview.command(integrated,request);return;}
            if(operation.startsWith("consumable-")){ConsumablePreview.command(integrated,request);return;}
            if(operation.startsWith("predator-")){PredatorPreview.command(integrated,request);return;}
            if(operation.equals("waddle-setup")){WaddlePreview.command(integrated,request);return;}
            if(operation.startsWith("house-interaction-")){HouseInteractionPreview.command(integrated,request);return;}
            if(operation.startsWith("wound-")){WoundPreview.command(integrated,request);return;}
            if(operation.startsWith("remains-")){RemainsPreview.command(integrated,request);return;}
            if (operation.startsWith("human-")) { HumanPreview.command(integrated, request); return; }
            if (operation.startsWith("badge-")) { BadgePreview.command(integrated, request); return; }
            if (operation.startsWith("settlement-")) { SettlementPreview.command(integrated, request); return; }
            if (operation.startsWith("living-")) { LivingPreview.command(integrated, request); return; }
            if (operation.startsWith("core-")) { CorePreview.command(integrated, request); return; }
            if (operation.startsWith("operation-")) { OperationPreview.command(integrated, request); return; }
            if (operation.startsWith("ecology-")) { EcologyPreview.command(integrated, request); return; }
            if (operation.startsWith("event-")) { WildEventPreview.command(integrated, request); return; }
            if (operation.startsWith("group-")) { GroupPreview.command(integrated, request); return; }
            if (operation.startsWith("play-")) { PlaygroundPreview.command(integrated, request); return; }
            if (!List.of("setup", "view", "action", "snapshot", "save").contains(operation)) throw new IllegalArgumentException("Unsupported preview command");
            P0Server fixture = new P0Server(); fixture.server = integrated; fixture.level = integrated.overworld();
            JsonObject forwarded = request.deepCopy(); forwarded.addProperty("op", operation);
            fixture.command(forwarded);
        } catch (Exception error) { P0Control.reply(request, false, error.toString()); }
    }

    public static void runGameTest(net.minecraft.gametest.framework.GameTestHelper helper) {
        P0Server fixture = new P0Server();
        fixture.level = helper.getLevel(); fixture.server = fixture.level.getServer();
        fixture.setup(); fixture.buildChecks();
        helper.startSequence().thenWaitUntil(() -> GameTestChunks.assertTicking(helper, -1, 2, -1, 2)).thenExecute(() -> {
            long at = helper.getTick() + 1;
            for (Step step : fixture.steps) {
                at += step.delay;
                helper.runAtTickTime(at, () -> {
                    try { step.run.run(); }
                    catch (Throwable error) { fixture.checks.add(new Check(step.name, false, error.toString())); }
                    P0Control.write("server-checks", fixture.checks);
                });
            }
            helper.runAtTickTime(at + 1, () -> {
                var failures = fixture.checks.stream().filter(c -> !c.pass).toList();
                if (failures.isEmpty()) helper.succeed();
                else helper.fail(failures.size() + " checks failed; first: " + failures.getFirst().name + "; details in p0-control/server-checks.json");
            });
        });
    }

    public void started(ServerStartedEvent event) {
        server = event.getServer();
        if (!server.isDedicatedServer()) return;
        if (!server.getWorldPath(LevelResource.ROOT).normalize().getFileName().toString().equals("p0-world")) {
            throw new IllegalStateException("P0 only operates on its isolated p0-world");
        }
        level = server.overworld();
        level.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0, server);
        P0Control.write("ready", Map.of("dedicated", true, "world", "p0-world", "tick", server.getTickCount()));
        if (Files.exists(P0Control.DIRECTORY.resolve("persistence.json"))) {
            forceArena(true);
            restoreAt = server.getTickCount() + 40;
        }
    }

    public void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (level != null && event.getEntity() instanceof ServerPlayer observer) positionObserver(observer, "front");
    }

    public void tick(ServerTickEvent.Post event) {
        if (level == null || event.getServer() != server) return;
        if (restoreAt > 0 && server.getTickCount() >= restoreAt) {
            restoreAt = -1;
            verifyRestart();
        }
        if (activeRequest != null) {
            if (server.getTickCount() >= nextTick) {
                Step step = steps.poll();
                if (step != null) {
                    try { step.run.run(); }
                    catch (Throwable error) { checks.add(new Check(step.name, false, error.toString())); }
                }
                if (steps.isEmpty()) {
                    P0Control.reply(activeRequest, checks.stream().allMatch(Check::pass), checks);
                    P0Control.write("server-checks", checks);
                    activeRequest = null;
                } else nextTick = server.getTickCount() + steps.peek().delay;
            }
            return;
        }
        if (server.getTickCount() % 5 != 0) return;
        JsonObject request = P0Control.poll();
        if (request == null) return;
        try { command(request); }
        catch (Throwable error) { P0Control.reply(request, false, error.toString()); }
    }

    private void command(JsonObject request) throws Exception {
        if(request.get("op").getAsString().startsWith("integration-")){IntegrationAcceptance.command(server,request);return;}
        if(request.get("op").getAsString().startsWith("core-")){var forwarded=request.deepCopy();forwarded.addProperty("command",request.get("op").getAsString());CorePreview.command(server,forwarded);return;}
        switch (request.get("op").getAsString()) {
            case "setup" -> { setup(); P0Control.reply(request, true, snapshot()); }
            case "checks" -> {
                checks.clear(); steps.clear(); buildChecks();
                activeRequest = request; nextTick = server.getTickCount() + steps.peek().delay;
            }
            case "snapshot" -> P0Control.reply(request, true, snapshot());
            case "action" -> {
                String action = request.get("action").getAsString();
                for (YukkuriEntity entity : displays()) {
                    if (!entity.playAction(action)) throw new IllegalArgumentException("Rejected action " + action);
                }
                P0Control.reply(request, true, snapshot());
            }
            case "view" -> {
                for (ServerPlayer observer : server.getPlayerList().getPlayers()) positionObserver(observer, request.get("view").getAsString());
                P0Control.reply(request, true, snapshot());
            }
            case "save" -> {
                server.saveEverything(false, true, true);
                P0Control.reply(request, true, snapshot());
            }
            case "prepare-restart" -> {
                for (YukkuriEntity entity : displays()) {
                    entity.setHealth(7); entity.playAction("anatomy_mamu_extend");
                    field(entity, "foodCooldown", 321); field(entity, "restDelay", 456);
                }
                P0Control.write("persistence", snapshot());
                server.saveEverything(false, true, true);
                P0Control.reply(request, true, snapshot());
                server.halt(false);
            }
            case "unload" -> {
                P0Control.write("before-unload", snapshot());
                forceArena(false);
                for (ServerPlayer observer : server.getPlayerList().getPlayers()) observer.teleportTo(512, 80, 512);
                P0Control.reply(request, true, "Observers moved away; awaiting actual chunk unload");
            }
            case "unloaded" -> P0Control.reply(request, true, Map.of("loaded", level.getChunkSource().hasChunk(0, 0),
                    "entitiesPresent", displayIds.stream().filter(id -> level.getEntity(id) != null).count()));
            case "load" -> {
                forceArena(true);
                for (ServerPlayer observer : server.getPlayerList().getPlayers()) positionObserver(observer, "front");
                P0Control.reply(request, true, "Chunk load requested");
            }
            case "network-feed" -> {
                YukkuriEntity entity = displays().getFirst();
                entity.playAction("idle"); entity.setHealth(10); field(entity, "foodCooldown", 0);
                for (ServerPlayer observer : server.getPlayerList().getPlayers()) {
                    observer.setGameMode(GameType.SURVIVAL);
                    observer.teleportTo(entity.getX(), 65, entity.getZ() + 1.5);
                    observer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.APPLE, 2));
                }
                P0Control.reply(request, true, Map.of("target", entity.getId(), "uuid", entity.getUUID()));
            }
            case "players" -> P0Control.reply(request, true, server.getPlayerList().getPlayers().stream().map(p ->
                    Map.of("name", p.getGameProfile().getName(), "held", p.getMainHandItem().getCount())).toList());
            case "stop" -> { P0Control.reply(request, true, "Stopping owned P0 server"); server.halt(false); }
            default -> throw new IllegalArgumentException("Unknown P0 server operation");
        }
    }

    private void setup() {
        forceArena(true);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, server);
        level.setDayTime(6000);
        level.setWeatherParameters(0, 0, false, false);
        level.setDefaultSpawnPos(new BlockPos(3, 65, 5), 180);
        level.getEntitiesOfClass(Entity.class, ARENA, e -> e.getTags().contains("p0")).forEach(Entity::discard);
        for (int x = -16; x < 48; x++) for (int z = -16; z < 48; z++) {
            level.setBlockAndUpdate(new BlockPos(x, 64, z), Blocks.SMOOTH_STONE.defaultBlockState());
        }
        // Repeatable environmental lane: steps, one-block corridor, shelter, shallow/deep water, food floor.
        for (int x = 0; x < 9; x++) {
            level.setBlockAndUpdate(new BlockPos(x, 65, 12), Blocks.STONE_SLAB.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(x, 65, 14), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(x, 65, 17), Blocks.STONE_BRICKS.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(x, 65, 19), Blocks.STONE_BRICKS.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(x, 67, 22), Blocks.OAK_PLANKS.defaultBlockState());
        }
        for (int x = 11; x < 16; x++) for (int z = 12; z < 17; z++) {
            level.setBlockAndUpdate(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(x, 64, z), Blocks.WATER.defaultBlockState());
        }
        displayIds.clear();
        for (int i = 0; i < TYPES.size(); i++) {
            YukkuriEntity entity = spawn(TYPES.get(i).get(), i * 2, 65, 0, true);
            entity.setCustomName(Component.literal("P0 " + entity.getVariant().assetId()));
            entity.setCustomNameVisible(true); entity.setNoAi(true);
            displayIds.add(entity.getUUID());
        }
        P0Control.write("display-ids", displayIds);
        for (ServerPlayer observer : server.getPlayerList().getPlayers()) positionObserver(observer, "front");
    }

    private void forceArena(boolean force) {
        for (int x = -1; x <= 2; x++) for (int z = -1; z <= 2; z++) GameTestChunks.setForced(level, x, z, force);
    }

    private void positionObserver(ServerPlayer observer, String view) {
        observer.setGameMode(GameType.SPECTATOR);
        switch (view) {
            case "front" -> observer.teleportTo(level, 3, 65.3, 7.2, 180, 9);
            case "back" -> observer.teleportTo(level, 3, 65.3, -7.2, 0, 9);
            case "side" -> observer.teleportTo(level, 10, 65.2, 2, 105, 10);
            case "house" -> observer.teleportTo(level, 5, 65, 2.5, 180, 26);
            case "house-reimu" -> observer.teleportTo(level, 4, 63.9, 1.1, 180, 18);
            case "house-reimu-side" -> observer.teleportTo(level, 4.9, 63.9, .8, 132, 18);
            case "house-reimu-back" -> observer.teleportTo(level, 4, 63.9, -1.1, 0, 18);
            case "house-marisa" -> observer.teleportTo(level, 6, 63.9, 1.1, 180, 18);
            case "house-marisa-side" -> observer.teleportTo(level, 6.9, 63.9, .8, 132, 18);
            case "house-marisa-back" -> observer.teleportTo(level, 6, 63.9, -1.1, 0, 18);
            case "species" -> observer.teleportTo(level, 10, 65.7, 5.5, 180, 13);
            case "species-back" -> observer.teleportTo(level, 10, 65.7, -5.5, 0, 13);
            case "chen" -> observer.teleportTo(level, 10, 65.6, 2.8, 145, 17);
            case "alice" -> observer.teleportTo(level, 11.6, 65.6, 2.8, 150, 17);
            case "patchouli" -> observer.teleportTo(level, 13.6, 65.6, 2.8, 150, 17);
            case "course" -> observer.teleportTo(level, 14, 76, 29, 145, 38);
            default -> throw new IllegalArgumentException("Unknown view " + view);
        }
    }

    private List<YukkuriEntity> displays() {
        if (displayIds.isEmpty()) {
            Path saved = P0Control.DIRECTORY.resolve("display-ids.json");
            try {
                for (var id : JsonParser.parseString(Files.readString(saved)).getAsJsonArray()) displayIds.add(UUID.fromString(id.getAsString()));
            } catch (Exception error) { throw new IllegalStateException(error); }
        }
        return displayIds.stream().map(id -> (YukkuriEntity) level.getEntity(id)).filter(e -> e != null && e.isAlive()).toList();
    }

    private Object snapshot() {
        return Map.of("tick", level.getGameTime(), "serverTick", server.getTickCount(),
                "averageTickMillis", server.getAverageTickTimeNanos() / 1_000_000.0,
                "players", server.getPlayerList().getPlayers().stream().map(p -> p.getGameProfile().getName()).toList(),
                "entities", displays().stream().map(P0Server::state).toList());
    }

    public static JsonObject state(YukkuriEntity entity) {
        JsonObject state = new JsonObject();
        state.addProperty("uuid", entity.getUUID().toString()); state.addProperty("networkId", entity.getId());
        state.addProperty("variant", entity.getVariant().assetId()); state.addProperty("action", entity.getAnimationId());
        state.addProperty("start", entity.getAnimationStartTick()); state.addProperty("expression", entity.getExpressionId());
        state.addProperty("x", entity.getX()); state.addProperty("y", entity.getY()); state.addProperty("z", entity.getZ());
        state.addProperty("health", entity.getHealth());
        CompoundTag data = new CompoundTag(); entity.addAdditionalSaveData(data);
        state.addProperty("foodCooldown", data.getInt("YukkuriFoodCooldown"));
        state.addProperty("restDelay", data.getInt("YukkuriRestDelay"));
        return state;
    }

    private YukkuriEntity spawn(EntityType<YukkuriEntity> type, double x, double y, double z, boolean display) {
        YukkuriEntity entity = type.create(level);
        if (entity == null) throw new IllegalStateException("Entity creation failed");
        entity.moveTo(x, y, z, 0, 0); entity.yBodyRot = entity.yBodyRotO = 0;
        entity.addTag("p0"); entity.addTag(display ? "p0_display" : "p0_case"); entity.setPersistenceRequired();
        if (!level.addFreshEntity(entity)) throw new IllegalStateException("Entity insertion failed");
        return entity;
    }

    private void fresh(EntityType<YukkuriEntity> type) {
        clearCases();
        subject = spawn(type, 24.5, 65, 24.5, false);
        subject.getRandom().setSeed(501L);
        player = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("c06f708a-69f4-4ca3-a0b3-3a39d0b31100"), "P0Feeder"));
        player.setGameMode(GameType.SURVIVAL); player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.moveTo(25, 65, 25);
        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(true, server);
    }

    private void drop(ItemStack stack, double offset) {
        food = new ItemEntity(level, subject.getX() + offset, 65.1, subject.getZ(), stack);
        food.setDeltaMovement(Vec3.ZERO); food.setNoPickUpDelay(); food.addTag("p0"); food.addTag("p0_case");
        level.addFreshEntity(food);
    }

    private void clearCases() {
        // EntityLookup's live collection changes during discard; operate on a query snapshot.
        level.getEntitiesOfClass(Entity.class, ARENA, e -> e.getTags().contains("p0_case") || e instanceof ItemEntity).forEach(Entity::discard);
    }

    private Object foodState() {
        return Map.of("entity",state(subject),"itemAlive",food.isAlive(),"itemCount",food.getItem().getCount(),
                "items",level.getEntitiesOfClass(ItemEntity.class,ARENA).stream().map(e -> Map.of(
                        "id",e.getId(),"count",e.getItem().getCount(),"item",e.getItem().toString(),"tags",e.getTags(),"x",e.getX(),"z",e.getZ())).toList());
    }

    private void check(String name, boolean pass, Object detail) {
        var result = new Check(name, pass, String.valueOf(detail)); checks.add(result);
        if (!pass) System.out.println("MCYUKKURI_P0 entity check failed: " + result);
    }
    private void later(int ticks, String name, Runnable step) { steps.add(new Step(ticks, name, step)); }
    private static void field(YukkuriEntity entity, String name, Object value) {
        try { Field field = YukkuriEntity.class.getDeclaredField(name); field.setAccessible(true); field.set(entity, value); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private void buildChecks() {
        later(1, "registration", () -> {
            var parameters = new CreativeModeTab.ItemDisplayParameters(level.enabledFeatures(), true, level.registryAccess());
            ModCreativeTabs.YUKKURI_WORLD.get().buildContents(parameters);
            CreativeModeTab eggs = BuiltInRegistries.CREATIVE_MODE_TAB.get(CreativeModeTabs.SPAWN_EGGS);
            eggs.buildContents(parameters);
            check("notebook registered in custom tab", ModCreativeTabs.YUKKURI_WORLD.get().contains(new ItemStack(ModItems.FIELD_NOTEBOOK.get())), "five entries");
            for (var type : TYPES) {
                fresh(type.get());
                SpawnEggItem egg = SpawnEggItem.byId(type.get());
                check(subject.getVariant() + " spawn egg and both creative tabs", egg != null &&
                        ModCreativeTabs.YUKKURI_WORLD.get().contains(new ItemStack(egg)) && eggs.contains(new ItemStack(egg)), type.get());
                check(subject.getVariant() + " body and initial state", subject.getHealth() == 10 && subject.getAnimationId().equals("idle")
                        && Math.abs(subject.getBbWidth() - (subject.getVariant().isHouse() ? .27 : .8)) < .001, state(subject));
                subject.discard();
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(egg, 2));
                egg.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(new Vec3(24.5,65,24.5), Direction.UP, new BlockPos(24,64,24),false)));
                var spawned = level.getEntitiesOfClass(YukkuriEntity.class, new AABB(23,64,23,26,68,26), e -> e.getType() == type.get());
                check(type.get() + " actual spawn-egg use", spawned.size() == 1 && player.getMainHandItem().getCount() == 1, spawned.size());
                spawned.forEach(e -> { e.addTag("p0"); e.addTag("p0_case"); });
            }
        });
        for (var type : TYPES) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(type.get()).getPath();
            later(1, id + " feed", () -> {
                fresh(type.get()); subject.setHealth(6); player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.APPLE,3));
                subject.interact(player, InteractionHand.MAIN_HAND);
                check(id + " hand feed transfers once before swallowing", subject.getHealth() == 6 && player.getMainHandItem().getCount() == 2
                        && subject.life().mouth().getCount() == 1, state(subject));
                subject.setHealth(10); subject.interact(player, InteractionHand.MAIN_HAND);
                check(id + " busy mouth rejects another feed", player.getMainHandItem().getCount() == 2, player.getMainHandItem());
                field(subject,"foodCooldown",0); player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.STONE,2));
                subject.interact(player, InteractionHand.MAIN_HAND);
                check(id + " non-food rejected", player.getMainHandItem().getCount() == 2 && state(subject).get("foodCooldown").getAsInt() == 0, state(subject));
                subject.playAction("taiso"); player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.APPLE,2));
                subject.interact(player,InteractionHand.MAIN_HAND);
                check(id + " explicit action blocks hand feed", player.getMainHandItem().getCount()==2, state(subject));
                subject.playAction("idle"); subject.setHealth(8); player.setGameMode(GameType.CREATIVE);
                subject.interact(player,InteractionHand.MAIN_HAND);
                check(id + " creative feed keeps stack until swallow", player.getMainHandItem().getCount()==2 && subject.getHealth()==8
                        && subject.life().mouth().getCount()==1, state(subject));
            });
            later(75, id + " swallow", () -> check(id + " swallowing heals and clears held food", subject.getHealth()==10
                    && subject.life().mouth().isEmpty() && subject.life().nutrition()>75, state(subject)));
            later(1, id + " ground stack setup", () -> { fresh(type.get()); drop(new ItemStack(Items.APPLE,3),.15); });
            later(30, id + " ground stack", () -> check(id + " ground stack consumes one", food.isAlive() && food.getItem().getCount()==2
                    && (subject.life().mealTicks()>0 || subject.life().nutrition()>75), foodState()));
            later(1, id + " single ground setup", () -> { fresh(type.get()); drop(new ItemStack(Items.APPLE),.15); });
            later(30, id + " single ground", () -> check(id + " single item removed on eating", !food.isAlive(), state(subject)));
            later(1, id + " mobGriefing setup", () -> {
                fresh(type.get()); level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(false,server); drop(new ItemStack(Items.APPLE,2),.15);
            });
            later(30, id + " mobGriefing", () -> check(id + " mobGriefing prevents pickup", food.isAlive() && food.getItem().getCount()==2, state(subject)));
            later(1, id + " pickup delay setup", () -> { fresh(type.get()); drop(new ItemStack(Items.APPLE),.15); food.setPickUpDelay(200); });
            later(30, id + " pickup delay", () -> check(id + " pickup delay respected", food.isAlive() && state(subject).get("foodCooldown").getAsInt()==0, state(subject)));
            later(1, id + " navigation setup", () -> { fresh(type.get()); initialPosition=subject.position(); drop(new ItemStack(Items.APPLE),3); });
            later(120, id + " navigate to food", () -> check(id + " goal reaches and consumes distant food", !food.isAlive()
                    && (subject.life().mealTicks()>0 || subject.life().nutrition()>75), foodState()));
            later(1, id + " sleep setup", () -> {
                fresh(type.get()); var life=subject.life().save(); life.putFloat("Energy",5); life.putInt("RestWait",0); subject.life().load(life);
            });
            later(35, id + " sleep", () -> {
                check(id + " natural rest enters sleep", subject.getExpressionId().equals("sleep"), state(subject));
                subject.hurt(level.damageSources().generic(),1);
                check(id + " damage immediately startles", subject.getAnimationId().equals("startle"), state(subject)); initialPosition=subject.position();
            });
            later(25, id + " flee", () -> check(id + " damage interrupts rest and flees", subject.getAnimationId().equals("flee")
                    && subject.getExpressionId().equals("surprise") && subject.position().distanceTo(initialPosition)>.1, state(subject)));
            later(110, id + " recover", () -> check(id + " panic expires", !subject.getAnimationId().equals("flee") && !subject.getExpressionId().equals("surprise"), state(subject)));
            later(1, id + " animation state", () -> {
                fresh(type.get()); subject.setNoAi(true);
                for (YukkuriAction action : YukkuriAction.values()) {
                    if (action == YukkuriAction.SHELL_RETRACT) {
                        String previous = subject.getAnimationId(); long start = subject.getAnimationStartTick();
                        check(id + " ordinary form rejects shell retraction without changing pose", !subject.shellRetraction().hasShell()
                                && !subject.playAction(action.id()) && subject.getAnimationId().equals(previous)
                                && subject.getAnimationStartTick() == start, state(subject));
                    } else {
                        if (action.id().startsWith("anatomy_") && action.id().endsWith("_retract"))
                            subject.playAction(action.id().replace("_retract","_extend"));
                        check(id + " accepts " + action.id(), subject.playAction(action.id()) && subject.getAnimationId().equals(action.id()), state(subject));
                    }
                }
                check(id + " unknown action rejected", !subject.playAction("invalid"), "invalid");
                subject.playAction("idle"); subject.playAction("anatomy_mamu_retract");
                check(id + " retract hidden part stays idle", subject.getAnimationId().equals("idle"), state(subject));
                subject.playAction("anatomy_mamu_extend"); actionStart=subject.getAnimationStartTick();
            });
            later(2, id + " half retract", () -> {
                subject.playAction("anatomy_mamu_retract");
                check(id + " partial retract starts partway through clip", subject.getAnimationId().equals("anatomy_mamu_retract")
                        && subject.getAnimationStartTick()<level.getGameTime(), state(subject));
            });
            later(20, id + " retract finished", () -> {
                check(id + " retract returns idle", subject.getAnimationId().equals("idle"), state(subject));
                subject.playAction("anatomy_peni_extend");
            });
            later(15, id + " hold and interrupt", () -> {
                check(id + " extension holds last pose", subject.getAnimationId().equals("anatomy_peni_extend"), state(subject));
                subject.playAction("taiso"); actionStart=subject.getAnimationStartTick();
            });
            later(10, id + " stable start", () -> {
                check(id + " loop start tick remains stable", subject.getAnimationStartTick()==actionStart, state(subject));
                subject.playAction("turn");
            });
            later(12, id + " one-shot completed", () -> {
                check(id + " one-shot ends", subject.getAnimationId().equals("idle"), state(subject));
                subject.playAction("anatomy_mamu_extend"); subject.hurt(level.damageSources().generic(),1);
                check(id + " damage clears extension", subject.getAnimationId().equals("startle"), state(subject));
            });
            later(1, id + " NBT roundtrip", () -> {
                fresh(type.get()); subject.setNoAi(true); subject.playAction("anatomy_mamu_extend");
                field(subject,"foodCooldown",321); field(subject,"restDelay",456);
                CompoundTag tag=new CompoundTag(); subject.save(tag); UUID uuid=subject.getUUID(); subject.discard();
                YukkuriEntity restored=(YukkuriEntity)EntityType.loadEntityRecursive(tag,level,e->e);
                check(id + " native NBT preserves identity and cooldowns", restored!=null && restored.getUUID().equals(uuid)
                        && restored.getVariant()==subject.getVariant() && state(restored).get("foodCooldown").getAsInt()==321
                        && state(restored).get("restDelay").getAsInt()==456, restored==null?"null":state(restored));
                check(id + " native NBT clears transient pose", restored!=null && restored.getAnimationId().equals("idle")
                        && restored.getExpressionId().equals("neutral"), restored==null?"null":state(restored));
                if (restored!=null) level.addFreshEntity(restored);
            });
        }
        later(1,"shell-specific command guards",()-> {
            fresh(ModEntities.MARISA.get()); subject.setNoAi(true);
            check("Marisa can become the actual shell subspecies",subject.subspecies().set(
                    io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.SHELL_MARISA,
                    io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Congenital.NORMAL),state(subject));
            check("actual worn shell accepts shell retraction",subject.shellRetraction().hasShell()
                    && subject.playAction("shell_retract") && subject.getAnimationId().equals("shell_retract"),state(subject));
            UUID shell = subject.life().takeAccessory(null);
            check("removed real shell refuses shell retraction",shell != null && !subject.shellRetraction().hasShell()
                    && !subject.playAction("shell_retract"),state(subject));
            check("same shell identity can be returned",shell != null && subject.life().returnAccessory(subject.getUUID(),shell,null),state(subject));
            check("returning the actual shell restores its command",subject.shellRetraction().hasShell()
                    && subject.playAction("shell_retract") && subject.getAnimationId().equals("shell_retract"),state(subject));
        });
        later(1,"cleanup",()-> {
            clearCases();
            level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(true,server);
        });
    }

    private void verifyRestart() {
        List<Check> result = new ArrayList<>();
        try {
            JsonObject old=JsonParser.parseString(Files.readString(P0Control.DIRECTORY.resolve("persistence.json"))).getAsJsonObject();
            var entities=displays();
            result.add(new Check("all entities reloaded from disk",entities.size()==TYPES.size(),String.valueOf(entities.size())));
            for(var value:old.getAsJsonArray("entities")) {
                JsonObject previous=value.getAsJsonObject();
                YukkuriEntity entity=(YukkuriEntity)level.getEntity(UUID.fromString(previous.get("uuid").getAsString()));
                boolean valid=entity!=null && entity.getVariant().assetId().equals(previous.get("variant").getAsString())
                        && entity.getHealth()==7 && entity.getAnimationId().equals("idle")
                        && state(entity).get("foodCooldown").getAsInt()>0 && state(entity).get("foodCooldown").getAsInt()<=321
                        && state(entity).get("restDelay").getAsInt()>0 && state(entity).get("restDelay").getAsInt()<=456;
                result.add(new Check(previous.get("variant").getAsString()+" disk identity/state and transient reset",valid,entity==null?"missing":state(entity).toString()));
            }
        } catch(Exception error) { result.add(new Check("restart verification",false,error.toString())); }
        P0Control.write("restart-checks",result);
    }
}
