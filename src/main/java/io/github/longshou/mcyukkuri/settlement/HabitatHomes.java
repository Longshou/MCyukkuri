package io.github.longshou.mcyukkuri.settlement;

import static io.github.longshou.mcyukkuri.world.SearchBudget.add;

import static io.github.longshou.mcyukkuri.world.SearchBudget.generation;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.world.SearchBudget.Cost;
import io.github.longshou.mcyukkuri.world.SearchBudget.Lease;
import io.github.longshou.mcyukkuri.world.SearchBudget.Purpose;
import io.github.longshou.mcyukkuri.world.SearchBudget.Result;
import io.github.longshou.mcyukkuri.settlement.HabitatLife.Task;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/** Transient home search, excavation and relocation delegated by HabitatLife. */
final class HabitatHomes {
    private final HabitatLife o;
    /** Transient work; the world retains the dug cells. */
    private Burrowing.Plan dig;
    private int digIndex,digWork,extendWait,roamWait,searchWait,moveWait;
    private boolean noExtension;
    private String moveNote="";
    private boolean extending;
    private BlockPos roamTarget;
    /** Retained across interruption so the next attempt resumes the same hollow. */
    private BlockPos resumeMouth;
    private Direction resumeDirection;
    private UUID searchGroup,searchSite;
    private long homeGeneration,relocationGeneration;
    private boolean homeContext;
    private BlockPos relocationHome;
    /** FIND_HOME has finished NOT_FOUND; only the next independent, fixed-cost DIG admission remains. */
    private record DigPending(UUID owner,ResourceKey<Level> dimension,UUID group,UUID site) {}
    private DigPending pendingDig;
    HabitatHomes(HabitatLife o) { this.o=o; }
    private ServerLevel level() { return o.level(); }
    void clock() {
        if(roamWait>0)roamWait--;
        if(searchWait>0)searchWait--;
        if(extendWait>0)extendWait--;
        if(moveWait>0)moveWait--;
        if(pendingDig!=null&&(!pendingDig.equals(digContext())||!o.vagrant()
                ||o.entity().life().nutrition()<35||o.entity().life().energy()<30||level().isNight()))cancelVagrantProject();
    }
    boolean vagrantPending() { return pendingDig!=null; }
    private DigPending digContext() {
        var site=SiteLife.of(o.entity());
        return new DigPending(o.entity().getUUID(),o.entity().level().dimension(),o.entity().settlement().groupId(),site==null?null:site.id);
    }
    void cancelVagrantProject() {
        if(pendingDig!=null&&o.entity().level() instanceof ServerLevel level)SearchBudget.cancel(level,o.entity(),Purpose.DIG,0);
        pendingDig=null;
    }
    void debug(CompoundTag tag) { tag.putBoolean("NoExtension",noExtension);tag.putInt("ExtendWait",extendWait);tag.putInt("MoveWait",moveWait);tag.putString("MoveNote",moveNote); }
    int digProgress() { return dig==null||dig.cells().isEmpty()?0:digIndex*100/dig.cells().size(); }
    boolean canDigContinue() { return dig!=null&&o.ticks()<900&&EventHooks.canEntityGrief(level(),o.entity())&&!o.entity().life().hasUrgentNeed()&&o.entity().life().nutrition()>=25; }
    boolean canRoamContinue() { return roamTarget!=null&&o.ticks()<700&&!o.entity().life().hasUrgentNeed(); }
    void stop(Task task) {
        if(o.entity().level() instanceof ServerLevel level) {
            SearchBudget.cancel(level,o.entity(),Purpose.FIND_HOME,homeGeneration);
            SearchBudget.cancel(level,o.entity(),Purpose.RELOCATE,relocationGeneration);
            SearchBudget.cancel(level,o.entity(),Purpose.DIG,0);
        }
        pendingDig=null;homeContext=false;relocationHome=null;
        if(task==Task.DIG){dig=null;digWork=0;}roamTarget=null;
    }
    void homeChosen() {
        cancelVagrantProject();
        if(o.entity().level() instanceof ServerLevel level) {
            SearchBudget.cancel(level,o.entity(),Purpose.FIND_HOME,homeGeneration);
            SearchBudget.cancel(level,o.entity(),Purpose.RELOCATE,relocationGeneration);
        }
        homeContext=false;relocationHome=null;
    }
    /** Lower is better: enough free cells for the household first, then a size close to what the household needs plus room to grow, then near. */
    private static double fit(int cells,int free,int need,double distanceSqr) {
        return Math.max(0,need+1-free)*40+Math.abs(cells-(need+3))*2+Math.sqrt(distanceSqr)/4;
    }
    /** Recognise a home: a registered one nearby (a roomy-enough one first), else an unregistered cave or hollow that qualifies (see {@link NaturalShelter#survey}). */
    Cost homeSearchCost(long extraCores) {
        var site=SiteLife.of(o.entity());
        long slots=site==null||site.plan()==null?0:site.plan().slots().size();
        long cores=Homes.loadedWithin(level(),o.entity().blockPosition().offset(-12,-2,-12),o.entity().blockPosition().offset(12,2,12)).size();
        return SearchBudget.findHome(add(cores,extraCores),slots);
    }
    Lease requestHomeSearch() {
        var group=o.entity().settlement().groupId();
        var site=SiteLife.of(o.entity());var siteId=site==null?null:site.id;
        if(!homeContext||!Objects.equals(group,searchGroup)||!Objects.equals(siteId,searchSite)) {
            if(pendingDig!=null&&!pendingDig.equals(digContext()))cancelVagrantProject();
            SearchBudget.cancel(level(),o.entity(),Purpose.FIND_HOME,homeGeneration);
            homeGeneration=generation();
            searchGroup=group;searchSite=siteId;homeContext=true;
        }
        return SearchBudget.request(level(),o.entity(),Purpose.FIND_HOME,homeGeneration,homeSearchCost(0));
    }
    Result findHome() {
        if(!(o.entity().level() instanceof ServerLevel))return Result.NOT_FOUND;
        if(o.roaming()||o.entity().profile().houseBody()||o.entity().profile().dangerToYukkuri()||o.unavailable()) {
            SearchBudget.cancel(level(),o.entity(),Purpose.FIND_HOME,homeGeneration);return Result.NOT_FOUND;
        }
        var lease=requestHomeSearch();
        if(lease==null)return Result.DEFERRED;
        try(lease){return findHome(lease);}
    }
    Result findHome(Lease lease) {
        try(var scope=lease.bind(o.entity())) {
            var result=Result.of(searchHome(lease));
            SearchBudget.cancel(level(),o.entity(),Purpose.FIND_HOME,homeGeneration);
            return result;
        }
    }
    private boolean searchHome(Lease lease) {
        if(o.roaming()||o.entity().profile().houseBody()||o.entity().profile().dangerToYukkuri()||o.unavailable())return false;
        // A community on a planned site moves into that site's own burrows first (spare ones included).
        var site=SiteLife.of(o.entity());
        // While the community moves, the old site's burrows are no longer home: look around the new ground instead.
        if(site!=null&&!o.entity().settlement().migrating()&&SiteLife.settle(o.entity(),o,site,lease)==Result.FOUND)return true;
        int need=o.householdNeed();
        var cores=new ArrayList<>(Homes.loadedWithin(level(),o.entity().blockPosition().offset(-12,-2,-12),o.entity().blockPosition().offset(12,2,12)));
        cores.sort(Comparator.comparingDouble(core->fit(core.capacity(),core.capacity()-core.usedSlots(),need,o.entity().distanceToSqr(Vec3.atBottomCenterOf(core.getBlockPos())))));
        for(var core:cores){SearchBudget.probe(level());if(!CommunityRules.barred(o.entity(),core.getBlockPos())&&o.choose(core))return true;}
        // A dependent child follows its parents' home; only a grown individual founds a home of its own.
        if(!o.grownEnough()||!EventHooks.canEntityGrief(level(),o.entity()))return false;
        // Banished from this territory: it must not found a home inside it (package 12).
        if(CommunityRules.barred(o.entity(),o.entity().blockPosition()))return false;
        record Candidate(ShelterPlacement.Plan plan,double score) {}
        var candidates=new ArrayList<Candidate>();
        for(BlockPos pos:BlockPos.betweenClosed(o.entity().blockPosition().offset(-8,-2,-8),o.entity().blockPosition().offset(8,2,8))) {
            SearchBudget.probe(level());
            if(!NaturalShelter.enclosed(level(),pos))continue;
            for(var direction:Direction.Plane.HORIZONTAL) {
                var plan=new ShelterPlacement.Plan(pos.immutable(),ShelterBlock.Kind.BURROW,direction);
                if(!ShelterPlacement.canPlace(level(),plan))continue;
                int cells=plan.beds(level()).size();
                candidates.add(new Candidate(plan,fit(cells,cells,need,o.entity().distanceToSqr(Vec3.atBottomCenterOf(pos)))));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score));
        int tried=0;
        for(var candidate:candidates) {
            if(++tried>4)break;
            SearchBudget.path(o.entity());
            if(!o.entity().life().reachable(candidate.plan().center(),false))continue;
            var placed=ShelterPlacement.place(level(),candidate.plan());
            if(placed!=null){if(o.choose(placed.shelter()))return true;placed.rollback(level());}
        }
        return false;
    }
    // ---- vagrants: look for a home, scrape one into soft ground, or move on
    /** A grown individual without a home: it recognises one in reach, otherwise digs a hollow into a soil face nearby, otherwise wanders on. */
    Result vagrantProject() {
        if(!o.vagrant()||!EventHooks.canEntityGrief(level(),o.entity())){cancelVagrantProject();return Result.NOT_FOUND;}
        if(o.entity().life().nutrition()<35||o.entity().life().energy()<30||level().isNight()){cancelVagrantProject();return Result.NOT_FOUND;}
        if(pendingDig!=null&&!pendingDig.equals(digContext()))cancelVagrantProject();
        if(searchWait>0)return pendingDig==null?Result.NOT_FOUND:deferVagrantProject();
        searchWait=100;
        // Banished from this territory: move on instead of settling in it (package 12).
        if(CommunityRules.barred(o.entity(),o.entity().blockPosition())) {
            cancelVagrantProject();
            SearchBudget.cancel(level(),o.entity(),Purpose.FIND_HOME,homeGeneration);
            return Result.of(roamWait<=0&&startRoam());
        }
        if(pendingDig==null) {
            var found=findHome();
            if(found==Result.DEFERRED)return deferVagrantProject();
            if(found==Result.FOUND)return Result.NOT_FOUND;
            pendingDig=digContext();
        }
        var started=startDig();
        if(started==Result.DEFERRED)return deferVagrantProject();
        pendingDig=null;
        if(started==Result.FOUND)return Result.FOUND;
        return Result.of(roamWait<=0&&startRoam());
    }
    private Result deferVagrantProject() { searchWait=20;o.retrySearch();return Result.DEFERRED; }
    /** Cells the household wants in a new hollow: a nest and a store for the first, two more for everyone else. */
    private int wantedCells() { return Math.min(NaturalShelter.MAX_CELLS-2,4+2*o.householdNeed()); }
    private Result startDig() {
        if(!SearchBudget.take(level(),o.entity(),Purpose.DIG))return Result.DEFERRED;
        int want=wantedCells();
        Burrowing.Plan best=null;double bestScore=-1;
        if(resumeMouth!=null) {
            var again=Burrowing.plan(level(),resumeMouth,resumeDirection,want);
            if(again!=null&&o.entity().life().reachable(resumeMouth,false)){dig=again;extending=false;digIndex=0;digWork=0;o.task(Task.DIG);o.resetTicks();return Result.FOUND;}
            resumeMouth=null;resumeDirection=null;
        }
        var faces=new ArrayList<>(Burrowing.faces(level(),o.entity().blockPosition(),9));
        faces.sort(Comparator.comparingDouble(face->face.distSqr(o.entity().blockPosition())));
        for(var face:faces.subList(0,Math.min(faces.size(),40))) {
            if(!o.entity().life().reachable(face,false))continue;
            for(var direction:Direction.Plane.HORIZONTAL) {
                if(!Burrowing.soft(level(),face.relative(direction)))continue;
                var plan=Burrowing.plan(level(),face,direction,want);
                if(plan==null)continue;
                double score=plan.cells().size()*6-Math.sqrt(face.distSqr(o.entity().blockPosition()));
                if(score>bestScore){best=plan;bestScore=score;}
            }
        }
        if(best==null)return Result.NOT_FOUND;
        resumeMouth=best.mouth();resumeDirection=best.dig();
        dig=best;extending=false;digIndex=0;digWork=0;o.task(Task.DIG);o.resetTicks();return Result.FOUND;
    }
    boolean extensionWanted(ShelterBlockEntity value) {
        if(extendWait>0)return false;
        return o.entity().family().adult()&&value.owns(o.entity().getUUID())&&value.usable(o.entity())&&value.crowded()&&!level().isNight()
                &&EventHooks.canEntityGrief(level(),o.entity())&&o.entity().life().nutrition()>=50&&o.entity().life().energy()>=40&&!o.entity().life().needsFood()
                &&!o.entity().independence().householdOuting()&&o.carrying().isEmpty()&&!o.hasNestProject();
    }
    /** The family has outgrown the cave: scrape one more cell out of the wall, away from the doorway. */
    boolean startExtension(ShelterBlockEntity value) {
        extendWait=2400;
        var plan=Burrowing.extension(level(),value);
        noExtension=plan==null;
        if(plan==null)return false;
        dig=plan;extending=true;digIndex=0;digWork=0;o.task(Task.DIG);o.resetTicks();return true;
    }
    // ---- moving the household to a bigger home
    boolean relocationWanted(ShelterBlockEntity value) {
        if(moveWait>0)return false;
        return noExtension&&o.entity().family().adult()&&value.owns(o.entity().getUUID())&&value.crowded()&&!level().isNight()
                &&EventHooks.canEntityGrief(level(),o.entity())&&o.carrying().isEmpty()&&!o.hasNestProject();
    }
    /** A recognised, empty, roomier home within reach: a registered one, or a cave that qualifies and is registered now. */
    private ShelterBlockEntity biggerHome(int minSleeping,ShelterBlockEntity old) {
        ShelterBlockEntity best=null;
        for(var core:Homes.loadedWithin(level(),o.entity().blockPosition().offset(-24,-2,-24),o.entity().blockPosition().offset(24,2,24))) {
            SearchBudget.probe(level());
            if(core==old||!core.residentIds().isEmpty()
                    ||core.kind()!=ShelterBlock.Kind.BURROW||!core.sound()||core.capacity()<minSleeping)continue;
            if(best==null||core.capacity()<best.capacity())best=core;
        }
        if(best!=null)return best;
        ShelterPlacement.Plan plan=null;int cells=Integer.MAX_VALUE;int seen=0,fitting=0;
        for(BlockPos pos:BlockPos.betweenClosed(o.entity().blockPosition().offset(-30,-2,-30),o.entity().blockPosition().offset(30,2,30))) {
            SearchBudget.probe(level());
            if(!NaturalShelter.enclosed(level(),pos))continue;
            for(var direction:Direction.Plane.HORIZONTAL) {
                var candidate=new ShelterPlacement.Plan(pos.immutable(),ShelterBlock.Kind.BURROW,direction);
                if(!ShelterPlacement.canPlace(level(),candidate))continue;
                int size=candidate.beds(level()).size();seen++;
                if(size>=minSleeping)fitting++;
                if(size>=minSleeping&&size<cells){SearchBudget.path(o.entity());if(o.entity().life().reachable(pos,false)){plan=candidate;cells=size;}}
            }
        }
        moveNote="caves="+seen+" big_enough="+fitting+" min="+minSleeping+(plan==null?" none_reachable":" chosen");
        if(plan==null)return null;
        var placed=ShelterPlacement.place(level(),plan);
        return placed==null?null:placed.shelter();
    }
    /** The whole household (everyone of the old home who is near) walks into the bigger home: adults first, the young into a parent's cell. */
    Result relocate(ShelterBlockEntity old) {
        long cores=Homes.loadedWithin(level(),o.entity().blockPosition().offset(-24,-2,-24),o.entity().blockPosition().offset(24,2,24)).size();
        if(!old.getBlockPos().equals(relocationHome)) {
            SearchBudget.cancel(level(),o.entity(),Purpose.RELOCATE,relocationGeneration);
            relocationHome=old.getBlockPos();relocationGeneration=generation();
        }
        var lease=SearchBudget.request(level(),o.entity(),Purpose.RELOCATE,relocationGeneration,SearchBudget.relocate(cores,old.residentIds().size()));
        if(lease==null){moveWait=20;o.retrySearch();return Result.DEFERRED;}
        try(lease;var scope=lease.bind(o.entity())) { return relocate(old,lease); }
    }
    private Result relocate(ShelterBlockEntity old,Lease lease) {
        moveWait=6000;
        int need=o.householdNeed();
        var target=biggerHome(Math.max(old.capacity()+2,need+3),old);
        if(target==null){moveWait=1200;return Result.NOT_FOUND;}
        var movers=new ArrayList<YukkuriEntity>();
        for(var id:old.residentIds())
            if(level().getEntity(id) instanceof YukkuriEntity y&&y.isAlive()&&y.distanceToSqr(o.entity())<=48*48)movers.add(y);
        movers.sort(Comparator.comparingInt(y->y.family().adult()?0:1));
        int moved=0;
        for(var mover:movers) {
            mover.life().trustRoutes(true);
            try(var scope=lease.bind(mover)){if(mover.habitat().choose(target))moved++;}finally{mover.life().trustRoutes(false);}
        }
        moveNote+=" movers="+movers.size()+" moved="+moved;
        if(moved==0&&target.residentIds().isEmpty())target.dismantle();
        return Result.of(moved>0);
    }
    void tickDig() {
        while(digIndex<dig.cells().size()&&Burrowing.open(level(),dig.cells().get(digIndex)))digIndex++;
        if(digIndex>=dig.cells().size()){finishDig();return;}
        var cell=dig.cells().get(digIndex);
        if(!Burrowing.soft(level(),cell)){o.stop();return;}
        var from=dig.reach(cell);
        if(!o.approach(from))return;
        var state=level().getBlockState(cell);
        SurfaceApproach.face(o.entity(),SurfaceApproach.yaw(o.entity().position(),Vec3.atCenterOf(cell)));
        digWork++;
        if(digWork%12==0) {
            level().sendParticles(new BlockParticleOption(ParticleTypes.BLOCK,state),
                    cell.getX()+.5,cell.getY()+.3,cell.getZ()+.5,6,.25,.15,.25,.02);
            level().playSound(null,cell,state.getSoundType().getHitSound(),SoundSource.BLOCKS,.6f,.8f);
        }
        if(digWork<Burrowing.digTicks(state))return;
        level().destroyBlock(cell,false,o.entity());
        o.entity().life().spendEnergy(1.2f);o.entity().life().spendNutrition(.35f);
        digIndex++;digWork=0;o.resetTicks();
    }
    /** All planned cells are open: the hollow is recognised as a home (or the cave's survey is refreshed after an extension). */
    private void finishDig() {
        var plan=dig;boolean wasExtending=extending;
        dig=null;resumeMouth=null;resumeDirection=null;
        // The hollow may have been recognised as a home while the last cells were still being dug: bring its layout up to date.
        if(!wasExtending&&level().getBlockEntity(plan.doorway()) instanceof ShelterBlockEntity early&&!early.isRemoved()) {
            early.refreshSite(NaturalShelter.survey(level(),early.getBlockPos(),early.facing(),early.pileCell()));
            if(!early.owns(o.entity().getUUID()))o.choose(early);
            o.entity().showReaction("happy","gentle",80);o.stop();return;
        }
        if(wasExtending) {
            var value=o.shelter();
            if(value!=null)value.refreshSite(NaturalShelter.survey(level(),value.getBlockPos(),value.facing(),value.pileCell()));
            o.entity().showReaction("happy","gentle",60);o.stop();return;
        }
        var placement=new ShelterPlacement.Plan(plan.doorway(),ShelterBlock.Kind.BURROW,plan.facing());
        if(ShelterPlacement.canPlace(level(),placement)) {
            var placed=ShelterPlacement.place(level(),placement);
            if(placed!=null) {
                if(o.choose(placed.shelter()))o.entity().showReaction("happy","gentle",80);else placed.rollback(level());
            }
        }
        o.stop();
    }
    /** No soil face to dig into here: walk on a good way and look again. */
    private boolean startRoam() {
        roamWait=300;
        for(int attempt=0;attempt<8;attempt++) {
            int dx=o.entity().getRandom().nextInt(41)-20,dz=o.entity().getRandom().nextInt(41)-20;
            if(dx*dx+dz*dz<100)continue;
            var ground=level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,o.entity().blockPosition().offset(dx,0,dz));
            if(!level().hasChunkAt(ground)||!o.entity().life().dryStand(ground)||!o.entity().life().reachable(ground,false))continue;
            SearchBudget.cancel(level(),o.entity(),Purpose.DIG,0);
            roamTarget=ground;o.task(Task.ROAM);o.resetTicks();return true;
        }
        return false;
    }
    void tickRoam() {
        if(roamTarget==null){o.stop();return;}
        if(o.approach(roamTarget))o.stop();
    }
}
