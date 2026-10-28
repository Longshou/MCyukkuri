package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.status.GameplayLog;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
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
import io.github.longshou.mcyukkuri.house.HouseNestBlockEntity;
import io.github.longshou.mcyukkuri.human.FactoryProduction;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.notebook.SurvivalProgress;
import io.github.longshou.mcyukkuri.settlement.Homes;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.Comparator;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/** Authoritative ancestry, partnerships, population reservations and births shared by reproduction paths. */
public final class FamilyData extends SavedData {
    public enum Status { UNBORN, ALIVE, DEAD, REMOVED, LOST }
    public enum PartnerState { UNIMPORTED, SINGLE, PARTNERED, WIDOWED, BLOCKED }
    public enum BirthKind { STEM, EGG, LIVE }
    public record BadgeAcquisition(int grade, long acquiredAt) {}
    public static final class Person {
        public final UUID id;
        public UUID mother, father, brood;
        /** Biological source of an aftermath birth; never a care parent or household member. */
        public UUID incidentSource;
        public GlobalPos position;
        public Status status = Status.ALIVE;
        public String variant;
        private PartnerState partnerState = PartnerState.UNIMPORTED;
        private String unknownPartnerState;
        private UUID partner;
        private final List<UUID> partnerHistory = new ArrayList<>();
        private final Set<UUID> dismissedChildren = new HashSet<>();
        /** Informational genealogy records only; never used as an attribute, price, or qualification. */
        private final List<BadgeAcquisition> badgeAcquisitions = new ArrayList<>(3);
        public PartnerState partnerState() { return partnerState; }
        public UUID partner() { return partner; }
        public List<UUID> partnerHistory() { return List.copyOf(partnerHistory); }
        Person(UUID id, String variant, GlobalPos position) { this.id=id; this.variant=variant; this.position=position; }
    }
    public static final class Child {
        public UUID id;
        public YukkuriVariant variant;
        public int subspeciesFlags;
        public float adultScale;
        public float birthScale;
        /** Fixed five-axis heredity, selected while both ancestry and visible form are known. */
        public YukkuriPersonality.BirthProfile birthProfile;
        public boolean born;
        public boolean landed;
        /** How the birth ended: HAT, CUSHION, GROUND or WATER (empty until it lands). */
        public String landing="";
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
    /** Conservative, session-long retention: identities themselves are never compacted or removed here. */
    private final Set<UUID> cleanupRetained = new HashSet<>();
    final RelationshipCleanup cleanup = new RelationshipCleanup();
    public void retainForCleanup(UUID id) { if(id!=null)cleanupRetained.add(id); }
    public boolean retainedForCleanup(UUID id) { return cleanupRetained.contains(id); }
    public boolean deadForCleanup(UUID id) { return id!=null&&knownDead(id); }
    private void retainPersonReferences(Person person) {
        if(person.status!=Status.DEAD||person.partnerState==PartnerState.UNIMPORTED||person.partnerState==PartnerState.BLOCKED)retainForCleanup(person.id);
        retainForCleanup(person.mother);retainForCleanup(person.father);retainForCleanup(person.incidentSource);retainForCleanup(person.partner);
    }
    /** Derived incoming claims, including unresolved legacy claims and dead claimants. */
    private final Map<UUID, Set<UUID>> partnerClaims = new HashMap<>();
    private boolean partnerIndexDirty = true;
    /** Derived snapshots in the current people traversal order, including every life status. */
    private final Map<UUID, List<UUID>> childrenByParent = new HashMap<>();
    private boolean childrenIndexDirty = true;
    private void putPerson(Person person) {
        people.put(person.id, person);
        retainPersonReferences(person);
        childrenIndexDirty = true;
        partnerIndexDirty = true;
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
    /** Every birth route selects form before deriving this one persisted genetic profile. */
    private static YukkuriPersonality.BirthProfile inheritBirthProfile(Child child,YukkuriEntity first,YukkuriEntity second) {
        int ordinal=child.subspeciesFlags&15;
        var kinds=SubspeciesLife.Kind.values();
        var kind=ordinal<kinds.length?kinds[ordinal]:SubspeciesLife.Kind.NORMAL;
        boolean deficient=((child.subspeciesFlags>>>4)&15)!=SubspeciesLife.Congenital.NORMAL.ordinal();
        return YukkuriPersonality.inheritBirth(child.id,first==null?null:first.life().personality(),second==null?null:second.life().personality(),deficient,kind);
    }
    private final Map<UUID, Brood> broods = new HashMap<>();
    private final Set<UUID> spawning = new HashSet<>();
    public static FamilyData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new Factory<>(FamilyData::new, FamilyData::load), "mcyukkuri_families");
    }
    public Person person(UUID id) { return id == null ? null : people.get(id); }
    /** An opaque preimage used only by the settlement generation transaction. */
    public static final class PartnerSnapshot {
        private final UUID id;
        private final boolean existed;
        private final PartnerState state;
        private final String unknownState;
        private final UUID partner;
        private final List<UUID> history;
        private PartnerSnapshot(UUID id, Person person) {
            this.id=id;existed=person!=null;
            state=person==null?PartnerState.UNIMPORTED:person.partnerState;
            unknownState=person==null?null:person.unknownPartnerState;
            partner=person==null?null:person.partner;
            history=person==null?List.of():List.copyOf(person.partnerHistory);
        }
    }
    public PartnerSnapshot snapshotPartner(UUID id) { return new PartnerSnapshot(id,people.get(id)); }
    /** Used only to undo members allocated by a failed encounter transaction. */
    public void rollbackEncounter(Set<UUID> allocated) {
        rollbackEncounter(allocated,null);
    }
    /** Restore the seed's authority explicitly; loading its old entity mirror must never do this. */
    public void rollbackEncounter(Set<UUID> allocated, PartnerSnapshot seed) {
        people.keySet().removeAll(allocated);
        if(seed!=null) {
            if(!seed.existed)people.remove(seed.id);
            else {
                Person person=people.get(seed.id);
                if(person==null)throw new IllegalStateException("Encounter removed its pre-existing seed");
                person.partnerState=seed.state;person.unknownPartnerState=seed.unknownState;person.partner=seed.partner;
                person.partnerHistory.clear();person.partnerHistory.addAll(seed.history);
            }
        }
        childrenIndexDirty=true;partnerIndexDirty=true;setDirty();
    }
    /** Records the first real acquisition of each badge level, independently of later expiry or loss. */
    public void recordBadge(UUID identity, int grade, long acquiredAt) {
        Person person = people.get(identity);
        if (person == null || grade < 1 || grade > 3
                || person.badgeAcquisitions.stream().anyMatch(record -> record.grade() == grade)) return;
        person.badgeAcquisitions.add(new BadgeAcquisition(grade, Math.max(0, acquiredAt)));
        setDirty();
    }
    /** -1 means missing parent data; zero means no recorded badge acquisition. */
    public int badgeAcquired(UUID identity) {
        Person person = identity == null ? null : people.get(identity);
        return person == null ? -1 : person.badgeAcquisitions.stream().mapToInt(BadgeAcquisition::grade).max().orElse(0);
    }
    public List<BadgeAcquisition> badgeAcquisitions(UUID identity) {
        Person person = identity == null ? null : people.get(identity);
        return person == null ? List.of() : List.copyOf(person.badgeAcquisitions);
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
        importPartner(record,entity);
        retainPersonReferences(record);
        setDirty(); return record;
    }
    private boolean admitted(YukkuriEntity entity) {
        return entity.level() instanceof ServerLevel level&&!entity.isRemoved()
                &&level.getEntity(entity.getUUID())==entity&&!YukkuriWorldData.get(level).isCaptured(entity.getUUID());
    }
    /** Called after admission, never while deserializing an entity or accepting a join event. */
    void importPartner(YukkuriEntity entity) {
        Person person=people.get(entity.getUUID());
        if(person!=null&&person.partnerState!=PartnerState.UNIMPORTED)return;
        if(!admitted(entity))return;
        if(person==null)register(entity);
        else importPartner(person,entity);
    }
    private void importPartner(Person person,YukkuriEntity entity) {
        if(person.partnerState!=PartnerState.UNIMPORTED||!admitted(entity))return;
        person.partner=entity.family().legacyPartner();
        person.partnerState=person.partner==null?PartnerState.SINGLE:PartnerState.PARTNERED;
        if(person.id.equals(person.partner))person.partnerState=PartnerState.BLOCKED;
        normalizeWidow(person);
        retainForCleanup(person.partner);
        partnerIndexDirty=true;setDirty();
    }
    private boolean knownDead(UUID id) {
        Person person=people.get(id);
        return person!=null&&person.status==Status.DEAD;
    }
    /** Uses only this ledger, including during load; absence is never evidence of death. */
    private void normalizeWidow(Person person) {
        if(person.partner==null||!knownDead(person.partner))return;
        boolean changed=false;
        // Preserve BLOCKED/unknown schemas, but retain the confirmed death in their readable history too.
        if(person.partnerState==PartnerState.PARTNERED) { person.partnerState=PartnerState.WIDOWED;changed=true; }
        if(!person.partnerHistory.contains(person.partner)) { person.partnerHistory.add(person.partner);changed=true; }
        retainForCleanup(person.partner);
        if(changed)setDirty();
    }
    private void rebuildPartnerIndex() {
        partnerClaims.clear();
        for(Person person:people.values())if(person.partner!=null)
            partnerClaims.computeIfAbsent(person.partner,id->new HashSet<>()).add(person.id);
        partnerIndexDirty=false;
    }
    private boolean availableFor(Person person,UUID other,boolean allowWidow) {
        // An admitted returning entity may still be REMOVED until its periodic register; this is not death.
        if(person==null||person.status==Status.DEAD||person.status==Status.LOST||person.status==Status.UNBORN
                ||person.partnerState==PartnerState.UNIMPORTED||person.partnerState==PartnerState.BLOCKED)return false;
        if(person.partner!=null&&!person.partner.equals(other)&&!(allowWidow&&knownDead(person.partner)))return false;
        for(UUID claimant:partnerClaims.getOrDefault(person.id,Set.of()))
            if(!claimant.equals(other)&&!knownDead(claimant))return false;
        return true;
    }
    /** Read eligibility after one-time migration; a single own slot may still have incoming claims. */
    boolean canPair(YukkuriEntity first,YukkuriEntity second,boolean allowWidow) {
        if(first==second||!first.isAlive()||!second.isAlive()||first.level()!=second.level()||!admitted(first)||!admitted(second))return false;
        importPartner(first);importPartner(second);
        if(partnerIndexDirty)rebuildPartnerIndex();
        return availableFor(people.get(first.getUUID()),second.getUUID(),allowWidow)
                &&availableFor(people.get(second.getUUID()),first.getUUID(),allowWidow);
    }
    /** Both checks precede both writes; only successful introduce/conceive commits call this. */
    boolean pair(YukkuriEntity first,YukkuriEntity second,boolean allowWidow) {
        if(!canPair(first,second,allowWidow))return false;
        Person a=people.get(first.getUUID()),b=people.get(second.getUUID());
        normalizeWidow(a);normalizeWidow(b);
        boolean changed=!b.id.equals(a.partner)||!a.id.equals(b.partner)||a.partnerState!=PartnerState.PARTNERED||b.partnerState!=PartnerState.PARTNERED;
        a.partner=b.id;b.partner=a.id;a.partnerState=b.partnerState=PartnerState.PARTNERED;
        retainForCleanup(a.id);retainForCleanup(b.id);
        partnerIndexDirty=true;setDirty();
        if(changed)GameplayLog.event(first,"family","partnership formed partner={} allowWidow={}",second.getUUID(),allowWidow);
        return true;
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
    public Set<UUID> dismissedChildren(UUID parent){var person=people.get(parent);return person==null?Set.of():Set.copyOf(person.dismissedChildren);}
    public boolean acknowledgesChild(UUID parent, UUID child) {
        var person=people.get(parent);
        return person==null||!person.dismissedChildren.contains(child);
    }
    public void dismissChild(YukkuriEntity parent, UUID child) {
        register(parent);
        if(people.get(parent.getUUID()).dismissedChildren.add(child)){
            setDirty();
            GameplayLog.event(parent,"family","parent dismissed child={} direction=parent_to_child child_recognition=retained",child);
        }
    }
    public boolean socialKin(UUID first, UUID second) {
        if (first.equals(second)) return true;
        Person a=people.get(first), b=people.get(second);
        if (a==null || b==null) return false;
        if (first.equals(b.mother) || first.equals(b.father)) return acknowledgesChild(first,second);
        if (second.equals(a.mother) || second.equals(a.father)) return true;
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
        for(var entity:level.getEntitiesOfClass(YukkuriEntity.class,new AABB(position).inflate(32))) register(entity);
        var custody=YukkuriWorldData.get(level);
        return (int)people.values().stream().filter(p->(p.status==Status.ALIVE||p.status==Status.UNBORN)
                && p.position!=null&&p.position.dimension().equals(level.dimension())&&p.position.pos().distSqr(position)<=32*32
                && !custody.isCaptured(p.id)).count();
    }
    public int reservedInside(ServerLevel level,Predicate<BlockPos> inside) {
        return (int)people.values().stream().filter(p->p.status==Status.UNBORN&&p.position!=null
                &&p.position.dimension().equals(level.dimension())&&inside.test(p.position.pos())).count();
    }
    /** A mother's outing must not release the sleeping places reserved for her unborn children. */
    public int reservedForResidents(Predicate<UUID> resident) {
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
                && Custody.view(mother).unassigned() && Custody.view(father).unassigned();
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
        if(!FactoryProduction.reproductionAllowed(mother,father,count))return refused("factory");
        var shelter=mother.habitat().shelter();
        boolean joinMother=ordinaryWildBurrow(mother,father,shelter);
        if(joinMother) {
            int incoming=count+(shelter.owns(father.getUUID())?0:1);
            if(!shelter.suitableForYoung(incoming))return refused("shelter");
        } else if(shelter!=null&&!shelter.suitableForYoung(count))return refused("shelter");
        else if(shelter==null&&kind!=BirthKind.EGG&&!Homes.petBedFits(mother,count))return refused("bed");
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
        if(plan.refusal()!=null){
            GameplayLog.limited(mother,"family","conceive."+plan.refusal(),200,
                    "conceive refused father={} kind={} reason={}",father.getUUID(),kind,plan.refusal());return null;
        }
        if(!pair(mother,father,true)){
            GameplayLog.limited(mother,"family","conceive.pair",200,"conceive refused father={} kind={} reason=pair",father.getUUID(),kind);return null;
        }
        long seed=plan.seed();int count=plan.count();var shelter=plan.shelter();boolean joinMother=plan.joinMother();float cost=plan.cost();
        Brood brood=new Brood();brood.id=UUID.randomUUID();brood.mother=mother.getUUID();brood.father=father.getUUID();brood.kind=kind;brood.anchor=anchor;
        brood.duration=kind!=BirthKind.EGG?FamilyConfig.GESTATION.get():FamilyConfig.EGG_INCUBATION.get();
        Random random=new Random(seed);brood.variation=.93f+random.nextFloat()*.14f;
        for(int i=0;i<count;i++) {
            Child child=new Child();child.id=UUID.randomUUID();child.variant=random.nextBoolean()?mother.getVariant():father.getVariant();
            child.subspeciesFlags=SubspeciesLife.birthFlags(child.id,child.variant,mother.subspecies().kind(),father.subspecies().kind());
            child.adultScale=Math.clamp((mother.family().adultScale()+father.family().adultScale())*.5f+(random.nextFloat()-.5f)*.12f,.8f,1.2f);
            child.birthProfile=inheritBirthProfile(child,mother,father);
            brood.children.add(child);
            Person person=new Person(child.id,child.variant.assetId(),anchor);person.mother=brood.mother;person.father=brood.father;person.brood=brood.id;
                    person.status=Status.UNBORN;person.partnerState=PartnerState.SINGLE;
            putPerson(person);
        }
        GrowthSize.assignBirthSizes(brood);
        broods.put(brood.id,brood);mother.life().spendNutrition(cost);father.life().spendNutrition(5);
        mother.family().conceived(brood.id,father.getUUID());father.family().partnerConceived(mother.getUUID());
        var custody=Custody.view(mother);
        if(custody.role()==Custody.Role.BREEDER && custody.owner()!=null && level.getEntity(custody.owner()) instanceof HumanNpc worker)
            worker.production().breeding().conceived(mother,father);
        // This is a normal reachability-checked claim.  A failed path leaves both homes intact;
        // the father retries while the brood is pending from FamilyLife.tick().
        if(joinMother&&!shelter.owns(father.getUUID()))father.habitat().choose(shelter);
        setDirty();
        GameplayLog.event(mother,"family","conceived brood={} father={} kind={} children={} nutritionCost={} fatherCost=5",
                brood.id,brood.father,kind,count,cost);
        return brood;
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
        if(!FactoryProduction.reproductionAllowed(victim,source,count))return "当前收容或工厂身份不允许建立这个事件";
        var shelter=victim.habitat().shelter();
        if(shelter!=null&&!shelter.suitableForYoung(count))return "当前住所的子代容量不足";
        if(shelter==null&&!Homes.petBedFits(victim,count))return "当前宠物床的子代空间不足";
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
            child.subspeciesFlags=SubspeciesLife.birthFlags(child.id,child.variant,victim.subspecies().kind(),source.subspecies().kind());
            child.adultScale=Math.clamp((victim.family().adultScale()+source.family().adultScale())*.5f+(random.nextFloat()-.5f)*.12f,.8f,1.2f);
            child.birthProfile=inheritBirthProfile(child,victim,source);brood.children.add(child);
            var person=new Person(child.id,child.variant.assetId(),brood.anchor);
            person.mother=brood.mother;person.incidentSource=brood.incidentSource;person.brood=brood.id;person.status=Status.UNBORN;person.partnerState=PartnerState.SINGLE;
            putPerson(person);
        }
        GrowthSize.assignBirthSizes(brood);
        broods.put(brood.id,brood);
        victim.family().receiveAftermath(brood.id);
        victim.life().spendNutrition(Math.min(2,victim.life().nutrition()));
        victim.life().applyEnvironment(0,0,8);victim.pet().addStress(25);
        victim.frightenFrom(source);victim.mind().felt(EmotionEvent.FRIGHTENED); victim.showReaction("teary","afraid",200);
        victim.syncFamily();
        setDirty();
        GameplayLog.event(victim,"family","aftermath reserved brood={} source={} children={}",brood.id,source.getUUID(),count);
        return brood;
    }
    /** A manufactured dose has no known individual father. Never substitute a nearby animal. */
    public boolean canAssist(YukkuriEntity mother){
        if(!(mother.level() instanceof ServerLevel level)||!mother.family().readyForAssistance()||busyParent(mother.getUUID()))return false;
        var custody=Custody.view(mother);
        // Known NPC identity survives unloading; legacy UNKNOWN keeps the old positive-entity check.
        if(custody.hasPendingStaffRole()||HumanCommerce.data(mother).getBoolean("RetiredParent")||custody.hasFactoryStage()
                ||custody.owner()!=null&&(Set.of(Custody.Role.BREEDER,Custody.Role.RESERVE_PARENT,Custody.Role.NURSERY,Custody.Role.INPUT,
                Custody.Role.LABOR,Custody.Role.RESERVE,Custody.Role.STOCK,Custody.Role.LOST).contains(custody.role())
                ||custody.ownerKind()==Custody.OwnerKind.NPC
                ||custody.ownerKind()==Custody.OwnerKind.UNKNOWN&&level.getEntity(custody.owner()) instanceof HumanNpc))return false;
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
            child.subspeciesFlags=SubspeciesLife.birthFlags(child.id,child.variant,mother.subspecies().kind(),null);
            child.birthProfile=inheritBirthProfile(child,mother,null);
            brood.children.add(child);var person=new Person(child.id,child.variant.assetId(),brood.anchor);person.mother=brood.mother;person.brood=brood.id;
                    person.status=Status.UNBORN;person.partnerState=PartnerState.SINGLE;putPerson(person);
        }
        GrowthSize.assignBirthSizes(brood);
        broods.put(brood.id,brood);mother.life().spendNutrition(10+count*2);mother.family().assisted(brood.id);setDirty();
        GameplayLog.event(mother,"family","assisted conception brood={} kind={} children={} nutritionCost={}",brood.id,brood.kind,count,10+count*2);
        return brood;
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
    private YukkuriEntity refuse(Brood brood,ServerLevel level,String why){
        refusals.put(brood.id,why);
        GameplayLog.worldLimited(level,"family","birth."+brood.id+"."+why,200,
                "birth refused brood={} mother={} kind={} reason={} elapsed={}/{} unborn={} anchor={}",
                brood.id,brood.mother,brood.kind,why,brood.elapsed,brood.duration,brood.unborn(),brood.anchor.pos());
        return null;
    }
    /** Why the last birth attempt of this brood was refused (diagnostic; empty when none or since succeeded). */
    public String birthBlock(Brood brood){return brood==null?"":refusals.getOrDefault(brood.id,"");}
    /** Read-only water-birth capacity preflight. The actual child alone may move riders or jettison cargo at reservation time. */
    public boolean prepareWaterBirthCargoSpace(Brood brood,HatBoatEntity mine,ServerLevel level,Vec3 position){
        if(mine==null)return true;
        // The unborn probe has the same birth-size class/load as the concrete child admitted below, but this phase never changes
        // cargo or deck state. Only the mother's own hull may nominate the rescue rim.
        var newborn=brood.children.stream().filter(c->!c.born&&!c.eggLost).findFirst().orElse(null);
        if(newborn==null)return true;
        if(mine.canMakeRoomForNewborn(brood.kind,newborn,true))return true;
        var other=waterBirthPartner(brood,mine,level,position);
        if(other==null)return false;
        if(fatherBusy(brood,level))return false;
        return other.canMakeRoomForNewborn(brood.kind,newborn,false);
    }

