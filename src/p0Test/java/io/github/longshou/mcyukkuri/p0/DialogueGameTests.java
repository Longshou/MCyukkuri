package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.dialogue.NativeDialogue;
import io.github.longshou.mcyukkuri.dialogue.SpeechTiming;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class DialogueGameTests {
    @GameTest(templateNamespace=P0Mod.ID, template="empty", timeoutTicks=240, batch="dialogue_species")
    public static void speciesSpeech(GameTestHelper helper) {
        var level=helper.getLevel();var speakers=new java.util.ArrayList<io.github.longshou.mcyukkuri.entity.YukkuriEntity>();
        forceChunks(helper,true);
        var checks=new java.util.ArrayList<String>();
        int offset=0;
        for(var type:java.util.List.of(ModEntities.ALICE.get(),ModEntities.CHEN.get(),ModEntities.PATCHOULI.get())) {
            for(boolean young:new boolean[]{false,true}) {
                var e=type.create(level);e.setNoAi(true);e.setNoGravity(true);e.setPersistenceRequired();e.setPos(helper.absolutePos(new net.minecraft.core.BlockPos(1+offset%3,2,1+offset/3)).getCenter());offset++;
                var family=e.family().save();family.putString("Stage",young?"BABY":"ADULT");family.putFloat("Scale",young?.35f:1);e.family().load(family);
                level.addFreshEntity(e);speakers.add(e);String species=e.getVariant().assetId();String age=young?"young":"adult";
                for(String event:NativeDialogue.events()) {
                    var line=NativeDialogue.select(species,young,event,e.getRandom());
                    helper.assertTrue(line!=null&&!line.sound().isEmpty(),species+"/"+age+" missing "+event);
                    helper.assertTrue(line.species().equals(species)&&line.age().equals(age),"No cross-species fallback: "+species);
                }
                helper.assertTrue(e.speak("hello"),"Native greeting starts: "+species);
                helper.assertTrue(e.getDialogueKey().startsWith("dialogue.mcyukkuri.native."+species+"."+age),"Correct species and age");
                int sequence=e.speechSequence();helper.assertTrue(!e.speak("hello"),"Repeat blocked");
                helper.assertTrue(e.speak("wounded")&&e.speechSequence()==sequence+1,"Urgent interrupt");
                var meal=NativeDialogue.select(species,young,"fed",e.getRandom());
                helper.assertTrue(meal.key().contains(species.equals("alice")?".native.":".original."),"Authorship remains explicit");
                checks.add(species+"/"+age+": all events, native greeting, duplicate, urgent interrupt, authorship");
            }
        }
        for(String species:java.util.List.of("reimu","marisa","alice"))for(boolean young:new boolean[]{false,true}) {
            var line=NativeDialogue.select(species,young,"nuzzle",net.minecraft.util.RandomSource.create(1));
            helper.assertTrue(line!=null&&line.id().endsWith("l3291"),"Native nuzzle source row");
        }
        long deadline=speakers.stream().mapToLong(e->e.speechEnd()).max().orElse(0);
        // Forced chunks may still be preparing entity ticking after the test's own clock has started.
        helper.startSequence().thenWaitUntil(()->helper.assertTrue(speakers.stream().allMatch(e->e.tickCount>0),"Speakers become ticking entities"))
                .thenExecute(()->helper.runAfterDelay(Math.max(2,deadline-level.getGameTime()+2),()->{
            for(var e:speakers){helper.assertTrue(e.tickCount>0&&e.isAlive(),"Speaker ticks and survives empty fixture");helper.assertTrue(e.getDialogueKey().isEmpty(),"Dialogue clears on expiry: "+e.getDialogueKey()+" tick="+e.tickCount);e.discard();}
            forceChunks(helper,false);
            P0Control.write("dialogue-species-checks",java.util.Map.of("passed",true,"eventsPerGroup",NativeDialogue.events().size(),"groups",checks));helper.succeed();
        }));
    }
    @GameTest(templateNamespace=P0Mod.ID, template="empty", timeoutTicks=200, batch="dialogue")
    public static void nativeSpeech(GameTestHelper helper) {
        var level = helper.getLevel();
        forceChunks(helper,true);
        var reimu = ModEntities.REIMU.get().create(level);
        helper.assertTrue(reimu != null, "Entity created");
        reimu.setNoAi(true);
        reimu.setNoGravity(true);reimu.setPersistenceRequired();
        reimu.setPos(helper.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1)).getCenter());
        level.addFreshEntity(reimu);
        helper.assertTrue(reimu.speak("hello"), "First greeting admitted");
        helper.assertTrue(reimu.getDialogueKey().contains("reimu.adult.l0013"), "Original adult greeting selected");
        int first = reimu.speechSequence();
        helper.assertTrue(!reimu.speak("hello") && reimu.speechSequence()==first, "Duplicate does not restart");
        helper.assertTrue(!reimu.speak("not_an_event"), "Unknown event is silent");
        helper.assertTrue(reimu.speak("wounded") && reimu.speechSequence()==first+1, "Urgent reaction interrupts");
        helper.assertTrue(!reimu.speak("idle") && !reimu.speak("wounded"), "No overlapping low/equal priority");
        helper.assertTrue(NativeDialogue.select("marisa",true,"wounded",reimu.getRandom()).key().contains("marisa.young.l0102"), "Native Marisa infant hurt line");
        helper.assertTrue(NativeDialogue.select("reimu",true,"hello",reimu.getRandom()).key().contains("reimu.young"), "Infant table selected");
        CompoundTag saved = new CompoundTag(); reimu.saveWithoutId(saved);
        var restored = ModEntities.REIMU.get().create(level); restored.load(saved);
        helper.assertTrue(restored.getDialogueKey().isEmpty() && restored.speechSequence()==0, "Reload does not replay old speech");
        var timing = new SpeechTiming();
        helper.assertTrue(timing.admit(100,50,0), "Initial ambient admitted");
        helper.assertTrue(!timing.admit(149,50,0), "Active ambient blocked");
        helper.assertTrue(!timing.admit(151,50,1), "Quiet gap retained");
        helper.assertTrue(timing.admit(152,50,2), "User reaction allowed through quiet gap");
        long deadline = reimu.speechEnd();
        helper.startSequence().thenWaitUntil(()->helper.assertTrue(reimu.tickCount>0,"Speaker becomes a ticking entity"))
                .thenExecute(()->helper.runAfterDelay(Math.max(2,deadline-level.getGameTime()+2), () -> {
            P0Control.write("dialogue-expiry-observation",java.util.Map.of("now",level.getGameTime(),"deadline",deadline,"entityTicks",reimu.tickCount,"alive",reimu.isAlive(),"key",reimu.getDialogueKey(),"start",reimu.speechStart(),"end",reimu.speechEnd()));
            helper.assertTrue(reimu.getDialogueKey().isEmpty(), "Speech expires without depending on expression timer; now="+level.getGameTime()+",deadline="+deadline+",entityTicks="+reimu.tickCount+",key="+reimu.getDialogueKey());
            helper.assertTrue(reimu.tickCount>0&&reimu.isAlive(),"Speaker ticks and survives empty fixture");
            reimu.discard();forceChunks(helper,false); helper.succeed();
        }));
    }
    private static void forceChunks(GameTestHelper helper,boolean force){
        var pos=helper.absolutePos(net.minecraft.core.BlockPos.ZERO);
        for(int x=(pos.getX()>>4)-1;x<=(pos.getX()>>4)+1;x++)for(int z=(pos.getZ()>>4)-1;z<=(pos.getZ()>>4)+1;z++)helper.getLevel().setChunkForced(x,z,force);
    }
}
