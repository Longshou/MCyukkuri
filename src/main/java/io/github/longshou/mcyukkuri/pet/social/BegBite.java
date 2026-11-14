package io.github.longshou.mcyukkuri.pet.social;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildEventData.Phase;
import io.github.longshou.mcyukkuri.event.WildEventData.Scene;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.MouthOccupancy.Purpose;
import io.github.longshou.mcyukkuri.settlement.Rumours;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import static io.github.longshou.mcyukkuri.entity.YukkuriAction.*;

/** One live encounter; the food stays in the actors' existing mouth occupancy between contact and chewing. */
public final class BegBite {
    static final int MAX_TICKS = 1200;
    private enum Beat { APPROACH, HESITATE, PLEAD, ANSWER, GUARD, TURN_AWAY, CIRCLE, STAMP, CRY, CONTACT, PASS, BACK, EAT, HELP, LOOK, LEAVE }

    private final UUID pet;
    private final double distance;
    private Vec3 stand;
    private Vec3 chase;
    private Beat beat = Beat.APPROACH;
    private UUID human;
    private ItemStack petFood, strayFood = ItemStack.EMPTY;
    private String result = "";
    private boolean snatch;
    private int duration;

    private BegBite(YukkuriEntity pet, double distance, Vec3 stand) {
        this.pet = pet.getUUID();
        this.distance = distance;
        this.stand = stand;
    }

    static boolean ready(YukkuriEntity body) {
        return BadgeArcs.usable(body) && !body.isLeashed() && !body.isCommandPlaying() && !body.life().hasUrgentNonFoodNeed()
                && !body.family().stemFoodUrgent() && body.life().nutrition() >= 12 && !body.body().sick()
                && body.body().condition().readyForWildEvent();
    }

    static String check(ServerLevel level, YukkuriEntity stray, YukkuriEntity pet) {
        if (!stray.status().identity().free()) return "observer_owned";
        if (!pet.pet().isPet() || pet.profile().dangerToYukkuri() || pet.profile().houseBody()) return "subject_not_pet";
        if (!ready(stray) || !ready(pet)) return "begging_unavailable";
        if (!pet.family().canEatIndependently() || !pet.life().canOfferMeal()) return "no_shareable_serving";
        if (!BadgeArcPolicy.beggingHunger(stray.life().nutrition()) || !stray.life().canEat()) return "observer_not_hungry";
        if (!stray.component(YukkuriComponents.MOUTH).freeForExternalWork() || !stray.life().seeksFood(pet.life().mouth()))
            return "observer_cannot_take_serving";
        var seen = stray.pet().badgeLedger().get(pet.getUUID(), level.getGameTime());
        return seen != null && seen.sinceArc(level.getGameTime()) < 24000 ? "pair_cooling" : null;
    }

    static BegBite plan(YukkuriEntity stray, YukkuriEntity pet) {
        var a = SocialDisposition.contact(stray, pet);
        var b = SocialDisposition.contact(pet, stray);
        double boldness = stray.life().personality().boldness() / 100.0;
        double space = Math.clamp(.55 + .35 * Math.max(a.wariness(), b.wariness()) - .25 * boldness - .15 * a.insistence(), 0, 1);
        double distance = Math.max(FamilyFeedingContact.mouthDistance(stray, pet) + .35,
                (stray.getBbWidth() + pet.getBbWidth()) * (.75 + .35 * space) + .30 + .70 * space);
        Vec3 stand = SurfaceApproach.beside(stray, pet, distance, .5);
        return stand == null ? null : new BegBite(pet, distance, stand);
    }

    void start(ServerLevel level, YukkuriEntity stray, YukkuriEntity pet) {
        long now = level.getGameTime();
        BadgeArcs.note(stray, pet, 0, now);
        stray.pet().badgeLedger().arc(pet.getUUID(), BadgeArcPolicy.Arc.BEG_BITE.id, now);
        petFood = mouth(pet);
        GameplayLog.event(stray, "badge", "beg approach pet={} stand={} spacing={} item={}", this.pet, stand, distance, petFood.getItem());
        stray.showReaction("open_drool", "", ClipMarkers.of(LOOK_AROUND.id()).length());
    }