    private int nextUnbornFlags(Brood brood){
        for(var child:brood.children)if(!child.born&&!child.eggLost)return child.subspeciesFlags;
        return 0;
    }

    private HatBoatEntity waterBirthPartner(Brood brood,HatBoatEntity mine,ServerLevel level,Vec3 position){
        if(brood.father==null||!(level.getEntity(brood.father) instanceof YukkuriEntity father)||!father.isAlive())return null;
        var other=father.family().boatHome();
        return other==null||other==mine||other.position().subtract(position).horizontalDistance()>FamilyLife.SHARE_REACH+other.getBbWidth()*.5?null:other;
    }

    private boolean fatherBusy(Brood brood,ServerLevel level){
        if(brood.father==null||!(level.getEntity(brood.father) instanceof YukkuriEntity father))return true;
        return father.isNoAi()||father.isCommandPlaying()||father.operations().active()||father.life().isSleeping()
                ||father.life().mealTicks()>0||father.family().tiedToLabor();
    }
    /**
     * The hat boat that takes this newborn of a water birth. No partner and no shore are needed: it lands on a seat claimed in its
     * mother's own hat. Only when her hat has none does a partner afloat alongside take it, as long as his seat can be reached by the same
     * flight; sharing the litter between the two hulls is the mother's own lift afterwards (LiveDelivery), not decided at birth.
     */
    private HatBoatEntity waterCradle(Brood brood,int index,HatBoatEntity mine,YukkuriEntity child,ServerLevel level){
        if(brood.kind==BirthKind.LIVE){
            if(!(level.getEntity(brood.mother) instanceof YukkuriEntity mother))return null;
            var exit=mother.family().selectedBirthExit(index);
            if(exit==null||exit.target()==null||exit.target().hat()==null||!BirthExit.valid(mother,index,exit))return null;
            var target=exit.target();var selected=target.hat();
            if(selected!=mine&&(fatherBusy(brood,level)||!Objects.equals(selected.ownerYukkuri(),brood.father)))return null;
            if(!selected.makeRoom(child,selected==mine,target.admission(),target.slot()))return null;
            var seat=selected.reserveBirthAdmission(child,target.admission(),child.position(),target.slot());
            if(seat==null)return null;
            if(selected.toWorld(seat).subtract(child.position()).horizontalDistance()>target.reach()){
                selected.cancelBirthAdmission(child);return null;
            }
            GameplayLog.event(mother,"family","birth berth reserved child={} hat={} slot={} admission={} outlet={}",
                    child.getUUID(),selected.getUUID(),target.slot(),target.admission(),child.position());
            return selected;
        }
        HatBoatEntity partner=waterBirthPartner(brood,mine,level,child.position());
        if(partner!=null&&fatherBusy(brood,level))partner=null;
        var motherAdmission=prepareBirthAdmission(mine,child,true);
        if(motherAdmission!=HatBoatEntity.NewbornAdmission.NONE&&reserveBirth(mine,child,1.25,motherAdmission))return mine;
        var fatherAdmission=prepareBirthAdmission(partner,child,false);
        if(fatherAdmission!=HatBoatEntity.NewbornAdmission.NONE&&reserveBirth(partner,child,FamilyLife.SHARE_REACH,fatherAdmission))return partner;
        return null;
    }

