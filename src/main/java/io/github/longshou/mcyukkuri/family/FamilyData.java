package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/** Authoritative ancestry, population reservations and births shared by stem and nest-egg reproduction. */
public final class FamilyData extends SavedData {
    public enum Status { UNBORN, ALIVE, DEAD, REMOVED, LOST }
    public enum BirthKind { STEM, EGG, LIVE }
    public static final class Person {
        public final UUID id;
        public UUID mother, father, brood;
        /** Biological source of an aftermath birth; never a care parent or household member. */
        public UUID incidentSource;
        public GlobalPos position;
        public Status status = Status.ALIVE;
        public String variant;
        Person(UUID id, String variant, GlobalPos position) { this.id=id; this.variant=variant; this.position=position; }
    }
    public static final class Child {
        public UUID id;
        public YukkuriVariant variant;
        public int subspeciesFlags;
        public float adultScale;
        public long lifespan;
        public long maturity=GrowthTiming.DEFAULT_MATURITY;
        public int[] traits;
        public int[] dispositions = new int[0];
        public boolean born;
        public boolean landed;
        // House eggs keep individual custody and time when separated from their clutch.
        public boolean eggManaged, eggLost;
        public int eggElapsed;
        public GlobalPos eggNest;
        public UUID eggNestId, eggToken;
    }
    public static final class Brood {
        public UUID id, mother, father, detachedHolder;
        public UUID incidentSource;
        /** Loaded-time physiological burden, independent of gestation progress. */
        public int burdenTicks;
        public BirthKind kind;
        public GlobalPos anchor;
        public int elapsed, duration;
        public float variation;
        public boolean cancelled;
        public final List<Child> children = new ArrayList<>();
        public boolean complete() { return children.stream().allMatch(child -> child.born || child.eggLost); }
        public int unborn() { return (int)children.stream().filter(child -> !child.born && !child.eggLost).count(); }
        public float progress() { return Math.min(1, elapsed/(float)Math.max(1,duration)); }
    }
    private final Map<UUID, Person> people = new HashMap<>();
    /** Derived snapshots in the current people traversal order, including every life status. */
    private final Map<UUID, List<UUID>> childrenByParent = new HashMap<>();
    private boolean childrenIndexDirty = true;
    private void putPerson(Person person) {
        people.put(person.id, person);
        childrenIndexDirty = true;
    }
    private void rebuildChildrenIndex() {
        childrenByParent.clear();
        // An unrelated insertion can resize people and reorder another parent's children too.
        for (Person person : people.values()) {
            if (person.mother != null) childrenByParent.computeIfAbsent(person.mother, id -> new ArrayList<>()).add(person.id);
            if (person.father != null && !person.father.equals(person.mother))
                childrenByParent.computeIfAbsent(person.father, id -> new ArrayList<>()).add(person.id);
        }
        childrenByParent.replaceAll((parent, children) -> List.copyOf(children));
        childrenIndexDirty = false;
    }
    private static int[] inheritDispositions(YukkuriEntity a,YukkuriEntity b,Random random) {
        int[] result=new int[4];
        for(var axis:io.github.longshou.mcyukkuri.social.YukkuriPersonality.Disposition.values())
            result[axis.ordinal()]=Math.clamp((a.life().personality().innate(axis)+b.life().personality().innate(axis))/2+random.nextInt(17)-8,0,100);
        return result;
    }
    private final Map<UUID, Brood> broods = new HashMap<>();
    private final Set<UUID> spawning = new HashSet<>();
    public static FamilyData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new Factory<>(FamilyData::new, FamilyData::load), "mcyukkuri_families");
    }
    public Person person(UUID id) { return id == null ? null : people.get(id); }
    /** Used only to undo members allocated by a failed encounter transaction. */
    public void rollbackEncounter(java.util.Set<UUID> allocated) {
        people.keySet().removeAll(allocated);childrenIndexDirty=true;setDirty();
    }
    public Brood brood(UUID id) { return id == null ? null : broods.get(id); }
    public Person register(YukkuriEntity entity) {
        GlobalPos position = GlobalPos.of(entity.level().dimension(), entity.blockPosition());
        // computeIfAbsent can resize before finding even an existing record; parent backfill also invalidates the index.
        childrenIndexDirty = true;
        Person record = people.computeIfAbsent(entity.getUUID(), id -> new Person(id,entity.getVariant().assetId(),position));
        if (record.status == Status.REMOVED) record.status = Status.ALIVE;
        record.position=position;
        if (record.mother == null && entity.family().mother() != null) {
            record.mother=entity.family().mother(); record.father=entity.family().father(); record.brood=entity.family().birthId();
        }
        setDirty(); return record;
    }
    /** Biological close kin, including incident ancestry, for reproductive compatibility checks. */
    public boolean related(UUID first, UUID second) {
        if (first.equals(second)) return true;
        Person a=people.get(first), b=people.get(second);
        if (a==null || b==null) return false;
        UUID aFather=a.father!=null?a.father:a.incidentSource, bFather=b.father!=null?b.father:b.incidentSource;
        if (first.equals(b.mother) || first.equals(bFather) || second.equals(a.mother) || second.equals(aFather)) return true;
        return a.mother!=null && (a.mother.equals(b.mother)||a.mother.equals(bFather))
                || aFather!=null && (aFather.equals(b.mother)||aFather.equals(bFather));
    }
    /** Family treatment follows recorded care parents; incident ancestry alone grants no social relationship. */
    public boolean socialKin(UUID first, UUID second) {
        if (first.equals(second)) return true;
        Person a=people.get(first), b=people.get(second);
        if (a==null || b==null) return false;
        if (first.equals(b.mother) || first.equals(b.father) || second.equals(a.mother) || second.equals(a.father)) return true;
        return a.mother!=null && (a.mother.equals(b.mother)||a.mother.equals(b.father))
                || a.father!=null && (a.father.equals(b.mother)||a.father.equals(b.father));
    }
    public List<UUID> children(UUID parent) {
        if (childrenIndexDirty) rebuildChildrenIndex();
        if (people.isEmpty()) return List.of();
        Objects.requireNonNull(parent);
        return childrenByParent.getOrDefault(parent, List.of());
    }
    public boolean busyParent(UUID parent) {
        return broods.values().stream().anyMatch(b->!b.cancelled&&!b.complete()&&(parent.equals(b.mother)||parent.equals(b.father)));
    }
    public int population(ServerLevel level, BlockPos position) {
        for(var entity:level.getEntitiesOfClass(YukkuriEntity.class,new net.minecraft.world.phys.AABB(position).inflate(32))) register(entity);
        var custody=YukkuriWorldData.get(level);
        return (int)people.values().stream().filter(p->(p.status==Status.ALIVE||p.status==Status.UNBORN)
                && p.position!=null&&p.position.dimension().equals(level.dimension())&&p.position.pos().distSqr(position)<=32*32
                && !custody.isCaptured(p.id)).count();
    }
    public int reservedInside(ServerLevel level,java.util.function.Predicate<BlockPos> inside) {
        return (int)people.values().stream().filter(p->p.status==Status.UNBORN&&p.position!=null
                &&p.position.dimension().equals(level.dimension())&&inside.test(p.position.pos())).count();
    }
    /** A mother's outing must not release the sleeping places reserved for her unborn children. */
    public int reservedForResidents(java.util.function.Predicate<UUID> resident) {
        return (int)people.values().stream().filter(p->p.status==Status.UNBORN&&p.mother!=null&&resident.test(p.mother)).count();
    }
    /** A size-dependent distribution. The random seed is fixed until a conception actually commits. */
    public static int offspringCount(float maternalScale, long seed) {
        Random random=new Random(seed);
        double influence=FamilyConfig.OFFSPRING_SIZE_INFLUENCE.get();
        double size=Math.clamp((maternalScale-.8)/.4,0,1);
        // Keep the old default arithmetic exact; center other settings on p=.5 (mean three).
        double probability=Math.clamp(.15+influence*size+(FamilyConfig.DEFAULT_OFFSPRING_SIZE_INFLUENCE-influence)*.5,0,1);
        int count=1; for(int i=0;i<4;i++)if(random.nextDouble()<probability)count++;
        return count;
    }
    private record PopulationBudget(BlockPos center, int limit) {}
    private PopulationBudget birthBudget(ServerLevel level, BlockPos position, UUID parentId) {
        if (level.getEntity(parentId) instanceof YukkuriEntity parent) {
            var group = parent.settlement().group();
            if (group != null && group.kind == SettlementData.Kind.FOREST && position.distSqr(group.center) <= 32 * 32)
                return new PopulationBudget(group.center, YukkuriConfig.COMMUNITY_POPULATION_CAP.get());
        }
        return new PopulationBudget(position, FamilyConfig.POPULATION_CAP.get());
    }
    /** Wild land parents may consolidate only in the mother's ordinary natural burrow. */
    public static boolean ordinaryWildBurrow(YukkuriEntity mother, YukkuriEntity father, ShelterBlockEntity shelter) {
        return shelter != null && shelter.kind() == ShelterBlock.Kind.BURROW
                && !mother.profile().houseBody() && !father.profile().houseBody()
                && !mother.profile().dangerToYukkuri() && !father.profile().dangerToYukkuri()
                && !mother.profile().aquatic()
                && !father.profile().aquatic()
                && !HumanCommerce.data(mother).hasUUID("Owner") && !HumanCommerce.data(father).hasUUID("Owner")
                && !HumanCommerce.data(mother).contains("PendingRole") && !HumanCommerce.data(father).contains("PendingRole");
    }
    /** The checked facts of a prospective conception; refusal is null when every gate passes. */
    private record Plan(String refusal,long seed,int count,ShelterBlockEntity shelter,boolean joinMother,float cost){}
    private static Plan refused(String reason){return new Plan(reason,0,0,null,false,0);}
    private Plan plan(ServerLevel level,YukkuriEntity mother,YukkuriEntity father,BirthKind kind,GlobalPos anchor){
        if(!mother.family().canReproduceWith(father))return refused("pair");
        if(busyParent(mother.getUUID())||busyParent(father.getUUID()))return refused("busy");
        if(kind==BirthKind.EGG != mother.profile().houseBody())return refused("kind");
        if(kind==BirthKind.LIVE&&mother.profile().dangerToYukkuri())return refused("kind");
        long seed=mother.getUUID().getMostSignificantBits()^father.getUUID().getLeastSignificantBits()^mother.family().conceptions();
        int count=kind!=BirthKind.EGG?offspringCount(mother.family().adultScale(),seed):2+new Random(seed).nextInt(3);
        if(!io.github.longshou.mcyukkuri.human.FactoryProduction.reproductionAllowed(mother,father,count))return refused("factory");
        var shelter=mother.habitat().shelter();
        boolean joinMother=ordinaryWildBurrow(mother,father,shelter);
        if(joinMother) {
            int incoming=count+(shelter.owns(father.getUUID())?0:1);
            if(!shelter.suitableForYoung(incoming))return refused("shelter");
        } else if(shelter!=null&&!shelter.suitableForYoung(count))return refused("shelter");
        else if(shelter==null&&kind!=BirthKind.EGG&&!io.github.longshou.mcyukkuri.settlement.Homes.petBedFits(mother,count))return refused("bed");
        var budget = birthBudget(level, anchor.pos(), mother.getUUID());
        if(population(level,budget.center())+count>budget.limit())return refused("crowded");
        float cost=10+count*2;
        if(mother.life().nutrition()<cost+45)return refused("hungry_mother");
        if(father.life().nutrition()<50)return refused("hungry_father");
        return new Plan(null,seed,count,shelter,joinMother,cost);
    }
    /** Why conceive() would return null right now (pair, busy, kind, factory, shelter, crowded, hungry_mother, hungry_father), or null when it would succeed. Changes nothing. */
    public String conceiveRefusal(YukkuriEntity mother, YukkuriEntity father, BirthKind kind, GlobalPos anchor) {
        return plan((ServerLevel)mother.level(),mother,father,kind,anchor).refusal();
    }
    public Brood conceive(YukkuriEntity mother, YukkuriEntity father, BirthKind kind, GlobalPos anchor) {
        ServerLevel level=(ServerLevel)mother.level();register(mother);register(father);
        Plan plan=plan(level,mother,father,kind,anchor);
        if(plan.refusal()!=null)return null;
        long seed=plan.seed();int count=plan.count();var shelter=plan.shelter();boolean joinMother=plan.joinMother();float cost=plan.cost();
        Brood brood=new Brood();brood.id=UUID.randomUUID();brood.mother=mother.getUUID();brood.father=father.getUUID();brood.kind=kind;brood.anchor=anchor;
        brood.duration=kind!=BirthKind.EGG?FamilyConfig.GESTATION.get():FamilyConfig.EGG_INCUBATION.get();
        Random random=new Random(seed);brood.variation=.93f+random.nextFloat()*.14f;
        for(int i=0;i<count;i++) {
            Child child=new Child();child.id=UUID.randomUUID();child.variant=random.nextBoolean()?mother.getVariant():father.getVariant();
            child.subspeciesFlags=io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.birthFlags(child.id,child.variant,mother.subspecies().kind(),father.subspecies().kind());
            child.adultScale=Math.clamp((mother.family().adultScale()+father.family().adultScale())*.5f+(random.nextFloat()-.5f)*.12f,.8f,1.2f);
            double growthVariation=.95+random.nextDouble()*.10;
            child.maturity=Math.max(200,Math.round((mother.family().maturityAge()+father.family().maturityAge())*.5*growthVariation));
            child.lifespan=child.maturity+Math.max(400,Math.round(((mother.family().lifespan()-mother.family().maturityAge())
                    +(father.family().lifespan()-father.family().maturityAge()))*.5*growthVariation));
            child.traits=new int[6];for(Trait trait:Trait.values())child.traits[trait.ordinal()]=Math.clamp(
                    (mother.life().personality().innate(trait)+father.life().personality().innate(trait))/2+random.nextInt(17)-8,0,100);
            child.dispositions=inheritDispositions(mother,father,random);
            brood.children.add(child);
            Person person=new Person(child.id,child.variant.assetId(),anchor);person.mother=brood.mother;person.father=brood.father;person.brood=brood.id;person.status=Status.UNBORN;
            putPerson(person);
        }
        broods.put(brood.id,brood);mother.life().spendNutrition(cost);father.life().spendNutrition(5);
        mother.family().conceived(brood.id,father.getUUID());father.family().partnerConceived(mother.getUUID());
        var custody=io.github.longshou.mcyukkuri.human.HumanCommerce.data(mother);
        if(custody.getString("Use").equals("breeder") && custody.hasUUID("Owner") && level.getEntity(custody.getUUID("Owner")) instanceof io.github.longshou.mcyukkuri.human.HumanNpc worker)
            worker.production().breeding().conceived(mother,father);
        // This is a normal reachability-checked claim.  A failed path leaves both homes intact;
        // the father retries while the brood is pending from FamilyLife.tick().
        if(joinMother&&!shelter.owns(father.getUUID()))father.habitat().choose(shelter);
        setDirty();return brood;
    }
    /**
     * Checked aftermath reservation for explicit scenarios or AgitationLife's single-use offscreen event.
     * Proximity or a presentation phase alone is never evidence that such an event occurred.
     * An adult victim may already be hungry: low nutrition is a consequence to care for, not an immunity.
     */
    public String aftermathRefusal(YukkuriEntity victim,YukkuriEntity source) {
        if(!(victim.level() instanceof ServerLevel level)||victim.level()!=source.level())return "双方必须在同一服务端维度";
        if(source.getVariant()!=YukkuriVariant.ALICE||!source.isAlive()||!source.family().adult()
                ||source.body().dying()||!source.body().coreIntact())return "来源必须是未濒死、核心完好的存活成年爱丽丝";
        register(victim);register(source);
        if(!victim.family().canReceiveAftermath())return "受影响者必须是存活成体，不能处于濒死、核心损伤、已有胎次、恢复期、未落定或不育状态";
        if(!victim.family().compatibleWith(source))return "双方种类或生理状态不兼容，或存在亲缘关系";
        if(busyParent(victim.getUUID())||busyParent(source.getUUID()))return "已有未完成胎次，不能叠加或覆盖";
        int count=offspringCount(victim.family().adultScale(),aftermathSeed(victim,source));
        if(!io.github.longshou.mcyukkuri.human.FactoryProduction.reproductionAllowed(victim,source,count))return "当前收容或工厂身份不允许建立这个事件";
        var shelter=victim.habitat().shelter();
        if(shelter!=null&&!shelter.suitableForYoung(count))return "当前住所的子代容量不足";
        if(shelter==null&&!io.github.longshou.mcyukkuri.settlement.Homes.petBedFits(victim,count))return "当前宠物床的子代空间不足";
        var budget=birthBudget(level,victim.blockPosition(),victim.getUUID());
        if(population(level,budget.center())+count>budget.limit())return "附近实际人口与未出生预留已达上限";
        return null;
    }
    private long aftermathSeed(YukkuriEntity victim,YukkuriEntity source) {
        return victim.getUUID().getMostSignificantBits()^source.getUUID().getLeastSignificantBits()^victim.family().conceptions()^0x41465445524cL;
    }
    /** A real STEM reservation with biological ancestry but no invented partnership, care father or source reward. */
    public Brood createAftermath(YukkuriEntity victim,YukkuriEntity source) {
        if(aftermathRefusal(victim,source)!=null)return null;
        var level=(ServerLevel)victim.level();
        long seed=aftermathSeed(victim,source);int count=offspringCount(victim.family().adultScale(),seed);
        var brood=new Brood();brood.id=UUID.randomUUID();brood.mother=victim.getUUID();brood.incidentSource=source.getUUID();
        brood.kind=BirthKind.STEM;brood.anchor=GlobalPos.of(level.dimension(),victim.blockPosition());
        brood.duration=FamilyConfig.GESTATION.get();
        var random=new Random(seed);brood.variation=.93f+random.nextFloat()*.14f;
        for(int i=0;i<count;i++) {
            var child=new Child();child.id=UUID.randomUUID();child.variant=random.nextBoolean()?victim.getVariant():source.getVariant();
            child.subspeciesFlags=io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.birthFlags(child.id,child.variant,victim.subspecies().kind(),source.subspecies().kind());
            child.adultScale=Math.clamp((victim.family().adultScale()+source.family().adultScale())*.5f+(random.nextFloat()-.5f)*.12f,.8f,1.2f);
            double growthVariation=.95+random.nextDouble()*.10;
            child.maturity=Math.max(200,Math.round((victim.family().maturityAge()+source.family().maturityAge())*.5*growthVariation));
            child.lifespan=child.maturity+Math.max(400,Math.round(((victim.family().lifespan()-victim.family().maturityAge())
                    +(source.family().lifespan()-source.family().maturityAge()))*.5*growthVariation));
            child.traits=new int[6];
            for(Trait trait:Trait.values())child.traits[trait.ordinal()]=Math.clamp(
                    (victim.life().personality().innate(trait)+source.life().personality().innate(trait))/2+random.nextInt(17)-8,0,100);
            child.dispositions=inheritDispositions(victim,source,random);brood.children.add(child);
            var person=new Person(child.id,child.variant.assetId(),brood.anchor);
            person.mother=brood.mother;person.incidentSource=brood.incidentSource;person.brood=brood.id;person.status=Status.UNBORN;
            putPerson(person);
        }
        broods.put(brood.id,brood);
        victim.family().receiveAftermath(brood.id);
        victim.life().spendNutrition(Math.min(2,victim.life().nutrition()));
        victim.life().applyEnvironment(0,0,8);victim.pet().addStress(25);
        victim.frightenFrom(source);victim.showReaction("teary","afraid",200);
        victim.syncFamily();
        setDirty();return brood;
    }
    /** A manufactured dose has no known individual father. Never substitute a nearby animal. */
    public boolean canAssist(YukkuriEntity mother){
        if(!(mother.level() instanceof ServerLevel level)||!mother.family().readyForAssistance()||busyParent(mother.getUUID()))return false;
        var custody=io.github.longshou.mcyukkuri.human.HumanCommerce.data(mother);
        if(custody.contains("PendingRole")||custody.getBoolean("RetiredParent")||custody.contains("FactoryStage")
                ||custody.hasUUID("Owner")&&(java.util.Set.of("breeder","reserve_parent","nursery","input","labor","reserve","stock","lost").contains(custody.getString("Use"))
                ||level.getEntity(custody.getUUID("Owner")) instanceof io.github.longshou.mcyukkuri.human.HumanNpc))return false;
        int count=offspringCount(mother.family().adultScale(),mother.getUUID().getMostSignificantBits()^mother.family().conceptions());
        var shelter=mother.habitat().shelter();if(shelter!=null&&!shelter.suitableForYoung(count))return false;
        var budget=birthBudget(level,mother.life().homeStand(),mother.getUUID());
        return mother.life().nutrition()>=55+count*2&&population(level,budget.center())+count<=budget.limit();
    }
    public Brood assist(YukkuriEntity mother){
        if(!canAssist(mother))return null;
        var level=(ServerLevel)mother.level();register(mother);
        long seed=mother.getUUID().getMostSignificantBits()^mother.family().conceptions();var random=new Random(seed);
        int count=offspringCount(mother.family().adultScale(),seed);
        var brood=new Brood();brood.id=UUID.randomUUID();brood.mother=mother.getUUID();brood.father=null;brood.kind=BirthKind.LIVE;
        brood.anchor=GlobalPos.of(level.dimension(),mother.life().homeStand());brood.duration=FamilyConfig.GESTATION.get();brood.variation=1;
        for(int i=0;i<count;i++){
            var child=new Child();child.id=UUID.randomUUID();child.variant=mother.getVariant();child.adultScale=Math.clamp(mother.family().adultScale()+(random.nextFloat()-.5f)*.12f,.8f,1.2f);
            child.subspeciesFlags=io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.birthFlags(child.id,child.variant,mother.subspecies().kind(),null);
            double growthVariation=.95+random.nextDouble()*.1;
            child.maturity=Math.max(200,Math.round(mother.family().maturityAge()*growthVariation));
            child.lifespan=child.maturity+Math.max(400,Math.round((mother.family().lifespan()-mother.family().maturityAge())*growthVariation));child.traits=new int[6];
            for(var trait:Trait.values())child.traits[trait.ordinal()]=Math.clamp(mother.life().personality().innate(trait)+random.nextInt(17)-8,0,100);
            child.dispositions=inheritDispositions(mother,mother,random);
            brood.children.add(child);var person=new Person(child.id,child.variant.assetId(),brood.anchor);person.mother=brood.mother;person.brood=brood.id;person.status=Status.UNBORN;putPerson(person);
        }
        broods.put(brood.id,brood);mother.life().spendNutrition(10+count*2);mother.family().assisted(brood.id);setDirty();return brood;
    }
    public boolean relocate(Brood brood, ServerLevel level, BlockPos destination) {
        if(brood.anchor.dimension().equals(level.dimension())&&brood.anchor.pos().distSqr(destination)<16)return true;
        var budget = birthBudget(level, destination, brood.mother);
        int own=(int)brood.children.stream().filter(child->!child.born).map(child->people.get(child.id))
                .filter(p->p.position.dimension().equals(level.dimension())&&p.position.pos().distSqr(budget.center())<=32*32).count();
        if(population(level,budget.center())-own+brood.unborn()>budget.limit())return false;
        brood.anchor=GlobalPos.of(level.dimension(),destination);
        for(Child child:brood.children)if(!child.born)people.get(child.id).position=brood.anchor;
        setDirty();return true;
    }
    private final Map<UUID,String> refusals=new HashMap<>();
    private YukkuriEntity refuse(Brood brood,String why){refusals.put(brood.id,why);return null;}
    /** Why the last birth attempt of this brood was refused (diagnostic; empty when none or since succeeded). */
    public String birthBlock(Brood brood){return brood==null?"":refusals.getOrDefault(brood.id,"");}
    /** Prepare real provisions before constructing a newborn. A blocked mother may still use a nearby father's hat. */
    public boolean prepareWaterBirthCargoSpace(Brood brood,io.github.longshou.mcyukkuri.subspecies.HatBoatEntity mine,ServerLevel level,Vec3 position){
        if(mine==null||mine.cargo().count()==0||mine.prepareBirthCargoSpace())return true;
        if(brood.father==null||!(level.getEntity(brood.father) instanceof YukkuriEntity father)||!father.isAlive())return false;
        var other=father.family().boatHome();
        if(other==null||other==mine||other.position().subtract(position).horizontalDistance()>FamilyLife.SHARE_REACH+other.getBbWidth()*.5)return false;
        if(other.hasRoomForYoung())return true;
        if(father.isNoAi()||father.isCommandPlaying()||father.operations().active()||father.life().isSleeping()
                ||father.life().mealTicks()>0||father.family().tiedToLabor())return false;
        return other.prepareBirthCargoSpace();
    }
    /**
     * The hat boat that takes this newborn of a water birth. No partner and no shore are needed: it goes into its mother's own hat (a free
     * seat, else the compressed refuge). A partner afloat alongside in his own boat takes a share: every other newborn, and all that follow
     * once her hat is full, as long as his seat can be reached by the same flight that fills hers.
     */
    private io.github.longshou.mcyukkuri.subspecies.HatBoatEntity waterCradle(Brood brood,io.github.longshou.mcyukkuri.subspecies.HatBoatEntity mine,YukkuriEntity child,ServerLevel level){
        io.github.longshou.mcyukkuri.subspecies.HatBoatEntity partner=null;
        if(brood.father!=null&&level.getEntity(brood.father) instanceof YukkuriEntity father&&father.isAlive()){
            var other=father.family().boatHome();
            if(other!=null&&other!=mine&&other.young(child)&&other.hasRoomFor(child)){
                var seat=other.peekSeat(child);
                if(seat!=null&&other.toWorld(seat).subtract(child.position()).horizontalDistance()<=FamilyLife.SHARE_REACH)partner=other;
            }
        }
        int born=(int)brood.children.stream().filter(c->c.born).count();
        boolean room=mine.hasRoomFor(child);
        if(partner!=null&&(!room||born%2==1))return partner;
        // Compression remains a real close-contact fallback, but normal delivery
        // never invokes the rescue policy that discards food.
        if(room||(mine.cargo().count()==0||mine.prepareBirthCargoSpace())&&mine.requestEmergencyBerth(child))return mine;
        return partner;
    }
    public YukkuriEntity birth(Brood brood, int index, ServerLevel level, Vec3 position) {
        if(brood.cancelled||index<0||index>=brood.children.size())return null;
        if(brood.detachedHolder==null&&brood.kind!=BirthKind.EGG
                &&level.getEntity(brood.mother) instanceof YukkuriEntity mother&&!mother.subspecies().canReproduce()) {
            cancel(brood);return null;
        }
        Child child=brood.children.get(index);if(child.born||child.eggLost)return null;
        int elapsed=brood.kind==BirthKind.EGG&&child.eggManaged?child.eggElapsed:brood.elapsed;
        if(elapsed<brood.duration+(brood.kind==BirthKind.STEM?index*8:brood.kind==BirthKind.LIVE?LiveBirthGeometry.birthTick(index):0))return null;
        if(brood.kind==BirthKind.EGG&&child.eggManaged
                &&(child.eggToken!=null||child.eggNest==null||!child.eggNest.dimension().equals(level.dimension())
                ||child.eggNest.pos().distToCenterSqr(position)>4
                ||!(level.getBlockEntity(child.eggNest.pos()) instanceof io.github.longshou.mcyukkuri.house.HouseNestBlockEntity nest)
                ||!nest.identity().equals(child.eggNestId)))return null;
        if(brood.kind==BirthKind.LIVE){
            if(!(level.getEntity(brood.mother) instanceof YukkuriEntity mother)||mother.family().brood()!=brood)return refuse(brood,"mother_missing");
            if(!mother.family().readyForDelivery())return refuse(brood,"mother_not_ready");
            if(position.distanceToSqr(LiveBirthGeometry.position(mother,index))>.0001)return refuse(brood,"outlet_moved");
            if(!LiveBirthGeometry.clear(mother,child,index))return refuse(brood,"outlet_blocked");
            for(int earlier=0;earlier<index;earlier++)if(!brood.children.get(earlier).born)return refuse(brood,"earlier_sibling_unborn");
        }
        for(ServerLevel dimension:level.getServer().getAllLevels())if(dimension.getEntity(child.id) instanceof YukkuriEntity existing) {
            if(brood.id.equals(existing.family().birthId())) { child.born=true;people.get(child.id).status=Status.ALIVE;setDirty();trackStemCompletion(brood,level); }
            return null;
        }
        if(YukkuriWorldData.get(level).isCaptured(child.id)) { child.born=true;people.get(child.id).status=Status.ALIVE;setDirty();trackStemCompletion(brood,level);return null; }
        if(!level.hasChunkAt(BlockPos.containing(position)))return null;
        // Moving one egg must never move its siblings' population reservations to the new nest.
        if(brood.kind!=BirthKind.EGG||!child.eggManaged) {
            if(!relocate(brood,level,BlockPos.containing(position)))return refuse(brood,"population_limit");
        }
        var boat=brood.detachedHolder==null&&brood.kind!=BirthKind.EGG&&level.getEntity(brood.mother) instanceof YukkuriEntity afloat?afloat.family().boatHome():null;
        if(!prepareWaterBirthCargoSpace(brood,boat,level,position))return refuse(brood,"boat_cargo_space_pending");
        YukkuriEntity entity=ModEntities.forVariant(child.variant).create(level);if(entity==null)return null;
        entity.setUUID(child.id);entity.family().newborn(child,brood);entity.moveTo(position.x,position.y,position.z,0,0);
        entity.subspecies().initializeBirth(child.subspeciesFlags);
        if(brood.kind==BirthKind.LIVE&&level.getEntity(brood.mother) instanceof YukkuriEntity mother){
            float facing=mother.yBodyRot;
            entity.setYRot(facing);entity.yRotO=facing;entity.yBodyRot=entity.yBodyRotO=facing;entity.setYHeadRot(facing);entity.yHeadRotO=facing;
        }
        if(brood.detachedHolder!=null){entity.setYRot(180);entity.yRotO=180;entity.yBodyRot=entity.yBodyRotO=180;entity.setYHeadRot(180);entity.yHeadRotO=180;}
        entity.setPersistenceRequired();
        // An aquatic mother afloat gives birth into her hat boat: the newborn boards it instead of landing.
        io.github.longshou.mcyukkuri.subspecies.HatBoatEntity waterHat=null;
        if(boat!=null){
            waterHat=waterCradle(brood,boat,entity,level);
            if(waterHat==null)return refuse(brood,"boat_has_no_room");
        }else if(!level.noCollision(entity)||level.containsAnyLiquid(entity.getBoundingBox()))return refuse(brood,"newborn_space_blocked");
        spawning.add(child.id);boolean added;
        try { added=level.addFreshEntity(entity); } finally { spawning.remove(child.id); }
        if(!added)return refuse(brood,"entity_rejected");
        // Born into a hat: the mother's boat, or a Marisa's hat set down at the outlet (lying, or floated at a bank).
        // An overstrained push can send the newborn past it [original 12%]: into the river, or hard onto the ground.
        // Pushed out, it flies on a parabola over the near brim into its own place in the hat (HatVessel, delivery.py).
        // A demonstrated miss withholds the cushion; a requested catch with no hat down at the moment of birth also fails visibly.
        YukkuriEntity scene=brood.kind==BirthKind.LIVE&&level.getEntity(brood.mother) instanceof YukkuriEntity m?m:null;
        boolean noCushion=scene!=null&&scene.family().withholdsCushion();
        var hat=waterHat!=null?waterHat:brood.kind==BirthKind.LIVE&&!noCushion?level.getEntitiesOfClass(io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.class,entity.getBoundingBox().inflate(2,1,2),
                cradle->cradle.catches()&&cradle.young(entity)&&cradle.hasRoomForYoung()).stream().min(java.util.Comparator.comparingDouble(cradle->cradle.distanceToSqr(entity))).orElse(null):null;
        if(hat!=null) {
            boolean miss=level.random.nextFloat()<.12f;
            if(brood.kind==BirthKind.LIVE&&level.getEntity(brood.mother) instanceof YukkuriEntity parent)miss=parent.family().missesDelivery(miss);
            if(miss) { entity.family().doom();entity.family().launchPast(hat); }
            else entity.family().launchInto(hat,Math.max(io.github.longshou.mcyukkuri.subspecies.HatVessel.TMIN,LiveBirthGeometry.CATCH_ARC_TICKS),null,
                    waterHat!=null&&waterHat!=boat?FamilyLife.SHARE_REACH:1.25);
            // The partner's hat was out of that flight's reach after all: the mother's own hat takes it.
            if(waterHat!=null&&waterHat!=boat&&!entity.family().flying()&&boat!=null)
                entity.family().launchInto(boat,Math.max(io.github.longshou.mcyukkuri.subspecies.HatVessel.TMIN,LiveBirthGeometry.CATCH_ARC_TICKS),null,1.25);
        } else if(brood.kind==BirthKind.STEM&&level.random.nextFloat()<.12f&&!level.getEntitiesOfClass(io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.class,
                entity.getBoundingBox().inflate(1.2,3,1.2),cradle->cradle.catches()&&cradle.young(entity)).isEmpty()) {
            // A fruit that comes away awkwardly misses the hat held under the stem.
            entity.family().doom();var side=Vec3.directionFromRotation(0,entity.yBodyRot+90);entity.setDeltaMovement(side.x*.22,.05,side.z*.22);
        } else if(brood.kind==BirthKind.LIVE&&scene!=null&&(scene.family().sceneRequested()||scene.family().catchExpected(brood)))entity.family().bumpOnLanding();
        YukkuriEntity actualMother=level.getEntity(brood.mother) instanceof YukkuriEntity parent?parent:null;
        YukkuriEntity actualFather=brood.father!=null&&level.getEntity(brood.father) instanceof YukkuriEntity parent?parent:null;
        io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.onBirth(entity,actualMother,actualFather);
        if(actualMother!=null)io.github.longshou.mcyukkuri.human.HumanCommerce.inheritFactoryChild(entity,actualMother);
        io.github.longshou.mcyukkuri.human.HumanCommerce.inheritPet(entity,actualMother,actualFather);
        for(var parent:new YukkuriEntity[]{actualMother,actualFather})if(parent!=null){
            var custody=io.github.longshou.mcyukkuri.human.HumanCommerce.data(parent);
            if(custody.hasUUID("Owner")&&level.getServer().getPlayerList().getPlayer(custody.getUUID("Owner")) instanceof net.minecraft.server.level.ServerPlayer owner)
                io.github.longshou.mcyukkuri.notebook.SurvivalProgress.award(owner,"family_birth");
        }
        boolean joined = level.getEntity(brood.mother) instanceof YukkuriEntity parent && entity.settlement().inherit(parent);
        if (!joined && brood.father!=null && level.getEntity(brood.father) instanceof YukkuriEntity parent) entity.settlement().inherit(parent);
        boolean inheritedHome=actualMother!=null&&entity.habitat().inherit(actualMother);
        if(!inheritedHome&&actualFather!=null)entity.habitat().inherit(actualFather);
        refusals.remove(brood.id);
        child.born=true;Person person=people.get(child.id);person.status=Status.ALIVE;person.position=GlobalPos.of(level.dimension(),entity.blockPosition());
        trackStemCompletion(brood,level);
        io.github.longshou.mcyukkuri.cognition.Cognition.born(entity,brood.mother,brood.father);
        YukkuriWorldData.get(level).relate(child.id,brood.mother,30);YukkuriWorldData.get(level).relate(brood.mother,child.id,35);
        if(brood.father!=null){YukkuriWorldData.get(level).relate(child.id,brood.father,25);YukkuriWorldData.get(level).relate(brood.father,child.id,30);}
        setDirty();return entity;
    }
    private void trackStemCompletion(Brood brood,ServerLevel level) {
        if(brood.complete()&&level.getEntity(brood.mother) instanceof YukkuriEntity mother)
            ShedStemDrops.get(level).queue(mother,brood);
    }
    /** Landing is distinct from successful entity insertion, and survives mother or child unloading. */
    public void landed(YukkuriEntity entity) {
        var brood=brood(entity.family().birthId());
        if(brood==null||!entity.isAlive()||entity.family().awaitingLanding()||entity.family().flying()
                ||!(entity.onGround()||entity.isInWaterOrBubble()||entity.getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity))return;
        for(var child:brood.children)if(child.id.equals(entity.getUUID())&&child.born&&!child.landed){child.landed=true;setDirty();return;}
    }
    /** Unloaded ALIVE children remain pending. Only recorded landing or a known terminal outcome resolves a birth. */
    public boolean birthsResolved(Brood brood,ServerLevel level) {
        if(!brood.complete())return false;
        var custody=YukkuriWorldData.get(level);
        for(var child:brood.children) {
            if(child.landed)continue;
            // Reconcile only against a real loaded child's completed landing state.
            for(var dimension:level.getServer().getAllLevels())
                if(dimension.getEntity(child.id) instanceof YukkuriEntity loaded)landed(loaded);
            if(child.landed)continue;
            var person=people.get(child.id);
            if(person!=null&&(person.status==Status.DEAD||person.status==Status.REMOVED||person.status==Status.LOST))continue;
            if(custody.isCaptured(child.id))continue;
            return false;
        }
        return true;
    }
    public void cancel(Brood brood) {
        if(brood==null||brood.cancelled)return;
        brood.cancelled=true;for(Child child:brood.children)if(!child.born)people.get(child.id).status=Status.LOST;
        setDirty();
    }
    public void died(YukkuriEntity entity) {
        Person p=register(entity);p.status=Status.DEAD;
        for(Brood brood:broods.values())if(brood.kind!=BirthKind.EGG&&brood.detachedHolder==null&&brood.mother.equals(entity.getUUID())&&!brood.complete())cancel(brood);
        setDirty();
    }
    public static void joining(EntityJoinLevelEvent event) {
        if(!(event.getEntity() instanceof YukkuriEntity entity)||!(event.getLevel() instanceof ServerLevel level))return;
        var data=get(level);Person person=data.person(entity.getUUID());
        if(person!=null&&(person.status==Status.DEAD||person.status==Status.LOST||person.status==Status.UNBORN&&!data.spawning.contains(person.id))) {
            event.setCanceled(true);return;
        }
        for(ServerLevel dimension:level.getServer().getAllLevels()) {
            var existing=dimension.getEntity(entity.getUUID());
            if(existing!=null&&existing!=entity) { event.setCanceled(true);return; }
        }
    }
    public static void leaving(EntityLeaveLevelEvent event) {
        if(event.getEntity() instanceof YukkuriEntity entity&&event.getLevel() instanceof ServerLevel level
                && entity.getRemovalReason()==net.minecraft.world.entity.Entity.RemovalReason.DISCARDED&&!YukkuriWorldData.get(level).isCaptured(entity.getUUID())) {
            Person person=get(level).person(entity.getUUID());if(person!=null&&person.status==Status.ALIVE){person.status=Status.REMOVED;get(level).setDirty();}
        }
    }
    public static void putPosition(CompoundTag tag, String name, GlobalPos position) {
        if(position==null)return;CompoundTag value=new CompoundTag();value.putString("Dimension",position.dimension().location().toString());value.putLong("Pos",position.pos().asLong());tag.put(name,value);
    }
    public static GlobalPos position(CompoundTag tag,String name) {
        if(!tag.contains(name,Tag.TAG_COMPOUND))return null;CompoundTag value=tag.getCompound(name);ResourceLocation id=ResourceLocation.tryParse(value.getString("Dimension"));
        return id==null?null:GlobalPos.of(ResourceKey.create(Registries.DIMENSION,id),BlockPos.of(value.getLong("Pos")));
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Version",4);ListTag persons=new ListTag(),pregnancies=new ListTag();
        for(Person person:people.values()) {
            CompoundTag value=new CompoundTag();value.putUUID("Id",person.id);value.putString("Variant",person.variant);value.putString("Status",person.status.name());
            if(person.mother!=null)value.putUUID("Mother",person.mother);if(person.father!=null)value.putUUID("Father",person.father);if(person.brood!=null)value.putUUID("Brood",person.brood);
            if(person.incidentSource!=null)value.putUUID("IncidentSource",person.incidentSource);
            putPosition(value,"Position",person.position);persons.add(value);
        }
        for(Brood brood:broods.values()) {
            CompoundTag value=new CompoundTag();value.putUUID("Id",brood.id);value.putUUID("Mother",brood.mother);if(brood.father!=null)value.putUUID("Father",brood.father);
            if(brood.incidentSource!=null){value.putUUID("IncidentSource",brood.incidentSource);value.putInt("BurdenTicks",brood.burdenTicks);}
            value.putString("Kind",brood.kind.name());putPosition(value,"Anchor",brood.anchor);value.putInt("Elapsed",brood.elapsed);value.putInt("Duration",brood.duration);
            if(brood.detachedHolder!=null)value.putUUID("DetachedHolder",brood.detachedHolder);
            value.putFloat("Variation",brood.variation);value.putBoolean("Cancelled",brood.cancelled);ListTag children=new ListTag();
            for(Child child:brood.children) {
                CompoundTag c=new CompoundTag();c.putUUID("Id",child.id);c.putString("Variant",child.variant.name());c.putFloat("AdultScale",child.adultScale);
                c.putInt("SubspeciesFlags",child.subspeciesFlags);
                if(child.eggManaged){
                    c.putBoolean("EggManaged",true);c.putBoolean("EggLost",child.eggLost);c.putInt("EggElapsed",child.eggElapsed);
                    putPosition(c,"EggNest",child.eggNest);
                    if(child.eggNestId!=null)c.putUUID("EggNestId",child.eggNestId);
                    if(child.eggToken!=null)c.putUUID("EggToken",child.eggToken);
                }
                c.putLong("Lifespan",child.lifespan);c.putLong("Maturity",child.maturity);c.putIntArray("Traits",child.traits);c.putIntArray("Dispositions",child.dispositions);c.putBoolean("Born",child.born);c.putBoolean("Landed",child.landed);children.add(c);
            }
            value.put("Children",children);pregnancies.add(value);
        }
        tag.put("People",persons);tag.put("Broods",pregnancies);return tag;
    }
    public static FamilyData load(CompoundTag tag, HolderLookup.Provider registries) {
        FamilyData data=new FamilyData();
        for(Tag entry:tag.getList("People",Tag.TAG_COMPOUND)) {
            CompoundTag value=(CompoundTag)entry;if(!value.hasUUID("Id"))continue;
            Person person=new Person(value.getUUID("Id"),value.getString("Variant"),position(value,"Position"));
            try { person.status=Status.valueOf(value.getString("Status")); }catch(IllegalArgumentException ignored){person.status=Status.REMOVED;}
            if(value.hasUUID("Mother"))person.mother=value.getUUID("Mother");if(value.hasUUID("Father"))person.father=value.getUUID("Father");if(value.hasUUID("Brood"))person.brood=value.getUUID("Brood");
            if(value.hasUUID("IncidentSource"))person.incidentSource=value.getUUID("IncidentSource");
            data.putPerson(person);
        }
        for(Tag entry:tag.getList("Broods",Tag.TAG_COMPOUND)) {
            CompoundTag value=(CompoundTag)entry;
            if(!value.hasUUID("Id")||!value.hasUUID("Mother"))continue;
            try {
                Brood brood=new Brood();brood.id=value.getUUID("Id");brood.mother=value.getUUID("Mother");brood.father=value.hasUUID("Father")?value.getUUID("Father"):null;brood.kind=BirthKind.valueOf(value.getString("Kind"));
                brood.incidentSource=value.hasUUID("IncidentSource")?value.getUUID("IncidentSource"):null;
                if(brood.incidentSource!=null&&(brood.kind!=BirthKind.STEM||brood.father!=null||brood.incidentSource.equals(brood.mother)))continue;
                if(brood.father==null&&brood.kind!=BirthKind.LIVE&&brood.incidentSource==null)continue;
                brood.burdenTicks=Math.clamp(value.getInt("BurdenTicks"),0,199);
                brood.anchor=position(value,"Anchor");if(brood.anchor==null)continue;
                brood.duration=Math.max(1,value.getInt("Duration"));brood.elapsed=Math.clamp(value.getInt("Elapsed"),0,brood.duration+600);
                brood.detachedHolder=value.hasUUID("DetachedHolder")?value.getUUID("DetachedHolder"):null;
                brood.variation=Math.clamp(value.getFloat("Variation"),.85f,1.15f);brood.cancelled=value.getBoolean("Cancelled");
                for(Tag childEntry:value.getList("Children",Tag.TAG_COMPOUND)) {
                    CompoundTag c=(CompoundTag)childEntry;Child child=new Child();child.id=c.getUUID("Id");child.variant=YukkuriVariant.valueOf(c.getString("Variant"));
                    child.subspeciesFlags=Math.clamp(c.getInt("SubspeciesFlags"),0,255);
                    child.eggManaged=c.getBoolean("EggManaged");child.eggLost=c.getBoolean("EggLost");
                    child.eggElapsed=Math.clamp(c.getInt("EggElapsed"),0,brood.duration+600);
                    child.eggNest=position(c,"EggNest");child.eggNestId=c.hasUUID("EggNestId")?c.getUUID("EggNestId"):null;
                    child.eggToken=c.hasUUID("EggToken")?c.getUUID("EggToken"):null;
                    child.adultScale=Math.clamp(c.getFloat("AdultScale"),.8f,1.2f);child.lifespan=Math.max(600,c.getLong("Lifespan"));child.traits=c.getIntArray("Traits");child.born=c.getBoolean("Born");child.landed=child.born&&c.getBoolean("Landed");
                    child.dispositions=c.getIntArray("Dispositions");
                    child.maturity=Math.clamp(c.getLong("Maturity"),1,Math.max(1,child.lifespan-1));
                    if(child.traits.length!=6||!data.people.containsKey(child.id))throw new IllegalArgumentException("Incomplete child record");
                    if(child.dispositions.length!=4) child.dispositions=new int[]{100-child.traits[3],50,child.traits[1],(child.traits[4]+child.traits[5])/2};
                    for(int i=0;i<4;i++)child.dispositions[i]=Math.clamp(child.dispositions[i],0,100);
                    brood.children.add(child);
                }
                if(!brood.children.isEmpty()&&brood.children.size()<=5)data.broods.put(brood.id,brood);
            } catch(IllegalArgumentException ignored) { /* Malformed records never invent a child. */ }
        }
        return data;
    }
}