    private static ItemStack mouth(YukkuriEntity e) { return e.component(YukkuriComponents.MOUTH).stack(Purpose.KINDNESS); }

    boolean holdsFood(YukkuriEntity e) {
        return !mouth(e).isEmpty() && mouth(e) == (pet.equals(e.getUUID()) ? petFood : strayFood);
    }

    void advance(ServerLevel level, Scene scene, YukkuriEntity stray, YukkuriEntity pet) {
        if (!ready(stray) || !ready(pet) || !stray.status().identity().free() || !pet.pet().isPet()) {
            WildEvents.finish(level, scene, Phase.INTERRUPTED, "begging_unavailable");
            return;
        }
        if (beat != Beat.EAT && (stray.life().mealTicks() > 0
                || stray.life().nutrition() >= 50 && !result.equals("shared") && !result.equals("snatched"))) {
            stray.showReaction("happy", "", ClipMarkers.of(BOW.id()).length());
            stray.speakCue("beg_thanks");
            WildEvents.finish(level, scene, Phase.INTERRUPTED, "beggar_fed");
            return;
        }
        if (!petFood.isEmpty() && mouth(pet) != petFood || !strayFood.isEmpty() && mouth(stray) != strayFood) {
            WildEvents.finish(level, scene, Phase.INTERRUPTED, "serving_changed");
            return;
        }
        int elapsed = scene.ticks - scene.sourceCount;
        switch (beat) {
            case APPROACH -> {
                if (arrived(stray, pet)) {
                    GameplayLog.event(stray, "badge", "beg social stand pet={} spacing={} gap={}", this.pet, distance, gap(stray, pet));
                    pose(scene, Beat.HESITATE, stray, LOOK_AROUND, "side_teary", "", 1);
                }
                else walkDeadline(level, scene, stray, "beggar_unreached");
            }
            case HESITATE -> {
                if (elapsed < duration) return;
                pose(scene, Beat.PLEAD, stray, BEG, "teary_smile", "beg_ask", 4);
                pet.showReaction(STARTLE, "surprise", "");
            }
            case PLEAD -> {
                if (elapsed >= duration) answer(level, scene, stray, pet);
            }
            case ANSWER -> {
                if (elapsed < duration) return;
                if (human != null) {
                    var helper = level.getEntity(human);
                    if (helper instanceof LivingEntity live && live.isAlive()) {
                        walk(scene, Beat.HELP, SurfaceApproach.beside(pet, helper, footDistance(pet, helper), .5));
                    } else guard(scene, stray, pet);
                } else contact(scene, stray, pet);
            }
            case GUARD -> {
                if (elapsed >= duration) refusal(level, scene, stray, pet);
            }
            case TURN_AWAY -> {
                if (stand != null && SurfaceApproach.arrived(pet, stand)) {
                    var radial = stray.position().subtract(pet.position()).multiply(1, 0, 1).normalize();
                    var side = pet.position().add(radial.yRot((float) (Math.PI / 3)).scale(distance));
                    stray.showReaction("teary", "", ClipMarkers.of(LOOK_AROUND.id()).length());
                    stray.speakCue("beg_plead_again");
                    walk(scene, Beat.CIRCLE, SurfaceApproach.floor(stray, side));
                } else walkDeadline(level, scene, pet, "withdrawal_unreached");
            }
            case CIRCLE -> {
                if (arrived(stray, pet)) {
                    GameplayLog.event(stray, "badge", "beg circled pet={} gap={}", this.pet, gap(stray, pet));
                    pose(scene, Beat.STAMP, stray, YukkuriAction.STAMP, "down_angry", "beg_pester", 1);
                }
                else walkDeadline(level, scene, stray, "circle_unreached");
            }
            case STAMP -> {
                if (elapsed >= duration) pose(scene, Beat.CRY, stray, DROOP, "open_cry", "beg_cry", 2);
            }
            case CRY -> {
                if (elapsed >= duration) lastLook(scene, stray, pet);
            }
            case CONTACT -> {
                if (stand != null && SurfaceApproach.arrived(stray, stand) && FamilyFeedingContact.mouthsMeet(pet, stray)) {
                    pose(scene, Beat.PASS, snatch ? stray : pet, snatch ? SNATCH_PULL : OFFER_BITE,
                            snatch ? "angry" : "happy", "", 1);
                } else walkDeadline(level, scene, stray, "contact_unreached");
            }
            case PASS -> {
                if (elapsed == duration / 2) {
                    if (!FamilyFeedingContact.mouthsMeet(pet, stray) || !pet.life().passCarriedMeal(stray, !snatch)) {
                        WildEvents.finish(level, scene, Phase.INTERRUPTED, "contact_lost");
                        return;
                    }
                    commit(level, stray, pet);
                    if (snatch) {
                        pet.showReaction(STARTLE, "horrified", "");
                        pet.speakCue("beg_robbed");
                    }
                }
                if (elapsed >= duration) walk(scene, Beat.BACK, away(stray, pet, snatch ? distance * 1.8 : distance));
            }
            case BACK -> {
                if (arrived(stray, pet)) {
                    setBeat(scene, Beat.EAT);
                    if (snatch) {
                        pet.showReaction(DROOP, "open_cry", "");
                        pet.speakCue("beg_grudge");
                    } else {
                        stray.showReaction(SocialDisposition.selfEsteem(stray) < 50 ? BOW : GREET, "happy", "");
                        stray.speakCue("beg_thanks");
                    }
                } else walkDeadline(level, scene, stray, "release_space_unreached");
            }
            case EAT -> {
                if (!strayFood.isEmpty() && elapsed >= ClipMarkers.of(GREET.id()).length()) {
                    if (!stray.life().finishCarriedMeal(snatch ? null : pet.getUUID())) {
                        WildEvents.finish(level, scene, Phase.INTERRUPTED, "meal_refused");
                        return;
                    }
                    strayFood = ItemStack.EMPTY;
                    if (!petFood.isEmpty() && pet.life().finishCarriedMeal(null)) petFood = ItemStack.EMPTY;
                }
                if (strayFood.isEmpty() && stray.life().mealTicks() == 0 && pet.life().mealTicks() == 0) lastLook(scene, stray, pet);
            }
            case HELP -> {
                var helper = level.getEntity(human);
                if (!(helper instanceof LivingEntity live) || !live.isAlive() || live.isSpectator() || live.distanceToSqr(pet) > 144) {
                    WildEvents.finish(level, scene, Phase.INTERRUPTED, "helper_left");
                    return;
                }
                if (stand != null && SurfaceApproach.arrived(pet, stand) && pet.distanceTo(live) <= footDistance(pet, live) + .45) {
                    if (live instanceof Player player && pet.pet().ownedBy(player.getUUID())) BadgePraise.start(pet, player);
                    result = "asked_help";
                    GameplayLog.event(pet, "badge", "beg help reached human={} stray={}", human, stray.getUUID());
                    pet.showReaction(HIDE_FACE, "teary", "", ClipMarkers.of(DROOP.id()).length());
                    pet.speakCue("beg_help");
                    if (SocialDisposition.contact(stray, live).wariness() < .5) stray.speakCue("beg_curse");
                    lastLook(scene, stray, pet);
                } else walkDeadline(level, scene, pet, "helper_unreached");
            }
            case LOOK -> {
                if (elapsed >= duration) {
                    walk(scene, Beat.LEAVE, away(stray, pet, Math.max(distance * 2.3, stray.distanceTo(pet) + distance)));
                    stray.showReaction(YukkuriAction.WALK_LOOK_BACK, departureFace(), "",
                            ClipMarkers.of(YukkuriAction.WALK_LOOK_BACK.id()).length());
                }
            }
            case LEAVE -> {
                if (arrived(stray, pet)) WildEvents.finish(level, scene, result.equals("refused") ? Phase.REFUSED : Phase.COMPLETE, result);
                else walkDeadline(level, scene, stray, "departure_unreached");
            }
        }
    }

