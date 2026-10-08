package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.freezer.FreezerBlock;
import io.github.longshou.mcyukkuri.freezer.FreezerModule;
import io.github.longshou.mcyukkuri.item.CarrierItem;
import io.github.longshou.mcyukkuri.item.NotebookItem;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.notebook.NotebookGuide;
import io.github.longshou.mcyukkuri.notebook.SurvivalProgress;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class FreezerGameTests {
    private record Check(String name, boolean pass) {}
    private final List<Check> checks = new ArrayList<>();
    private static final int X = 3104;
    private ServerLevel level;
    private FakePlayer player;
    private UUID motherId;
    private UUID broodId;
    private long age;
    private float nutrition;
    private int elapsed;
    private static final class ProgressPlayer extends FakePlayer {
        private net.minecraft.server.PlayerAdvancements actual;
        ProgressPlayer(ServerLevel level) { super(level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"FreezerRules")); }
        @Override public net.minecraft.server.PlayerAdvancements getAdvancements() {
            if(actual==null) actual=new net.minecraft.server.PlayerAdvancements(server.getFixerUpper(),server.getPlayerList(),server.getAdvancements(),
                    P0Control.DIRECTORY.resolve("player-"+getUUID()+".json"),this);
            return actual;
        }
    }
    private void check(String name, boolean pass) { checks.add(new Check(name, pass)); }
    @GameTest(templateNamespace=P0Mod.ID, template="empty", timeoutTicks=1200, batch="freezer")
    public static void rules(GameTestHelper helper) {
        var t = new FreezerGameTests(); t.level = helper.getLevel(); t.setup();
        helper.startSequence().thenWaitUntil(() -> GameTestChunks.assertTicking(helper,X/16,X/16+1,0,0))
            .thenExecute(() -> t.run(t::inventoryRules)).thenExecute(() -> t.run(t::pregnancyStart))
            .thenIdle(80).thenExecute(() -> t.run(t::pregnancyResume))
            .thenExecute(() -> {
                P0Control.write("freezer-checks", t.checks);
                t.level.getEntitiesOfClass(YukkuriEntity.class, new AABB(X,64,0,X+32,76,16)).forEach(Entity::discard);
                GameTestChunks.setForced(t.level,X/16,0,false); GameTestChunks.setForced(t.level,X/16+1,0,false);
                var failed = t.checks.stream().filter(c -> !c.pass()).toList();
                if (failed.isEmpty()) helper.succeed(); else helper.fail(failed.toString());
            });
    }
    private void run(Runnable action) { try { action.run(); } catch (Throwable error) { check("unexpected: " + error, false); } }
    private void setup() {
        GameTestChunks.setForced(level,X/16,0,true); GameTestChunks.setForced(level,X/16+1,0,true);
        for (int x=X; x<X+32; x++) for (int z=0; z<16; z++) {
            level.setBlockAndUpdate(new BlockPos(x,64,z), Blocks.STONE.defaultBlockState());
            for (int y=65; y<76; y++) level.setBlockAndUpdate(new BlockPos(x,y,z), Blocks.AIR.defaultBlockState());
        }
        player = new ProgressPlayer(level);
        player.moveTo(X+8,65,8); player.getInventory().clearContent();
    }
    private YukkuriEntity spawn(int offset) {
        var entity = ModEntities.REIMU.get().create(level); entity.moveTo(X+offset+.5,65,8.5,0,0); entity.setOnGround(true);
        entity.setPersistenceRequired(); level.addFreshEntity(entity);
        var life = entity.life().save(); life.putFloat("Nutrition",100); life.putFloat("Energy",90); life.putFloat("Hygiene",100); life.putFloat("Bladder",0); entity.life().load(life);
        var family = entity.family().save(); family.putBoolean("Initialized",true); entity.family().load(family);
        FamilyData.get(level).register(entity); return entity;
    }
    private ItemStack carrier(UUID token) {
        ItemStack stack = new ItemStack(ModItems.CARRIER.get());
        CustomData.update(DataComponents.CUSTOM_DATA,stack,tag -> tag.putUUID("CaptiveToken",token)); return stack;
    }
    private void inventoryRules() {
        var world = YukkuriWorldData.get(level); var id = player.getUUID();
        var creativeAnimal=spawn(25);var creativeId=creativeAnimal.getUUID();
        player.moveTo(X+25.5,65,6.5);
        player.getAbilities().instabuild=true;player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(ModItems.CARRIER.get()));
        player.interactOn(creativeAnimal,InteractionHand.MAIN_HAND);
        var creativeToken=CarrierItem.token(player.getMainHandItem());
        check("vanilla creative capture writes capability into actual hand",creativeToken!=null&&world.isCaptured(creativeId));
        var creativeReleased=world.release(creativeToken,level,new Vec3(X+25.5,65,8.5),0);
        check("creative capture remains recoverable with same identity",creativeReleased!=null&&creativeReleased.getUUID().equals(creativeId));
        if(creativeReleased!=null){
            player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(ModItems.FIELD_NOTEBOOK.get()));
            player.interactOn(creativeReleased,InteractionHand.MAIN_HAND);
            check("vanilla creative observation persists in real notebook",player.getMainHandItem().getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getCompound("ObservedSpecies").getBoolean("reimu"));
            creativeReleased.discard();
        }
        player.getAbilities().instabuild=false;
        vanillaCarrier(false,InteractionHand.MAIN_HAND,22);
        vanillaCarrier(false,InteractionHand.OFF_HAND,23);
        vanillaCarrier(true,InteractionHand.OFF_HAND,24);
        player.getAbilities().instabuild=false;
        player.moveTo(X+8,65,8);
        var first = spawn(4); UUID original = first.getUUID(); var lifeBefore = first.life().save();
        var token = world.capture(first); ItemStack stack = carrier(token); ItemStack clone = stack.copy();
        check("capture keeps family alive instead of abandonment", FamilyData.get(level).person(original).status == FamilyData.Status.ALIVE);
        check("dedicated carrier stores one living individual", FreezerBlock.transfer(level,player,stack));
        check("successful store clears carrier only after commit", CarrierItem.token(stack)==null && world.freezer.count(id)==1);
        check("old carrier token is revoked", !world.hasToken(CarrierItem.token(clone)) && world.release(token,level,new Vec3(X+4.5,65,8.5),0)==null);
        check("another player cannot retrieve this vault", world.freezer.retrieve(UUID.randomUUID())==null);
        for (int i=0;i<3;i++) check("fills remaining slot " + i, world.freezer.store(id,world.capture(spawn(8+i*3))));
        var extra = world.capture(spawn(21));
        check("capacity rejects fifth without consuming token", !world.freezer.store(id,extra) && world.hasToken(extra));
        check("stale duplicate cannot be stored", !world.freezer.store(UUID.randomUUID(),token));
        var pos = new BlockPos(X+2,65,2); level.setBlockAndUpdate(pos,FreezerModule.CABINET.get().defaultBlockState());
        level.destroyBlock(pos,true); check("breaking access point preserves all four lives", world.freezer.count(id)==4);
        level.setBlockAndUpdate(pos,FreezerModule.CABINET.get().defaultBlockState());
        try {
            var path = P0Control.DIRECTORY.resolve("freezer-roundtrip.nbt"); java.nio.file.Files.createDirectories(path.getParent());
            NbtIo.writeCompressed(world.save(new CompoundTag(),level.registryAccess()),path);
            var loaded = YukkuriWorldData.load(NbtIo.readCompressed(path,NbtAccounter.unlimitedHeap()),level.registryAccess());
            level.getServer().overworld().getDataStorage().set("mcyukkuri_living",loaded); world=loaded;
            check("disk reload preserves vault and captured identity", world.freezer.count(id)==4 && world.isCaptured(original));
            check("other dimensions use the same personal vault",YukkuriWorldData.get(level.getServer().getLevel(net.minecraft.world.level.Level.NETHER))==world);
        } catch (Exception e) { throw new IllegalStateException(e); }
        check("empty carrier retrieves earliest stored individual", FreezerBlock.transfer(level,player,stack));
        var restoredToken = CarrierItem.token(stack); check("retrieval mints one fresh capability", restoredToken!=null && !restoredToken.equals(token));
        var blocked = new Vec3(X+2.5,65,2.5);
        check("blocked release leaves live token", world.release(restoredToken,level,blocked,0)==null && world.hasToken(restoredToken));
        var released = world.release(restoredToken,level,new Vec3(X+4.5,65,8.5),0);
        check("release restores same UUID alive", released!=null && released.getUUID().equals(original) && released.isAlive());
        check("release cannot duplicate after success", world.release(restoredToken,level,new Vec3(X+5.5,65,8.5),0)==null);
        if (released != null) {
            check("storage preserves nutrition and health", released.life().nutrition()==lifeBefore.getFloat("Nutrition") && released.getHealth()==released.getMaxHealth());
            var notebook = new ItemStack(ModItems.FIELD_NOTEBOOK.get()); var before = released.life().save();
            player.setItemInHand(InteractionHand.MAIN_HAND,notebook);
            for(int i=0;i<3;i++) ModItems.FIELD_NOTEBOOK.get().interactLivingEntity(notebook,player,released,InteractionHand.MAIN_HAND);
            check("observation records actual species", notebook.get(DataComponents.CUSTOM_DATA).copyTag().getCompound("ObservedSpecies").getBoolean("reimu"));
            check("repeated observation grants no health or nutrition", before.equals(released.life().save()));
            check("guide includes each registered species", NotebookGuide.pages(notebook).size()>=18+io.github.longshou.mcyukkuri.entity.YukkuriVariant.values().length);
            var adv = level.getServer().getAdvancements().get(io.github.longshou.mcyukkuri.MCYukkuri.id("survival/observe"));
            check("observation grants only the real criterion", adv!=null && player.getAdvancements().getOrStartProgress(adv).isDone());
            var awarded = player.getAdvancements().getOrStartProgress(adv).getCriterion("performed").getObtained();
            SurvivalProgress.award(player,"observe");
            check("repeat criterion keeps original completion", awarded.equals(player.getAdvancements().getOrStartProgress(adv).getCriterion("performed").getObtained()));
            player.getAdvancements().save();player.getAdvancements().reload(level.getServer().getAdvancements());
            check("progress persists across disk reload",player.getAdvancements().getOrStartProgress(adv).isDone());
            released.discard();
        }
        while(world.freezer.count(id)>0) { var next=world.freezer.retrieve(id); var e=world.release(next,level,new Vec3(X+4.5,65,8.5),0); if(e!=null)e.discard(); }
        var e=world.release(extra,level,new Vec3(X+4.5,65,8.5),0); if(e!=null)e.discard();
    }
    private void vanillaCarrier(boolean creative,InteractionHand hand,int offset) {
        var world=YukkuriWorldData.get(level);var animal=spawn(offset);var id=animal.getUUID();
        player.moveTo(X+offset+.5,65,6.5);player.getAbilities().instabuild=creative;
        player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(net.minecraft.world.item.Items.APPLE));
        player.setItemInHand(hand,new ItemStack(ModItems.CARRIER.get()));
        player.interactOn(animal,hand);var token=CarrierItem.token(player.getItemInHand(hand));
        String label=(creative?"creative":"survival")+" "+hand;
        check(label+" actual interaction stores token",token!=null&&world.isCaptured(id));
        if(hand==InteractionHand.OFF_HAND)check(label+" leaves main hand unchanged",player.getMainHandItem().is(net.minecraft.world.item.Items.APPLE));
        var released=world.release(token,level,new Vec3(X+offset+.5,65,8.5),0);
        check(label+" recovers same identity exactly once",released!=null&&released.getUUID().equals(id)&&world.release(token,level,new Vec3(X+offset+.5,65,8.5),0)==null);
        if(released!=null){
            if(creative&&hand==InteractionHand.OFF_HAND){
                player.setItemInHand(hand,new ItemStack(ModItems.FIELD_NOTEBOOK.get()));player.interactOn(released,hand);
                check("creative offhand notebook records without replacing main hand",player.getItemInHand(hand).getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getCompound("ObservedSpecies").getBoolean("reimu")
                        &&player.getMainHandItem().is(net.minecraft.world.item.Items.APPLE));
            }
            released.discard();
        }
    }
    private void pregnancyStart() {
        var mother=spawn(8); motherId=mother.getUUID();
        var home=new BlockPos(X+8,65,8); level.setBlockAndUpdate(home,ModBlocks.NEST.get().defaultBlockState());
        for(int x=X+6;x<=X+10;x++)for(int z=6;z<=10;z++)level.setBlockAndUpdate(new BlockPos(x,69,z),Blocks.OAK_PLANKS.defaultBlockState());
        check("mother assigned real sheltered nest",mother.life().assignHome(GlobalPos.of(level.dimension(),home)));
        var brood=FamilyData.get(level).assist(mother);
        check("fixture creates a real authoritative live brood",brood!=null);
        if(brood==null)return;
        broodId=brood.id; brood.elapsed=45; elapsed=brood.elapsed; age=mother.family().age(); nutrition=mother.life().nutrition();
        check("pregnancy enters freezer without cancelling children",YukkuriWorldData.get(level).freezer.store(player.getUUID(),YukkuriWorldData.get(level).capture(mother)));
        check("frozen pregnancy remains same authoritative brood",FamilyData.get(level).brood(broodId)==brood&&!brood.cancelled);
    }
    private void pregnancyResume() {
        if(broodId==null)return;
        var family=FamilyData.get(level); var brood=family.brood(broodId);
        check("80 real server ticks do not advance stored gestation",brood.elapsed==elapsed);
        var world=YukkuriWorldData.get(level); var token=world.freezer.retrieve(player.getUUID());
        var mother=world.release(token,level,new Vec3(X+8.5,65.2,8.5),0);
        check("frozen mother resumes same UUID and brood",mother!=null&&mother.getUUID().equals(motherId)&&mother.family().brood()==brood);
        if(mother!=null) {
            check("frozen age and physiology remain paused",mother.family().age()==age&&mother.life().nutrition()==nutrition);
            check("unknown father preserved in assisted pregnancy",brood.father==null);
            mother.setOnGround(true);
            brood.elapsed=brood.duration+200;
            var position=io.github.longshou.mcyukkuri.family.LiveBirthGeometry.position(mother,0);
            var child=family.birth(brood,0,level,position);
            check("resumed original brood can birth once",child!=null&&child.family().mother().equals(motherId));
            check("same child cannot be born twice",family.birth(brood,0,level,position)==null);
        }
        family.cancel(brood);
    }
}
