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
import io.github.longshou.mcyukkuri.intent.HostIntent;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Rendezvous;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.intent.WorldSignature;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Gesture;
import io.github.longshou.mcyukkuri.motor.action.PlaceProp;
import io.github.longshou.mcyukkuri.motor.action.TakeProp;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The birth as one choreography, conducted by the mother's {@link FamilyLife} over the last
 * {@value #APPROACH_LEAD} ticks of a LIVE or STEM gestation. Roles: the mother, and the catcher (a Marisa father nearby,
 * hatted or not, else a hatted Marisa mother herself). The receiver is a hat the catcher sets down, a large nest, the ground,
 * or the boat she rides ({@link OwnHull}: her own hat afloat, no catcher, no hat to set down or take back; the outlet turns to
 * a free place in its hollow and the drawn push may still carry the child over the brim into the water). Both tables share the
 * site, meeting, hat and cleanup beats; LIVE adds the labour beats, STEM one drop beat. The labour clock ({@link #conductData})
 * only advances the brood ledger; the bodies move through their own BirthGoal.
 */
final class LiveBirth extends Choreography {
    static final String MOTHER="mother", CATCHER="catcher";
    /** The catcher notices this early; the hat may come down up to CATCH_WAIT past term; the last NEST_GRACE ticks need the site. */
    static final int APPROACH_LEAD=1200, CATCH_WAIT=600, NEST_GRACE=400, PAUSE=100;
    /** How near the outlet's child must be to the receiver's aim for the outlet to be aimed at it (blocks). */
    private static final double NEST_REACH=.8;
    /** Her turns from facing the catcher a meeting site tries, in sixteenths, the least first (her stand and the catcher's
     *  outside the brim may need more room than one eighth leaves beside a wall or a tree). */
    private static final float[] TURNS={0,22.5f,-22.5f,45,-45,67.5f,-67.5f,90,-90,112.5f,-112.5f,135,-135,157.5f,-157.5f,180};
    private static final Set<String> BEFORE_HAT=Set.of("site","meet","regroup","self_wait","place"),LABOUR=Set.of("term","exit","exit_any","push","drop");
    /** Where the newborns are meant to come down ({@code Receiver}); a placed hat's host is set once it is down. */
    sealed interface Receiver permits PlacedHat, Nest, Ground, OwnHull { default String kind(){return getClass().getSimpleName();} }
    record PlacedHat(UUID host) implements Receiver {}
    /** The boat the mother rides, her own hat afloat (帽船分娩). */
    record OwnHull(UUID host) implements Receiver {}
    record Nest() implements Receiver {}
    record Ground() implements Receiver {}
    /** A newborn's real landing, greeted by its father and siblings from their own ticks. */
    record Welcome(UUID baby, long tick) {}

    private final FamilyLife o;
    private final UUID broodId;
    private final boolean live;
    // The receiver, outlet flags and qualified exit are saved; searches are rebuilt from the world.
    private Receiver receiver=new Ground();
    private Rendezvous meeting;
    /** Meeting sites that failed, kept out of the search for NEST_GRACE ticks (bounded, never saved). */
    private record Excluded(Vec3 mother,Vec3 helper,long until){}
    private final List<Excluded> excluded=new ArrayList<>();
    private long windowEnd,cleanupEnd;
    /** Sticky once upgraded: any clear outlet (liquid allowed), and then (forced) no outlet check at all. */
    private boolean unprotected,forced;
    private int pushing,welcomed,unloads,takes;
    private Welcome welcome;
    /** The world the last cleanup step failed in: the same step waits until it has changed (never saved). */
    private WorldSignature failed;
    /** One child's qualified stand/aim in the receiver's frame, and facing relative to its yaw; never a reserved seat. */
    private record Exit(int child,UUID hat,Vec3 stand,float yaw,Vec3 aim,int ticks) {}
    private Exit exit;
    private List<Exit> choices;
    private List<Vec3> receiverPoints;
    private long exitUntil,qualificationTick=-1;
    private boolean qualification;
    private int qualificationMisses;
    private Vec3 last;
    private String hold="";
    private String rejection="";
    LiveBirth(FamilyLife o,UUID broodId,boolean live){super(live?"live_birth":"stem_birth","site");this.o=o;this.broodId=broodId;this.live=live;}
    /** Casts a new birth: who catches, and so what receives (a hat, else her large nest, else the ground). */
    static LiveBirth cast(FamilyLife o,FamilyData.Brood brood){
        var birth=new LiveBirth(o,brood.id,brood.kind==FamilyData.BirthKind.LIVE);birth.bind(MOTHER,o.entity().getUUID());
        birth.recast(catcher(o.entity(),brood));
        return birth;
    }
    /** Binds the catcher and picks the receiver: the boat she rides (no catcher), else the catcher's hat (already down, or to be set
     *  down; one whose hat is out as a boat catches nothing and is not cast), else the large nest or the ground. */
    private void recast(YukkuriEntity catcher){
        var mother=o.entity();var hull=ownHull(mother);
        if(hull!=null){bind(CATCHER,null);receiver=new OwnHull(hull.getUUID());return;}
        var hat=catcher==null?null:PlacedHatEntity.find(catcher);boolean boat=hat!=null&&!hat.catches();
        bind(CATCHER,catcher==null||boat?null:catcher.getUUID());
        if(catcher!=null&&!boat&&HatProp.usable(catcher)&&!(catcher==mother&&FamilyNest.large(mother)))receiver=new PlacedHat(hat==null?null:hat.getUUID());
        else receiver=FamilyNest.large(mother)?new Nest():new Ground();
    }
    /** Her own hat she rides (a hat bears its owner only as her boat, never as a cradle), upright. */
    static PlacedHatEntity ownHull(YukkuriEntity m){var hat=PlacedHatEntity.find(m);return hat!=null&&m.getVehicle()==hat&&!hat.upsideDown()?hat:null;}
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
            t.put("exit",new Beat.Perform(Beat.roles(MOTHER,()->new Deliver(false),CATCHER,this::attend),()->(int)Math.max(1,exitUntil-now()),"turned","exit_any"));
            t.put("exit_any",new Beat.Perform(Beat.roles(MOTHER,()->new Deliver(true),CATCHER,this::attend),()->1200,"turned_any","force"));
            t.put("turned",new Beat.Branch(r->r.done()?"push":now()<exitUntil?"exit":"exit_any"));
            t.put("turned_any",new Beat.Branch(r->r.done()?"push":interrupted(r)?"exit_any":"force"));
            // No clear outlet at all: the birth is forced; real collision still applies to the newborn (never a permanent wait).
            t.put("force",new Beat.Branch(r->{forced=true;GameplayLog.event(o.entity(),"family","birth forced reason={} child={}",hold.isEmpty()?"no_safe_exit":hold,pushing);return "push";}));
            t.put("push",new Beat.Await(Beat.roles(MOTHER,null,CATCHER,this::attend),()->brood()==null||brood().children.get(pushing).born,this::pushTicks,"pushed","pushed"));
            t.put("pushed",new Beat.Branch(r->brood()==null||brood().unborn()==0?"linger":!brood().children.get(pushing).born?"force":forced?"push":unprotected?"exit_any":"exit"));
            t.put("linger",new Beat.Present(Beat.roles(MOTHER,null,CATCHER,this::attend),Behaviors.POSTPARTUM,LiveBirthGeometry.RECOVERY,"lingered"));
        }else{
            t.put("drop",new Beat.Await(Beat.roles(MOTHER,null,CATCHER,this::attend),()->brood()==null||brood().complete(),()->toTerm()+1240,"linger","drop"));
            t.put("linger",new Beat.Present(Beat.roles(MOTHER,null,CATCHER,this::attend),"",120,"lingered"));
        }
        t.put("lingered",new Beat.Branch(r->placedHat()!=null&&catcher()!=null?"unload":null));
        // The cleanup has one deadline from the end of the linger and at most four real attempts of each step: a failed step is
        // tried again only once the world it failed in has changed, the catcher attending meanwhile; then the hat errand.
        t.put("unload",new Beat.Perform(Beat.roles(CATCHER,()->placedHat()==null||catcher()==null?null:new Unload(placedHat(),catcher(),false)),
                ()->Math.min(Unload.DEADLINE_TICKS+40,cleanupLeft()),"unloaded","unloaded"));
        t.put("unloaded",new Beat.Branch(this::unloaded));
        t.put("rest",new Beat.Await(Beat.roles(CATCHER,this::attend),()->placedHat()==null||failed==null||!failed.equals(cleanupWorld()),this::cleanupLeft,"rested","rested"));
        t.put("rested",new Beat.Branch(r->placedHat()==null||catcher()==null?null:placedHat().getPassengers().isEmpty()?"take_hat":"unload"));
        t.put("take_hat",new Beat.Perform(Beat.roles(CATCHER,()->takeHat(catcher(),placedHat(),now()+600)),()->Math.min(600,cleanupLeft()),"taken","taken"));
        t.put("taken",new Beat.Branch(r->{
            if(r.done()||placedHat()==null)return caption(catcher(),"帽子已戴回头上");
            if(catcher()==null)return null;
            failed=cleanupWorld();return "rest";
        }));
        return t;
    }

    @Override protected void entered(String beat){
        var c=catcher();
        switch(beat){
            case "meet" -> BirthStaging.caption(c,"父油察觉母油临产，走向她面前的出口");
            case "place" -> BirthStaging.caption(c,self()?"母油自己摘下帽子（hat_off_self）":"接生者走到出口前方，用辫子摘下自己的帽子（hat_off）");
            case "exit","exit_any","push" -> {
                boolean widening=beat.equals("exit_any")&&!unprotected;
                unprotected|=beat.equals("exit_any");int next=firstUnborn();
                if(next!=pushing||exitUntil==0){choices=null;receiverPoints=null;exitUntil=now()+NEST_GRACE;qualificationTick=-1;qualificationMisses=0;}
                if(exit!=null&&exit.child()!=next)exit=null;
                if(widening){exit=null;choices=null;}
                if(beat.equals("exit_any"))rejection="";
                pushing=next;
                // She went aboard her boat after the cast (no catch hat down): it is the receiver now.
                if(placedHat()==null&&!(receiver instanceof OwnHull)&&ownHull(o.entity()) instanceof PlacedHatEntity hull){
                    receiver=new OwnHull(hull.getUUID());bind(CATCHER,null);GameplayLog.event(o.entity(),"family","birth receiver own_hull hat={} beat={}",hull.getUUID(),beat);
                }
                deliveryReadout("transition",beat);
            }
            default -> {}
        }
        if(!beat.equals("unload")&&!beat.equals("take_hat"))return;
        if(cleanupEnd==0)cleanupEnd=now()+Unload.DEADLINE_TICKS+1200;
        int entries=beat.equals("unload")?++unloads:++takes;
        if(entries<=4&&now()<cleanupEnd){
            // Said once: a later entry is the same step tried in a changed world.
            if(entries==1&&beat.equals("take_hat"))BirthStaging.caption(c,self()?"接生者把帽子戴回头上（hat_on_self）":"幼体已被放出，接生者用辫子把帽子戴回（hat_on）");
            return;
        }
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
            if(!"GROUND".equals(child.landing)&&!"WATER".equals(child.landing))BirthStaging.caption(o.entity(),"HAT".equals(child.landing)?"新生儿落进帽中，被稳稳接住":"新生儿落在地面（这一胎没有放帽）");
        }
        if(b==null||b.cancelled){if(BEFORE_HAT.contains(beat())||attending())jump(null,"brood_gone");return;}
        if(b.complete())return;
        // Nobody to catch yet and the hat could still come down before term: notice a catcher who arrives (a real failure keeps hers bound).
        if(role(CATCHER)==null&&!(receiver instanceof OwnHull)&&now%20==0&&b.elapsed<b.duration&&(beat().equals("site")||LABOUR.contains(beat()))
                &&catcher(o.entity(),b) instanceof YukkuriEntity c){
            recast(c);
            GameplayLog.event(c,"family","catch duty noticed brood={} mother={} beat={} receiver={}",b.id,o.entity().getUUID(),beat(),receiver.kind());
            if(!beat().equals("site")&&receiver instanceof PlacedHat){jump("sited","catcher_found");return;}
        }
        String before=hold;hold=advance(b);
        if(!hold.equals(before))deliveryReadout(hold.isEmpty()?"resumed":beat().startsWith("exit")?"exit_wait":beat().equals("push")
                ?b.elapsed-b.duration>LiveBirthGeometry.emergenceStart(pushing)?"mid_push_hold":"push_hold":"preparation_wait",hold);
    }
    private String advance(FamilyData.Brood b){
        // Read the host's authoritative spot in blocks: vanilla refreshes her world position after her own tick.
        var m=o.entity();Vec3 was=last;last=m.getVehicle() instanceof SurfaceHost host?host.riders().local(m.getUUID()).scale(host.frame().unit()):m.position();
        boolean sited=!"site".equals(beat());
        if(!live){
            if(b.elapsed>=b.duration-200&&BEFORE_HAT.contains(beat())&&sited)return "waiting_for_catcher";
            String gestation=o.gestate(b,sited);
            if(!gestation.isEmpty()||!"drop".equals(beat()))return gestation;
            for(int i=0;i<b.children.size();i++){
                var child=b.children.get(i);
                if(child.born||b.elapsed<b.duration+i*8)continue;
                var at=FamilyGeometry.toWorld(m,FamilyGeometry.childBase(b.children.size(),i,1,m.growthScale(),b.variation,m.getVariant(),child.variant,GrowthSize.physicalBirth(b.kind,child)));
                // A blocked attachment delays the birth; it never spawns through the ceiling or into a wall.
                if(FamilyData.get(o.level()).birth(b,i,o.level(),at,receiverHat(),null,receiver instanceof OwnHull)!=null)
                    m.showReaction(b.incidentSource==null?"happy":"teary",b.incidentSource==null?"birth":"",100);
            }
            return "";
        }
        if(b.elapsed<b.duration)return o.gestate(b,sited);
        if(!"push".equals(beat()))return BEFORE_HAT.contains(beat())?"waiting_for_catcher":beat().startsWith("exit")?rejection.isEmpty()?"turning_to_exit":rejection:"";
        if(!o.readyForDelivery())return "mother_not_ready";
        if(was!=null&&was.distanceToSqr(last)>.0004)return "mother_moving";
        int delivery=b.elapsed-b.duration;
        boolean visible=delivery>LiveBirthGeometry.emergenceStart(pushing);
        var hat=receiverHat();
        if(!forced&&!LiveBirthGeometry.clear(m,pushing,m.yBodyRot,unprotected||receiver instanceof OwnHull))return "outlet_blocked";
        if(!o.bellyRoom(b))return "no_room_for_belly";
        // Replanning is permitted before the child is visible. Once emerging, retain this aim and the real risk of lost room.
        if(hat!=null&&!unprotected&&!forced&&!visible&&(exit==null||!qualified(hat)&&qualificationMisses>=3)){
            discardExit("nominal_exit_lost");jump("exit","nominal_exit_lost");return "nominal_exit_lost";
        }
        if(delivery<LiveBirthGeometry.birthTick(pushing)){
            b.elapsed++;if(b.elapsed%200==0)m.life().spendNutrition(1);FamilyData.get(o.level()).setDirty();
            labourBeats(m,++delivery);
        }
        if(delivery<LiveBirthGeometry.birthTick(pushing))return "";
        var data=FamilyData.get(o.level());var at=LiveBirthGeometry.position(m,pushing);
        if(hat!=null&&(unprotected||forced)&&exit==null)riskyExit(hat,m.yBodyRot);
        var nominal=hat!=null&&exit!=null?(unprotected||forced?BirthLaunch.risky(at,hat.frame().toWorld(exit.aim()),m.yBodyRot)
                :BirthLaunch.forward(at,hat.frame().toWorld(exit.aim()),m.yBodyRot,exit.ticks())):null;
        if(nominal==null&&(exit!=null||!unprotected&&!forced&&(receiver instanceof PlacedHat||receiver instanceof OwnHull)))GameplayLog.limited(m,"family","birth_lost_aim",200,
                "birth lost aim reason={} delivery={} phase=emerging host={}",hat==null?"host_missing":exit==null?"exit_missing":"behind",delivery,hat==null?null:hat.getUUID());
        if(nominal==null)hat=null;
        // Failed entity insertion pauses at this child's release, never running into the next child's clock; inside her boat's
        // dry hollow the outlet's space is checked for blocks only.
        var child=data.birth(b,pushing,o.level(),at,hat,nominal,unprotected||forced||hat!=null&&receiver instanceof OwnHull);
        if(child==null&&forced&&"newborn_space_blocked".equals(data.birthBlock(b))){
            // A forced birth with no room at the outlet comes out where her own body stands: a spawn place, never a moved body.
            hat=null;child=data.birth(b,pushing,o.level(),m.position(),null,null,true);
            if(child!=null)GameplayLog.event(m,"family","birth forced reason=newborn_space_blocked at=mother_feet child={}",child.getUUID());
        }
        if(child==null)return data.birthBlock(b);
        deliveryReadout("release","spawned",hat);
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
    private void deliveryReadout(String stage,String reason){
        deliveryReadout(stage,reason,null);
    }
    private void deliveryReadout(String stage,String reason,PlacedHatEntity launchHat){
        var b=brood();if(b==null||!live)return;
        int delivery=b.elapsed-b.duration;var hat=receiverHat();var out=LiveBirthGeometry.position(o.entity(),pushing);
        var planned=hat==null||exit==null?null:hat.frame().toLocal(LiveBirthGeometry.position(o.entity(),pushing,hat.frame().toWorld(exit.stand()),hat.getYRot()+exit.yaw()));
        GameplayLog.limited(o.entity(),"family","birth."+stage+"."+reason,200,
                "birth delivery stage={} reason={} rejection={} beat={} child={} delivery={} phase={} host={} outlet={} aim={} stand={} plannedOutlet={} launchHat={} forced={} unprotected={}",
                stage,reason,rejection,beat(),pushing,delivery,delivery<LiveBirthGeometry.emergenceStart(pushing)?"before_emergence":"emerging",
                hat==null?null:hat.getUUID(),hat==null?out:hat.frame().toLocal(out),exit==null?null:exit.aim(),exit==null?null:exit.stand(),planned,launchHat==null?null:launchHat.getUUID(),forced,unprotected);
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
        if(r.done())return "take_hat";
        failed=cleanupWorld();return "rest";
    }
    /** The world a cleanup step is tried in: the young aboard (the nearest one's place) or the catcher's place, the hat's place,
     *  and the signature's time bucket, so a wait for it to change is bounded. */
    private WorldSignature cleanupWorld(){
        var hat=placedHat();var c=catcher();
        if(hat==null||c==null)return null;
        var young=hat.youngPassengers();
        var at=young.stream().map(Entity::position).min(Comparator.comparingDouble(p->p.distanceToSqr(c.position()))).orElse(c.position());
        return WorldSignature.of(at,hat.position(),young.stream().map(Entity::getId).collect(Collectors.toSet()),now());
    }
    private String caption(YukkuriEntity actor,String text){BirthStaging.caption(actor==null?o.entity():actor,text);return null;}
    /** A catcher lost before the hat is down means no catch; during the labour the hat stays the receiver; in the cleanup it ends. */
    @Override protected String onRoleLost(String role){return MOTHER.equals(role)?null:BEFORE_HAT.contains(beat())?nocatch("catcher_lost"):LABOUR.contains(beat())||beat().equals("linger")?beat():null;}
    @Override protected String resume(String saved){return saved.equals("meet")||saved.equals("regroup")?"find":saved;}
    @Override protected void saveData(CompoundTag tag){
        var saved=new CompoundTag();saved.putString("Kind",receiver.kind());
        var host=receiver instanceof PlacedHat p?p.host():receiver instanceof OwnHull h?h.host():null;
        if(host!=null)saved.putUUID("Host",host);tag.put("Receiver",saved);
        if(unprotected)tag.putBoolean("Unprotected",true);
        if(forced)tag.putBoolean("Forced",true);
        if(exit!=null){
            var e=new CompoundTag();e.putInt("Child",exit.child());e.putUUID("Hat",exit.hat());e.putFloat("Yaw",exit.yaw());e.putInt("Ticks",exit.ticks());
            e.putDouble("StandX",exit.stand().x);e.putDouble("StandY",exit.stand().y);e.putDouble("StandZ",exit.stand().z);
            e.putDouble("AimX",exit.aim().x);e.putDouble("AimY",exit.aim().y);e.putDouble("AimZ",exit.aim().z);tag.put("Exit",e);
        }
        var b=brood();if(b!=null)tag.putInt("Born",b.children.size()-b.unborn()); // a readout of the ledger, never read back
    }
    @Override protected void loadData(CompoundTag tag){
        var saved=tag.getCompound("Receiver");String kind=saved.getString("Kind");
        receiver=kind.equals("PlacedHat")?new PlacedHat(saved.hasUUID("Host")?saved.getUUID("Host"):null):kind.equals("OwnHull")&&saved.hasUUID("Host")
                ?new OwnHull(saved.getUUID("Host")):kind.equals("Nest")?new Nest():new Ground();
        unprotected=tag.getBoolean("Unprotected");forced=tag.getBoolean("Forced");
        if(tag.contains("Exit")){
            var e=tag.getCompound("Exit");exit=new Exit(e.getInt("Child"),e.getUUID("Hat"),new Vec3(e.getDouble("StandX"),e.getDouble("StandY"),e.getDouble("StandZ")),
                    e.getFloat("Yaw"),new Vec3(e.getDouble("AimX"),e.getDouble("AimY"),e.getDouble("AimZ")),e.getInt("Ticks"));
        }
        pushing=firstUnborn();
        // The landings already greeted are the ledger's landed children: nobody is greeted twice after a reload.
        var b=brood();
        while(b!=null&&welcomed<b.children.size()&&b.children.get(welcomed).born&&b.children.get(welcomed).landed)welcomed++;
    }

    /** The mother's place for the term: out of the nest for a helper or a one-body cushion, into her large nest, or home. */
    private Intent site(){
        if(receiver instanceof OwnHull)return null; // aboard her own boat she is where she gives birth
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
    /** Why this meeting would not work, or null: both bodies supported, their footprints apart (hers with the full belly's front),
     *  the brim off her footprint (the catcher's stand lies outside it by construction) and clear of blocks. */
    private String refusal(YukkuriEntity m,YukkuriEntity c,FamilyData.Brood b,Vec3 at,HatGeometry.Pose pose,HatGeometry.Stand stand){
        if(!SurfaceApproach.supported(m,at))return "mother_support";
        if(!new HatProp(c,false).fits(pose.at(),pose.yaw()).ok())return "hat_support";
        if(!SurfaceApproach.supported(c,stand.at()))return "helper_support";
        double width=Math.max(pose.profile().brim()[0],pose.profile().brim()[1])*HatGeometry.unit(c);
        float belly=live?LiveBirthGeometry.belly(1,b.children.size()-b.unborn(),b.children.size()):0;
        var body=live?LiveBirthGeometry.bodyDimensions(m,m.growthScale(),belly).scale(m.getScale()).makeBoundingBox(at):m.getBoundingBox().move(at.subtract(m.position()));
        if(!m.level().noCollision(m,body))return "mother_belly_room";
        double foot=LiveBirthGeometry.footprint(m,belly);
        if(at.subtract(stand.at()).horizontalDistance()<foot+Occupancy.foot(c.getBbWidth()))return "parent_bodies_overlap";
        // A stem hat's near lip may lie under her front (stemClearance); a live catch keeps the whole brim off her footprint.
        if(live?HatGeometry.overlaps(pose,HatGeometry.unit(c),at,foot):at.subtract(pose.at()).horizontalDistance()<=HatGeometry.stemClearance(m,c)-.01)return "mother_brim_overlap";
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
    /** The catcher while the young come and after: with the hat (Attend), or (no hat) beside the outlet to cheer. */
    private Intent attend(){
        var m=o.entity();var c=catcher();
        if(c==null||self())return null;
        var hat=placedHat();
        if(hat!=null)return new Attend(hat,c);
        var forward=Vec3.directionFromRotation(0,m.yBodyRot);var side=new Vec3(-forward.z,0,forward.x);
        for(int sign:new int[]{1,-1}){
            var spot=SurfaceApproach.floor(c,m.position().add(forward.scale(m.getBbWidth()*.45)).add(side.scale(sign*(LiveBirthGeometry.footprint(m,o.liveBelly())+Occupancy.foot(c.getBbWidth())+.05))));
            if(spot!=null&&m.level().noCollision(c,c.getBoundingBox().move(spot.subtract(c.position()))))
                return new GoTo("attend",c,spot,now()+NEST_GRACE).around(m).facing(SurfaceApproach.yaw(spot,LiveBirthGeometry.position(m,0)));
        }
        return null;
    }
    /**
     * The catcher with the hat from the first birth until it goes back on (项目设计): at a stand outside its brim, turned to the
     * young, watching, its greeting hop included (the hop's one owner). Through the labour the clip's own stand first, then the
     * other stands round the hat (HatGeometry.stands) off her side of it; after the labour where it stands (outside the brim
     * and near), then the nearest ones. A stand it cannot get to is not chosen again in the same world, so the next one is. It
     * only attends the young ({@code unloads()} false): the crawlers gather at its side, nobody is asked off. Never satisfied,
     * the beat ends it.
     */
    private final class Attend implements HostIntent {
        /** How far out (blocks from the hat's middle) a catcher already outside the brim may stay where it is. */
        private static final double WATCH_REACH=1.5;
        private final PlacedHatEntity hat;
        private final YukkuriEntity c;
        private final boolean labour=attending();
        private final long until=now()+2400;
        private final List<Method> stands=new ArrayList<>();
        private Welcome hopped;
        private Attend(PlacedHatEntity hat,YukkuriEntity c){
            this.hat=hat;this.c=c;var m=o.entity();
            var own=HatGeometry.stand(HatGeometry.poseOf(hat),c);
            var here=HatGeometry.outside(hat,c,c.position(),Math.min(WATCH_REACH,c.position().subtract(hat.position()).horizontalDistance()));
            var first=labour?new Watch("clip",own.at(),own.yaw()):new Watch("here",here,SurfaceApproach.yaw(here,hat.position()));
            var all=HatGeometry.stands(hat,c);
            for(int i=0;i<all.size();i++){
                var s=all.get(i);
                // Through the labour none between her and the hat, where the newborns come over the near brim.
                if(labour&&s.at().distanceToSqr(m.position())<hat.position().distanceToSqr(m.position()))continue;
                stands.add(new Watch("stand_"+i,s.at(),labour?s.yaw():SurfaceApproach.yaw(s.at(),hat.position())));
            }
            stands.sort(Comparator.comparingDouble(w->((Watch)w).stand.distanceToSqr(c.position())));
            stands.addFirst(first);
        }
        @Override public String id(){return "attend_hat";}
        @Override public SurfaceHost host(){return hat;}
        @Override public boolean unloads(){return false;}
        @Override public boolean satisfied(){return false;}
        @Override public long deadline(){return until;}
        @Override public List<Method> methods(){return stands;}
        private final class Watch implements Method {
            private final String id;
            private final Vec3 stand;
            private final float yaw;
            private Vec3 at;
            private Watch(String id,Vec3 stand,float yaw){this.id=id;this.stand=stand;this.yaw=yaw;}
            @Override public String id(){return id;}
            @Override public Feasibility applicable(){
                if(hat.isRemoved()||!hat.catches())return Feasibility.of(Reason.TARGET_GONE,0,"hat_gone");
                return (at=SurfaceApproach.floor(c,stand))==null?Feasibility.of(Reason.NO_SUPPORT,0,"no_floor"):Feasibility.pass();
            }
            @Override public Set<Reason> reduces(){return Set.of(Reason.NO_PATH,Reason.NO_PROGRESS,Reason.NO_SUPPORT);}
            @Override public Step next(){
                if(!SurfaceApproach.arrived(c,at))return new Step.Do(labour?Walk.around(at,o.entity()):new Walk(at));
                if(Math.abs(Mth.wrapDegrees(yaw-c.getYRot()))>Face.FACE_ARRIVED)return new Step.Do(new Face(yaw));
                var hello=c.family().greetHop();
                if(hello!=null&&!hello.equals(hopped)){hopped=hello;return new Step.Do(new Gesture(Gesture.Kind.HOP_SMALL));}
                long end=Math.min(until,now()+20);
                return new Step.Wait(()->now()>=end||!SurfaceApproach.arrived(c,at)||c.family().greetHop() instanceof Welcome w&&!w.equals(hopped),(int)(end-now())+2,"attend");
            }
        }
    }
    /** Put the hat back on (shared with the orphan-hat errand). */
    static Intent takeHat(YukkuriEntity c,PlacedHatEntity hat,long deadline){return c==null||hat==null?null:new TakeHat(c,hat,deadline);}
    /**
     * Walk to a stand outside the brim (HatGeometry.stands: the hat_on clip's own turned about the hat, nearest first), face its
     * yaw and run TakeProp; the errand ends once the clip has carried the hat to the head. A stand that failed is not chosen again
     * in the same world, so the runner goes on to the next one.
     */
    private static final class TakeHat implements HostIntent {
        private final YukkuriEntity c;
        private final PlacedHatEntity hat;
        private final HatProp prop;
        private final long deadline;
        private final List<Method> stands=new ArrayList<>();
        private TakeHat(YukkuriEntity c,PlacedHatEntity hat,long deadline){
            this.c=c;this.hat=hat;this.deadline=deadline;prop=new HatProp(c,hat.role()==HatGeometry.Role.SELF);
            var all=HatGeometry.stands(hat,c);
            for(int i=0;i<all.size();i++)stands.add(new From(i,all.get(i)));
            stands.sort(Comparator.comparingDouble(m->((From)m).stand.at().distanceToSqr(c.position())));
        }
        @Override public String id(){return "take_hat";}
        @Override public SurfaceHost host(){return hat;}
        @Override public boolean satisfied(){return !prop.deployed()&&!(c.motor().current() instanceof TakeProp);}
        @Override public long deadline(){return deadline;}
        @Override public List<Method> methods(){return stands;}
        private final class From implements Method {
            private final int index;
            private final HatGeometry.Stand stand;
            private Vec3 at;
            private boolean acted;
            private From(int index,HatGeometry.Stand stand){this.index=index;this.stand=stand;}
            @Override public String id(){return "stand_"+index;}
            @Override public Feasibility applicable(){
                if((at=SurfaceApproach.floor(c,stand.at()))==null)return Feasibility.of(Reason.NO_SUPPORT,0,"no_floor");
                // Only a stand the braid or the mouth reaches the hat from (TakeProp asks the same of the body there).
                return ContactGeometry.reachesFrom(c,at,hat)?Feasibility.pass():Feasibility.of(Reason.NO_CONTACT,0,"out_of_reach");
            }
            @Override public Set<Reason> reduces(){return Set.of(Reason.NO_PATH,Reason.NO_PROGRESS,Reason.NO_CONTACT,Reason.NO_SUPPORT);}
            @Override public Step next(){
                if(!SurfaceApproach.arrived(c,at)){acted=false;return new Step.Do(new Walk(at));}
                if(!GoTo.at(c,at,stand.yaw())){acted=false;return new Step.Do(new Face(stand.yaw()));}
                if(acted)return null;
                acted=true;return new Step.Do(new TakeProp(prop));
            }
        }
    }
    /** The attempt owns its bounded candidates; new Deliver instances never restart the search. */
    private List<Vec3> points(PlacedHatEntity hat){
        if(receiverPoints==null)receiverPoints=hat.hollowPlaces(FamilyData.BirthKind.LIVE,brood().children.get(pushing)).stream().map(hat.frame()::toLocal).toList();
        return receiverPoints.stream().map(hat.frame()::toWorld).toList();
    }
    private double score(PlacedHatEntity hat,Vec3 out,Vec3 aim,float yaw){
        var end=hat.catches()?hat.frame().toWorld(hat.spots().get("centre")):out.add(Vec3.directionFromRotation(0,yaw).scale(.2));
        return aim.subtract(end).horizontalDistanceSqr();
    }
    private void riskyExit(PlacedHatEntity hat,float yaw){
        if(choices==null){
            choices=new ArrayList<>();var m=o.entity();var out=LiveBirthGeometry.position(m,pushing,yaw);
            var aim=points(hat).stream().filter(p->BirthLaunch.ahead(out,p,yaw)>0)
                    .min(Comparator.comparingDouble(p->score(hat,out,p,yaw))).orElse(null);
            if(aim!=null)choices.add(new Exit(pushing,hat.getUUID(),hat.frame().toLocal(m.position()),yaw-hat.getYRot(),hat.frame().toLocal(aim),LiveBirthGeometry.CATCH_ARC_TICKS));
        }
        exit=choices.isEmpty()?null:choices.getFirst();
    }
    private void discardExit(String why){
        if(exit!=null&&choices!=null){var stand=exit.stand();choices.removeIf(e->e.stand().equals(stand));}
        exit=null;qualificationTick=-1;qualificationMisses=0;rejection=why;
        deliveryReadout("discarded",why);
    }
    private record Aim(Vec3 at,Vec3 out,float yaw,double score) {}
    private void search(PlacedHatEntity hat){
        choices=new ArrayList<>();var m=o.entity();var child=brood().children.get(pushing);var places=points(hat);
        var stands=new ArrayList<Vec3>();stands.add(m.position());
        if(receiver instanceof OwnHull){
            var grid=hat.walkGrid(m,true);var local=new ArrayList<Vec3>();
            for(int j=0;j<grid.nz();j++)for(int i=0;i<grid.nx();i++)if(grid.supported(i,j)){
                var p=new Vec3(grid.centreX(i),0,grid.centreZ(j));var support=hat.support(p,m);
                if(support.isPresent())local.add(hat.frame().toWorld(new Vec3(p.x,support.getAsDouble(),p.z)));
            }
            local.sort(Comparator.comparingDouble(m::distanceToSqr));stands.addAll(local.subList(0,Math.min(11,local.size())));
        }
        int clear=0,forward=0,room=0;double ahead=Double.NEGATIVE_INFINITY;
        for(var stand:stands){
            var aims=new ArrayList<Aim>();
            for(var aim:places){
                float yaw=SurfaceApproach.yaw(stand,aim);var out=LiveBirthGeometry.position(m,pushing,stand,yaw);
                double reach=BirthLaunch.ahead(out,aim,yaw);ahead=Math.max(ahead,reach);
                if(reach<=0)continue;
                yaw=SurfaceApproach.yaw(out,aim);out=LiveBirthGeometry.position(m,pushing,stand,yaw);
                if(BirthLaunch.ahead(out,aim,yaw)<=0)continue;
                forward++;aims.add(new Aim(aim,out,yaw,score(hat,out,aim,yaw)));
            }
            aims.sort(Comparator.comparingDouble(Aim::score));var facings=new HashMap<Float,Boolean>();
            for(var aim:aims){
                if(!facings.computeIfAbsent(aim.yaw(),yaw->LiveBirthGeometry.clear(m,pushing,stand,yaw,receiver instanceof OwnHull)))continue;
                clear++;if(!BirthLaunch.room(m,child,hat,stand,aim.yaw(),aim.at()))continue;room++;
                var plan=BirthLaunch.qualify(m,child,hat,stand,aim.out(),aim.yaw(),aim.at());
                if(plan==null)continue;
                choices.add(new Exit(pushing,hat.getUUID(),hat.frame().toLocal(stand),aim.yaw()-hat.getYRot(),hat.frame().toLocal(aim.at()),plan.ticks()));break;
            }
        }
        String why=!choices.isEmpty()?"":places.isEmpty()?"no_cavity":forward==0?"cavity_behind_outlet":clear==0?"outlet_blocked":room==0?"cavity_occupied":"no_nominal_contact";
        if(!why.equals(rejection)){
            rejection=why;GameplayLog.limited(m,"family","birth_exit_geometry",200,
                    "birth exit geometry stands={} places={} clear={} forward={} room={} furthestForward={} rejection={}",stands.size(),places.size(),clear,forward,room,ahead,rejection);
        }
    }
    private final class Deliver implements Intent, Method {
        private final boolean any;
        private final long deadline;
        private float yaw=Float.NaN;
        private long walkStarted=-1;
        private Deliver(boolean any){this.any=any;deadline=any?now()+1200:exitUntil;}
        private float choose(){
            var hat=receiverHat();var m=o.entity();
            if(any||hat==null){
                yaw=LiveBirthGeometry.exitYaw(m,pushing,receiver instanceof Nest?FamilyNest.largeCentre(m):null,NEST_REACH,any);
                if(hat!=null&&!Float.isNaN(yaw))riskyExit(hat,yaw);
                return yaw;
            }
            if(choices==null)search(hat);
            if(exit==null&&!choices.isEmpty()){
                exit=choices.getFirst();qualificationTick=-1;qualificationMisses=0;rejection="";
                deliveryReadout("qualified",atStand(hat)?"turn":"reposition");
            }
            return yaw=exit==null?Float.NaN:hat.getYRot()+exit.yaw();
        }
        @Override public String id(){return any?"turn_to_any_exit":"turn_to_exit";}
        @Override public boolean satisfied(){
            var m=o.entity();var hat=receiverHat();
            return !Float.isNaN(yaw)&&Math.abs(Mth.wrapDegrees((exit!=null&&hat!=null?hat.getYRot()+exit.yaw():yaw)-m.yBodyRot))<=Face.FACE_ARRIVED
                    &&LiveBirthGeometry.clear(m,pushing,m.yBodyRot,any||receiver instanceof OwnHull)
                    &&(any||hat==null||exit!=null&&atStand(hat)&&qualified(hat));
        }
        @Override public long deadline(){return deadline;}
        @Override public List<Method> methods(){return List.of(this);}
        @Override public Feasibility applicable(){return brood()==null?Feasibility.of(Reason.TARGET_GONE,0,"brood_gone"):Feasibility.pass();}
        @Override public Set<Reason> reduces(){return Set.of(Reason.NO_PROGRESS);}
        @Override public Step next(){
            int left=(int)Math.max(1,deadline-now());
            if(Float.isNaN(choose()))return new Step.Wait(()->false,left,"no_safe_exit");
            var hat=receiverHat();
            if(!any&&exit!=null&&receiver instanceof OwnHull&&!atStand(hat)){walkStarted=now();return new Step.Do(new Walk(hat,exit.stand()));}
            walkStarted=-1;
            return Math.abs(Mth.wrapDegrees(yaw-o.entity().yBodyRot))>Face.FACE_ARRIVED?new Step.Do(new Face(yaw)):new Step.Wait(this::satisfied,left,"outlet_clear");
        }
        @Override public void ended(IntentRunner.Result result){
            if(result==IntentRunner.Result.EXHAUSTED&&walkStarted>=0&&o.entity().motor().lastTick()>=walkStarted&&o.entity().motor().last() instanceof Outcome.Blocked failure)
                discardExit("walk_"+failure.reason().name().toLowerCase(Locale.ROOT));
        }
    }
    private boolean atStand(PlacedHatEntity hat){
        return !(receiver instanceof OwnHull)||hat.frame().toWorld(exit.stand()).subtract(o.entity().position()).horizontalDistance()<=Walk.STAND_ARRIVED;
    }
    private boolean qualified(PlacedHatEntity hat){
        if(qualificationTick!=now()){
            qualificationTick=now();var m=o.entity();var start=LiveBirthGeometry.position(m,pushing);
            var plan=BirthLaunch.forward(start,hat.frame().toWorld(exit.aim()),m.yBodyRot,exit.ticks());
            qualification=plan!=null&&exit.hat().equals(hat.getUUID())&&BirthLaunch.catches(m,brood().children.get(pushing),hat,m.position(),start,m.yBodyRot,plan);
            qualificationMisses=qualification?0:qualificationMisses+1;
        }
        return qualification;
    }
    /** The hat the outlet aims at now: the catch hat set down, or the boat she rides. */
    private PlacedHatEntity receiverHat(){
        if(!(receiver instanceof OwnHull h))return placedHat();
        var hull=ownHull(o.entity());return hull!=null&&hull.getUUID().equals(h.host())?hull:null;
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
    /** The catcher watches the young: the labour, the linger after it and the waits of the cleanup. */
    boolean watching(){return attending()||"linger".equals(beat())||"rest".equals(beat());}
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
