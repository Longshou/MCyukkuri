package io.github.longshou.mcyukkuri.life;

import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Delegated life state; persistence stays in the original YukkuriLife compound. */
final class LifeDoses {
    private final YukkuriLife o;
    LifeDoses(YukkuriLife o) { this.o = o; }

    private int spicyTicks, sprayDelay, drugSleep;
    private int leakCooldown, flourDoses, flourWindow, sinceFlour;
    private boolean addicted, returnDoseBottle;
    int spicyTicks(){return spicyTicks;}

    int sprayDelay(){return sprayDelay;}

    int drugSleep(){return drugSleep;}

    void drink(float relief){o.relieveDryness(relief);spicyTicks=Math.max(0,spicyTicks-Math.round(relief*4));o.addBladder(2);}

    boolean canTakeDose(ItemStack dose){return Consumables.oral(dose)&&!o.entity().family().awaitingLanding()&&o.mouth().isEmpty()&&o.mealTicks()==0&&o.entity().isAlive()&&!o.entity().isNoAi()
            &&!o.entity().isPanicking()&&!o.entity().isCommandPlaying()&&!o.entity().operations().active()&&!o.isSleeping()&&!o.entity().isInWaterOrBubble()&&Consumables.applicable(o.entity(),dose);}

    boolean beginDose(ItemStack one,UUID player,boolean returnBottle){
        if(one.getCount()!=1||!canTakeDose(one))return false;
        o.temperature().stop();
        o.beginDoseMeal(one, player, returnBottle);
        o.beginConsumption();o.entity().family().stop();o.entity().social().stop();o.entity().groupInteraction().stop();o.entity().playLife().stop();
        o.entity().getNavigation().stop();o.entity().setPersistenceRequired();return true;
    }

    boolean canSpray(){return o.entity().isAlive()&&!o.entity().isNoAi()&&!o.entity().isPanicking()&&!o.entity().isCommandPlaying()&&!o.entity().operations().active()
            &&!o.isSleeping()&&!o.entity().isInWaterOrBubble()&&!o.entity().isOnFire()&&sprayDelay==0&&drugSleep==0;}

    boolean sedated(){return sprayDelay>0||drugSleep>0;}

    void shortenDrugSleep(int ticks){if(drugSleep>ticks)drugSleep=Math.max(ticks,0);}

    int distress(){return o.entity().mind().stressView();}

    boolean involuntaryRelease(String cause, boolean force){
        if(o.entity().level().isClientSide||!o.entity().isAlive()||leakCooldown>0||o.bladder()<15||o.entity().isInWaterOrBubble()||o.entity().getVehicle()!=null&&!force)return false;
        if(!force&&o.entity().getRandom().nextFloat()>=Math.min(1f,.3f+o.bladder()/100f))return false;
        Vec3 outlet=ToiletContact.anus(o.entity(),0);
        if(outlet==null)outlet=o.entity().position().add(0,.05,0);
        if(!ToiletContact.drop(o.entity(),outlet))return false;
        o.leaked();leakCooldown=2400;
        o.entity().showReaction("teary","leak",80);
        return true;
    }

    boolean addicted(){return addicted;}

    int flourDoses(){return flourDoses;}

    int sinceFlour(){return sinceFlour;}

    boolean takeFlour(){
        o.addNutrition(8);o.increaseDryness(20);
        flourDoses++;flourWindow=2400;sinceFlour=0;
        if(!addicted&&flourDoses>=3){addicted=true;o.entity().showReaction("drool","hungry",80);}
        return true;
    }

    void emotionTick(){
        if(leakCooldown>0)leakCooldown--;
        if(o.entity().tickCount%100==0&&distress()>=YukkuriLife.DISTRESS_LEAK)involuntaryRelease("distress",false);
        if(flourWindow>0&&--flourWindow==0)flourDoses=0;
        if(sinceFlour<1000000)sinceFlour++;
        if(addicted){
            // Withdrawal: shaking, restless, more stress, every half minute; after two game days clean the habit is gone.
            if(sinceFlour>6000&&sinceFlour%600==0){o.entity().mind().add(2,"flour_withdrawal");if(!o.isSleeping())o.entity().showReaction("teary","craving",80);}
            if(sinceFlour>48000){addicted=false;flourDoses=0;}
        }
    }

    boolean spray(){
        if(!canSpray())return false;
        o.entity().playAction("doze_off");// playing an action resets the life state, so the delay is set after it
        o.interrupt();sprayDelay=90;o.entity().setPersistenceRequired();o.entity().showReaction("sleepy","spray_drowsy",90);
        o.level().sendParticles(ParticleTypes.CLOUD,o.entity().getX(),o.entity().getEyeY(),o.entity().getZ(),8,.15,.08,.15,.015);return true;
    }
    void tickSpice() { if(spicyTicks>0&&--spicyTicks%80==0)o.entity().body().irritated(); }
    void tickSpray() {
        if(sprayDelay>0&&--sprayDelay==0){o.interrupt();drugSleep=400;o.beginDrugSleep();o.entity().family().stop();o.entity().social().stop();o.entity().groupInteraction().stop();o.entity().playLife().stop();}
    }
    void tickDrugSleep() { if (drugSleep > 0) drugSleep--; }
    void clearDrugSleep() { drugSleep = 0; }
    void spicyTicks(int ticks) { spicyTicks = ticks; }
    boolean returnBottle() { return returnDoseBottle; }
    void returnBottle(boolean value) { returnDoseBottle = value; }
    void clearReturnBottle() { returnDoseBottle = false; }
    void stopSedation() { sprayDelay = drugSleep = 0; }
    void save(CompoundTag tag) { tag.putInt("SpicyTicks",spicyTicks);tag.putInt("SprayDelay",sprayDelay);tag.putInt("DrugSleep",drugSleep);tag.putInt("LeakCooldown",leakCooldown);tag.putInt("FlourDoses",flourDoses);tag.putInt("FlourWindow",flourWindow);tag.putInt("SinceFlour",sinceFlour);tag.putBoolean("Addicted",addicted);tag.putBoolean("ReturnDoseBottle",returnDoseBottle); }
    void load(CompoundTag tag) { spicyTicks=Math.clamp(tag.getInt("SpicyTicks"),0,240);sprayDelay=Math.clamp(tag.getInt("SprayDelay"),0,90);drugSleep=Math.clamp(tag.getInt("DrugSleep"),0,600);leakCooldown=Math.clamp(tag.getInt("LeakCooldown"),0,2400);flourDoses=Math.clamp(tag.getInt("FlourDoses"),0,16);flourWindow=Math.clamp(tag.getInt("FlourWindow"),0,2400);sinceFlour=Math.max(0,tag.getInt("SinceFlour"));addicted=tag.getBoolean("Addicted");returnDoseBottle=tag.getBoolean("ReturnDoseBottle"); }

}