    /**
     * Where this kit would get next on the hull, read only: the aim of its drawn launch. Never kept for it, and the actual
     * landing is still decided by its first real contact with the surface.
     */
    private static Vec3 launchPlace(HatBoatEntity hat,YukkuriEntity child){
        var place=hat.peekSeat(child);
        if(place!=null)return place;
        var centre=hat.spots().get("centre");
        return centre==null?Vec3.ZERO:centre;
    }

    /** The concrete newborn is now known: only this step may free room, and its chosen purpose remains fixed for reservation. */
    private HatBoatEntity.NewbornAdmission prepareBirthAdmission(HatBoatEntity boat,YukkuriEntity child,boolean emergency){
        if(boat==null||!boat.isAfloat()||boat.unsteadyMotion()||!boat.makeRoom(child,emergency))return HatBoatEntity.NewbornAdmission.NONE;
        return boat.newbornAdmission(child,emergency);
    }

    /** Reserve the exact physical berth before the newborn is inserted; the physical launch no longer follows a kept seat. */
    private boolean reserveBirth(HatBoatEntity boat,YukkuriEntity child,double reach,HatBoatEntity.NewbornAdmission admission){
        if(admission==HatBoatEntity.NewbornAdmission.NONE)return false;
        var seat=boat.reserveBirthAdmission(child,admission);
        if(seat==null)return false;
        if(boat.toWorld(seat).subtract(child.position()).horizontalDistance()<=reach)return true;
        boat.cancelBirthAdmission(child);return false;
    }
    public YukkuriEntity birth(Brood brood, int index, ServerLevel level, Vec3 position) {
        if(brood.cancelled||index<0||index>=brood.children.size())return null;
        if(brood.detachedHolder==null&&brood.kind!=BirthKind.EGG
                &&level.getEntity(brood.mother) instanceof YukkuriEntity mother&&!mother.subspecies().canReproduce()) {
            cancel(brood,mother,"mother_cannot_reproduce");return null;
        }
        Child child=brood.children.get(index);if(child.born||child.eggLost)return null;
        int elapsed=brood.kind==BirthKind.EGG&&child.eggManaged?child.eggElapsed:brood.elapsed;
        if(elapsed<brood.duration+(brood.kind==BirthKind.STEM?index*8:brood.kind==BirthKind.LIVE?LiveBirthGeometry.birthTick(index):0))return null;
        if(brood.kind==BirthKind.EGG&&child.eggManaged
                &&(child.eggToken!=null||child.eggNest==null||!child.eggNest.dimension().equals(level.dimension())
                ||child.eggNest.pos().distToCenterSqr(position)>4
                ||!(level.getBlockEntity(child.eggNest.pos()) instanceof HouseNestBlockEntity nest)
                ||!nest.identity().equals(child.eggNestId)))return null;
        if(brood.kind==BirthKind.LIVE){
            if(!(level.getEntity(brood.mother) instanceof YukkuriEntity mother)||mother.family().brood()!=brood)return refuse(brood,level,"mother_missing");
            if(!mother.family().readyForDelivery())return refuse(brood,level,"mother_not_ready");
            if(position.distanceToSqr(LiveBirthGeometry.position(mother,index))>.0001)return refuse(brood,level,"outlet_moved");
            if(mother.family().unprotectedBirthExit()?!BirthExit.unobstructed(mother,index):!LiveBirthGeometry.clear(mother,child,index))
                return refuse(brood,level,"outlet_blocked");
            for(int earlier=0;earlier<index;earlier++)if(!brood.children.get(earlier).born)return refuse(brood,level,"earlier_sibling_unborn");
        }
        for(ServerLevel dimension:level.getServer().getAllLevels())if(dimension.getEntity(child.id) instanceof YukkuriEntity existing) {
            if(brood.id.equals(existing.family().birthId())) { child.born=true;people.get(child.id).status=Status.ALIVE;setDirty();trackStemCompletion(brood,level); }
            return null;
        }
        if(YukkuriWorldData.get(level).isCaptured(child.id)) { child.born=true;people.get(child.id).status=Status.ALIVE;setDirty();trackStemCompletion(brood,level);return null; }
        if(!level.hasChunkAt(BlockPos.containing(position)))return null;
        // Moving one egg must never move its siblings' population reservations to the new nest.
        if(brood.kind!=BirthKind.EGG||!child.eggManaged) {
            if(!relocate(brood,level,BlockPos.containing(position)))return refuse(brood,level,"population_limit");
        }
        var boat=brood.detachedHolder==null&&brood.kind!=BirthKind.EGG&&level.getEntity(brood.mother) instanceof YukkuriEntity afloat?afloat.family().boatHome():null;
        // The live water route may be delayed by an unavailable shore or a failed launch, but it never falls back to a
        // land insertion. Keeping the ledger child unborn lets the same confirmed brood retry when the real own hat exists.
        if(brood.kind==BirthKind.LIVE&&level.getEntity(brood.mother) instanceof YukkuriEntity mother
                &&mother.family().waterBirthRequired()&&boat==null)return refuse(brood,level,"waiting_for_own_hat");
        boolean unprotected=brood.kind==BirthKind.LIVE&&level.getEntity(brood.mother) instanceof YukkuriEntity m&&m.family().unprotectedBirthExit();
        if(!unprotected&&!prepareWaterBirthCargoSpace(brood,boat,level,position))return refuse(brood,level,"boat_cargo_space_pending");
        YukkuriEntity entity=ModEntities.forVariant(child.variant).create(level);if(entity==null)return null;
        entity.setUUID(child.id);entity.subspecies().initializeBirth(child.subspeciesFlags);entity.family().newborn(child,brood);
        entity.subspecies().refreshStage();
        entity.life().personality().applyBirthProfile(child.birthProfile);entity.moveTo(position.x,position.y,position.z,0,0);
        if(brood.kind==BirthKind.LIVE&&level.getEntity(brood.mother) instanceof YukkuriEntity mother){
            float facing=mother.yBodyRot;
            entity.setYRot(facing);entity.yRotO=facing;entity.yBodyRot=entity.yBodyRotO=facing;entity.setYHeadRot(facing);entity.yHeadRotO=facing;
        }
        if(brood.detachedHolder!=null){entity.setYRot(180);entity.yRotO=180;entity.yBodyRot=entity.yBodyRotO=180;entity.setYHeadRot(180);entity.yHeadRotO=180;}
        entity.setPersistenceRequired();
        YukkuriEntity scene=brood.kind==BirthKind.LIVE&&level.getEntity(brood.mother) instanceof YukkuriEntity m?m:null;
        boolean noCushion=unprotected||scene!=null&&scene.family().withholdsCushion();
        // An aquatic mother afloat gives birth into her hat boat: the newborn boards it instead of landing.
        HatBoatEntity waterHat=null,landHat=null;
        if(boat!=null&&!unprotected){
            waterHat=waterCradle(brood,index,boat,entity,level);
            if(waterHat==null)return refuse(brood,level,"boat_has_no_room");
        }else if(unprotected?level.getBlockCollisions(entity,entity.getBoundingBox()).iterator().hasNext()
                :!level.noCollision(entity)||level.containsAnyLiquid(entity.getBoundingBox()))return refuse(brood,level,"newborn_space_blocked");
        if(boat==null&&scene!=null&&!noCushion){
            var exit=scene.family().selectedBirthExit(index);
            if(exit!=null&&exit.target()!=null&&exit.target().hat()!=null){
                var target=exit.target();landHat=target.hat();
                // The outlet preflight chooses the receiver; the berth itself is not kept, the first real contact decides.
                if(!BirthExit.valid(scene,index,exit)||!landHat.catches()||!landHat.makeRoom(entity,false,target.admission(),target.slot()))
                    return refuse(brood,level,"catch_berth_unavailable");
            }
        }
        spawning.add(child.id);boolean added=false;
        try { added=level.addFreshEntity(entity); }
        finally {
            spawning.remove(child.id);
            // The reservation is keyed only by the newborn UUID. An insertion failure would otherwise look like an
            // independent subadult on the next capacity pass, permanently consuming eight units in this hat.
            if(!added&&waterHat!=null)waterHat.cancelBirthAdmission(entity);
            if(!added&&landHat!=null)landHat.cancelBirthAdmission(entity);
        }
        if(!added)return refuse(brood,level,"entity_rejected");
        // One drawn impulse, then physics: the receiver is the hat at the outlet, the mother's own hull, the nest she lies in or
        // the bare ground; where the kit comes down is decided by its first real contact, never by a kept seat.
        var hat=waterHat!=null?waterHat:landHat;
        boolean landBirth=boat==null&&waterHat==null&&scene!=null;
        if(hat!=null&&landBirth){
            // On land the push is random, not a preset catch or miss: a strong or crooked one carries the kit past the hat.
            boolean overstrain=scene.family().missesDelivery(false);
            double push=overstrain?BirthLaunch.OVERSTRAIN_PUSH:BirthLaunch.push(level.random),yaw=BirthLaunch.yaw(level.random);
            entity.family().launch(BirthLaunch.intoHat(entity,hat,hat.toWorld(launchPlace(hat,entity)),push,yaw),brood,index,hat);
        } else if(hat!=null){
            boolean miss=level.random.nextFloat()<.12f;
            if(brood.kind==BirthKind.LIVE&&level.getEntity(brood.mother) instanceof YukkuriEntity parent)miss=parent.family().missesDelivery(miss);
            var aim=hat.toWorld(launchPlace(hat,entity));
            if(miss){
                if(waterHat!=null)waterHat.cancelBirthAdmission(entity);else hat.cancelIncoming(entity);
                entity.family().doom();
                // The drawn overstrain goes on past the far brim instead of into the hull; the water incident is opened at once.
                entity.family().launch(BirthLaunch.past(entity,hat,aim),brood,index,null);
                if(waterHat!=null)io.github.longshou.mcyukkuri.subspecies.crew.WaterIncidents.open(entity,waterHat.getUUID(),null,
                        io.github.longshou.mcyukkuri.subspecies.crew.WaterIncidents.Cause.BIRTH_MISS);
            }
            else entity.family().launch(BirthLaunch.intoHat(entity,hat,aim,1,0),brood,index,hat);
        } else if((landBirth||unprotected)&&scene!=null)
            entity.family().launch(BirthLaunch.live(level.random,scene,entity,entity.position()),brood,index,null);
        YukkuriEntity actualMother=level.getEntity(brood.mother) instanceof YukkuriEntity parent?parent:null;
        YukkuriEntity actualFather=brood.father!=null&&level.getEntity(brood.father) instanceof YukkuriEntity parent?parent:null;
        if(actualMother!=null)HumanCommerce.inheritFactoryChild(entity,actualMother);
        HumanCommerce.inheritPet(entity,actualMother,actualFather);
        for(var parent:new YukkuriEntity[]{actualMother,actualFather})if(parent!=null){
            var custody=Custody.view(parent);
            if(custody.owner()!=null&&level.getServer().getPlayerList().getPlayer(custody.owner()) instanceof ServerPlayer owner)
                SurvivalProgress.award(owner,"family_birth");
        }
        boolean joined = level.getEntity(brood.mother) instanceof YukkuriEntity parent && entity.settlement().inherit(parent);
        if (!joined && brood.father!=null && level.getEntity(brood.father) instanceof YukkuriEntity parent) entity.settlement().inherit(parent);
        boolean inheritedHome=actualMother!=null&&entity.habitat().inherit(actualMother);
        if(!inheritedHome&&actualFather!=null)entity.habitat().inherit(actualFather);
        refusals.remove(brood.id);
        child.born=true;Person person=people.get(child.id);person.status=Status.ALIVE;person.position=GlobalPos.of(level.dimension(),entity.blockPosition());
        trackStemCompletion(brood,level);
        YukkuriWorldData.get(level).relate(child.id,brood.mother,30);YukkuriWorldData.get(level).relate(brood.mother,child.id,35);
        if(brood.father!=null){YukkuriWorldData.get(level).relate(child.id,brood.father,25);YukkuriWorldData.get(level).relate(brood.father,child.id,30);}
        setDirty();
        GameplayLog.event(entity,"family","born brood={} index={} kind={} mother={} father={} hat={} flying={} unborn={} stage={} scale={} width={} height={}",
                brood.id,index,brood.kind,brood.mother,brood.father,hat==null?null:hat.getUUID(),entity.family().flying(),brood.unborn(),
                entity.family().stage(),entity.family().scale(),entity.getBbWidth(),entity.getBbHeight());
        return entity;
    }
    private void trackStemCompletion(Brood brood,ServerLevel level) {
        if(brood.complete()&&level.getEntity(brood.mother) instanceof YukkuriEntity mother)
            ShedStemDrops.get(level).queue(mother,brood);
    }
    /** Landing is distinct from successful entity insertion, and survives mother or child unloading. */
    public void landed(YukkuriEntity entity) {
        var brood=brood(entity.family().birthId());
        if(brood==null||!entity.isAlive()||entity.family().awaitingLanding()||entity.family().flying()
                ||!(entity.onGround()||entity.isInWaterOrBubble()||entity.getVehicle() instanceof HatBoatEntity))return;
        for(var child:brood.children)if(child.id.equals(entity.getUUID())&&child.born&&!child.landed){
            child.landed=true;child.landing=entity.family().landingOutcome();setDirty();
            double fromAim=entity.family().landingFromAim();
            GameplayLog.event(entity,"family","birth landed brood={} vehicle={} water={} ground={} bumped={} outcome={} at={} fromAim={} impactVy={}",brood.id,
                    entity.getVehicle()==null?null:entity.getVehicle().getUUID(),entity.isInWaterOrBubble(),entity.onGround(),entity.family().bumped(),
                    child.landing.isEmpty()?"UNKNOWN":child.landing,entity.position(),Double.isNaN(fromAim)?"n/a":String.format(java.util.Locale.ROOT,"%.2f",fromAim),
                    String.format(java.util.Locale.ROOT,"%.3f",entity.family().landingImpactVy()));return;
        }
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
        cancel(brood,null,"unspecified");
    }
    /** Callers with a real actor retain the cancellation reason without adding another ledger owner. */
    public void cancel(Brood brood,Entity source,String reason) {
        if(brood==null||brood.cancelled)return;
        int unborn=brood.unborn();
        brood.cancelled=true;for(Child child:brood.children)if(!child.born)people.get(child.id).status=Status.LOST;
        setDirty();
        GameplayLog.event(source,"family","brood cancelled brood={} kind={} mother={} unborn={} reason={}",
                brood.id,brood.kind,brood.mother,unborn,reason);
    }
    public void died(YukkuriEntity entity) {
        Person p=register(entity);p.status=Status.DEAD;
        for(Person person:people.values())if(p.id.equals(person.partner))normalizeWidow(person);
        for(Brood brood:broods.values())if(brood.kind!=BirthKind.EGG&&brood.detachedHolder==null&&brood.mother.equals(entity.getUUID())&&!brood.complete())cancel(brood,entity,"mother_died");
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
        data.retainForCleanup(entity.getUUID());
    }
    public static void leaving(EntityLeaveLevelEvent event) {
        if(event.getEntity() instanceof YukkuriEntity entity&&event.getLevel() instanceof ServerLevel level
                && entity.getRemovalReason()==Entity.RemovalReason.DISCARDED&&!YukkuriWorldData.get(level).isCaptured(entity.getUUID())) {
            Person person=get(level).person(entity.getUUID());if(person!=null&&person.status==Status.ALIVE){person.status=Status.REMOVED;get(level).setDirty();}
        }
    }
    /** People and broods dropped by {@link #forgetPlayground}. */
    public record Forgotten(Set<UUID> people, Set<UUID> broods) {}
    /**
     * Playground field clear only (command/PlaygroundTools): forgets the records of {@code removed} individuals, of everybody last
     * registered inside {@code box}, and the broods those mothers carried or that are anchored inside the box, together with their
     * children. A cleared scene can then start again from the same ledger state, including scenes that reuse fixed UUIDs and
     * individuals that died during the previous run. Individuals held in custody ({@code captured}) keep their records.
     * Ordinary play never calls this: absence and unloading are still no evidence of death.
     */
    public Forgotten forgetPlayground(ServerLevel level,AABB box,Set<UUID> removed,Predicate<UUID> captured) {
        var dimension=level.dimension();
        Set<UUID> forget=new HashSet<>(removed);
        for(Person person:people.values())
            if(person.position!=null&&person.position.dimension().equals(dimension)&&box.contains(Vec3.atCenterOf(person.position.pos())))
                forget.add(person.id);
        forget.removeIf(captured);
        Set<UUID> dropped=new HashSet<>();
        for(Brood brood:broods.values())
            if(forget.contains(brood.mother)||brood.anchor!=null&&brood.anchor.dimension().equals(dimension)
                    &&box.contains(Vec3.atCenterOf(brood.anchor.pos()))&&!captured.test(brood.mother)) {
                dropped.add(brood.id);
                for(Child child:brood.children)if(!captured.test(child.id))forget.add(child.id);
            }
        broods.keySet().removeAll(dropped);
        people.keySet().removeAll(forget);
        spawning.removeAll(forget);
        childrenIndexDirty=true;partnerIndexDirty=true;
        if(!forget.isEmpty()||!dropped.isEmpty())setDirty();
        return new Forgotten(Set.copyOf(forget),Set.copyOf(dropped));
    }
    public static void putPosition(CompoundTag tag, String name, GlobalPos position) {
        if(position==null)return;CompoundTag value=new CompoundTag();value.putString("Dimension",position.dimension().location().toString());value.putLong("Pos",
                position.pos().asLong());tag.put(name,value);
    }
    public static GlobalPos position(CompoundTag tag,String name) {
        if(!tag.contains(name,Tag.TAG_COMPOUND))return null;CompoundTag value=tag.getCompound(name);ResourceLocation id=ResourceLocation.tryParse(value.getString("Dimension"));
        return id==null?null:GlobalPos.of(ResourceKey.create(Registries.DIMENSION,id),BlockPos.of(value.getLong("Pos")));
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Version",6);ListTag persons=new ListTag(),pregnancies=new ListTag();
        for(Person person:people.values()) {
            CompoundTag value=new CompoundTag();value.putUUID("Id",person.id);value.putString("Variant",person.variant);value.putString("Status",person.status.name());
            if(person.mother!=null)value.putUUID("Mother",person.mother);if(person.father!=null)value.putUUID("Father",person.father);if(person.brood!=null)value.putUUID("Brood",person.brood);
            if(person.incidentSource!=null)value.putUUID("IncidentSource",person.incidentSource);
            savePartner(person,value);
            ListTag dismissed=new ListTag();
            for(UUID child:person.dismissedChildren){var entry=new CompoundTag();entry.putUUID("Child",child);dismissed.add(entry);}
            value.put("DismissedChildren",dismissed);
            ListTag badges = new ListTag();
            for (var acquisition : person.badgeAcquisitions) {
                CompoundTag badge = new CompoundTag(); badge.putInt("Grade", acquisition.grade()); badge.putLong("AcquiredAt", acquisition.acquiredAt()); badges.add(badge);
            }
            value.put("BadgeAcquisitions", badges);
            putPosition(value,"Position",person.position);persons.add(value);
        }
        for(Brood brood:broods.values()) {
            CompoundTag value=new CompoundTag();value.putUUID("Id",brood.id);value.putUUID("Mother",brood.mother);if(brood.father!=null)value.putUUID("Father",brood.father);
            if(brood.incidentSource!=null){value.putUUID("IncidentSource",brood.incidentSource);value.putInt("BurdenTicks",brood.burdenTicks);}
            value.putString("Kind",brood.kind.name());putPosition(value,"Anchor",brood.anchor);value.putInt("Elapsed",brood.elapsed);value.putInt("Duration",brood.duration);
            if(brood.detachedHolder!=null)value.putUUID("DetachedHolder",brood.detachedHolder);
            value.putFloat("Variation",brood.variation);value.putBoolean("Cancelled",brood.cancelled);ListTag children=new ListTag();
            for(Child child:brood.children) {
                CompoundTag c=new CompoundTag();c.putUUID("Id",child.id);c.putString("Variant",child.variant.name());c.putFloat("AdultScale",child.adultScale);c.putFloat("BirthScale",child.birthScale);
                c.putInt("SubspeciesFlags",child.subspeciesFlags);
                if(child.eggManaged){
                    c.putBoolean("EggManaged",true);c.putBoolean("EggLost",child.eggLost);c.putInt("EggElapsed",child.eggElapsed);
                    putPosition(c,"EggNest",child.eggNest);
                    if(child.eggNestId!=null)c.putUUID("EggNestId",child.eggNestId);
                    if(child.eggToken!=null)c.putUUID("EggToken",child.eggToken);
                }
                c.putIntArray("BirthProfile",child.birthProfile.innateValues());
                        c.putBoolean("Born",child.born);c.putBoolean("Landed",child.landed);if(!child.landing.isEmpty())c.putString("Landing",child.landing);children.add(c);
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
            if(value.hasUUID("Mother"))person.mother=value.getUUID("Mother");if(value.hasUUID("Father"))person.father=value.getUUID("Father");
                    if(value.hasUUID("Brood"))person.brood=value.getUUID("Brood");
            if(value.hasUUID("IncidentSource"))person.incidentSource=value.getUUID("IncidentSource");
            data.loadPartner(person,value);
            for(var raw:value.getList("DismissedChildren",Tag.TAG_COMPOUND)){var dismissedEntry=(CompoundTag)raw;if(dismissedEntry.hasUUID("Child"))person.dismissedChildren.add(dismissedEntry.getUUID("Child"));}
            for (Tag badgeEntry : value.getList("BadgeAcquisitions", Tag.TAG_COMPOUND)) {
                CompoundTag badge = (CompoundTag) badgeEntry; int grade = badge.getInt("Grade");
                if (grade >= 1 && grade <= 3 && person.badgeAcquisitions.stream().noneMatch(record -> record.grade() == grade))
                    person.badgeAcquisitions.add(new BadgeAcquisition(grade, Math.max(0, badge.getLong("AcquiredAt"))));
            }
            data.putPerson(person);
        }
        for(Person person:data.people.values())data.normalizeWidow(person);
        for(Tag entry:tag.getList("Broods",Tag.TAG_COMPOUND)) {
            CompoundTag value=(CompoundTag)entry;
            if(!value.hasUUID("Id")||!value.hasUUID("Mother"))continue;
            try {
                Brood brood=new Brood();brood.id=value.getUUID("Id");brood.mother=value.getUUID("Mother");brood.father=value.hasUUID("Father")?value.getUUID("Father"):null;
                        brood.kind=BirthKind.valueOf(value.getString("Kind"));
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
                    child.adultScale=Math.clamp(c.getFloat("AdultScale"),.8f,1.2f);child.birthScale=c.getFloat("BirthScale");child.birthProfile=YukkuriPersonality.BirthProfile.fromInnateValues(c.getIntArray("BirthProfile"));
                            child.born=c.getBoolean("Born");child.landed=child.born&&c.getBoolean("Landed");child.landing=child.landed?c.getString("Landing"):"";
                    if(!data.people.containsKey(child.id))throw new IllegalArgumentException("Incomplete child record");
                    brood.children.add(child);
                    data.retainForCleanup(child.id);
                }
                if(!brood.children.isEmpty()&&brood.children.size()<=5) {
                    data.broods.put(brood.id,brood);
                    data.retainForCleanup(brood.mother);data.retainForCleanup(brood.father);data.retainForCleanup(brood.incidentSource);
                }
            } catch(IllegalArgumentException ignored) { /* Malformed records never invent a child. */ }
        }
        return data;
    }
    private static void savePartner(Person person,CompoundTag tag) {
        tag.putString("PartnerState",person.unknownPartnerState!=null?person.unknownPartnerState:person.partnerState.name());
        if(person.partner!=null)tag.putUUID("Partner",person.partner);
        if(!person.partnerHistory.isEmpty()) {
            ListTag history=new ListTag();
            for(UUID id:person.partnerHistory) { CompoundTag entry=new CompoundTag();entry.putUUID("Id",id);history.add(entry); }
            tag.put("PartnerHistory",history);
        }
    }
    private void loadPartner(Person person,CompoundTag tag) {
        if(tag.hasUUID("Partner"))person.partner=tag.getUUID("Partner");
        boolean malformed=tag.contains("Partner")&&!tag.hasUUID("Partner")
                ||tag.contains("PartnerHistory")&&!tag.contains("PartnerHistory",Tag.TAG_LIST);
        if(tag.get("PartnerHistory") instanceof ListTag history)for(Tag entry:history) {
            if(!(entry instanceof CompoundTag value)||!value.hasUUID("Id")) { malformed=true;continue; }
            UUID id=value.getUUID("Id");if(!person.partnerHistory.contains(id))person.partnerHistory.add(id);
            retainForCleanup(id);
        }
        if(!tag.contains("PartnerState")) {
            // Only genuinely old records are eligible for entity import.
            if(tag.contains("Partner")||tag.contains("PartnerHistory"))malformed=true;
        } else {
            String state=tag.getString("PartnerState");
            try { person.partnerState=PartnerState.valueOf(state); }
            catch(IllegalArgumentException ignored) { person.partnerState=PartnerState.BLOCKED;person.unknownPartnerState=state; }
        }
        if(person.id.equals(person.partner)
                ||person.partnerState==PartnerState.UNIMPORTED&&(person.partner!=null||!person.partnerHistory.isEmpty())
                ||person.partnerState==PartnerState.SINGLE&&person.partner!=null
                ||(person.partnerState==PartnerState.PARTNERED||person.partnerState==PartnerState.WIDOWED)&&person.partner==null)malformed=true;
        if(malformed)person.partnerState=PartnerState.BLOCKED;
    }
}
