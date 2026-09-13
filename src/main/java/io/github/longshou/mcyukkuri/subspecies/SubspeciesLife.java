package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import io.github.longshou.mcyukkuri.MCYukkuri;
import java.util.UUID;

/** Biological ancestry, congenital shape and acquired transport are separate saved axes. */
public final class SubspeciesLife {
    public enum Kind { NORMAL, SHELL_MARISA, WASA_REIMU }
    public enum Congenital { NORMAL, SPARSE_HAIR, FACE_ASYMMETRY, BEAN_BODY }
    private final YukkuriEntity entity;
    private Kind kind = Kind.NORMAL;
    private Congenital congenital = Congenital.NORMAL;
    private boolean initialized;
    private UUID boat;
    private boolean boatExperienced;
    private int lessonAttempts, lessonCooldown;
    public static final int DEIBU_FLAG=256;
    private boolean deibu;
    private int deibuExposure, susceptibility=-1;
    public SubspeciesLife(YukkuriEntity entity) { this.entity = entity; }
    public Kind kind() { return entity.level().isClientSide ? Kind.values()[Math.min(2, entity.subspeciesFlags() & 15)] : kind; }
    public Congenital congenital() { return entity.level().isClientSide ? Congenital.values()[Math.min(3, (entity.subspeciesFlags() >> 4) & 15)] : congenital; }
    public UUID boatId() { return boat; }
    public void boatId(UUID id) { boat = id; if (id != null) boatExperienced = true; }
    public boolean boatExperienced() { return boatExperienced; }
    public boolean deibu() { return entity.level().isClientSide ? (entity.subspeciesFlags()&DEIBU_FLAG)!=0 : deibu; }
    public float formScale() { return deibu()?1.5f:1; }
    /** Deficient forms are infertile; Wasa has NORMAL congenital form and remains fertile. */
    public boolean canReproduce() { return congenital() == Congenital.NORMAL; }
    public int deibuExposure() { return deibuExposure; }
    public int susceptibility() {
        if(susceptibility<0)susceptibility=new java.util.Random(entity.getUUID().getMostSignificantBits()^entity.getUUID().getLeastSignificantBits()^0x44454942554cL).nextInt(100);
        return susceptibility;
    }
    /** Willingness is independent of cognition; patient individuals can still learn. */
    public boolean acceptsLesson(UUID player) {
        if (kind() != Kind.WASA_REIMU || entity.life().personality().knowsWait()) return true;
        if (lessonCooldown > 0) return false;
        lessonCooldown = 240;
        long seed = entity.getUUID().getLeastSignificantBits() ^ player.getMostSignificantBits() ^ ++lessonAttempts;
        return new java.util.Random(seed).nextInt(20) == 0;
    }
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
        String suffix = switch ((flags>>4)&15) { case 1 -> "_sparse"; case 2 -> "_face"; case 3 -> "_bean"; default -> ""; };
        if (variant == YukkuriVariant.REIMU || variant == YukkuriVariant.MARISA) return variant.assetId() + suffix;
        return variant.assetId();
    }
    public boolean set(Kind value, Congenital shape) {
        if (entity.level().isClientSide) return false;
        if (value == Kind.SHELL_MARISA && entity.getVariant() != YukkuriVariant.MARISA
                || value == Kind.WASA_REIMU && entity.getVariant() != YukkuriVariant.REIMU) return false;
        if (entity.getVariant() != YukkuriVariant.REIMU && entity.getVariant() != YukkuriVariant.MARISA) return false;
        if (value != Kind.NORMAL && shape != Congenital.NORMAL) return false;
        String previous=assetId();kind = value; congenital = shape; if(shape!=Congenital.NORMAL)deibu=false;initialized = true; sync();entity.body().remapSurface(previous);return true;
    }
    private void sync() { entity.syncSubspecies(kind.ordinal() | congenital.ordinal() << 4 | (deibu?DEIBU_FLAG:0)); }
    public void onNaturalSpawn(MobSpawnType reason) {
        if (initialized || reason != MobSpawnType.NATURAL && reason != MobSpawnType.CHUNK_GENERATION) return;
        roll(null, null);
    }
    private void roll(YukkuriEntity a, YukkuriEntity b) {
        if (initialized) return;
        initializeBirth(birthFlags(entity.getUUID(),entity.getVariant(),a==null?null:a.subspecies().kind(),b==null?null:b.subspecies().kind()));
    }
    /** Pure UUID selection; family records should save this once while parent ancestry is known. */
    public static int birthFlags(UUID child,YukkuriVariant variant,Kind parentA,Kind parentB) {
        var random=new java.util.Random(child.getMostSignificantBits() ^ child.getLeastSignificantBits() ^ 0x53484150454CL);
        var candidate=variant==YukkuriVariant.MARISA?Kind.SHELL_MARISA:variant==YukkuriVariant.REIMU?Kind.WASA_REIMU:Kind.NORMAL;
        Kind selected=Kind.NORMAL;int shape=0;
        if(candidate!=Kind.NORMAL&&random.nextInt(parentA==candidate||parentB==candidate?4:160)==0)selected=candidate;
        if(selected==Kind.NORMAL&&(variant==YukkuriVariant.REIMU||variant==YukkuriVariant.MARISA)&&random.nextInt(200)==0)shape=1+random.nextInt(3);
        return selected.ordinal() | shape<<4;
    }
    public void initializeBirth(int flags) {
        if(initialized||entity.level().isClientSide)return;
        int k=flags&15,c=(flags>>4)&15;
        if(k>=Kind.values().length||c>=Congenital.values().length)flags=0;
        k=flags&15;c=(flags>>4)&15;
        if(!set(Kind.values()[k],Congenital.values()[c])){kind=Kind.NORMAL;congenital=Congenital.NORMAL;initialized=true;sync();}
    }
    public static void onBirth(YukkuriEntity child, YukkuriEntity a, YukkuriEntity b) { child.subspecies().roll(a, b); }
    public void tick() {
        if (entity.level().isClientSide) return;
        if (lessonCooldown > 0) lessonCooldown--;
        develop();
        sync();
        var speed = entity.getAttribute(Attributes.MOVEMENT_SPEED);
        var id = MCYukkuri.id("shell_movement");
        if (speed != null) {
            if (kind == Kind.SHELL_MARISA && !speed.hasModifier(id)) speed.addTransientModifier(new AttributeModifier(id, -.35, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            else if (kind != Kind.SHELL_MARISA) speed.removeModifier(id);
        }
        // Boats are acquired from an existing personal hat on a dry bank, never on falling into water.
        if (entity.tickCount % 400 == 0 && kind == Kind.NORMAL && entity.getVariant() == YukkuriVariant.MARISA
                && !entity.isNoAi() && entity.life().hasAccessory() && !entity.isInWaterOrBubble()
                && entity.onGround() && !entity.isPassenger() && entity.getRandom().nextInt(8) == 0)
            HatBoatEntity.tryLaunchNearby(entity);
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putString("Kind", kind.name()); tag.putString("Congenital", congenital.name());
        tag.putBoolean("Initialized", initialized); tag.putBoolean("BoatExperienced", boatExperienced);
        tag.putInt("LessonAttempts", lessonAttempts); tag.putInt("LessonCooldown", lessonCooldown);
        tag.putBoolean("Deibu",deibu);tag.putInt("DeibuExposure",deibuExposure);tag.putInt("Susceptibility",susceptibility());
        if (boat != null) tag.putUUID("Boat", boat); return tag;
    }
    public void load(CompoundTag tag) {
        try { kind = Kind.valueOf(tag.getString("Kind")); } catch (IllegalArgumentException ex) { kind = Kind.NORMAL; }
        try { congenital = Congenital.valueOf(tag.getString("Congenital")); } catch (IllegalArgumentException ex) { congenital = Congenital.NORMAL; }
        if (kind == Kind.SHELL_MARISA && entity.getVariant() != YukkuriVariant.MARISA || kind == Kind.WASA_REIMU && entity.getVariant() != YukkuriVariant.REIMU) kind = Kind.NORMAL;
        if (kind != Kind.NORMAL || entity.getVariant() != YukkuriVariant.REIMU && entity.getVariant() != YukkuriVariant.MARISA) congenital = Congenital.NORMAL;
        initialized = tag.getBoolean("Initialized"); boatExperienced = tag.getBoolean("BoatExperienced");
        lessonAttempts = Math.max(0, tag.getInt("LessonAttempts")); lessonCooldown = Math.clamp(tag.getInt("LessonCooldown"), 0, 240);
        boat = tag.hasUUID("Boat") ? tag.getUUID("Boat") : null; sync();
        deibu=tag.getBoolean("Deibu")&&entity.getVariant()==YukkuriVariant.REIMU&&congenital==Congenital.NORMAL;
        deibuExposure=Math.clamp(tag.getInt("DeibuExposure"),0,1728000);
        susceptibility=tag.contains("Susceptibility")?Math.clamp(tag.getInt("Susceptibility"),0,99):-1;
        sync();
    }
    private void develop() {
        if(deibu||entity.isNoAi()||!entity.isAlive())return;
        boolean qualified=entity.getVariant()==YukkuriVariant.REIMU&&congenital==Congenital.NORMAL&&entity.family().adult()
                &&entity.life().nutrition()>=io.github.longshou.mcyukkuri.life.YukkuriConfig.DEIBU_NUTRITION.get()
                &&entity.life().personality().environment().friendliness()<io.github.longshou.mcyukkuri.life.YukkuriConfig.DEIBU_FRIENDLINESS.get();
        int required=io.github.longshou.mcyukkuri.life.YukkuriConfig.DEIBU_EXPOSURE.get();
        deibuExposure=qualified?Math.min(required,deibuExposure+1):Math.max(0,deibuExposure-2);
        if(!qualified||deibuExposure<required||susceptibility()>=io.github.longshou.mcyukkuri.life.YukkuriConfig.DEIBU_SUSCEPTIBILITY.get()||entity.tickCount%20!=0)return;
        if(!entity.onGround()||entity.isPassenger()||entity.isPanicking()||entity.isCommandPlaying()||entity.life().isSleeping()||entity.life().mealTicks()>0
                ||entity.operations().active()||entity.family().active()||entity.family().brood()!=null||entity.groupInteraction().active()
                ||entity.social().active()||entity.dailyLife().active()||entity.playLife().active()||entity.house().active()||entity.habitat().active()
                ||entity.badges().active()||entity.predator().active()||io.github.longshou.mcyukkuri.event.WildEvents.reserved(entity))return;
        var dimensions=io.github.longshou.mcyukkuri.family.LiveBirthGeometry.bodyDimensions(entity,entity.family().scale()*1.5f,0).scale(entity.getScale());
        var box=dimensions.makeBoundingBox(entity.position());
        if(!entity.level().getWorldBorder().isWithinBounds(box)||!entity.level().noCollision(entity,box)||entity.level().containsAnyLiquid(box)
                ||!entity.level().getEntities(entity,box,e->e.isAlive()&&!e.isSpectator()).isEmpty())return;
        String previous=assetId();deibu=true;initialized=true;sync();entity.body().remapSurface(previous);entity.syncFamily();entity.refreshDimensions();entity.setPersistenceRequired();
        entity.life().personality().decided("deibu_developed");entity.showReaction("neutral","deibu_developed",100);
    }
}
