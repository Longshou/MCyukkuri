package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.house.HouseNestBlockEntity;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.Map;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** User-operated tour in the existing isolated P0 world. Never included in the release mod. */
final class Showcase {
    private static final BlockPos EGGS=new BlockPos(210,65,35),WORK=new BlockPos(220,65,49);
    private static final Map<BlockPos,String> BUTTONS=Map.of(new BlockPos(179,66,16),"birth",new BlockPos(199,66,16),"grow",
            new BlockPos(210,66,30),"hatch",new BlockPos(176,66,30),"care");
    private static boolean active;
    private static long lastButton=-100;
    static void commands(RegisterCommandsEvent event){
        var root=Commands.literal("yukkuridemo").executes(c->{help(c.getSource().getPlayerOrException());return 1;});
        for(String name:new String[]{"family","house","care","birth","grow","hatch","treat","reset"})
            root.then(Commands.literal(name).executes(c->{var p=c.getSource().getPlayerOrException();if(name.equals("reset"))setup(p.server);else action(p,name);return 1;}));
        event.getDispatcher().register(root);
    }
    static void button(PlayerInteractEvent.RightClickBlock event){
        if(!active||event.getHand()!=InteractionHand.MAIN_HAND||!(event.getEntity() instanceof ServerPlayer player))return;
        String action=BUTTONS.get(event.getPos());if(action==null||!player.level().getBlockState(event.getPos()).is(Blocks.STONE_BUTTON))return;
        if(player.level().getGameTime()-lastButton<10)return;lastButton=player.level().getGameTime();
        action(player,action.equals("care")?"treat":action);
    }
    static void setup(MinecraftServer server){
        var level=server.overworld();var request=new JsonObject();request.addProperty("scene","family");request.addProperty("count",5);request.addProperty("progress",.95);
        CorePreview.setup(server,request);
        for(int x=160;x<224;x++)for(int z=0;z<56;z++){
            if(x>=200||z>=35)for(int y=65;y<75;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.SMOOTH_STONE.defaultBlockState());
        }
        for(int x=168;x<=220;x++)for(int z=21;z<=23;z++)level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState());
        level.getEntitiesOfClass(ItemEntity.class,new net.minecraft.world.phys.AABB(160,60,0,224,85,56)).forEach(ItemEntity::discard);
        roof(level,194,4,204,13);roof(level,204,32,223,49);roof(level,170,32,189,43);
        for(var e:CorePreview.entities(level)){
            e.setCustomName(Component.literal(CorePreview.index(e)%2==0?"五胎展示 · 按按钮出生":"亲代"));
            bowl(level,(int)e.getX(),(int)e.getZ()+2);
        }
        var young=CorePreview.spawn(level,ModEntities.REIMU.get(),199,8,10);
        var family=young.family().save();family.putString("Stage","JUVENILE");family.putFloat("Scale",.3f);family.putLong("Age",24001);family.putLong("Nourishment",24000);young.family().load(family);
        young.setCustomName(Component.literal("成长展示 · 按按钮睡醒"));bowl(level,200,10);
        level.setBlockAndUpdate(EGGS,ModBlocks.HOUSE_NEST.get().defaultBlockState());var nest=(HouseNestBlockEntity)level.getBlockEntity(EGGS);
        var a=CorePreview.spawn(level,ModEntities.HOUSE_REIMU.get(),209.8,34.3,20);var b=CorePreview.spawn(level,ModEntities.HOUSE_MARISA.get(),211.2,34.3,21);
        if(!a.house().choose(nest)||!b.house().choose(nest))throw new IllegalStateException("Prepared house parents cannot reach their crevice");
        YukkuriWorldData.get(level).relate(a.getUUID(),b.getUUID(),30);YukkuriWorldData.get(level).relate(b.getUUID(),a.getUUID(),30);
        for(int i=0;i<6;i++)nest.deposit(new ItemStack(ModItems.NEST_LINT.get()));for(int i=0;i<20;i++)nest.deposit(new ItemStack(ModItems.FOOD_SCRAP.get()));
        // Make the prepared egg display wait for its button, while using the ordinary laying logic.
        a.setNoAi(false);b.setNoAi(false);
        for(int i=0;i<100;i++)nest.tick();a.setNoAi(true);b.setNoAi(true);
        if(nest.brood()==null)throw new IllegalStateException("Prepared nest has no eggs");
        nest.brood().duration=120000;FamilyData.get(level).setDirty();
        var tag=nest.saveWithFullMetadata(level.registryAccess());tag.putInt("Arrivals",2);nest.loadWithComponents(tag,level.registryAccess());
        level.setBlockAndUpdate(WORK,ModBlocks.HOUSE_NEST.get().defaultBlockState());var work=(HouseNestBlockEntity)level.getBlockEntity(WORK);
        var worker=CorePreview.spawn(level,ModEntities.HOUSE_MARISA.get(),218,48,22);worker.house().choose(work);worker.setNoAi(false);worker.setCustomName(Component.literal("搬料与藏身"));
        drop(level,216,48,new ItemStack(ModItems.NEST_LINT.get(),6));drop(level,216,47,new ItemStack(ModItems.FOOD_SCRAP.get(),16));
        sign(level,218,65,46,"家油搬运区","棉絮和碎食在地上","夜间观察叼回缝隙","可以继续投放");
        for(int i=0;i<2;i++){
            var e=CorePreview.spawn(level,i==0?ModEntities.REIMU.get():ModEntities.MARISA.get(),175+i*5,36,30+i);
            var body=e.body().save();body.putInt("Wound",2);body.putInt("Filling",10);if(i==1)body.putInt("Mold",3);e.body().load(body);
            e.setCustomName(Component.literal(i==0?"伤口 · 用橙汁右键":"油霉 · 橙汁与去霉药"));bowl(level,175+i*5,38);
        }
        sign(level,180,65,23,"八景核心玩法展示","蓝：茎生／成长","绿：家油巢卵","红：伤口与治疗");
        station(level,179,16,Blocks.LIGHT_BLUE_CONCRETE,"五胎茎生","右键下面按钮","开始真实出生","大约二十秒");
        station(level,199,16,Blocks.LIGHT_BLUE_CONCRETE,"成长展示","右键下面按钮","入睡后醒来长大","演示已预置年龄");
        station(level,210,30,Blocks.LIME_CONCRETE,"家油孵化","右键下面按钮","开始孵化","旁边可看搬料");
        station(level,176,30,Blocks.RED_CONCRETE,"伤口与治疗","先右键包扎／用药","再按下面按钮","开始照料计时");
        active=true;lastButton=-100;level.setDayTime(18000);
        for(var p:server.getPlayerList().getPlayers()){equip(p);teleport(p,180,65,20,180,8);help(p);}
        server.saveEverything(false,true,true);
    }
    private static void action(ServerPlayer player,String action){
        if(!active){player.sendSystemMessage(Component.literal("请先运行 /yukkuridemo reset 准备展示场景。"));return;}
        var level=player.server.overworld();
        switch(action){
            case "family"->teleport(player,180,65,16,180,3);
            case "house"->{level.setDayTime(18000);teleport(player,211,65,31,0,12);}
            case "care"->teleport(player,177.5,65,32,0,12);
            case "birth"->{
                for(var e:CorePreview.entities(level))if(CorePreview.index(e)<4){var brood=e.family().brood();if(brood!=null&&!brood.complete()){brood.duration=600;brood.elapsed=220;FamilyData.get(level).setDirty();}e.setNoAi(false);}
                player.sendSystemMessage(Component.literal("茎生已开始。约二十秒后子代依次脱落，可用观察笔记查看亲缘。"));
            }
            case "grow"->{
                CorePreview.entities(level).stream().filter(e->CorePreview.index(e)==10).forEach(e->{var life=e.life().save();life.putFloat("Energy",5);life.putFloat("Nutrition",95);life.putInt("RestWait",0);e.life().load(life);e.setNoAi(false);});
                player.sendSystemMessage(Component.literal("幼体开始休息，醒来后检查成长。此展示提前准备了年龄和营养。"));
            }
            case "hatch"->{
                if(level.getBlockEntity(EGGS) instanceof HouseNestBlockEntity nest&&nest.brood()!=null){
                    for(int i=0;i<24;i++)nest.deposit(new ItemStack(ModItems.FOOD_SCRAP.get()));nest.brood().duration=400;nest.brood().elapsed=100;
                    for(var child:nest.brood().children)if(!child.born)child.eggElapsed=100;
                    for(var e:level.getEntitiesOfClass(YukkuriEntity.class,new net.minecraft.world.phys.AABB(EGGS).inflate(8)))
                        if(e.getVariant().isHouse()&&EGGS.equals(e.house().nestPosition()))e.setNoAi(false);
                    level.setDayTime(6000);FamilyData.get(level).setDirty();
                }
                player.sendSystemMessage(Component.literal("已补充巢粮并唤醒亲代。亲代接触照卵后，幼体会依次破壳。"));
            }
            case "treat"->{CorePreview.entities(level).stream().filter(e->CorePreview.index(e)>=30&&CorePreview.index(e)<40).forEach(e->e.setNoAi(false));player.sendSystemMessage(Component.literal("照料计时已开始。橙汁治疗物理创伤，去霉药清除油霉；观察笔记查看结果。"));}
            default->help(player);
        }
    }
    private static void equip(ServerPlayer player){
        player.setGameMode(GameType.CREATIVE);player.setHealth(player.getMaxHealth());player.getFoodData().setFoodLevel(20);
        ItemStack cloth=new ItemStack(ModItems.WASH_CLOTH.get());CustomData.update(DataComponents.CUSTOM_DATA,cloth,t->t.putInt("Water",8));
        ItemStack[] items={new ItemStack(ModItems.FIELD_NOTEBOOK.get()),new ItemStack(Items.APPLE,64),new ItemStack(ModItems.ORANGE_JUICE.get(),16),new ItemStack(ModItems.MOLD_REMOVER.get(),16),cloth,new ItemStack(ModItems.NEST_LINT.get(),64),new ItemStack(ModItems.FOOD_SCRAP.get(),64),new ItemStack(ModItems.CARRIER.get()),new ItemStack(ModItems.COMPANION_CORD.get())};
        for(int i=0;i<items.length;i++)player.getInventory().setItem(i,items[i]);player.getInventory().selected=0;player.inventoryMenu.broadcastChanges();
    }
    private static void help(ServerPlayer player){player.sendSystemMessage(Component.literal("展示已准备：前方茎生与成长，右后方家油，左后方治疗。右键标牌下的按钮启动。\n快捷移动：/yukkuridemo family、house、care。重置：/yukkuridemo reset。创造模式，双击空格飞行。"));}
    private static void teleport(ServerPlayer p,double x,double y,double z,float yaw,float pitch){p.teleportTo(p.server.overworld(),x,y,z,yaw,pitch);}
    private static void roof(ServerLevel level,int x0,int z0,int x1,int z1){for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++)level.setBlockAndUpdate(new BlockPos(x,71,z),Blocks.OAK_PLANKS.defaultBlockState());}
    private static void bowl(ServerLevel level,int x,int z){var pos=new BlockPos(x,65,z);level.setBlockAndUpdate(pos,ModBlocks.FOOD_BOWL.get().defaultBlockState());((CareBlockEntity)level.getBlockEntity(pos)).setItem(0,new ItemStack(Items.APPLE,64));}
    private static void drop(ServerLevel level,double x,double z,ItemStack item){var entity=new ItemEntity(level,x,65,z,item);entity.setDeltaMovement(Vec3.ZERO);entity.setNoPickUpDelay();level.addFreshEntity(entity);}
    private static void sign(ServerLevel level,int x,int y,int z,String... lines){var pos=new BlockPos(x,y,z);level.setBlockAndUpdate(pos,Blocks.OAK_SIGN.defaultBlockState());if(level.getBlockEntity(pos) instanceof SignBlockEntity sign){var text=new SignText();for(int i=0;i<lines.length;i++)text=text.setMessage(i,Component.literal(lines[i]));sign.setText(text,true);sign.setText(text,false);sign.setChanged();level.sendBlockUpdated(pos,sign.getBlockState(),sign.getBlockState(),3);}}
    private static void station(ServerLevel level,int x,int z,net.minecraft.world.level.block.Block color,String... lines){
        level.setBlockAndUpdate(new BlockPos(x,65,z),color.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(x,66,z),Blocks.STONE_BUTTON.defaultBlockState().setValue(FaceAttachedHorizontalDirectionalBlock.FACE,AttachFace.FLOOR));
        sign(level,x+1,65,z,lines);
    }
    private Showcase(){}
}
