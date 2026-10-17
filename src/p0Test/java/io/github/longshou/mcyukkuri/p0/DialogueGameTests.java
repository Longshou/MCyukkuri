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
    @GameTest(templateNamespace=P0Mod.ID, template="empty", timeoutTicks=20, batch="dialogue")
    public static void registeredSounds(GameTestHelper helper) {
        var registry = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT;
        for (String path : io.github.longshou.mcyukkuri.registry.ModSounds.paths()) {
            var id = io.github.longshou.mcyukkuri.MCYukkuri.id(path);
            helper.assertTrue(registry.containsKey(id), "Sound registered on the server: " + id);
            helper.assertTrue(registry.get(id) == io.github.longshou.mcyukkuri.registry.ModSounds.event(path), "Client uses registered singleton: " + id);
        }
        for (var line : NativeDialogue.lines()) if (!line.sound().isEmpty()) {
            helper.assertTrue(io.github.longshou.mcyukkuri.registry.ModSounds.paths().contains(line.sound()), "Dialogue sound registered: " + line.id());
        }
        helper.assertTrue(NativeDialogue.lines().stream().filter(line -> !line.sound().isEmpty()).count() == 288,
                "The 288 recorded lines remain voiced");
        helper.assertTrue(io.github.longshou.mcyukkuri.registry.ModSounds.paths().size() == 290,
                "Original subtitles do not manufacture sound registrations");
        for (var line : NativeDialogue.lines()) if (line.sound().isEmpty()) {
            boolean predator = java.util.Set.of("remilia", "flandre").contains(line.species());
            boolean settlement = line.events().stream().allMatch(java.util.Set.of("settlement_shun", "settlement_drive",
                    "settlement_hurt", "settlement_assign", "settlement_submit",
                    "exam_nervous", "exam_answer", "exam_unsure", "exam_hide_fail", "exam_promise_wait", "exam_promise_fail", "exam_report", "exam_sorry", "exam_pass", "exam_fail", "badge_proud", "shop_appeal", "shop_bought", "shop_sold_sad", "shop_sold_proud", "wild_role_beg", "wild_role_provoke", "lesson_praised", "lesson_scolded", "badge_envy", "badge_admire", "date_meet", "date_fond", "permit_glad", "courtship_held", "badge_pinned", "badge_taken", "spray_drowsy", "basket_refuse", "basket_enter", "basket_exit", "wild_plea_keep", "pet_report_owner", "predator_restrain", "predator_obey", "predator_defy")::contains);
            helper.assertTrue(line.key().startsWith("dialogue.mcyukkuri.original.") && (predator || settlement),
                    "Only explicitly authored subtitle groups may be unvoiced: " + line.id());
        }
        helper.succeed();
    }

    @GameTest(templateNamespace=P0Mod.ID, template="empty", timeoutTicks=20, batch="dialogue_predators")
    public static void predatorSpeech(GameTestHelper helper) {
        var expected = java.util.Set.of("hello", "introduced", "peer", "known_peer", "idle", "hungry", "food_found",
                "take_food", "chew", "fed", "share", "wait_turn", "mine", "need_toilet", "toilet_done", "washed",
                "sleep", "awake", "gentle", "caregiver", "cold", "shy", "afraid", "wounded", "treated", "lost_accessory",
                "returned_accessory", "unrecognized", "toy", "exercise", "lesson_refused", "lesson_start", "lesson_progress",
                "lesson_learned", "wait_learned", "busy", "heard", "newborn", "grown", "courtship", "expecting", "birth",
                "wait_family", "lost_parent", "family_died", "nuzzle", "want_nuzzle", "nuzzle_done", "leisure_done", "puff");
        for (var type : java.util.List.of(ModEntities.REMILIA.get(), ModEntities.FLANDRE.get())) {
            for (String mode : java.util.List.of("ADULT", "BABY", "JUVENILE", "ADULT_JUVENILE_VOICE", "ADULT_BABY_TALK")) {
                // Selection and admission need a server level, but no ticking entity or forced chunk.
                var speaker = type.create(helper.getLevel());
                helper.assertTrue(speaker != null, "Predator speaker created");
                var family = speaker.family().save();
                boolean adult = mode.startsWith("ADULT");
                family.putString("Stage", adult ? "ADULT" : mode);
                family.putBoolean("JuvenileVoice", mode.equals("ADULT_JUVENILE_VOICE"));
                family.putBoolean("BabyTalk", false);
                speaker.family().load(family);
                if (mode.equals("ADULT_BABY_TALK")) {
                    helper.assertTrue(speaker.family().applyBabyTalk(), "Adult baby-talk state applied");
                    helper.assertTrue(speaker.family().adult(), "Baby-talk changes voice, not biological adulthood");
                }
                boolean young = !mode.equals("ADULT");
                helper.assertTrue(speaker.family().youngVoice() == young, "Actual age/voice rule: " + mode);
                String species = speaker.getVariant().dialogueSpecies();
                String age = young ? "young" : "adult";
                for (String event : expected) {
                    var line = NativeDialogue.select(species, speaker.family().youngVoice(), event, speaker.getRandom());
                    helper.assertTrue(line != null, species + "/" + mode + " missing " + event);
                    helper.assertTrue(line.species().equals(species) && line.age().equals(age), "No borrowed species or age");
                    helper.assertTrue(line.key().startsWith("dialogue.mcyukkuri.original." + species + "." + age + ".")
                            && line.sound().isEmpty(), "Predator original subtitle has no invented voice: " + line.id());
                }
                helper.assertTrue(NativeDialogue.select(species, young, "house_pickup", speaker.getRandom()) == null,
                        "No house-yukkuri event is assigned to predators");
                helper.assertTrue(NativeDialogue.select(species, young, "settlement_assign", speaker.getRandom()) == null,
                        "No ordinary settlement role is assigned to predators");
                helper.assertTrue(speaker.speak("hello"), "Unvoiced greeting still appears");
                helper.assertTrue(speaker.getDialogueKey().contains(species + "." + age + "."), "Entity uses its actual voice state");
                int sequence = speaker.speechSequence();
                helper.assertTrue(!speaker.speak("hello"), "Unvoiced duplicate does not restart");
                helper.assertTrue(speaker.speak("wounded") && speaker.speechSequence() == sequence + 1,
                        "Urgent unvoiced reaction interrupts the greeting");
                CompoundTag saved = new CompoundTag(); speaker.saveWithoutId(saved);
                var restored = type.create(helper.getLevel()); restored.load(saved);
                helper.assertTrue(restored.family().youngVoice() == young, "Voice state survives reload: " + mode);
                helper.assertTrue(restored.getDialogueKey().isEmpty(), "Reload does not replay an old subtitle");
                speaker.discard(); restored.discard();
            }
        }
        helper.succeed();
    }

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
                    if(event.startsWith("predator_")&&!java.util.Set.of("remilia","flandre").contains(species)) continue;
                    var line=NativeDialogue.select(species,young,event,e.getRandom());
                    helper.assertTrue(line!=null,species+"/"+age+" missing "+event);
                    boolean originalSubtitle = java.util.Set.of("settlement_shun", "settlement_drive", "settlement_hurt", "settlement_assign", "settlement_submit",
                            "exam_nervous", "exam_answer", "exam_unsure", "exam_hide_fail", "exam_promise_wait", "exam_promise_fail", "exam_report", "exam_sorry", "exam_pass", "exam_fail", "badge_proud", "shop_appeal", "shop_bought", "shop_sold_sad", "shop_sold_proud", "wild_role_beg", "wild_role_provoke", "lesson_praised", "lesson_scolded", "badge_envy", "badge_admire", "date_meet", "date_fond", "permit_glad", "courtship_held", "badge_pinned", "badge_taken", "spray_drowsy", "basket_refuse", "basket_enter", "basket_exit", "wild_plea_keep", "pet_report_owner", "predator_restrain", "predator_obey", "predator_defy").contains(event)
                            && line.key().startsWith("dialogue.mcyukkuri.original.");
                    helper.assertTrue(!line.sound().isEmpty() || originalSubtitle, species+"/"+age+" missing voice for "+event);
                    helper.assertTrue(line.species().equals(species)&&line.age().equals(age),"No cross-species fallback: "+species);
                }
                helper.assertTrue(e.speak("hello") == (offset == 1), "Only the first nearby greeting starts: " + species);
                int sequence=e.speechSequence();helper.assertTrue(!e.speak("hello"),"Repeat blocked");
                helper.assertTrue(e.speak("wounded")&&e.speechSequence()==sequence+1,"Urgent interrupt");
                helper.assertTrue(e.getDialogueKey().startsWith("dialogue.mcyukkuri.native."+species+"."+age),"Correct species and age");
                var meal=NativeDialogue.select(species,young,"fed",e.getRandom());
                helper.assertTrue(meal.key().contains(species.equals("alice")?".native.":".original."),"Authorship remains explicit");
                checks.add(species+"/"+age+": all events, local greeting limit, duplicate, urgent interrupt, authorship");
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
        var routine = new SpeechTiming();
        helper.assertTrue(routine.admit(100, 50, 1), "Initial routine admitted");
        helper.assertTrue(!routine.admit(269, 50, 1), "Routine retains six seconds of quiet after ending");
        helper.assertTrue(routine.admit(270, 50, 1), "Routine resumes at quiet-gap boundary");
        var ambient = new SpeechTiming();
        helper.assertTrue(ambient.admit(100, 50, 0), "Initial ambient admitted");
        helper.assertTrue(!ambient.admit(349, 50, 1), "Ambient retains ten seconds of quiet after ending");
        helper.assertTrue(ambient.admit(350, 50, 0), "Ambient resumes at quiet-gap boundary");
        long deadline = reimu.speechEnd();
        helper.startSequence().thenWaitUntil(()->helper.assertTrue(reimu.tickCount>0,"Speaker becomes a ticking entity"))
                .thenExecute(()->helper.runAfterDelay(Math.max(2,deadline-level.getGameTime()+2), () -> {
            P0Control.write("dialogue-expiry-observation",java.util.Map.of("now",level.getGameTime(),"deadline",deadline,"entityTicks",reimu.tickCount,"alive",reimu.isAlive(),"key",reimu.getDialogueKey(),"start",reimu.speechStart(),"end",reimu.speechEnd()));
            helper.assertTrue(reimu.getDialogueKey().isEmpty(), "Speech expires without depending on expression timer; now="+level.getGameTime()+",deadline="+deadline+",entityTicks="+reimu.tickCount+",key="+reimu.getDialogueKey());
            helper.assertTrue(reimu.tickCount>0&&reimu.isAlive(),"Speaker ticks and survives empty fixture");
            reimu.discard();forceChunks(helper,false); helper.succeed();
        }));
    }
    @GameTest(templateNamespace=P0Mod.ID, template="empty", timeoutTicks=20, batch="dialogue_crowd")
    public static void nearbySpeech(GameTestHelper helper) {
        var level = helper.getLevel();
        var first = ModEntities.REIMU.get().create(level);
        var nearby = ModEntities.MARISA.get().create(level);
        var distant = ModEntities.REIMU.get().create(level);
        var origin = helper.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1)).getCenter();
        var speakers = java.util.List.of(first, nearby, distant);
        for (int i = 0; i < speakers.size(); i++) {
            var speaker = speakers.get(i);
            speaker.setNoAi(true); speaker.setNoGravity(true);
            speaker.setPos(origin.add(i == 2 ? 40 : i, 0, 0));
            level.addFreshEntity(speaker);
        }
        try {
            helper.assertTrue(first.speak("peer"), "First nearby speaker admitted");
            helper.assertTrue(!nearby.speak("peer") && nearby.speechSequence() == 0,
                    "Nearby routine speech emits neither subtitle nor audio sequence");
            helper.assertTrue(distant.speak("peer"), "Distant groups do not share the limit");
            helper.assertTrue(!new SpeechTiming().admit(nearby, first.speechEnd() + 79, 50, 1),
                    "Nearby speech retains four seconds of quiet after ending");
            helper.assertTrue(new SpeechTiming().admit(nearby, first.speechEnd() + 80, 50, 1),
                    "Nearby speech resumes at the shared quiet-gap boundary");
            helper.assertTrue(nearby.speak("fed"), "Important feedback bypasses nearby chatter and retry delay");
            helper.assertTrue(nearby.speak("wounded"), "Urgent feedback still interrupts");
        } finally {
            speakers.forEach(net.minecraft.world.entity.Entity::discard);
        }
        helper.succeed();
    }

    private static void forceChunks(GameTestHelper helper,boolean force){
        var pos=helper.absolutePos(net.minecraft.core.BlockPos.ZERO);
        for(int x=(pos.getX()>>4)-1;x<=(pos.getX()>>4)+1;x++)for(int z=(pos.getZ()>>4)-1;z<=(pos.getZ()>>4)+1;z++)GameTestChunks.setForced(helper.getLevel(),x,z,force);
    }
}
