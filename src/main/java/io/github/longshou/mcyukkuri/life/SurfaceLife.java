package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;

/** Integrates actual grounded distance, never idle time, unloaded time or a teleported path. */
public final class SurfaceLife {
    private final YukkuriEntity entity;
    private Vec3 last;
    private float abrasion,soilDistance;
    public SurfaceLife(YukkuriEntity entity){this.entity=entity;}
    public void tick(){
        var previous=last;last=entity.position();
        if(previous==null||entity.isNoAi()||!entity.onGround()||entity.operations().active()||!entity.isAlive()||entity.isPassenger())return;
        double moved=last.subtract(previous).horizontalDistance();
        if(moved<=0||moved>.5||Math.abs(last.y-previous.y)>.2)return;
        var floor=Facilities.floor(entity);
        if(floor.is(Facilities.SOIL.get())){soilDistance+=moved;if(soilDistance>=2){soilDistance-=2;entity.life().applyEnvironment(0,1,0);}}
        if(!floor.is(Facilities.SANDPAPER.get()))return;
        abrasion+=moved*Facilities.roughness(floor);
        if(abrasion>=1.5f){
            abrasion-=1.5f;
            entity.hurt(new DamageSource(entity.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(Facilities.ABRASION),entity.position().add(0,-1,0)),1);
        }
    }
    public CompoundTag save(){var t=new CompoundTag();t.putFloat("Abrasion",abrasion);t.putFloat("SoilDistance",soilDistance);return t;}
    public void load(CompoundTag t){last=null;abrasion=bounded(t.getFloat("Abrasion"),1.5f);soilDistance=bounded(t.getFloat("SoilDistance"),2);}
    private static float bounded(float value,float max){return Float.isFinite(value)?Math.clamp(value,0,max):0;}
}