    private void answer(ServerLevel level, Scene scene, YukkuriEntity stray, YukkuriEntity pet) {
        var p = pet.life().personality();
        var view = SocialDisposition.contact(pet, stray);
        boolean kin = Bonds.careKin(pet, stray);
        int share = p.shareScore(pet.life().nutrition(), stray.life().nutrition(), kin, view.closeness());
        var helper = helper(level, pet);
        var answer = BadgeArcPolicy.begAnswer(pet.life().nutrition(), share, view.wariness(), p.boldness(), p.attachment(), kin, helper != null);
        GameplayLog.event(pet, "badge", "beg answer stray={} answer={} hunger={} esteem={} wariness={} share={} closeness={}",
                stray.getUUID(), answer, pet.life().nutrition(), view.esteem(), view.wariness(), share, view.closeness());
        switch (answer) {
            case SHARE -> pose(scene, Beat.ANSWER, pet, THINK, "happy", "beg_offer", 2);
            case REFUSE -> guard(scene, stray, pet);
            case HELP -> {
                human = helper.getUUID();
                pose(scene, Beat.ANSWER, pet, TREMBLE, "horrified", "", 8);
            }
        }
    }

    private void guard(Scene scene, YukkuriEntity stray, YukkuriEntity pet) {
        human = null;
        pose(scene, Beat.GUARD, pet, pet.life().personality().selfControl() >= 55 ? STAND_GROUND : PUFF,
                SocialDisposition.selfEsteem(pet) >= 50 ? "cheek_puff" : "pursed", "beg_refuse", 2);
        stray.showReaction(SHY, "teary_frown", "");
    }

