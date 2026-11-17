package io.github.longshou.mcyukkuri.interaction;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.item.CarrierContents;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** The occupant's operation stores the table position. A table never saves a second occupant record. */
public final class RestraintTableBlockEntity extends BlockEntity {
    public RestraintTableBlockEntity(BlockPos pos, BlockState state) { super(ModBlocks.RESTRAINT_ENTITY.get(), pos, state); }

    public YukkuriEntity occupant() {
        return level.getEntitiesOfClass(YukkuriEntity.class, new AABB(worldPosition).inflate(1),
                body -> worldPosition.equals(body.operations().table())).stream().findFirst().orElse(null);
    }

    public boolean fits(YukkuriEntity body) {
        var surface=RestraintTableBlock.surface(worldPosition);
        double reach=Math.max(body.getBbHeight(),body.getBbWidth())/2;
        var room=new AABB(surface.x-reach,surface.y,surface.z-reach,surface.x+reach,
                surface.y+Math.max(body.getBbHeight(),body.getBbWidth()),surface.z+reach);
        return level.noCollision(body,room) && !level.containsAnyLiquid(room) && SurfaceApproach.stands(body,surface)
                && level.getEntities(body,room,other->other instanceof LivingEntity living&&living.isAlive()&&!living.isSpectator()).isEmpty();
    }

    public YukkuriWorldData.GroundRelease placeFrom(LivingEntity actor, ItemStack basket, UUID token, float yaw) {
        if (occupant() != null) return new YukkuriWorldData.GroundRelease(null, "busy");
        var server = (ServerLevel) level;
        var destination = RestraintTableBlock.surface(worldPosition);
        var placed = YukkuriWorldData.get(server).releaseOnSurface(token, server, destination, yaw, body -> {
            var reason = Custody.harmRefusal(actor, body, worldPosition);
            if (reason != null) return reason;
            if (!fits(body)) return "space";
            return body.body().dying() || body.life().hasUrgentNeed() ? "condition" : null;
        });
        if (placed.animal() != null) {
            CarrierContents.remove(basket, token);
            placed.animal().operations().strap(actor, worldPosition);
        }
        return placed;
    }

    void release(String reason) {
        var body = occupant();
        if (body != null) body.operations().unstrap(reason);
    }
}
