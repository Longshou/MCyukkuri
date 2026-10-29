package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.headwear.HatProp;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Beat;
import io.github.longshou.mcyukkuri.intent.Choreography;
import io.github.longshou.mcyukkuri.intent.GoTo;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Rendezvous;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.PlaceProp;
import io.github.longshou.mcyukkuri.motor.action.TakeProp;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The land birth as one choreography (design 7, spec 14 WP3b), conducted by the mother's {@link FamilyLife} over the last
 * {@value #APPROACH_LEAD} ticks of a LIVE or STEM gestation. Roles: the mother, and the catcher (a Marisa father nearby,
 * hatted or not, else a hatted Marisa mother herself). The receiver is a hat the catcher sets down, a large nest or the
 * ground. Both tables share the site, meeting, hat and cleanup beats; LIVE adds the labour beats, STEM one drop beat. The
 * labour clock ({@link #conductData}) only advances the brood ledger; the bodies move through their own BirthGoal.
 */
final class LiveBirth extends Choreography {
    static final String MOTHER="mother", CATCHER="catcher";
    /** The catcher notices this early; the hat may come down up to CATCH_WAIT past term; the last NEST_GRACE ticks need the site. */
    static final int APPROACH_LEAD=1200, CATCH_WAIT=600, NEST_GRACE=400, PAUSE=100;
    /** How near the outlet's child must be to the receiver's aim for the outlet to be aimed at it (blocks). */
    private static final double HAT_REACH=1.25, NEST_REACH=.8;
    private static final float[] TURNS={0,45,-45,90,-90,135,-135,180};
    private static final Set<String> BEFORE_HAT=Set.of("site","meet","regroup","self_wait","place"),LABOUR=Set.of("term","exit","exit_any","push","drop");
    /** Where the newborns are meant to come down (spec 12 {@code Receiver}); a placed hat's host is set once it is down. */
    sealed interface Receiver permits PlacedHat, Nest, Ground { default String kind(){return getClass().getSimpleName();} }
    record PlacedHat(UUID host) implements Receiver {}
    record Nest() implements Receiver {}
    record Ground() implements Receiver {}
    /** A newborn's real landing, greeted by its father and siblings from their own ticks. */
    record Welcome(UUID baby, long tick) {}

    private final FamilyLife o;
    private final UUID broodId;
    private final boolean live;
    // The receiver and the outlet flags are saved; the rest is transient beat data rebuilt from the world.
    private Receiver receiver=new Ground();
    private Rendezvous meeting;
    /** Meeting sites that failed, kept out of the search for NEST_GRACE ticks (bounded, never saved). */
    private record Excluded(Vec3 mother,Vec3 helper,long until){}
    private final List<Excluded> excluded=new ArrayList<>();
    private long windowEnd,cleanupEnd;
    /** Sticky once upgraded: any clear outlet (liquid allowed), and then (forced) no outlet check at all (spec decision 3). */
    private boolean unprotected,forced;
    private int pushing,welcomed,unloads,takes;
    private Welcome welcome;
    private Vec3 last;
    private String hold="";
    LiveBirth(FamilyLife o,UUID broodId,boolean live){super(live?"live_birth":"stem_birth","site");this.o=o;this.broodId=broodId;this.live=live;}
    /** Casts a new birth: who catches, and so what receives (a hat, else her large nest, else the ground). */
    static LiveBirth cast(FamilyLife o,FamilyData.Brood brood){
        var birth=new LiveBirth(o,brood.id,brood.kind==FamilyData.BirthKind.LIVE);birth.bind(MOTHER,o.entity().getUUID());
        birth.recast(catcher(o.entity(),brood));
        return birth;
    }
    /** Binds the catcher and picks the receiver from her: her hat (already down, or to be set down), else the large nest or the ground. */
    private void recast(YukkuriEntity catcher){
        var mother=o.entity();var hat=catcher==null?null:PlacedHatEntity.find(catcher);
        bind(CATCHER,catcher==null?null:catcher.getUUID());
        if(catcher!=null&&HatProp.usable(catcher)&&!(catcher==mother&&FamilyNest.large(mother)))receiver=new PlacedHat(hat==null?null:hat.getUUID());
        else receiver=FamilyNest.large(mother)?new Nest():new Ground();
    }
    UUID broodId(){return broodId;}
    /** A Marisa father near enough to notice (with his hat, or without one only to cheer), else a hatted Marisa mother herself. */
    static YukkuriEntity catcher(YukkuriEntity mother,FamilyData.Brood brood){
        if(brood.father!=null&&mother.level() instanceof ServerLevel level&&level.getEntity(brood.father) instanceof YukkuriEntity father
                &&father.isAlive()&&!father.isPassenger()&&father.getVariant()==YukkuriVariant.MARISA
                &&father.distanceToSqr(mother)<ROLE_RADIUS*ROLE_RADIUS&&father.life().canRecognize(mother))return father;
        return HatProp.usable(mother)?mother:null;
    }
    @Override protected Map<String,Beat> table(){
        var t=new HashMap<String,Beat>();String labour=live?"term":"drop";
        t.put("site",new Beat.Perform(Beat.roles(MOTHER,this::site),()->Math.max(PAUSE,toTerm()),"sited","sited"));
        t.put("sited",new Beat.Branch(r->interrupted(r)?"site":receiver instanceof PlacedHat p&&p.host()==null?self()?"self_wait":"find":labour));
        // A failed site stays out for NEST_GRACE ticks: the search picks another one or waits for the world to change.
        t.put("find",new Beat.Branch(r->(meeting=meet())!=null?"meet":!excluded.isEmpty()&&window()>PAUSE?"regroup":nocatch("no_site")));
        t.put("meet",new Beat.Perform(Beat.roles(CATCHER,()->meeting.forB(),MOTHER,()->meeting.forA()),this::window,"met","nocatch"));
        t.put("met",new Beat.Branch(r->{
            if(r.done())return "place";
            if(!interrupted(r)){if(excluded.size()>=32)excluded.removeFirst();excluded.add(new Excluded(meeting.site().a(),meeting.site().b(),now()+NEST_GRACE));}
            return window()>PAUSE?"regroup":nocatch("rendezvous_"+r.detail());
        }));
        t.put("regroup",new Beat.Await(Beat.roles(MOTHER,null),()->false,()->PAUSE,"find","find"));
        t.put("self_wait",new Beat.Await(Beat.roles(MOTHER,null),()->brood()==null||brood().elapsed>=brood().duration-(live?2:200),this::window,"place","nocatch"));
        t.put("place",new Beat.Perform(Beat.roles(CATCHER,this::placeHat,MOTHER,null),()->Math.max(40,window()),"placed","nocatch"));
        t.put("placed",new Beat.Branch(this::placed));
        t.put("nocatch",new Beat.Branch(r->nocatch("placement_timeout")));
        if(live){
            t.put("term",new Beat.Await(Beat.roles(MOTHER,null,CATCHER,this::attend),()->brood()==null||toTerm()<=0,()->toTerm()+1200,"exit","term"));
            t.put("exit",new Beat.Perform(Beat.roles(MOTHER,()->new Deliver(false),CATCHER,this::attend),()->NEST_GRACE,"turned","exit_any"));
            t.put("exit_any",new Beat.Perform(Beat.roles(MOTHER,()->new Deliver(true),CATCHER,this::attend),()->1200,"turned_any","force"));
            t.put("turned",new Beat.Branch(r->r.done()?"push":interrupted(r)?"exit":"exit_any"));
            t.put("turned_any",new Beat.Branch(r->r.done()?"push":interrupted(r)?"exit_any":"force"));
            // No clear outlet at all: the birth is forced; real collision still applies to the newborn (never a permanent wait).
            t.put("force",new Beat.Branch(r->{forced=true;GameplayLog.event(o.entity(),"family","birth forced reason=no_safe_exit child={}",pushing);return "push";}));
            t.put("push",new Beat.Await(Beat.roles(MOTHER,null,CATCHER,this::attend),()->brood()==null||brood().children.get(pushing).born,this::pushTicks,"pushed","pushed"));
            t.put("pushed",new Beat.Branch(r->brood()==null||brood().unborn()==0?"linger":forced?"push":unprotected?"exit_any":"exit"));
            t.put("linger",new Beat.Present(Beat.roles(MOTHER,null),Behaviors.POSTPARTUM,LiveBirthGeometry.RECOVERY,"lingered"));
        }else{
            t.put("drop",new Beat.Await(Beat.roles(MOTHER,null,CATCHER,this::attend),()->brood()==null||brood().complete(),()->toTerm()+1240,"linger","drop"));
            t.put("linger",new Beat.Present(Beat.roles(MOTHER,null),"",120,"lingered"));
        }
        t.put("lingered",new Beat.Branch(r->placedHat()!=null&&catcher()!=null?"unload":null));
        // The cleanup has one deadline from the end of the linger and at most three re-entries of each step; then the hat errand.
        t.put("unload",new Beat.Perform(Beat.roles(CATCHER,()->placedHat()==null||catcher()==null?null:new Unload(placedHat(),catcher(),false)),
                ()->Math.min(Unload.DEADLINE_TICKS+40,cleanupLeft()),"unloaded","rest"));
        t.put("unloaded",new Beat.Branch(this::unloaded));
        t.put("rest",new Beat.Await(Map.of(),()->placedHat()==null||placedHat().getPassengers().isEmpty(),()->Math.min(PAUSE,cleanupLeft()),"take_hat","unload"));
        t.put("take_hat",new Beat.Perform(Beat.roles(CATCHER,()->takeHat(catcher(),placedHat(),now()+600)),()->Math.min(600,cleanupLeft()),"taken","rest"));
        t.put("taken",new Beat.Branch(r->r.done()||placedHat()==null?caption(catcher(),"帽子已戴回头上"):catcher()==null?null:"rest"));
        return t;
    }

    @Override protected void entered(String beat){
        var c=catcher();
        switch(beat){
            case "meet" -> BirthStaging.caption(c,"父油察觉母油临产，走向她面前的出口");
            case "place" -> BirthStaging.caption(c,self()?"母油自己摘下帽子（hat_off_self）":"接生者走到出口前方，用辫子摘下自己的帽子（hat_off）");
            case "take_hat" -> BirthStaging.caption(c,self()?"接生者把帽子戴回头上（hat_on_self）":"幼体已被放出，接生者用辫子把帽子戴回（hat_on）");
            case "exit","exit_any","push" -> {unprotected|=beat.equals("exit_any");pushing=firstUnborn();}
            default -> {}
        }
        if(!beat.equals("unload")&&!beat.equals("take_hat"))return;
        if(cleanupEnd==0)cleanupEnd=now()+Unload.DEADLINE_TICKS+1200;
        int entries=beat.equals("unload")?++unloads:++takes;
        if(entries<=4&&now()<cleanupEnd)return;
        var hat=placedHat();
        GameplayLog.event(c==null?o.entity():c,"family","catch cleanup gave up hat={} passengers={} reason={} unloads={} takes={}",hat==null?null:hat.getUUID(),
                hat==null?0:hat.getPassengers().size(),entries>4?beat+"_retries":"cleanup_deadline",unloads,takes);
        jump(null,"cleanup_exhausted"); // the owner's hat errand (FamilyLife.hatChore) takes the hat over
    }
    /** The labour clock: gestation to term, then (LIVE) the current child's pushes and birth, or (STEM) the drops. */
    @Override protected void conductData(long now){
        var b=brood();
        while(b!=null&&welcomed<b.children.size()&&b.children.get(welcomed).born&&b.children.get(welcomed).landed){
            // Each born child that has really landed is greeted once, from its landing on.
            var child=b.children.get(welcomed++);welcome=new Welcome(child.id,now);
            if(!"GROUND".equals(child.landing))BirthStaging.caption(o.entity(),"HAT".equals(child.landing)?"新生儿落进帽中，被稳稳接住":"新生儿落在地面（这一胎没有放帽）");
        }
        if(b==null||b.cancelled){if(BEFORE_HAT.contains(beat())||attending())jump(null,"brood_gone");return;}
        if(b.complete())return;
        // Nobody to catch yet and the hat could still come down before term: notice a catcher who arrives (a real failure keeps hers bound).
        if(role(CATCHER)==null&&now%20==0&&b.elapsed<b.duration&&(beat().equals("site")||LABOUR.contains(beat()))
                &&catcher(o.entity(),b) instanceof YukkuriEntity c){
            recast(c);
            GameplayLog.event(c,"family","catch duty noticed brood={} mother={} beat={} receiver={}",b.id,o.entity().getUUID(),beat(),receiver.kind());
            if(!beat().equals("site")&&receiver instanceof PlacedHat){jump("sited","catcher_found");return;}
        }
        String before=hold;hold=advance(b);
        if(!hold.isEmpty())GameplayLog.limited(o.entity(),"family","delivery."+hold,200,"delivery waiting brood={} reason={} beat={} elapsed={}/{} unborn={}",
                b.id,hold,beat(),b.elapsed,b.duration,b.unborn());
        else if(!before.isEmpty())GameplayLog.limited(o.entity(),"family","delivery.resumed",200,"delivery resumed brood={} previous={} elapsed={}/{}",
                b.id,before,b.elapsed,b.duration);
    }
    private String advance(FamilyData.Brood b){
        var m=o.entity();Vec3 was=last;last=m.position();boolean sited=!"site".equals(beat());
        if(!live){
            if(b.elapsed>=b.duration-200&&BEFORE_HAT.contains(beat())&&sited)return "waiting_for_catcher";
            String gestation=o.gestate(b,sited);
            if(!gestation.isEmpty()||!"drop".equals(beat()))return gestation;
            for(int i=0;i<b.children.size();i++){
                var child=b.children.get(i);
                if(child.born||b.elapsed<b.duration+i*8)continue;
                var at=FamilyGeometry.toWorld(m,FamilyGeometry.childBase(b.children.size(),i,1,m.growthScale(),b.variation,m.getVariant(),child.variant,GrowthSize.physicalBirth(b.kind,child)));
                // A blocked attachment delays the birth; it never spawns through the ceiling or into a wall.
                if(FamilyData.get(o.level()).birth(b,i,o.level(),at,placedHat(),false)!=null)
                    m.showReaction(b.incidentSource==null?"happy":"teary",b.incidentSource==null?"birth":"",100);
            }
            return "";
        }
        if(b.elapsed<b.duration)return o.gestate(b,sited);
        if(!"push".equals(beat()))return BEFORE_HAT.contains(beat())?"waiting_for_catcher":beat().startsWith("exit")?"turning_to_exit":"";
        if(!o.readyForDelivery())return "mother_not_ready";
        if(was!=null&&was.distanceToSqr(last)>.0004)return "mother_moving";
        if(!forced&&!LiveBirthGeometry.clear(m,pushing,m.yBodyRot,unprotected)){jump(unprotected?"exit_any":"exit","outlet_blocked");return "outlet_blocked";}
        if(!o.bellyRoom(b))return "no_room_for_belly";
        int delivery=b.elapsed-b.duration;
        if(delivery<LiveBirthGeometry.birthTick(pushing)){
            b.elapsed++;if(b.elapsed%200==0)m.life().spendNutrition(1);FamilyData.get(o.level()).setDirty();
            labourBeats(m,++delivery);
        }
        if(delivery<LiveBirthGeometry.birthTick(pushing))return "";
        var hat=unprotected||forced?null:placedHat();var data=FamilyData.get(o.level());
        if(hat!=null&&!LiveBirthGeometry.aims(m,pushing,m.yBodyRot,hat.birthPlace(b.kind,b.children.get(pushing)),HAT_REACH))hat=null;
        // Failed entity insertion pauses at this child's release, never running into the next child's clock.
        var child=data.birth(b,pushing,o.level(),LiveBirthGeometry.position(m,pushing),hat,unprotected||forced);
        if(child==null&&forced&&"newborn_space_blocked".equals(data.birthBlock(b))){
            // A forced birth with no room at the outlet comes out where her own body stands: a spawn place, never a moved body.
            child=data.birth(b,pushing,o.level(),m.position(),null,true);
            if(child!=null)GameplayLog.event(m,"family","birth forced reason=no_safe_exit at=mother_feet child={}",child.getUUID());
        }
        if(child==null)return "";
        BirthStaging.emerge(m,child.position());
        m.showReaction(pushing>0?"sleepy":"closed_smile","birth",LiveBirthGeometry.REST); // later children come easier but tire her more
        if(b.complete())o.delivered(b);
        return "";
    }
    /** Labour signs in the lead-in, the calls and soft sounds at the pushes (project original); each fires on one clock tick. */
    private static void labourBeats(YukkuriEntity m,int delivery){
        int prep=LiveBirthGeometry.PREPARATION;
        if(delivery<prep){
            // Restless and nervous: a worried face, beads of sweat, then she settles into place.
            if(delivery==1)m.showReaction("teary","",34);if(delivery==prep/2)m.showReaction("surprise","",30);
            if(delivery%30==12)BirthStaging.sweat(m);if(delivery==prep-4)BirthStaging.settle(m);
            return;
        }
        int index=LiveBirthGeometry.cycleIndex(delivery),strain=LiveBirthGeometry.strain(index),cycle=(int)LiveBirthGeometry.cycleTick(delivery);
        if(cycle==0||cycle==strain+36)m.showReaction("chewing_closed","birth",28);
        if(cycle==strain+8)BirthStaging.push(m,.9f);
        if(cycle==strain+46)BirthStaging.push(m,.8f);
        if(cycle==strain+LiveBirthGeometry.EMERGENCE+10&&index>0)BirthStaging.sweat(m);
    }

    /** No hat this time (no site, no catcher, the window ran out, the hat would not go down): the nest she is in, or the ground. */
    private String nocatch(String why){
        // A hat the catcher has already set down still catches, whatever ended the meeting.
        var hat=catcherHat();var b=brood();
        receiver=hat!=null?new PlacedHat(hat.getUUID()):FamilyNest.largeCentre(o.entity())!=null?new Nest():new Ground();
        GameplayLog.event(o.entity(),"family","catch window closed brood={} reason={} receiver={}",b==null?null:b.id,why,receiver.kind());
        if(hat==null&&!why.startsWith("place_"))BirthStaging.caption(o.entity(),"这一胎没有接生帽，改为无垫分娩（"+why+"）");
        return live?"term":"drop";
    }
    private String placed(Report r){
        var c=catcher();var hat=catcherHat();var b=brood();
        if(interrupted(r)&&hat==null&&window()>PAUSE)return "regroup"; // the clip was broken off: meet and set it down again
        if(hat==null||b==null){BirthStaging.caption(c,"帽子没能放下（落点不可用），这次改为无垫分娩");return nocatch("place_"+r.detail());}
        receiver=new PlacedHat(hat.getUUID());
        GameplayLog.event(c,"family","catch hat placed brood={} hat={} mother={} kind={} at={} yaw={} motherAt={}",b.id,hat.getUUID(),o.entity().getUUID(),b.kind,
                hat.position(),String.format(Locale.ROOT,"%.1f",hat.getYRot()),o.entity().position());
        BirthStaging.caption(c,"帽子已翻面放在出口前（帽子离开了头顶，成为真实的帽实体），接生者扶住帽沿等待");
        return live?"term":"drop";
    }
    private String unloaded(Report r){
        var hat=placedHat();var c=catcher();
        if(hat==null||c==null)return null;
        GameplayLog.event(c,"family","catch cleanup {} hat={} passengers={} result={}",r.done()?"completed":"interrupted",hat.getUUID(),hat.getPassengers().size(),r.detail());
        return r.done()?"take_hat":"rest";
    }
    private String caption(YukkuriEntity actor,String text){BirthStaging.caption(actor==null?o.entity():actor,text);return null;}
    /** A catcher lost before the hat is down means no catch; during the labour the hat stays the receiver; in the cleanup it ends. */
    @Override protected String onRoleLost(String role){return MOTHER.equals(role)?null:BEFORE_HAT.contains(beat())?nocatch("catcher_lost"):LABOUR.contains(beat())||beat().equals("linger")?beat():null;}
    @Override protected String resume(String saved){return saved.equals("meet")||saved.equals("regroup")?"find":saved;}
    @Override protected void saveData(CompoundTag tag){
        var saved=new CompoundTag();saved.putString("Kind",receiver.kind());
        if(receiver instanceof PlacedHat p&&p.host()!=null)saved.putUUID("Host",p.host());tag.put("Receiver",saved);
        if(unprotected)tag.putBoolean("Unprotected",true);
        if(forced)tag.putBoolean("Forced",true);
        var b=brood();if(b!=null)tag.putInt("Born",b.children.size()-b.unborn()); // a readout of the ledger, never read back
    }
    @Override protected void loadData(CompoundTag tag){
        var saved=tag.getCompound("Receiver");String kind=saved.getString("Kind");
        receiver=kind.equals("PlacedHat")?new PlacedHat(saved.hasUUID("Host")?saved.getUUID("Host"):null):kind.equals("Nest")?new Nest():new Ground();
        unprotected=tag.getBoolean("Unprotected");forced=tag.getBoolean("Forced");
        // The landings already greeted are the ledger's landed children: nobody is greeted twice after a reload.
        var b=brood();
        while(b!=null&&welcomed<b.children.size()&&b.children.get(welcomed).born&&b.children.get(welcomed).landed)welcomed++;
    }

    /** The mother's place for the term: out of the nest for a helper or a one-body cushion, into her large nest, or home. */
    private Intent site(){
        var m=o.entity();var care=FamilyNest.care(m);var shelter=m.habitat().shelter();
        BlockPos bed=care!=null?care.getBlockPos():shelter!=null?shelter.bed(m.getUUID()):null;
        boolean helped=receiver instanceof PlacedHat&&catcher()!=null&&!self(),cushion=live&&care!=null&&!care.largeNest();
        Vec3 target=null;
        if((helped||cushion)&&bed!=null){if(m.distanceToSqr(Vec3.atBottomCenterOf(bed))<=4)target=leaveNest(m,bed,helped?"catcher":"one_body_cushion_nest");}
        else if(o.nestStand()!=null){
            target=FamilyNest.restPosition(m,o.nestStand());
            if(target==null&&receiver instanceof Nest)target=FamilyNest.bedMiddle(m,o.nestStand());
        }
        return target==null?null:new GoTo("birth_site",m,target,now()+Math.max(PAUSE,toTerm()));
    }
    private static Vec3 leaveNest(YukkuriEntity m,BlockPos bed,String why){
        for(var side:Direction.Plane.HORIZONTAL){
            var at=SurfaceApproach.floor(m,Vec3.atBottomCenterOf(bed.relative(side,3)));
            if(at!=null&&SurfaceApproach.supported(m,at)&&m.life().reachable(BlockPos.containing(at),false)){
                GameplayLog.event(m,"family","near-term leave nest target={} reason={}",at,why);return at;
            }
        }
        GameplayLog.limited(m,"family","nest_exit_blocked",200,"near-term nest exit blocked nest={}",bed);
        return null;
    }
    /** The two stands of the meeting: her place (where she is, else up to three blocks round) and the catcher's beside the hat. */
    private Rendezvous meet(){
        var m=o.entity();var c=catcher();var b=brood();
        if(c==null||b==null||self())return null;
        int checked=0,routes=0;String why="no_floor";
        for(int radius=0;radius<=3&&routes<12;radius++)for(int side=0;side<(radius==0?1:8)&&routes<12;side++){
            double angle=side*Math.PI/4;
            var at=radius==0?m.position():SurfaceApproach.floor(m,m.position().add(Math.cos(angle)*radius,0,Math.sin(angle)*radius));
            if(at==null)continue;
            for(float turn:TURNS){
                checked++;float yaw=SurfaceApproach.yaw(at,c.position())+turn;
                var pose=HatGeometry.catchPose(m,b,c,HatGeometry.Role.HELPER,at,yaw);var stand=HatGeometry.stand(pose,c);
                var helper=stand.at();long now=now();excluded.removeIf(e->e.until()<=now);
                why=excluded.stream().anyMatch(e->e.mother().distanceToSqr(at)<.5625&&e.helper().distanceToSqr(helper)<.5625)?"excluded":refusal(m,c,b,at,pose,stand);
                if(why!=null)continue;
                if(routes++>=12)break;
                if((radius==0||SurfaceApproach.canApproach(m,at))&&SurfaceApproach.canApproach(c,stand.at())){
                    GameplayLog.event(c,"family","catch site selected brood={} mother={} station={} yaw={} helper={} checked={}",b.id,m.getUUID(),at,yaw,stand.at(),checked);
                    return new Rendezvous(m,c,new Rendezvous.Site(at,yaw,stand.at(),stand.yaw()),now()+window());
                }
                why="route";
            }
        }
        GameplayLog.event(c,"family","catch site search failed brood={} reason={} checked={}",b.id,why,checked);
        return null;
    }
    /** Why this meeting would not work, or null: both bodies supported and apart, the brim clear of both and of blocks. */
    private String refusal(YukkuriEntity m,YukkuriEntity c,FamilyData.Brood b,Vec3 at,HatGeometry.Pose pose,HatGeometry.Stand stand){
        if(!SurfaceApproach.supported(m,at))return "mother_support";
        if(!new HatProp(c,false).fits(pose.at(),pose.yaw()).ok())return "hat_support";
        if(!SurfaceApproach.supported(c,stand.at()))return "helper_support";
        double width=Math.max(HatGeometry.brim()[0],HatGeometry.brim()[1])*HatGeometry.hatScale(c)*c.getScale()/16;
        var body=live?LiveBirthGeometry.bodyDimensions(m,m.growthScale(),LiveBirthGeometry.belly(1,b.children.size()-b.unborn(),b.children.size()))
                .scale(m.getScale()).makeBoundingBox(at):m.getBoundingBox().move(at.subtract(m.position()));
        if(!m.level().noCollision(m,body))return "mother_belly_room";
        if(body.intersects(c.getBoundingBox().move(stand.at().subtract(c.position()))))return "parent_bodies_overlap";
        // A stem hat's near lip may lie under her front (stemClearance); a live catch keeps the whole brim clear of her.
        if(at.subtract(pose.at()).horizontalDistance()<=(live?m.getBbWidth()*.5+width:HatGeometry.stemClearance(m,c)-.01))return "mother_brim_overlap";
        if(stand.at().subtract(pose.at()).horizontalDistance()<=c.getBbWidth()*.5+width)return "helper_brim_overlap";
        var p=pose.at();
        return m.level().getBlockCollisions(c,new AABB(p.x-width,p.y+.01,p.z-width,p.x+width,p.y+.3,p.z+width)).iterator().hasNext()?"hat_blocked":null;
    }
    /** The catcher's own hat, set down before the outlet (or under the stem) from the authored stand, at the clip's release marker. */
    private Intent placeHat(){
        var m=o.entity();var c=catcher();var b=brood();
        if(c==null||b==null)return null;
        var pose=HatGeometry.catchPose(m,b,c,self()?HatGeometry.Role.SELF:HatGeometry.Role.HELPER,m.position(),m.yBodyRot);
        var stand=HatGeometry.stand(pose,c);var prop=new HatProp(c,self());
        return new GoTo("place_hat",c,self()?c.position():stand.at(),now()+Math.max(40,window())).around(self()?null:m)
                .facing(self()?Float.NaN:stand.yaw()).until(prop::deployed).then(()->new PlaceProp(prop,pose.at(),pose.yaw()));
    }
    /** The catcher during the labour: back at the hat's stand holding its brim, or (no hat) beside the outlet to cheer. */
    private Intent attend(){
        var m=o.entity();var c=catcher();
        if(c==null||self())return null;
        var hat=placedHat();
        if(hat!=null){var stand=HatGeometry.stand(HatGeometry.poseOf(hat),c);return new GoTo("hold_hat",c,stand.at(),now()+NEST_GRACE).around(m).facing(stand.yaw());}
        var forward=Vec3.directionFromRotation(0,m.yBodyRot);var side=new Vec3(-forward.z,0,forward.x);
        for(int sign:new int[]{1,-1}){
            var spot=SurfaceApproach.floor(c,m.position().add(forward.scale(m.getBbWidth()*.45)).add(side.scale(sign*(m.getBbWidth()*.5+c.getBbWidth()*.5+.12))));
            if(spot!=null&&m.level().noCollision(c,c.getBoundingBox().move(spot.subtract(c.position()))))
                return new GoTo("attend",c,spot,now()+NEST_GRACE).around(m).facing(SurfaceApproach.yaw(spot,LiveBirthGeometry.position(m,0)));
        }
        return null;
    }
    /**
     * Walk to the hat's own stand (where the hat_on clip's braid finds it: the inverse of the clip's placement), face its yaw
     * and put it back on; the errand ends once the clip has carried the hat to the head. Without a floor there, beside the hat
     * facing it. Shared with the orphan-hat errand.
     */
    static Intent takeHat(YukkuriEntity c,PlacedHatEntity hat,long deadline){
        if(c==null||hat==null)return null;
        var prop=new HatProp(c,hat.role()==HatGeometry.Role.SELF);
        var stand=HatGeometry.stand(HatGeometry.poseOf(hat),c);var at=SurfaceApproach.floor(c,stand.at());float yaw=stand.yaw();
        if(at==null){
            var away=c.position().subtract(hat.position()).multiply(1,0,1);var dir=away.lengthSqr()<1E-6?new Vec3(0,0,1):away.normalize();
            var near=SurfaceApproach.floor(c,hat.position().add(dir.scale((hat.getBbWidth()+c.getBbWidth())*.5+.2)));
            at=near==null?c.position():near;yaw=SurfaceApproach.yaw(at,hat.position());
        }
        return new GoTo("take_hat",c,at,deadline).facing(yaw).until(()->!prop.deployed()&&!(c.motor().current() instanceof TakeProp))
                .then(()->new TakeProp(prop));
    }
    /** Turn the outlet to the receiver (or any clear floor) with Face, then hold until it is clear (spec 14 WP3b). */
    private final class Deliver implements Intent, Method {
        private final boolean any;
        private final long deadline;
        private float yaw=Float.NaN;
        private Deliver(boolean any){this.any=any;deadline=now()+(any?1200:NEST_GRACE);}
        private float choose(){
            var b=brood();var hat=any?null:placedHat();
            Vec3 aim=b==null?null:hat!=null?hat.birthPlace(b.kind,b.children.get(pushing)):receiver instanceof Nest?FamilyNest.largeCentre(o.entity()):null;
            return yaw=LiveBirthGeometry.exitYaw(o.entity(),pushing,aim,hat!=null?HAT_REACH:NEST_REACH,any);
        }
        @Override public String id(){return any?"turn_to_any_exit":"turn_to_exit";}
        @Override public boolean satisfied(){var m=o.entity();return !Float.isNaN(yaw)&&Math.abs(Mth.wrapDegrees(yaw-m.yBodyRot))<=Face.FACE_ARRIVED&&LiveBirthGeometry.clear(m,pushing,m.yBodyRot,any);}
        @Override public long deadline(){return deadline;}
        @Override public List<Method> methods(){return List.of(this);}
        @Override public Feasibility applicable(){return brood()==null?Feasibility.of(Reason.TARGET_GONE,0,"brood_gone"):Feasibility.pass();}
        @Override public Set<Reason> reduces(){return Set.of(Reason.NO_PROGRESS);}
        @Override public Step next(){
            int left=(int)Math.max(1,deadline-now());
            if(Float.isNaN(choose()))return new Step.Wait(()->!Float.isNaN(choose()),left,"no_safe_exit");
            return Math.abs(Mth.wrapDegrees(yaw-o.entity().yBodyRot))>Face.FACE_ARRIVED?new Step.Do(new Face(yaw)):new Step.Wait(this::satisfied,left,"outlet_clear");
        }
    }

    FamilyData.Brood brood(){return broodId==null?null:FamilyData.get(o.level()).brood(broodId);}
    YukkuriEntity catcher(){var id=role(CATCHER);return id!=null&&o.level().getEntity(id) instanceof YukkuriEntity c&&c.isAlive()?c:null;}
    private boolean self(){return o.entity().getUUID().equals(role(CATCHER));}
    /** The catch hat this birth set down, while it is in the world. */
    PlacedHatEntity placedHat(){
        return receiver instanceof PlacedHat p&&p.host()!=null&&o.level().getEntity(p.host()) instanceof PlacedHatEntity hat&&!hat.isRemoved()&&hat.catches()?hat:null;
    }
    /** The newest landing while its greeting lasts (80 ticks, project original). */
    Welcome welcome(long now){return welcome!=null&&now-welcome.tick()<=80?welcome:null;}
    /** Ticks left of the postpartum rest (the belly's last shrink reads it). */
    int recoveryLeft(){return live&&"linger".equals(beat())?(int)Math.max(0,LiveBirthGeometry.RECOVERY-(now()-beatTick())):0;}
    String hold(){return hold;}
    /** The labour (or the drops) is on: the catcher holds the hat's brim, or cheers beside the outlet. */
    boolean attending(){return LABOUR.contains(beat());}
    String describe(){return "beat="+beat()+" receiver="+receiver.kind()+" roles="+roles().keySet()+" hold="+hold;}
    private int firstUnborn(){var b=brood();int next=0;while(b!=null&&next<b.children.size()-1&&b.children.get(next).born)next++;return next;}
    private int toTerm(){var b=brood();return b==null?0:Math.max(0,b.duration-b.elapsed);}
    /** Ticks left of the one placement window (the rest of the gestation plus CATCH_WAIT), fixed the first time it is asked. */
    private int window(){if(windowEnd==0)windowEnd=now()+toTerm()+CATCH_WAIT;return (int)Math.max(1,windowEnd-now());}
    private int cleanupLeft(){return (int)Math.max(1,cleanupEnd-now());}
    private static boolean interrupted(Report r){return r.verdict()==Report.Verdict.INTERRUPTED;}
    /** The hat the catcher has set down near the mother, found by its owner (also after the catcher herself is lost). */
    private PlacedHatEntity catcherHat(){
        var id=role(CATCHER);
        return id==null?null:o.level().getEntitiesOfClass(PlacedHatEntity.class,o.entity().getBoundingBox().inflate(8),h->h.catches()&&id.equals(h.ownerYukkuri())).stream().findFirst().orElse(null);
    }
    private int pushTicks(){var b=brood();return b==null?1:Math.max(1,b.duration+LiveBirthGeometry.birthTick(pushing)-b.elapsed)+NEST_GRACE+CATCH_WAIT;}
    private long now(){return o.level().getGameTime();}
}