    private void refusal(ServerLevel level, Scene scene, YukkuriEntity stray, YukkuriEntity pet) {
        var view = SocialDisposition.contact(stray, pet);
        var p = stray.life().personality();
        double confidence = SocialDisposition.confidence(view.esteem());
        boolean people = level.players().stream().anyMatch(player -> player.isAlive() && !player.isSpectator()
                && player.distanceToSqr(stray) <= 64 && stray.hasLineOfSight(player)
                && SocialDisposition.contact(stray, player).response(false) == SocialDisposition.Response.GUARDED);
        boolean young = !level.getEntitiesOfClass(YukkuriEntity.class, stray.getBoundingBox().inflate(4),
                child -> child.isAlive() && !child.family().adult() && WildEvents.childOf(stray, child)).isEmpty();
        var refusal = BadgeArcPolicy.begRefusal(stray.life().nutrition(), view.wariness(), confidence, view.insistence(),
                p.relation(pet.getUUID()).fearBand(), p.selfControl(), p.escalationScore(),
                !people && !young && stray.getBbWidth() >= pet.getBbWidth() * .9);
        GameplayLog.event(stray, "badge", "beg refusal pet={} response={} nutrition={} esteem={} wariness={} confidence={} insistence={} harshness={}",
                this.pet, refusal, stray.life().nutrition(), view.esteem(), view.wariness(), confidence, view.insistence(), p.escalationScore());
        result = "refused";
        BadgeArcs.note(stray, pet, BadgeLedger.SNUBBED, level.getGameTime());
        BadgeSocial.nudge(YukkuriWorldData.get(level), stray.getUUID(), this.pet, -1);
        switch (refusal) {
            case LEAVE -> pose(scene, Beat.CRY, stray, DROOP, "teary_frown", "", 1);
            case PESTER -> {
                pet.showReaction(TURN, "cold", "");
                walk(scene, Beat.TURN_AWAY, away(pet, stray, pet.distanceTo(stray) + pet.getBbWidth()));
            }
            case SNATCH -> {
                snatch = true;
                stray.showReaction("angry", "", ClipMarkers.of(LOOK_AROUND.id()).length());
                contact(scene, stray, pet);
            }
        }
    }

