package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/** Stages travel with the individual; the line owns batch counters, a tray and its finite output. */
public final class FactoryPipeline {
    private final HumanNpc worker;
    private final FactoryProduction line;
    private UUID trayId;
    private boolean trayBroken;
    private int purposeMode, nextPurpose = 1, saleFilter, serial, inspected, held;
    private final Map<UUID,Integer> batches = new HashMap<>();
    private final Map<UUID,Integer> batchPurposes = new HashMap<>();
    private final Set<UUID> residents = new HashSet<>();
    public FactoryPipeline(HumanNpc worker, FactoryProduction line) { this.worker=worker; this.line=line; }
    public static String stage(YukkuriEntity y) { return HumanCommerce.data(y).getString("FactoryStage"); }
    public static boolean managed(YukkuriEntity y) { return !stage(y).isEmpty() && HumanCommerce.data(y).hasUUID("Owner"); }
    public static boolean healthy(YukkuriEntity y) {
        return y.isAlive() && y.getHealth() >= y.getMaxHealth()*.8 && y.body().infection()==0 && y.body().mold()==0 && y.body().wound()==0;
    }
    public static int sizeGrade(YukkuriEntity y) { return y.family().scale() < .45 ? 0 : y.family().scale() < .8 ? 1 : 2; }
    public static int purpose(ItemStack item) { return item.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getInt("Purpose"); }
    public int purposeMode() { return purposeMode; }
    public int saleFilter() { return saleFilter; }
    public int inspected() { return inspected; }
    public int held() { return held; }
    public int activeStage() { var t=tray();return t==null||t.occupant()==null?0:List.of("","receive","nursery_transfer","nursery","grade","wash","pack","observe_transfer","observe","parent_transfer").indexOf(stage(t.occupant())); }
    public int activeWork() { var t=tray();return t==null||t.occupant()==null?0:HumanCommerce.data(t.occupant()).getInt("StageWork"); }
    public void cyclePurpose() { if (line.kind().equals("bag")) purposeMode=(purposeMode+1)%3; }
    public void cycleSales() { if (line.kind().equals("bag")) saleFilter=(saleFilter+1)%3; }
    public boolean matches(ItemStack s) { return saleFilter==0 || !line.kind().equals("bag") || purpose(s)==saleFilter; }
    public List<YukkuriEntity> animals() {
        var all = new ArrayList<>(worker.animals("nursery")); all.addAll(worker.animals("input")); return all;
    }
    public boolean operating() {
        return (line.kind().equals("bag") || line.kind().equals("manju")) && fullyLoaded() && equipped() && !trayBroken && line.material()>0 && line.totalOutputCount()<16;
    }
    public boolean equipped() { return List.of("receive","nursery","grade","wash","pack","observe").stream().allMatch(s->destinationBlock(s)!=null); }
    public boolean fullyLoaded() {
        if(!(worker.level() instanceof ServerLevel level))return false;
        var p=worker.home();
        for(int x=(p.getX()-22)>>4;x<=(p.getX()+22)>>4;x++)for(int z=(p.getZ()-22)>>4;z<=(p.getZ()+22)>>4;z++)
            if(!level.isPositionEntityTicking(new BlockPos(x*16+8,p.getY(),z*16+8)))return false;
        return true;
    }
    public int trackedPopulation() {
        var level=(ServerLevel)worker.level();var family=FamilyData.get(level);var custody=io.github.longshou.mcyukkuri.life.YukkuriWorldData.get(level);
        animals().forEach(y->residents.add(y.getUUID()));
        residents.removeIf(id->custody.isCaptured(id) || family.person(id)!=null && family.person(id).status==FamilyData.Status.DEAD
                || level.getEntity(id) instanceof YukkuriEntity y && (!HumanCommerce.ownedBy(y,worker.getUUID()) || FactoryBreeding.member(y)));
        return residents.size();
    }
    public FactoryTray tray() {
        return trayId!=null && worker.level() instanceof ServerLevel l && l.getEntity(trayId) instanceof FactoryTray t ? t : null;
    }
    public void trayDestroyed(UUID id) { if (id.equals(trayId)) { trayId=null; trayBroken=true; } }
    public boolean repair(ItemStack material) {
        if (!trayBroken || !material.is(Items.IRON_INGOT) || material.getCount()<4) return false;
        material.shrink(4); trayBroken=false; return true;
    }
    public int status() {
        if (line.material()==0) return 1;
        if (line.totalOutputCount()>=16) return 2;
        if (trayBroken) return 3;
        if (!equipped()) return 4;
        if (!fullyLoaded()) return 6;
        if (trayId!=null && tray()==null) return 7;
        var t=tray(); return t!=null && t.blocked() ? 5 : 0;
    }
    public void enroll(YukkuriEntity y) {
        residents.add(y.getUUID());
        var tag=HumanCommerce.data(y).copy();
        if (tag.contains("FactoryStage")) return;
        UUID batch=y.family().birthId()!=null ? y.family().birthId() : UUID.randomUUID();
        tag.putUUID("FactoryBatch",batch); tag.putInt("BatchNumber",batches.computeIfAbsent(batch,k->++serial));
        tag.putString("FactoryStage","receive"); tag.putInt("StageWork",0);
        tag.putBoolean("FactoryYoung",tag.getString("Use").equals("nursery")); HumanCommerce.set(y,tag);
    }
    private void transition(YukkuriEntity y, String stage) {
        var tag=HumanCommerce.data(y).copy(); tag.putString("FactoryStage",stage); tag.putInt("StageWork",0);
        tag.putLong("LastFactoryWork",worker.level().getGameTime()); HumanCommerce.set(y,tag);
    }
    private BlockPos destinationBlock(String stage) {
        Block block=switch(stage) {
            case "receive" -> HumanWorld.RECEIVING_TABLE.get();
            case "nursery_transfer","nursery" -> HumanWorld.NURSERY_STATION.get();
            case "grade" -> HumanWorld.INSPECTION_TABLE.get();
            case "wash" -> HumanWorld.WASHING_TABLE.get();
            case "observe_transfer","observe" -> HumanWorld.OBSERVATION_STATION.get();
            case "pack" -> line.kind().equals("bag") ? HumanWorld.PACKING_TABLE.get() : HumanWorld.MANJU_STEAMER.get();
            default -> null;
        };
        return block==null ? null : line.station(block);
    }
    public BlockPos destination(YukkuriEntity y) {
        if(stage(y).equals("parent_transfer")) {
            var anchor=destinationBlock("nursery");if(anchor==null)return null;
            var tag=HumanCommerce.data(y);
            return anchor.relative(line.frontDirection(),tag.getInt("FactoryGroup")==0?10:6)
                    .relative(line.frontDirection().getClockWise(),tag.getInt("FactoryParentSlot")==0?-2:0);
        }
        var machine=destinationBlock(stage(y));
        if (machine==null) return null;
        // Nursery lanes alternate by brood, keeping siblings together. Docks are relative to the building rotation.
        var dock=line.frontOf(machine);
        if (stage(y).equals("nursery_transfer") || stage(y).equals("nursery"))
            dock=dock.relative(line.frontDirection().getClockWise(), (HumanCommerce.data(y).getInt("BatchNumber")%2)*3);
        return dock;
    }
    public boolean transportable(YukkuriEntity y) {
        return HumanCommerce.ownedBy(y,worker.getUUID()) && y.isAlive() && !y.family().awaitingLanding()
                && !y.isPassenger() && !y.operations().active() && !stage(y).isEmpty()
                && !stage(y).equals("nursery") && !stage(y).equals("observe");
    }
    public YukkuriEntity nextTransport() {
        var candidates=animals(); candidates.addAll(worker.animals("breeder"));
        return candidates.stream().filter(this::transportable)
                .min(Comparator.comparingInt(y->HumanCommerce.data(y).getInt("BatchNumber"))).orElse(null);
    }
    public boolean needsService(YukkuriEntity y) {
        var t=tray(); return t!=null && t.occupant()==y && t.docked(destination(y))
                && Set.of("receive","grade","wash","pack").contains(stage(y));
    }
    public void arrived(YukkuriEntity y, FactoryTray tray) {
        if(stage(y).equals("parent_transfer")) {
            transition(y,"");tray.unload();y.life().releaseHome();HumanCommerce.prepareBreederHome(y);return;
        }
        if (stage(y).equals("nursery_transfer") || stage(y).equals("observe_transfer")) {
            String next=stage(y).equals("nursery_transfer") ? "nursery" : "observe";
            transition(y,next); tray.unload();
        }
    }
    /** Contact and elapsed world ticks are checked again even when called by a menu or NPC routine. */
    public boolean work(YukkuriEntity y) {
        if (!operating() || worker.resting() || worker.isTrading() || !needsService(y) || worker.distanceToSqr(y)>9 || !worker.hasLineOfSight(y)) return false;
        var tag=HumanCommerce.data(y).copy(); long now=worker.level().getGameTime();
        if (now-tag.getLong("LastFactoryWork")<20) return false;
        tag.putLong("LastFactoryWork",now); int progress=tag.getInt("StageWork")+20;
        tag.putInt("StageWork",progress); HumanCommerce.set(y,tag); worker.swing(InteractionHand.MAIN_HAND);
        if (progress<100) return false;
        switch(stage(y)) {
            case "receive" -> {
                inspected++;
                if (!healthy(y)) hold(y);
                else transition(y,tag.getBoolean("FactoryYoung") ? "nursery_transfer" : "grade");
            }
            case "grade" -> {
                if (!healthy(y) || y.life().nutrition()<45) { hold(y); break; }
                UUID batch=tag.getUUID("FactoryBatch");
                int purpose=tag.getInt("Purpose");
                if(purpose==0) {
                    purpose=tag.getBoolean("RetiredParent") ? 2 : batchPurposes.getOrDefault(batch,0);
                    if(purpose==0) { purpose=purposeMode==0?nextPurpose:purposeMode;if(purposeMode==0)nextPurpose=3-nextPurpose; }
                    batchPurposes.put(batch,purpose);
                }
                tag=HumanCommerce.data(y).copy(); tag.putInt("Purpose",purpose); tag.putInt("SizeGrade",sizeGrade(y)); HumanCommerce.set(y,tag);
                transition(y,"wash");
            }
            case "wash" -> { y.life().wash(worker.getUUID()); transition(y,"pack"); }
            case "pack" -> {
                if (!healthy(y) || y.life().nutrition()<35) { hold(y); break; }
                var t=tray(); t.unload();
                if (line.finish(y)) return true;
            }
            default -> { }
        }
        return false;
    }
    private void hold(YukkuriEntity y) {
        held++; var tag=HumanCommerce.data(y).copy(); tag.putString("HoldReason",!healthy(y)?"health":"nutrition");
        HumanCommerce.set(y,tag); transition(y,"observe_transfer");
    }
    public boolean readyToFinish(YukkuriEntity y) {
        return stage(y).equals("pack") && HumanCommerce.data(y).getInt("StageWork")>=100
                && worker.distanceToSqr(y)<=9 && worker.hasLineOfSight(y) && healthy(y);
    }
    public void decorate(ItemStack item, YukkuriEntity y) {
        var source=HumanCommerce.data(y);
        CustomData.update(DataComponents.CUSTOM_DATA,item,tag->{
            tag.putInt("Purpose",source.getInt("Purpose")); tag.putInt("BatchNumber",source.getInt("BatchNumber"));
            if(source.hasUUID("FactoryBatch"))tag.putUUID("FactoryBatch",source.getUUID("FactoryBatch"));
            tag.putString("Variant",y.getVariant().assetId()); tag.putInt("SizeGrade",sizeGrade(y)); tag.putInt("Occupants",1);
            tag.putInt("HealthPercent",Math.round(y.getHealth()/y.getMaxHealth()*100)); tag.putInt("Nutrition",Math.round(y.life().nutrition()));
            tag.putInt("FillingPortions",y.body().filling()); tag.putInt("Burns",y.body().burns());
            tag.putInt("Energy",Math.round(y.life().energy())); tag.putBoolean("YoungVoice",y.family().youngVoice()); tag.putString("Treatment","inspected_cleaned");
        });
    }
    public void tick() {
        if (!(worker.level() instanceof ServerLevel level)) return;
        for(var y:animals()) {
            enroll(y);
            if(stage(y).equals("nursery") && FactoryProduction.nurseryReady(y)) {
                var tag=HumanCommerce.data(y).copy(); tag.putString("Use","input"); HumanCommerce.set(y,tag); transition(y,"grade");
            } else if(stage(y).equals("observe") && healthy(y) && y.life().nutrition()>=45) {
                transition(y,HumanCommerce.data(y).getBoolean("FactoryYoung")&&!FactoryProduction.nurseryReady(y)?"nursery_transfer":"grade");
            }
        }
        if(!operating()) return;
        if(trayId==null && !trayBroken) {
            var pos=destinationBlock("receive");
            if(pos!=null) {
                var t=HumanWorld.TRAY.get().create(level); t.assign(worker.getUUID()); t.moveTo(Vec3.atBottomCenterOf(line.frontOf(pos)));
                if(level.noCollision(t) && level.addFreshEntity(t)) trayId=t.getUUID();
            }
        }
        var t=tray(); if(t!=null && t.occupant()!=null) work(t.occupant());
    }
    public CompoundTag save() {
        var tag=new CompoundTag(); if(trayId!=null)tag.putUUID("Tray",trayId); tag.putBoolean("TrayBroken",trayBroken);
        tag.putInt("PurposeMode",purposeMode);tag.putInt("NextPurpose",nextPurpose);tag.putInt("SaleFilter",saleFilter);
        tag.putInt("Serial",serial);tag.putInt("Inspected",inspected);tag.putInt("Held",held);
        var people=new ListTag();residents.forEach(id->people.add(NbtUtils.createUUID(id)));tag.put("Residents",people);
        var list=new ListTag(); batches.forEach((id,number)->{var b=new CompoundTag();b.putUUID("Id",id);b.putInt("Number",number);b.putInt("Purpose",batchPurposes.getOrDefault(id,0));list.add(b);});tag.put("BatchNumbers",list);return tag;
    }
    public void load(CompoundTag tag) {
        trayId=tag.hasUUID("Tray")?tag.getUUID("Tray"):null;trayBroken=tag.getBoolean("TrayBroken");
        purposeMode=Math.clamp(tag.getInt("PurposeMode"),0,2);nextPurpose=tag.getInt("NextPurpose")==2?2:1;saleFilter=Math.clamp(tag.getInt("SaleFilter"),0,2);
        serial=Math.max(0,tag.getInt("Serial"));inspected=Math.max(0,tag.getInt("Inspected"));held=Math.max(0,tag.getInt("Held"));
        residents.clear();for(var id:tag.getList("Residents",11))residents.add(NbtUtils.loadUUID(id));
        batches.clear();batchPurposes.clear();for(var raw:tag.getList("BatchNumbers",10)){var b=(CompoundTag)raw;if(b.hasUUID("Id")){batches.put(b.getUUID("Id"),b.getInt("Number"));batchPurposes.put(b.getUUID("Id"),b.getInt("Purpose"));serial=Math.max(serial,b.getInt("Number"));}}
    }
}
