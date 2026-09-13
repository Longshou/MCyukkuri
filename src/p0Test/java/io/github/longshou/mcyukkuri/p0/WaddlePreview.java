package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;

/** Controlled physical travel; ordinary aiStep selects the animation, never playAction. */
final class WaddlePreview {
    private static YukkuriEntity subject;
    private static int ticks;
    private static double speed;
    static void tick(MinecraftServer server) {
        if(subject==null||!subject.isAlive())return;
        ticks++;
        subject.setDeltaMovement(0,subject.getDeltaMovement().y,ticks<=120?-speed:0);
    }
    static void command(MinecraftServer server,JsonObject request) {
        var setup=new JsonObject();setup.addProperty("scene","house_work");CorePreview.setup(server,setup);
        var level=server.overworld();
        level.getEntitiesOfClass(YukkuriEntity.class,new AABB(160,64,0,200,80,35)).forEach(Entity::discard);
        var variant=YukkuriVariant.valueOf(request.get("variant").getAsString().toUpperCase(java.util.Locale.ROOT));
        subject=ModEntities.forVariant(variant).create(level);
        WaddleGameTests.clearGoals(subject);
        subject.moveTo(185.5,65,12,180,0);subject.yBodyRot=subject.yHeadRot=180;level.addFreshEntity(subject);
        if(variant.isHouse()&&request.has("carry")&&request.get("carry").getAsBoolean()) {
            var tag=subject.house().save();
            tag.put("Carrying",new net.minecraft.world.item.ItemStack(io.github.longshou.mcyukkuri.registry.ModItems.FOOD_SCRAP.get()).save(subject.registryAccess()));
            subject.house().load(tag);
        }
        ticks=0;speed=request.has("speed")?request.get("speed").getAsDouble():.008;
        boolean rear=request.has("rear")&&request.get("rear").getAsBoolean();
        double distance=variant.isHouse()?1.15:2.3;
        for(var player:server.getPlayerList().getPlayers()) {
            player.setGameMode(GameType.SPECTATOR);
            player.teleportTo(level,185.5+(rear?.6:distance),variant.isHouse()?63.72:64.05,
                    rear?12+distance:11.5,rear?163:90,8);
        }
        P0Control.reply(request,true,Map.of("entity",subject.getId(),"variant",variant.assetId(),"speed",speed));
    }
}