    private void contact(Scene scene, YukkuriEntity stray, YukkuriEntity pet) {
        walk(scene, Beat.CONTACT, FamilyFeedingContact.approachSpot(stray, pet));
    }

    private void commit(ServerLevel level, YukkuriEntity stray, YukkuriEntity pet) {
        petFood = mouth(pet);
        strayFood = mouth(stray);
        long now = level.getGameTime();
        if (snatch) {
            result = "snatched";
            BadgeArcs.note(stray, pet, BadgeLedger.ROBBED, now);
            BadgeArcs.note(pet, stray, BadgeLedger.ROBBED_BY, now);
            var world = YukkuriWorldData.get(level);
            var before = world.relation(this.pet, stray.getUUID());
            var after = world.interact(this.pet, stray.getUUID(), -20, 18);
            pet.pet().addStress(3);
            BadgeSocial.spread(level, stray, Rumours.THIEF, stray.getUUID(), 2);
            GameplayLog.event(pet, "badge", "beg grudge stray={} closeness={}->{} fear={}->{}",
                    stray.getUUID(), before.closeness(), after.closeness(), before.fear(), after.fear());
        } else {
            result = "shared";
            BadgeArcs.note(stray, pet, BadgeLedger.FED_BY, now);
            BadgeArcs.note(pet, stray, BadgeLedger.HELPED, now);
            UUID owner = pet.pet().owner();
            BadgeSocial.spread(level, stray, Rumours.BADGE_FED, owner, 1);
            BadgeArcs.tell(level, BadgeSocial.ownerWithin(level, pet, 24), "pet.mcyukkuri.badge.shared", pet, false);
        }
        GameplayLog.event(stray, "badge", "beg transfer pet={} result={} received={} retained={} gap={}", this.pet,
                result, strayFood.getItem(), petFood.getItem(), gap(stray, pet));
    }

    void move(ServerLevel level, YukkuriEntity actor, YukkuriEntity stray, YukkuriEntity pet) {
        if (actor == stray && (beat == Beat.APPROACH || beat == Beat.CIRCLE || beat == Beat.CONTACT || beat == Beat.BACK || beat == Beat.LEAVE)) {
            if (beat == Beat.BACK && stand != null && gap(stray, pet) < .30) {
                // The food has changed hands. Turn at the contact stand before issuing forward gait.
                float yaw = SurfaceApproach.yaw(stray.position(), stand);
                if (Math.abs(Mth.wrapDegrees(yaw - stray.getYRot())) > 30) {
                    SurfaceApproach.halt(stray);
                    SurfaceApproach.face(stray, yaw);
                    return;
                }
            }
            travel(stray, pet, beat == Beat.LEAVE ? .7 : snatch ? 1.2 : .9);
            if (beat != Beat.BACK && (beat != Beat.LEAVE
                    || stray.position().subtract(pet.position()).horizontalDistance() < distance * 1.65)) look(stray, pet);
        } else if (actor == pet && beat == Beat.HELP && level.getEntity(human) instanceof LivingEntity helper) {
            travel(pet, helper, 1.2);
        } else if (actor == pet && beat == Beat.TURN_AWAY) {
            travel(pet, stray, .7);
        } else if (actor == pet && snatch && beat == Beat.BACK && pet.life().personality().boldness() >= 55) {
            look(pet, stray);
            if (chase == null && gap(pet, stray) >= distance) {
                chase = SurfaceApproach.floor(pet, stray.position().add(pet.position().subtract(stray.position())
                        .multiply(1, 0, 1).normalize().scale(distance)));
                if (chase != null) {
                    pet.showReaction("angry", "", ClipMarkers.of(DROOP.id()).length());
                    pet.speakCue("beg_chase");
                }
            }
            if (chase != null) SurfaceApproach.walkResult(pet, chase, stray, 1.15);
        } else {
            SurfaceApproach.halt(actor);
            Entity watch = actor == stray ? pet : stray;
            if (actor == pet && (beat == Beat.CIRCLE || beat == Beat.STAMP || beat == Beat.CRY) && !snatch) {
                SurfaceApproach.face(pet, SurfaceApproach.yaw(stray.position(), pet.position()));
            } else look(actor, watch);
        }
    }

