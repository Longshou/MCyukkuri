package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;

/** Semantic state wins over a stale reaction; the five added atlases retain approved UV/geometry. */
public final class EcologyPresentation {
    /** Body motion for a stationary behavior; walking is chosen before this (see motions/variants/lore). */
    public static YukkuriAction action(String behavior) {
        return switch (behavior) {
            case Behaviors.UNUN_EXERCISE -> YukkuriAction.TAISO;
            case Behaviors.TOILET, Behaviors.GROUND_TOILET, Behaviors.TOILET_ACCIDENT, Behaviors.FIRST_TOILET_STIMULATED -> YukkuriAction.BRACE;
            case Behaviors.TAKE_FOOD, Behaviors.CHEW, Behaviors.FAMILY_CHEW, Behaviors.SWALLOW -> YukkuriAction.EAT;
            case Behaviors.RECEIVE_MASH -> YukkuriAction.DRINK;
            case Behaviors.MEAL_FINISH -> YukkuriAction.SHIAWASE;
            case Behaviors.DRINK -> YukkuriAction.DRINK;
            case Behaviors.SLEEP_ENTER, Behaviors.SLEEP, Behaviors.SHADE_REST -> YukkuriAction.SLEEP;
            case Behaviors.HOUSE_SLEEP, Behaviors.HOUSE_HIDE -> YukkuriAction.SLEEP;
            case Behaviors.HOUSE_PEEK, Behaviors.HOUSE_SEARCH_HOME, Behaviors.HOUSE_SNIFF -> YukkuriAction.LOOK_AROUND;
            case Behaviors.HOUSE_SHAPE -> YukkuriAction.BRACE;
            case Behaviors.HOUSE_TEND, Behaviors.HOUSE_LISTEN -> YukkuriAction.IDLE;
            case Behaviors.HOUSE_STARTLED -> YukkuriAction.STARTLE;
            case Behaviors.SEEK_WARMTH, Behaviors.WARM_UP -> YukkuriAction.TREMBLE;
            // status hub reactions: predator in view, rain or snow on the body
            case Behaviors.THREAT_WATCH -> YukkuriAction.LOOK_AROUND;
            case Behaviors.THREAT_HIDE, Behaviors.RAIN_WAIT, Behaviors.RAIN_HUDDLE -> YukkuriAction.TREMBLE;
            case Behaviors.RAIN_SHAKE -> YukkuriAction.SHAKE;
            case Behaviors.SEEK_SHADE -> YukkuriAction.LOOK_AROUND;
            case Behaviors.INDEPENDENCE_BUILD, Behaviors.NEST_BUILD, Behaviors.NEST_PLACE, Behaviors.NEST_SPREAD, Behaviors.NEST_PRESS, Behaviors.NEST_HARVEST -> YukkuriAction.BRACE;
            // These distinct care/learning states select matching authored clips in YukkuriModel.
            // The base enum remains the closest locomotion family so server animation timing stays shared.
            case Behaviors.FEED_CHILD, Behaviors.FIRST_TOILET_ASSIST -> YukkuriAction.NUZZLE;
            // Leaf swaddle care keeps the first-toilet rear-contact clip; harvesting and folding the leaves reuse the bedding press.
            case Behaviors.LEAF_SWADDLE_WRAP, Behaviors.SWADDLE_UNWRAP, Behaviors.SWADDLE_TOILET_ASSIST, Behaviors.SWADDLE_CLEAN,
                 Behaviors.SWADDLE_REWRAP -> YukkuriAction.NUZZLE;
            case Behaviors.LEAF_SWADDLE_HARVEST, Behaviors.LEAF_SWADDLE_FOLD -> YukkuriAction.BRACE;
            case Behaviors.LEAF_SWADDLE_SEEK, Behaviors.LEAF_SWADDLE_APPROACH -> YukkuriAction.LOOK_AROUND;
            case Behaviors.FIRST_TOILET_SEEK -> YukkuriAction.LOOK_AROUND;
            case Behaviors.NEWBORN_CRAWL, Behaviors.HAT_WALK -> YukkuriAction.WADDLE;
            case Behaviors.LEARN_JUMP -> YukkuriAction.HOP;
            case Behaviors.NEWBORN_WAIT, Behaviors.FIRST_TOILET_WAIT, Behaviors.CARE_FINISH, Behaviors.LEARN_WATCH -> YukkuriAction.IDLE;
            case Behaviors.NEST_GATHER -> YukkuriAction.LOOK_AROUND;
            case Behaviors.INDEPENDENCE_FAREWELL, Behaviors.INDEPENDENCE_VISIT -> YukkuriAction.GREET;
            case Behaviors.INDEPENDENCE_GATHER, Behaviors.INDEPENDENCE_RETURN -> YukkuriAction.LOOK_AROUND;
            case Behaviors.WAKE -> YukkuriAction.WAKE;
            case Behaviors.NUZZLE -> YukkuriAction.NUZZLE;
            case Behaviors.COURTSHIP -> YukkuriAction.LOOK_AROUND;
            case Behaviors.PAIRING_ACCEPT -> YukkuriAction.GREET;
            case Behaviors.PAIRING -> YukkuriAction.NUZZLE;
            case Behaviors.COMFORT_LICK, Behaviors.SHARD_LICK -> YukkuriAction.COMFORT_LICK;
            // headwear: gathering the pieces of a shattered accessory; a sanction declared, then carried out by bullying
            case Behaviors.SHARD_SEEK, Behaviors.SANCTION_APPROACH -> YukkuriAction.LOOK_AROUND;
            case Behaviors.SANCTION_DECLARE, Behaviors.PROTECT_TAUNT -> YukkuriAction.PUFF;
            case Behaviors.SANCTION_BULLY -> YukkuriAction.PUSH;
            case Behaviors.SELF_DEFENSE -> YukkuriAction.PUSH;
            case Behaviors.COMFORT_RECEIVE, Behaviors.HURT_CRY -> YukkuriAction.DROOP;
            // The final offering remains a running SelfOffering goal during the ordinary short dying state.  It uses
            // the approved withdrawn pose instead of inventing a separate death animation.
            case Behaviors.SELF_OFFER_INSIST -> YukkuriAction.DROOP;
            case Behaviors.SING -> YukkuriAction.SING;
            case Behaviors.LISTEN_SONG, Behaviors.EXERCISE_WATCH -> YukkuriAction.SWAY;
            case Behaviors.SEARCH_FAMILY, Behaviors.WAIT_CHILD, Behaviors.STAY_HOME -> YukkuriAction.LOOK_AROUND;
            case Behaviors.TEND_BROOD -> YukkuriAction.BROOD;
            case Behaviors.LABOR -> YukkuriAction.LABOR;
            case Behaviors.POSTPARTUM -> YukkuriAction.POSTPARTUM;
            case Behaviors.MIDWIFE -> YukkuriAction.MIDWIFE;
            case Behaviors.HAT_OFF, Behaviors.HAT_ON, Behaviors.HAT_HOLD, Behaviors.HAT_OFF_SELF, Behaviors.HAT_ON_SELF -> YukkuriAction.byId(behavior).orElse(YukkuriAction.IDLE);
            case Behaviors.WATER_IMPACT -> YukkuriAction.WATER_PLUNGE;
            case Behaviors.ESCAPE_WATER, Behaviors.STRUGGLE, Behaviors.WATER_STRUGGLE -> YukkuriAction.WATER_STRUGGLE;
            case Behaviors.WATER_EXHAUSTED -> YukkuriAction.WATER_GASP;
            case Behaviors.WATER_RECOVER -> YukkuriAction.WATER_RECOVER;
            case Behaviors.HAT_SLIPPING -> YukkuriAction.HAT_JOSTLE;
            default -> YukkuriAction.IDLE;
        };
    }
    /** Sumo and bullying push; a defender keeps the threat puff. */
    public static YukkuriAction group(String activity) {
        return switch (activity) {
            case Behaviors.SUMO_PUSH, Behaviors.BULLY_PEER, Behaviors.SELF_DEFENSE -> YukkuriAction.PUSH;
            case Behaviors.SUMO_PREPARE -> YukkuriAction.BRACE;
            default -> YukkuriAction.PUFF;
        };
    }
    /** Short body reaction to the dialogue event of showReaction, used while otherwise idle. */
    public static YukkuriAction reaction(String event) {
        return switch (event) {
            case "hello", "peer", "known_peer", "caregiver", "introduced", "gentle" -> YukkuriAction.GREET;
            case "afraid", "wounded", "water_help", "family_alarm_flee" -> YukkuriAction.TREMBLE;
            case "boat_rock_startle" -> YukkuriAction.STARTLE;
            case "lost_accessory", "family_died", "lost_parent", "cold", "settlement_hurt", "settlement_submit" -> YukkuriAction.DROOP;
            case "washed" -> YukkuriAction.SHAKE;
            case "toy" -> YukkuriAction.HOP;
            case "newborn", "hatched" -> YukkuriAction.FIRST_WAKE;
            case "birth" -> YukkuriAction.POSTPARTUM;
            case "birth_lost" -> YukkuriAction.BEREAVED;
            case "hat_jostle", "hat_scramble", "hat_slipping" -> YukkuriAction.HAT_JOSTLE;
            case "returned_accessory" -> YukkuriAction.SHIAWASE;
            case "expecting" -> YukkuriAction.SHIAWASE;
            default -> YukkuriAction.IDLE;
        };
    }
    public static String expression(YukkuriEntity e, String fallback) {
        if(!e.isPassenger()&&e.isInWaterOrBubble())return "teary";
        if (e.life().isSleeping() || e.family().awaitingLanding()) return "sleep";
        if (e.isPanicking() || e.hurtTime > 0) return "surprise";
        // Being licked or just comforted eases the crying; it changes the face only, not the wound.
        if (e.woundComfort().receiving() || e.woundComfort().soothed()) {
            if (e.body().condition().hasSkinOrBurnDamage()) return e.woundComfort().inContact() ? "closed_smile" : "teary";
        }
        if (e.body().condition().needsPainExpression() || e.life().spicyTicks() > 0) return "cry";
        if (e.life().constipated() && e.life().bladder() >= 70) return "teary";
        String expression = switch (e.getBehaviorId()) {
            case Behaviors.COMFORT_LICK -> "teary";
            case Behaviors.NUZZLE, Behaviors.LISTEN_SONG, Behaviors.EXERCISE_WATCH -> "closed_smile";
            case Behaviors.PAIRING_ACCEPT -> "happy";
            case Behaviors.PAIRING -> "closed_smile";
            case Behaviors.MEAL_FINISH -> "satisfied";
            case Behaviors.INDEPENDENCE_FAREWELL, Behaviors.INDEPENDENCE_VISIT -> "happy";
            case Behaviors.INDEPENDENCE_BUILD, Behaviors.NEST_BUILD, Behaviors.NEST_PLACE, Behaviors.NEST_SPREAD, Behaviors.NEST_PRESS, Behaviors.NEST_HARVEST -> "chewing_closed";
            case Behaviors.FEED_CHILD -> "happy";
            case Behaviors.FIRST_TOILET_SEEK, Behaviors.FIRST_TOILET_WAIT -> "teary";
            case Behaviors.FIRST_TOILET_STIMULATED -> "surprise";
            case Behaviors.FIRST_TOILET_ASSIST -> "closed_smile";
            case Behaviors.LEAF_SWADDLE_HARVEST, Behaviors.LEAF_SWADDLE_FOLD -> "chewing_closed";
            case Behaviors.LEAF_SWADDLE_WRAP, Behaviors.SWADDLE_REWRAP, Behaviors.SWADDLE_TOILET_ASSIST, Behaviors.SWADDLE_CLEAN,
                 Behaviors.SWADDLE_RECEIVE -> "closed_smile";
            case Behaviors.SWADDLE_UNWRAP -> "neutral";
            case Behaviors.NEWBORN_CRAWL, Behaviors.LEARN_JUMP -> "happy";
            case Behaviors.RECEIVE_MASH -> "open_smile";
            case Behaviors.SING -> (e.level().getGameTime()-e.getBehaviorStartTick())%10 < 7 ? "open_smile" : "closed_smile";
            case Behaviors.UNUN_EXERCISE -> "happy";
            case Behaviors.SLEEP_ENTER -> "sleepy";
            case Behaviors.HOUSE_SLEEP -> "sleep";
            case Behaviors.HOUSE_HIDE -> e.getDeltaMovement().horizontalDistanceSqr()>.0001?"neutral":"sleepy";
            case Behaviors.HOUSE_PEEK -> "neutral";
            case Behaviors.HOUSE_SEARCH_HOME -> "teary";
            case Behaviors.HOUSE_STARTLED -> "surprise";
            case Behaviors.HOUSE_TEND -> "closed_smile";
            case Behaviors.HOUSE_LISTEN -> "happy";
            case Behaviors.HOUSE_PICKUP, Behaviors.HOUSE_CARRY, Behaviors.HOUSE_SHAPE -> "chewing_closed";
            case Behaviors.HOUSE_SNIFF -> "neutral";
            case Behaviors.HOUSE_DEPOSIT -> "satisfied";
            case Behaviors.SEEK_WARMTH, Behaviors.WARM_UP -> "teary";
            case Behaviors.THREAT_WATCH, Behaviors.THREAT_RETREAT -> "surprise";
            case Behaviors.HURT_CRY -> "cry";
            case Behaviors.THREAT_HIDE, Behaviors.RAIN_RUN, Behaviors.RAIN_WAIT, Behaviors.RAIN_HUDDLE -> "teary";
            case Behaviors.RAIN_SHAKE -> "neutral";
            case Behaviors.SEEK_SHADE, Behaviors.SHADE_REST -> "sleepy";
            case Behaviors.WAKE -> "yawn";
            case Behaviors.TOILET, Behaviors.GROUND_TOILET -> "chewing_closed";
            case Behaviors.TOILET_ACCIDENT -> "teary";
            case Behaviors.SEEK_TOILET -> e.life().bladder() >= 90 ? "teary" : "neutral";
            case Behaviors.FORAGE_SEEK -> "drool";
            case Behaviors.TAKE_FOOD, Behaviors.SEEK_FOOD -> "drool";
            case Behaviors.WATER_IMPACT -> "cry";
            case Behaviors.WATER_STRUGGLE, Behaviors.WATER_EXHAUSTED, Behaviors.WATER_RECOVER -> "teary";
            case Behaviors.HAT_SLIPPING -> "teary";
            case Behaviors.CHEW, Behaviors.FAMILY_CHEW -> (e.level().getGameTime()-e.getBehaviorStartTick())%8 < 4 ? "chewing" : "chewing_closed";
            case Behaviors.SWALLOW, Behaviors.DRINK -> "chewing_closed";
            case Behaviors.BULLY_PEER, Behaviors.SELF_DEFENSE, Behaviors.PROTECT_PEER, Behaviors.SUMO_PUSH, Behaviors.SANCTION_APPROACH, Behaviors.SANCTION_DECLARE, Behaviors.SANCTION_BULLY -> "angry";
            case Behaviors.SHARD_SEEK, Behaviors.SHARD_LICK -> "teary";
            default -> e.contactPose().equals("RUB") ? "closed_smile"
                    : e.life().nutrition() < 30 ? "drool" : e.life().energy() < 25 ? "sleepy"
                    : e.dailyLife().affectionNeed() >= 80 && fallback.equals("neutral") ? "teary" : fallback;
        };
        // Predator authoring is a separate task and does not use these ordinary-species face atlases.
        if(e.profile().dangerToYukkuri())return switch(expression){
            case "closed_smile", "satisfied", "open_smile" -> "happy";
            case "chewing", "chewing_closed" -> "neutral";
            default -> expression;
        };
        return expression;
    }
    private EcologyPresentation() {}
}
