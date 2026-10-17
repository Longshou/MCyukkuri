package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** Current exported meshes, rendered by the actual client in the disposable P0 world. */
final class PeripheralPreview {
    static void setup(MinecraftServer server){
        var level=server.overworld();
        for(int x=37;x<=39;x++)for(int z=0;z<=1;z++)level.setChunkForced(x,z,true);
        level.getEntitiesOfClass(Entity.class,new AABB(598,63,0,634,76,24),e->e.getTags().contains("peripheral_preview")).forEach(Entity::discard);
        for(int x=598;x<634;x++)for(int z=0;z<24;z++){
            level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.SMOOTH_QUARTZ.defaultBlockState());
            for(int y=65;y<76;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
        }
        level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new AABB(598,63,0,634,76,24)).forEach(Entity::discard);
        level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
        for(int i=0;i<2;i++){
            var pos=new BlockPos(602+i*3,65,0);level.setBlockAndUpdate(pos,ModBlocks.FOOD_BOWL.get().defaultBlockState());
            ((io.github.longshou.mcyukkuri.life.CareBlockEntity)level.getBlockEntity(pos)).setItem(0,new ItemStack(i==0?net.minecraft.world.item.Items.WHEAT:net.minecraft.world.item.Items.SHORT_GRASS,8));
        }
        for(int i=0;i<3;i++){
            var pos=new BlockPos(602+i*3,65,3);level.setBlockAndUpdate(pos,ModBlocks.FOOD_BOWL.get().defaultBlockState());
            if(i>0)((io.github.longshou.mcyukkuri.life.CareBlockEntity)level.getBlockEntity(pos)).setItem(0,new ItemStack(ModItems.GRAIN_REGULAR.get(),i==1?1:8));
        }
        level.setBlockAndUpdate(new BlockPos(611,65,3),ModBlocks.NEST.get().defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(614,65,3),ModBlocks.TOILET.get().defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(617,65,3),ModBlocks.HOUSE_DUST_NEST.get().defaultBlockState());
        var nest=(io.github.longshou.mcyukkuri.house.HouseNestBlockEntity)level.getBlockEntity(new BlockPos(617,65,3));
        for(int i=0;i<6;i++)nest.deposit(new ItemStack(ModItems.NEST_LINT.get()));
        level.setBlockAndUpdate(new BlockPos(620,65,3),ModBlocks.GRAVE.get().defaultBlockState());
        String[] names={"oil_grain_budget","oil_grain_regular","oil_grain_premium","orange_juice","mold_remover","wash_cloth","teaching_whistle","yarn_ball","companion_cord","yukkuri_waste","leaked_filling","reimu_ribbon","marisa_hat","yukkuri_carrier"};
        for(int i=0;i<names.length;i++){
            var display=EntityType.ITEM_DISPLAY.create(level);var tag=new CompoundTag();
            tag.put("item",new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(io.github.longshou.mcyukkuri.MCYukkuri.id(names[i]))).save(level.registryAccess()));
            tag.putString("item_display","fixed");display.load(tag);display.moveTo(602+(i%7)*3,65.8,8+(i/7)*4,180,0);
            display.addTag("peripheral_preview");level.addFreshEntity(display);
        }
        int[] quantities={1,3,13};
        for(int i=0;i<quantities.length;i++){
            var drop=io.github.longshou.mcyukkuri.registry.ModEntities.MATERIAL_DROP.get().create(level);
            drop.setPos(605+i*2,65,18);drop.setItem(new ItemStack(ModItems.FILLING.get(),quantities[i]));
            drop.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);drop.setUnlimitedLifetime();drop.setNeverPickUp();
            drop.addTag("peripheral_preview");level.addFreshEntity(drop);
        }
        for(var player:server.getPlayerList().getPlayers()){
            player.setGameMode(GameType.SPECTATOR);player.teleportTo(level,611,71,19,180,28);
        }
    }
}