    private void travel(YukkuriEntity actor, Entity peer, double speed) {
        if (stand != null) SurfaceApproach.walkResult(actor, stand, peer, speed);
    }

    private boolean arrived(YukkuriEntity stray, YukkuriEntity pet) {
        return stand != null && SurfaceApproach.arrived(stray, stand) && gap(stray, pet) >= .30;
    }

    private void walkDeadline(ServerLevel level, Scene scene, YukkuriEntity actor, String reason) {
        if (stand == null || SurfaceApproach.lastResult(actor, stand) == SurfaceApproach.Result.BLOCKED
                || scene.ticks - scene.sourceCount >= ClipMarkers.of(FLEE.id()).length() * 2)
            WildEvents.finish(level, scene, Phase.INTERRUPTED, reason);
    }

    private void lastLook(Scene scene, YukkuriEntity stray, YukkuriEntity pet) {
        pose(scene, Beat.LOOK, stray, snatch || result.equals("shared") ? SHIAWASE : DROOP, departureFace(), "", 1);
        if (!result.equals("asked_help") && pet.life().mealTicks() == 0) pet.showReaction(TURN, result.equals("shared") ? "closed_smile" : "pursed", "");
    }

    private String departureFace() {
        return snatch ? "smug" : result.equals("shared") ? "closed_smile" : "side_teary";
    }

    private Vec3 away(YukkuriEntity stray, YukkuriEntity pet, double radius) {
        var radial = stray.position().subtract(pet.position()).multiply(1, 0, 1).normalize();
        return SurfaceApproach.floor(stray, pet.position().add(radial.scale(radius)));
    }

    private static double gap(YukkuriEntity a, YukkuriEntity b) {
        return a.position().subtract(b.position()).horizontalDistance() - (a.getBbWidth() + b.getBbWidth()) * .5;
    }

    private static double footDistance(YukkuriEntity pet, Entity human) { return (pet.getBbWidth() + human.getBbWidth()) * .5 + .30; }

    private static LivingEntity helper(ServerLevel level, YukkuriEntity pet) {
        Entity owner = pet.pet().owner() == null ? null : level.getEntity(pet.pet().owner());
        if ((owner instanceof Player || owner instanceof HumanNpc) && owner.isAlive() && !owner.isSpectator()
                && owner.distanceToSqr(pet) <= 144 && pet.hasLineOfSight(owner)) return (LivingEntity) owner;
        return level.getNearestPlayer(pet.getX(), pet.getY(), pet.getZ(), 8,
                e -> e instanceof Player player && player.isAlive() && !player.isSpectator()
                        && Bonds.trustedPlayer(pet, player.getUUID()) && pet.hasLineOfSight(player));
    }

    private static void look(YukkuriEntity actor, Entity peer) {
        actor.getLookControl().setLookAt(peer, 30, 30);
    }

    private void pose(Scene scene, Beat next, YukkuriEntity actor, YukkuriAction clip, String face, String line, int repeats) {
        setBeat(scene, next);
        duration = ClipMarkers.of(clip.id()).length() * repeats;
        actor.showReaction(clip, face, "", duration);
        if (!line.isEmpty()) actor.speakCue(line);
    }

    private void walk(Scene scene, Beat next, Vec3 target) {
        setBeat(scene, next);
        stand = target;
    }

    private void setBeat(Scene scene, Beat next) {
        beat = next;
        scene.sourceCount = scene.ticks;
    }
}
