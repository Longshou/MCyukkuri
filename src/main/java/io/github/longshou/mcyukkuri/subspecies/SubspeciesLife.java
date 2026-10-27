package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.status.GameplayLog;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import io.github.longshou.mcyukkuri.MCYukkuri;
import java.util.UUID;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;

/** Biological ancestry, congenital shape and acquired transport are separate saved axes. */
public final class SubspeciesLife {
    /** AQUATIC_MARISA looks like an ordinary Marisa and sails her hat as a boat (wiki aquatic_marisa; Hakkei U sprite 7899 水上まりさ用).
     *  The last four are the rare forms of komicolle 40720 No.11518905 (rare/RareForms, docs/RARE_FORMS.md): kotatsu Marisa,
     *  long- and round-tailed Chen and the red-eyed Myon. Ordinals are saved and synced in four bits: append only. */
    public enum Kind {
        NORMAL(null), SHELL_MARISA(YukkuriVariant.MARISA), WASA_REIMU(YukkuriVariant.REIMU), AQUATIC_MARISA(YukkuriVariant.MARISA),
        KOTATSU_MARISA(YukkuriVariant.MARISA), LONGTAIL_CHEN(YukkuriVariant.CHEN), ROUNDTAIL_CHEN(YukkuriVariant.CHEN), REDEYE_MYON(YukkuriVariant.MYON);
        private final YukkuriVariant variant;
        Kind(YukkuriVariant variant) { this.variant = variant; }
        /** Whether this kind can belong to an individual of that species. */
        public boolean fits(YukkuriVariant species) { return variant == null || variant == species; }
    }
    public enum Congenital { NORMAL, SPARSE_HAIR, FACE_ASYMMETRY, BEAN_BODY }
    private final YukkuriEntity entity;
    private Kind kind = Kind.NORMAL;
    private Congenital congenital = Congenital.NORMAL;
    private boolean initialized;
    private boolean boatExperienced;
    public static final int DEIBU_FLAG=256;
    /** An aquatic young that has not yet started its first voyage still wears the land-size hat (hat_deck_layout.json
     *  young_hat_multiplier, project design); the boat-size hat comes with that voyage. */
    public static final int YOUNG_HAT_FLAG=512;
    /** One full loaded game-day at the end of subadulthood. */
    private static final int DEIBU_EXPOSURE_TICKS=24_000, DEIBU_MAX_SPACE_ATTEMPTS=3;
    private boolean deibu;
    private int deibuExposure, deibuSpaceAttempts;
    private boolean deibuAdultResolved, deibuPending;
    private long deibuRetryAt, deibuSpaceSignature;
    private BlockPos deibuCheckedAt;
    public SubspeciesLife(YukkuriEntity entity) { this.entity = entity; }
    public Kind kind() { return entity.level().isClientSide ? Kind.values()[Math.min(Kind.values().length-1, entity.subspeciesFlags() & 15)] : kind; }
    public Congenital congenital() { return entity.level().isClientSide ? Congenital.values()[Math.min(3, (entity.subspeciesFlags() >> 4) & 15)] : congenital; }
    /** She has sailed her hat at least once. */
    public void noteBoatExperience() { boatExperienced = true; }
    public boolean boatExperienced() { return boatExperienced; }
    public boolean deibu() { return entity.level().isClientSide ? (entity.subspeciesFlags()&DEIBU_FLAG)!=0 : deibu; }
    public float formScale() { return deibu()?1.5f:1; }
    /** Deficient forms are infertile; Wasa has NORMAL congenital form and remains fertile. */
    public boolean canReproduce() { return congenital() == Congenital.NORMAL; }
    public int deibuExposure() { return deibuExposure; }
    public boolean deibuPending() { return deibuPending; }
    /** Compact readout of the locked adult outcome and any delayed body expansion. */
    public String deibuSpaceState() {
        if(deibu)return "applied";
        if(deibuPending)return deibuSpaceAttempts>=DEIBU_MAX_SPACE_ATTEMPTS?"limited":"pending";
        return deibuAdultResolved?"not_qualified":"tracking";
    }
    /** Kept for callers that only need form eligibility; lesson willingness uses the shared five-axis rules. */
    public boolean acceptsLesson(UUID player) { return true; }
    public int waterGain(int normal) { return kind() == Kind.SHELL_MARISA ? Math.max(1, (normal + 3) / 4) : normal; }
    public String assetId() {
        return assetId(entity.getVariant(),kind().ordinal() | congenital().ordinal()<<4 | (deibu()?DEIBU_FLAG:0));
    }
    /** Saved unborn flags can select the same visible model before an entity exists. */
    public static String assetId(YukkuriVariant variant,int flags) {
        if((flags&DEIBU_FLAG)!=0&&variant==YukkuriVariant.REIMU&&((flags>>4)&15)==0)
            return (flags&15)==Kind.WASA_REIMU.ordinal()?"reimu_wasa_deibu":"reimu_deibu";
        if ((flags&15)==Kind.SHELL_MARISA.ordinal() && variant==YukkuriVariant.MARISA) return "marisa_tsumuri";
        if ((flags&15)==Kind.WASA_REIMU.ordinal() && variant==YukkuriVariant.REIMU) return "reimu_wasa";
        if ((flags&15)==Kind.KOTATSU_MARISA.ordinal() && variant==YukkuriVariant.MARISA) return "marisa_kotatsu";
        if ((flags&15)==Kind.LONGTAIL_CHEN.ordinal() && variant==YukkuriVariant.CHEN) return "chen_longtail";
        if ((flags&15)==Kind.ROUNDTAIL_CHEN.ordinal() && variant==YukkuriVariant.CHEN) return "chen_roundtail";
        if ((flags&15)==Kind.REDEYE_MYON.ordinal() && variant==YukkuriVariant.MYON) return "myon_redeye";
        String suffix = switch ((flags>>4)&15) { case 1 -> "_sparse"; case 2 -> "_face"; case 3 -> "_bean"; default -> ""; };
        if (variant == YukkuriVariant.REIMU || variant == YukkuriVariant.MARISA) return variant.assetId() + suffix;
        return variant.assetId();
    }
    public boolean set(Kind value, Congenital shape) {
        if (entity.level().isClientSide) return false;
        if (!value.fits(entity.getVariant())) return false;
        if (shape != Congenital.NORMAL && entity.getVariant() != YukkuriVariant.REIMU && entity.getVariant() != YukkuriVariant.MARISA) return false;
        if (value != Kind.NORMAL && shape != Congenital.NORMAL) return false;
        String previous=assetId();kind = value; congenital = shape; if(shape!=Congenital.NORMAL)deibu=false;initialized = true; sync();entity.body().remapSurface(previous);
        if(entity.life()!=null)entity.life().personality().constrainToForm();
        return true;
    }
    private void sync() { entity.syncSubspecies(kind.ordinal() | congenital.ordinal() << 4 | (deibu?DEIBU_FLAG:0) | (youngHat()?YOUNG_HAT_FLAG:0)); }
    /** Refresh derived stage flags after newborn initialization, before its first geometry/admission check. */
    public void refreshStage() { if(!entity.level().isClientSide)sync(); }
    private boolean youngHat() {
        return kind == Kind.AQUATIC_MARISA && !boatExperienced && entity.family() != null && !entity.family().adult()
                && entity.aquaticLife() != null && !entity.aquaticLife().firstVoyage().active();
    }
    public void onNaturalSpawn(MobSpawnType reason) {
        if (initialized || reason != MobSpawnType.NATURAL && reason != MobSpawnType.CHUNK_GENERATION) return;
        roll(null, null);
        // Marisa that appear by open water are often the aquatic kind [original rate].
        if (kind == Kind.NORMAL && congenital == Congenital.NORMAL && entity.getVariant() == YukkuriVariant.MARISA && nearOpenWater()
                && new Random(entity.getUUID().getMostSignificantBits() ^ 0x41515541L).nextInt(4) == 0)
            set(Kind.AQUATIC_MARISA, Congenital.NORMAL);
        // Her life is on the water: once spawned she is put into her own hat boat instead of standing on the bank.
        if (kind == Kind.AQUATIC_MARISA) entity.aquaticLife().requestNaturalAfloatSpawn();
        entity.life().personality().applyBirthProfile(YukkuriPersonality.inheritBirth(entity.getUUID(),null,null,
                congenital!=Congenital.NORMAL,kind));
        // A loaded-world spawn joins already aboard; chunk generation cannot place entities and falls back to her first ticks.
        if (kind == Kind.AQUATIC_MARISA && reason == MobSpawnType.NATURAL) entity.aquaticLife().attemptAfloatSpawn();
    }
    private boolean nearOpenWater() {
        var origin = entity.blockPosition();
        for (var p : BlockPos.betweenClosed(origin.offset(-6, -2, -6), origin.offset(6, 1, 6)))
            if (entity.level().getFluidState(p).is(FluidTags.WATER) && entity.level().getFluidState(p.above()).isEmpty()) return true;
        return false;
    }
    private void roll(YukkuriEntity a, YukkuriEntity b) {
        if (initialized) return;
        initializeBirth(birthFlags(entity.getUUID(),entity.getVariant(),a==null?null:a.subspecies().kind(),b==null?null:b.subspecies().kind()));
    }
    /** Pure UUID selection; family records should save this once while parent ancestry is known. */
    public static int birthFlags(UUID child,YukkuriVariant variant,Kind parentA,Kind parentB) {
        var random=new Random(child.getMostSignificantBits() ^ child.getLeastSignificantBits() ^ 0x53484150454CL);
        var candidate=variant==YukkuriVariant.MARISA?Kind.SHELL_MARISA:variant==YukkuriVariant.REIMU?Kind.WASA_REIMU:Kind.NORMAL;
        Kind selected=Kind.NORMAL;int shape=0;
        int inheritedOdds=candidate==Kind.WASA_REIMU?50:4;
        if(candidate!=Kind.NORMAL&&random.nextInt(parentA==candidate||parentB==candidate?inheritedOdds:160)==0)selected=candidate;
        // The aquatic kind passes to half of a Marisa's young when a parent is aquatic [original rate]; shell is decided first.
        if(selected==Kind.NORMAL&&variant==YukkuriVariant.MARISA&&(parentA==Kind.AQUATIC_MARISA||parentB==Kind.AQUATIC_MARISA)&&random.nextInt(2)==0)selected=Kind.AQUATIC_MARISA;
        if(selected==Kind.NORMAL&&(variant==YukkuriVariant.REIMU||variant==YukkuriVariant.MARISA)&&random.nextInt(200)==0)shape=1+random.nextInt(3);
        // Rare forms of komicolle 40720 No.11518905, drawn after every older roll so those outcomes stay as they were [project rates].
        if(selected==Kind.NORMAL&&shape==0)selected=rareForm(random,variant,parentA,parentB);
        return selected.ordinal() | shape<<4;
    }
    private static Kind rareForm(Random random,YukkuriVariant variant,Kind parentA,Kind parentB) {
        switch(variant){
            case MARISA -> { if(random.nextInt(parentA==Kind.KOTATSU_MARISA||parentB==Kind.KOTATSU_MARISA?3:120)==0)return Kind.KOTATSU_MARISA; }
            case CHEN -> {
                for(Kind tail:new Kind[]{Kind.LONGTAIL_CHEN,Kind.ROUNDTAIL_CHEN})
                    if(random.nextInt(parentA==tail||parentB==tail?2:10)==0)return tail;
            }
            case MYON -> { if(random.nextInt(parentA==Kind.REDEYE_MYON||parentB==Kind.REDEYE_MYON?4:40)==0)return Kind.REDEYE_MYON; }
            default -> {}
        }
        return Kind.NORMAL;
    }
    public void initializeBirth(int flags) {
        if(initialized||entity.level().isClientSide)return;
        int k=flags&15,c=(flags>>4)&15;
        if(k>=Kind.values().length||c>=Congenital.values().length)flags=0;
        k=flags&15;c=(flags>>4)&15;
        if(!set(Kind.values()[k],Congenital.values()[c])){kind=Kind.NORMAL;congenital=Congenital.NORMAL;initialized=true;sync();}
    }
    public static void onBirth(YukkuriEntity child, YukkuriEntity a, YukkuriEntity b) { child.subspecies().roll(a, b); }
    private boolean deibuCandidate() {
        return entity.getVariant()==YukkuriVariant.REIMU&&congenital==Congenital.NORMAL&&(kind==Kind.NORMAL||kind==Kind.WASA_REIMU);
    }
    /** Called by the fixed family clock before each subadult age increment. */
    public void trackSubadultDeibu() {
        if(deibu||deibuAdultResolved||!deibuCandidate()||!entity.family().subadult())return;
        if(entity.life().nutrition()>=75&&entity.mind().stressView()>=70)
            deibuExposure=Math.min(DEIBU_EXPOSURE_TICKS,deibuExposure+1);
        else deibuExposure=0;
    }
    /** The one and only biology decision occurs at the subadult-to-adult age boundary. */
    public void resolveAdultDeibu() {
        if(deibuAdultResolved)return;
        deibuAdultResolved=true;
        if(deibuCandidate()&&deibuExposure>=DEIBU_EXPOSURE_TICKS&&entity.life().nutrition()>=75&&entity.mind().stressView()>=70) {
            deibuPending=true;deibuSpaceAttempts=0;deibuRetryAt=0;deibuCheckedAt=null;
            GameplayLog.event(entity, "growth", "deibu qualified exposure={} nutrition={} stress={} pending=true",
                    deibuExposure, entity.life().nutrition(), entity.mind().stressView());
            entity.setPersistenceRequired();
        }
    }
    public void tick() {
        if (entity.level().isClientSide) return;
        tickPendingDeibu();
        sync();
        var speed = entity.getAttribute(Attributes.MOVEMENT_SPEED);
        var id = MCYukkuri.id("shell_movement");
        if (speed != null) {
            if (kind == Kind.SHELL_MARISA && !speed.hasModifier(id)) speed.addTransientModifier(new AttributeModifier(id, -.35, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            else if (kind != Kind.SHELL_MARISA) speed.removeModifier(id);
        }
        // Shore selection and departures belong to AquaticLife's exclusive server goal.
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putString("Kind", kind.name()); tag.putString("Congenital", congenital.name());
        tag.putBoolean("Initialized", initialized); tag.putBoolean("BoatExperienced", boatExperienced);
        tag.putBoolean("Deibu",deibu);tag.putInt("DeibuExposure",deibuExposure);tag.putBoolean("DeibuAdultResolved",deibuAdultResolved);
        tag.putBoolean("DeibuPending",deibuPending);tag.putInt("DeibuSpaceAttempts",deibuSpaceAttempts);tag.putLong("DeibuRetryAt",deibuRetryAt);
        tag.putLong("DeibuSpaceSignature",deibuSpaceSignature);if(deibuCheckedAt!=null)tag.putLong("DeibuCheckedAt",deibuCheckedAt.asLong());
        tag.put("AquaticLife", entity.aquaticLife().save());
        return tag;
    }
    public void load(CompoundTag tag) {
        try { kind = Kind.valueOf(tag.getString("Kind")); } catch (IllegalArgumentException ex) { kind = Kind.NORMAL; }
        try { congenital = Congenital.valueOf(tag.getString("Congenital")); } catch (IllegalArgumentException ex) { congenital = Congenital.NORMAL; }
        if (!kind.fits(entity.getVariant())) kind = Kind.NORMAL;
        if (kind != Kind.NORMAL || entity.getVariant() != YukkuriVariant.REIMU && entity.getVariant() != YukkuriVariant.MARISA) congenital = Congenital.NORMAL;
        initialized = tag.getBoolean("Initialized"); boatExperienced = tag.getBoolean("BoatExperienced");
        sync();
        entity.aquaticLife().load(tag.getCompound("AquaticLife"));
        deibu=tag.getBoolean("Deibu")&&deibuCandidate();
        deibuExposure=Math.clamp(tag.getInt("DeibuExposure"),0,DEIBU_EXPOSURE_TICKS);
        deibuAdultResolved=tag.getBoolean("DeibuAdultResolved")||deibu;
        deibuPending=!deibu&&tag.getBoolean("DeibuPending")&&deibuAdultResolved;
        deibuSpaceAttempts=Math.clamp(tag.getInt("DeibuSpaceAttempts"),0,DEIBU_MAX_SPACE_ATTEMPTS);
        deibuRetryAt=Math.max(0,tag.getLong("DeibuRetryAt"));deibuSpaceSignature=tag.getLong("DeibuSpaceSignature");
        deibuCheckedAt=tag.contains("DeibuCheckedAt")?BlockPos.of(tag.getLong("DeibuCheckedAt")):null;
        sync();
    }
    /** The adult result is already locked; this only waits for a safe post-action place to expand the body. */
    private void tickPendingDeibu() {
        if(!deibuPending||deibu||entity.isNoAi()||!entity.isAlive())return;
        long now=entity.level().getGameTime();if(now<deibuRetryAt)return;
        long signature=nearbySpaceSignature();boolean changed=deibuCheckedAt==null||!deibuCheckedAt.equals(entity.blockPosition())||signature!=deibuSpaceSignature;
        if(deibuSpaceAttempts>=DEIBU_MAX_SPACE_ATTEMPTS&&!changed){deibuRetryAt=now+20;return;}
        // Do not interrupt an ordinary short action merely to redraw the body.
        if(!safeForDeibuChange()){deibuRetryAt=now+20;return;}
        deibuCheckedAt=entity.blockPosition().immutable();deibuSpaceSignature=signature;deibuSpaceAttempts++;deibuRetryAt=now+100;
        BlockPos stand=nearbyExpansionStand();if(stand==null){
            GameplayLog.limited(entity, "growth", "deibu.space", 1200, "deibu waiting reason=no_expansion_space attempts={}", deibuSpaceAttempts);
            return;
        }
        if(entity.distanceToSqr(Vec3.atBottomCenterOf(stand))>.36) {
            entity.life().navigate(stand,.9,false);return;
        }
        if(!expandedSpaceAt(entity.position()))return;
        applyDeibu();
    }
    private boolean safeForDeibuChange() {
        return entity.onGround()&&!entity.isPassenger()&&!entity.isPanicking()&&!entity.isCommandPlaying()&&!entity.life().isSleeping()&&entity.life().mealTicks()==0
                &&!entity.family().flying()&&!entity.family().crew().busy()&&!entity.careApproach().waiting()
                &&!entity.operations().active()&&!entity.family().active()&&entity.family().brood()==null&&!entity.groupInteraction().active()
                &&!entity.social().active()&&!entity.dailyLife().active()&&!entity.playLife().active()&&!entity.house().active()&&!entity.habitat().active()
                &&!entity.pet().busy()&&!entity.predator().active()&&!WildEvents.reserved(entity);
    }
    private BlockPos nearbyExpansionStand() {
        if(expandedSpaceAt(entity.position())&&entity.life().dryStand(entity.blockPosition()))return entity.blockPosition();
        BlockPos origin=entity.blockPosition();BlockPos best=null;
        for(var candidate:BlockPos.betweenClosed(origin.offset(-3,-1,-3),origin.offset(3,1,3))) {
            if(!entity.level().hasChunkAt(candidate)||!entity.life().dryStand(candidate)||!entity.life().reachable(candidate,false)
                    ||!expandedSpaceAt(Vec3.atBottomCenterOf(candidate)))continue;
            if(best==null||candidate.distSqr(origin)<best.distSqr(origin))best=candidate.immutable();
        }
        return best;
    }
    private boolean expandedSpaceAt(Vec3 position) {
        var dimensions=LiveBirthGeometry.bodyDimensions(entity,entity.family().scale()*1.5f,0).scale(entity.getScale());
        var box=dimensions.makeBoundingBox(position);
        return entity.level().getWorldBorder().isWithinBounds(box)&&entity.level().noCollision(entity,box)&&!entity.level().containsAnyLiquid(box)
                &&entity.level().getEntities(entity,box,e->e.isAlive()&&!e.isSpectator()).isEmpty();
    }
    private long nearbySpaceSignature() {
        BlockPos origin=entity.blockPosition();long result=origin.asLong();
        result=31*result+Double.doubleToLongBits(entity.getX());
        result=31*result+Double.doubleToLongBits(entity.getY());
        result=31*result+Double.doubleToLongBits(entity.getZ());
        for(var p:BlockPos.betweenClosed(origin.offset(-3,-1,-3),origin.offset(3,1,3))) {
            var state=entity.level().getBlockState(p);
            result=31*result+state.hashCode();
            result=31*result+(state.getFluidState().isEmpty()?0:1);
        }
        // After the three bounded probes, moving a blocking body must be as meaningful as a
        // changed door: both can make the already-locked adult body fit without a new biology roll.
        long occupants=0;
        for(var other:entity.level().getEntities(entity,entity.getBoundingBox().inflate(3,1,3),
                value->value.isAlive()&&!value.isSpectator())) {
            long place=Double.doubleToLongBits(other.getX());
            place=31*place+Double.doubleToLongBits(other.getY());
            place=31*place+Double.doubleToLongBits(other.getZ());
            place=31*place+Float.floatToIntBits(other.getBbWidth());
            place=31*place+Float.floatToIntBits(other.getBbHeight());
            occupants+=31L*other.getUUID().hashCode()+place;
        }
        return 31*result+occupants;
    }
    private void applyDeibu() {
        String previous=assetId();deibu=true;deibuPending=false;initialized=true;sync();entity.body().remapSurface(previous);entity.syncFamily();entity.refreshDimensions();entity.setPersistenceRequired();
        entity.life().personality().decided("deibu_developed");entity.showReaction("neutral","deibu_developed",100);
        GameplayLog.event(entity, "growth", "deibu applied previous={} current={} attempts={}", previous, assetId(), deibuSpaceAttempts);
    }
}
