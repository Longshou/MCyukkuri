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
            case Behaviors.TOILET, Behaviors.GROUND_TOILET, Behaviors.BOAT_TOILET, Behaviors.TOILET_ACCIDENT, Behaviors.FIRST_TOILET_STIMULATED -> YukkuriAction.BRACE;
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
            case Behaviors.FIRST_TOILET_SEEK -> YukkuriAction.LOOK_AROUND;
            case Behaviors.NEWBORN_CRAWL, Behaviors.HAT_WALK -> YukkuriAction.WADDLE;
            case Behaviors.LEARN_JUMP -> YukkuriAction.HOP;
            case Behaviors.NEWBORN_WAIT, Behaviors.FIRST_TOILET_WAIT, Behaviors.CARE_FINISH, Behaviors.LEARN_WATCH -> YukkuriAction.IDLE;
            case Behaviors.BOAT_CARRY_YOUNG, Behaviors.BOAT_BE_CARRIED -> YukkuriAction.BRACE;
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
            case Behaviors.SANCTION_DECLARE -> YukkuriAction.PUFF;
            case Behaviors.SANCTION_BULLY -> YukkuriAction.PUSH;
            case Behaviors.COMFORT_RECEIVE -> YukkuriAction.DROOP;
            case Behaviors.SING -> YukkuriAction.SING;
            case Behaviors.LISTEN_SONG, Behaviors.EXERCISE_WATCH -> YukkuriAction.SWAY;
            case Behaviors.SEARCH_FAMILY, Behaviors.WAIT_CHILD, Behaviors.STAY_HOME -> YukkuriAction.LOOK_AROUND;
            case Behaviors.TEND_BROOD -> YukkuriAction.BROOD;
            case Behaviors.LABOR -> YukkuriAction.LABOR;
            case Behaviors.POSTPARTUM -> YukkuriAction.POSTPARTUM;
            case Behaviors.MIDWIFE -> YukkuriAction.MIDWIFE;
            case Behaviors.BOAT_LAUNCH, Behaviors.VOYAGE_LAUNCH -> YukkuriAction.HAT_OFF_SELF_AQ;
            case Behaviors.VOYAGE_PREPARE, Behaviors.VOYAGE_WAIT_PARENT, Behaviors.VOYAGE_WATCH -> YukkuriAction.LOOK_AROUND;
            case Behaviors.VOYAGE_PRAISE -> YukkuriAction.GREET;
            case Behaviors.VOYAGE_PROUD -> YukkuriAction.SHIAWASE;
            case Behaviors.AQUATIC_WATCH_PARENT, Behaviors.AQUATIC_PADDLE_PRACTICE, Behaviors.AQUATIC_IMITATE_PADDLE,
                 Behaviors.BOAT_RETURN_FAMILY, Behaviors.BOAT_FOLLOW_FAMILY -> YukkuriAction.BOAT_DRIFT;
            case Behaviors.AQUATIC_ANSWER_FAMILY -> YukkuriAction.BOAT_LULLABY;
            case Behaviors.BOAT_GUARD_YOUNG -> YukkuriAction.BOAT_STEADY;
            case Behaviors.BOAT_ROCKED, Behaviors.HAT_STUMBLE -> YukkuriAction.HAT_STUMBLE;
            case Behaviors.BOAT_CAPSIZING -> YukkuriAction.BOAT_STEADY;
            case Behaviors.HAT_JOSTLE, Behaviors.HAT_SCRAMBLE, Behaviors.HAT_SLIPPING -> YukkuriAction.HAT_JOSTLE;
            case Behaviors.BOAT_REASSURE, Behaviors.BOAT_RESCUE_YOUNG, Behaviors.BOAT_CROWD_GUARD -> YukkuriAction.BOAT_REASSURE;
            case Behaviors.BOAT_RESCUE_APPROACH -> YukkuriAction.BOAT_PADDLE;
            case Behaviors.BOAT_ALARM_CALL -> YukkuriAction.BOAT_ALARM_CALL;
            case Behaviors.BOAT_ALARM_WAIT -> YukkuriAction.BOAT_ALARM_WAIT;
            case Behaviors.BOAT_FLEE, Behaviors.BOAT_RETURN_CALLED -> YukkuriAction.BOAT_FLEE;
            case Behaviors.BOAT_CALM -> YukkuriAction.BOAT_CALM;
            case Behaviors.BOAT_ANSWER_CALL -> YukkuriAction.BOAT_ANSWER_CALL;
            case Behaviors.BRAID_RESCUE_OFFER -> YukkuriAction.HAT_BRIM_OFFER;
            case Behaviors.BRAID_RESCUE_PUSH -> YukkuriAction.BOAT_NUDGE_YOUNG;
            case Behaviors.BRAID_RESCUE_REACH -> YukkuriAction.BRAID_RESCUE_REACH;
            case Behaviors.BRAID_RESCUE_HOLD -> YukkuriAction.BRAID_RESCUE_HOLD;
            case Behaviors.BRAID_RESCUE_SETTLE -> YukkuriAction.BRAID_RESCUE_SETTLE;
            case Behaviors.BRAID_BE_RESCUED -> YukkuriAction.WATER_GASP;
            case Behaviors.WATER_IMPACT -> YukkuriAction.WATER_PLUNGE;
            case Behaviors.ESCAPE_WATER, Behaviors.STRUGGLE, Behaviors.WATER_STRUGGLE -> YukkuriAction.WATER_STRUGGLE;
            case Behaviors.WATER_EXHAUSTED -> YukkuriAction.WATER_GASP;
            case Behaviors.WATER_RECOVER -> YukkuriAction.WATER_RECOVER;
            case Behaviors.WATER_HAT_REMOVE -> YukkuriAction.WATER_HAT_SHAKE;
            case Behaviors.WATER_HAT_RIGHT -> YukkuriAction.WATER_HAT_PUSH;
            case Behaviors.WATER_HAT_REACH -> YukkuriAction.WATER_STRUGGLE;
            case Behaviors.WATER_HAT_GRIP, Behaviors.HAT_BOARD_GRIP -> YukkuriAction.WATER_MOUTH_GRIP;
            case Behaviors.WATER_HAT_CLIMB -> YukkuriAction.WATER_HAT_CLIMB;
            case Behaviors.HAT_BOARD_LIFT -> YukkuriAction.WATER_BOB_LUNGE;
            case Behaviors.HAT_BOARD_SETTLE -> YukkuriAction.HAT_SLIDE_OVER;
            case Behaviors.WATER_HAT_SLIP -> YukkuriAction.WATER_GASP;
            case Behaviors.WATER_HAT_SAFE -> YukkuriAction.WATER_RECOVER;
            case Behaviors.HAT_BOARD_ALIGN -> YukkuriAction.BOAT_STEADY;
            case Behaviors.HAT_BOARD_HOP -> YukkuriAction.HAT_HOP_UP;
            case Behaviors.BOAT_GIVE_PADDLE -> YukkuriAction.BOAT_CALL;
            case Behaviors.BOAT_RECEIVE_PADDLE -> YukkuriAction.HAT_CRADLE;
            case Behaviors.BOAT_WAIT_YOUNG, Behaviors.BOAT_FIND_PADDLE, Behaviors.BOAT_SEEK_BANK, Behaviors.BOAT_PROVISION -> YukkuriAction.LOOK_AROUND;
            case Behaviors.AQUATIC_HAT_CARE -> YukkuriAction.AQUATIC_HAT_CARE;
            case Behaviors.HAT_OFF, Behaviors.HAT_ON, Behaviors.HAT_HOLD, Behaviors.HAT_OFF_SELF, Behaviors.HAT_ON_SELF,
                 Behaviors.HAT_OFF_AQ, Behaviors.HAT_ON_AQ, Behaviors.HAT_HOLD_AQ, Behaviors.HAT_OFF_SELF_AQ, Behaviors.HAT_ON_SELF_AQ -> YukkuriAction.byId(behavior).orElse(YukkuriAction.IDLE);
            default -> YukkuriAction.IDLE;
        };
    }
    /** Sumo and bullying push; a defender keeps the threat puff. */
    public static YukkuriAction group(String activity) {
        return switch (activity) {
            case Behaviors.SUMO_PUSH, Behaviors.BULLY_PEER -> YukkuriAction.PUSH;
            case Behaviors.SUMO_PREPARE -> YukkuriAction.BRACE;
            default -> YukkuriAction.PUFF;
        };
    }
    /** Aquatic Marisa in her hat boat: labor and expecting first, asleep at night, then calling to a companion on the bank, steadying a troubled boat, else drifting. */
    public static YukkuriAction boat(YukkuriEntity owner, io.github.longshou.mcyukkuri.subspecies.HatBoatEntity boat, String behavior, String reaction) {
        if(boat.rocking()||boat.capsizing())return YukkuriAction.BOAT_STEADY;
        if(behavior.startsWith(Behaviors.BRAID_RESCUE_PREFIX)||behavior.equals(Behaviors.BOAT_ALARM_CALL)||behavior.equals(Behaviors.BOAT_ALARM_WAIT)
                ||behavior.equals(Behaviors.BOAT_CALM)||behavior.equals(Behaviors.BOAT_ANSWER_CALL))return action(behavior);
        if(behavior.equals(Behaviors.BOAT_FLEE)||behavior.equals(Behaviors.BOAT_RETURN_CALLED))
            return boat.paddling()?YukkuriAction.BOAT_PADDLE:YukkuriAction.BOAT_FLEE;
        if (behavior.equals(Behaviors.BOAT_REASSURE) || behavior.equals(Behaviors.BOAT_RESCUE_YOUNG) || behavior.equals(Behaviors.BOAT_RESCUE_APPROACH) || behavior.equals(Behaviors.BOAT_CROWD_GUARD))
            return boat.paddling() ? YukkuriAction.BOAT_PADDLE : YukkuriAction.BOAT_REASSURE;
        if (owner.family().laboring()) return YukkuriAction.BOAT_LABOR;
        if (boat.flooding() > 0 || owner.isPanicking()) return YukkuriAction.BOAT_STEADY;
        if (owner.life().isSleeping()) return YukkuriAction.BOAT_SLEEP;
        if (owner.life().mealTicks() > 0) return YukkuriAction.BOAT_EAT;
        // BOAT_EAT supplies the existing seated base. YukkuriModel replaces it with the
        // boat_toilet source clip, while HatBoatEntity owns the water-facing rear orientation.
        if (behavior.equals(Behaviors.BOAT_TOILET)) return YukkuriAction.BOAT_EAT;
        if (behavior.equals(Behaviors.FAMILY_CHEW)) return YukkuriAction.BOAT_EAT;
        if (behavior.equals(Behaviors.VOYAGE_BALANCE)) return YukkuriAction.BOAT_FIRST_BALANCE;
        if (behavior.equals(Behaviors.VOYAGE_PRAISE)) return YukkuriAction.BOAT_NUZZLE;
        if (behavior.equals(Behaviors.BOAT_FEED_YOUNG) || behavior.equals(Behaviors.FEED_CHILD)) return YukkuriAction.BOAT_FEED_YOUNG;
        if (behavior.equals(Behaviors.BOAT_NUZZLE)) return YukkuriAction.BOAT_NUZZLE;
        if (behavior.equals(Behaviors.BOAT_LULLABY)) return YukkuriAction.BOAT_LULLABY;
        // Looking and answering retain a supported seated motion, even while the neighbouring parent is paddling.
        if (behavior.equals(Behaviors.AQUATIC_WATCH_PARENT)) return YukkuriAction.BOAT_DRIFT;
        if (behavior.equals(Behaviors.AQUATIC_ANSWER_FAMILY)) return YukkuriAction.BOAT_LULLABY;
        if (behavior.equals(Behaviors.BOAT_GIVE_PADDLE)) return YukkuriAction.BOAT_CALL;
        if (behavior.equals(Behaviors.AQUATIC_PADDLE_PRACTICE) || behavior.equals(Behaviors.AQUATIC_IMITATE_PADDLE)
                || behavior.equals(Behaviors.BOAT_FOLLOW_FAMILY) || behavior.equals(Behaviors.BOAT_RETURN_FAMILY))
            return owner.getUUID().equals(boat.ownerYukkuri()) && boat.paddling() ? YukkuriAction.BOAT_PADDLE : YukkuriAction.BOAT_DRIFT;
        if (behavior.equals(Behaviors.BOAT_GUARD_YOUNG)) return boat.paddling() ? YukkuriAction.BOAT_PADDLE : YukkuriAction.BOAT_STEADY;
        if (reaction.equals("awake")) return YukkuriAction.BOAT_WAKE;
        if (behavior.equals(Behaviors.BOAT_DRINK)) return YukkuriAction.BOAT_DRINK;
        if (behavior.equals(Behaviors.BOAT_GATHER)) return YukkuriAction.BOAT_GATHER;
        if (behavior.equals(Behaviors.BOAT_STOW_FOOD) || behavior.equals(Behaviors.BOAT_RETRIEVE_FOOD)) return YukkuriAction.BOAT_GATHER;
        if (behavior.equals(Behaviors.BOAT_CALL) || behavior.equals(Behaviors.BOAT_WAIT_YOUNG)) return YukkuriAction.BOAT_CALL;
        if (boat.paddling()) return YukkuriAction.BOAT_PADDLE;
        var brood = owner.family().brood();
        if (brood != null && !brood.cancelled && !brood.complete()) return YukkuriAction.BOAT_BROOD;
        if (behavior.equals(Behaviors.EVENT_RIVER)) return YukkuriAction.BOAT_CALL;
        if (boat.flooding() > 0 || boat.towed() || reaction.equals("afraid")) return YukkuriAction.BOAT_STEADY;
        return YukkuriAction.BOAT_DRIFT;
    }
    /** Short body reaction to the dialogue event of showReaction, used while otherwise idle. */
    public static YukkuriAction reaction(String event) {
        return switch (event) {
            case "hello", "peer", "known_peer", "caregiver", "introduced", "gentle" -> YukkuriAction.GREET;
            case "afraid", "wounded", "water_help", "family_alarm_flee" -> YukkuriAction.TREMBLE;
            case "boat_rock_startle" -> YukkuriAction.STARTLE;
            case "family_alarm_call" -> YukkuriAction.BOAT_ALARM_CALL;
            case "family_alarm_answer" -> YukkuriAction.BOAT_ANSWER_CALL;
            case "family_alarm_calm" -> YukkuriAction.BOAT_CALM;
            case "lost_accessory", "family_died", "lost_parent", "cold", "settlement_hurt", "settlement_submit" -> YukkuriAction.DROOP;
            case "washed" -> YukkuriAction.SHAKE;
            case "toy" -> YukkuriAction.HOP;
            case "newborn", "hatched" -> YukkuriAction.FIRST_WAKE;
            case "birth" -> YukkuriAction.POSTPARTUM;
            case "birth_lost" -> YukkuriAction.BEREAVED;
            case "boat_reassure", "boat_rescue_young", "boat_crowd_guard" -> YukkuriAction.BOAT_REASSURE;
            case "hat_jostle", "hat_scramble", "hat_slipping" -> YukkuriAction.HAT_JOSTLE;
            case "returned_accessory" -> YukkuriAction.SHIAWASE;
            case "expecting" -> YukkuriAction.SHIAWASE;
            default -> YukkuriAction.IDLE;
        };
    }
    public static String expression(YukkuriEntity e, String fallback) {
        if(e.getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity boat&&(boat.rocking()||boat.capsizing()))
            return e.family().adult()?"surprise":"teary";
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
            case Behaviors.NUZZLE, Behaviors.LISTEN_SONG, Behaviors.EXERCISE_WATCH, Behaviors.BOAT_NUZZLE, Behaviors.BOAT_RECEIVE_CARE, Behaviors.BOAT_LISTEN -> "closed_smile";
            case Behaviors.PAIRING_ACCEPT -> "happy";
            case Behaviors.PAIRING -> "closed_smile";
            case Behaviors.MEAL_FINISH -> "satisfied";
            case Behaviors.INDEPENDENCE_FAREWELL, Behaviors.INDEPENDENCE_VISIT -> "happy";
            case Behaviors.INDEPENDENCE_BUILD, Behaviors.NEST_BUILD, Behaviors.NEST_PLACE, Behaviors.NEST_SPREAD, Behaviors.NEST_PRESS, Behaviors.NEST_HARVEST -> "chewing_closed";
            case Behaviors.FEED_CHILD -> "happy";
            case Behaviors.FIRST_TOILET_SEEK, Behaviors.FIRST_TOILET_WAIT -> "teary";
            case Behaviors.FIRST_TOILET_STIMULATED -> "surprise";
            case Behaviors.FIRST_TOILET_ASSIST -> "closed_smile";
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
            case Behaviors.THREAT_HIDE, Behaviors.RAIN_RUN, Behaviors.RAIN_WAIT, Behaviors.RAIN_HUDDLE -> "teary";
            case Behaviors.RAIN_SHAKE -> "neutral";
            case Behaviors.SEEK_SHADE, Behaviors.SHADE_REST -> "sleepy";
            case Behaviors.WAKE -> "yawn";
            case Behaviors.TOILET, Behaviors.GROUND_TOILET, Behaviors.BOAT_TOILET -> "chewing_closed";
            case Behaviors.TOILET_ACCIDENT -> "teary";
            case Behaviors.SEEK_TOILET -> e.life().bladder() >= 90 ? "teary" : "neutral";
            case Behaviors.TAKE_FOOD, Behaviors.SEEK_FOOD -> "drool";
            case Behaviors.BOAT_PADDLE, Behaviors.BOAT_DRIFT -> "closed_smile";
            case Behaviors.AQUATIC_WATCH_PARENT -> "neutral";
            case Behaviors.AQUATIC_ANSWER_FAMILY -> (e.level().getGameTime()-e.getBehaviorStartTick())%20 < 7 ? "open_smile" : "closed_smile";
            case Behaviors.AQUATIC_PADDLE_PRACTICE, Behaviors.BOAT_FOLLOW_FAMILY -> "happy";
            case Behaviors.AQUATIC_IMITATE_PADDLE, Behaviors.BOAT_GUARD_YOUNG -> "chewing_closed";
            case Behaviors.BOAT_GIVE_PADDLE, Behaviors.BOAT_RECEIVE_PADDLE -> "closed_smile";
            case Behaviors.BOAT_RETURN_FAMILY -> e.life().nutrition()<30 ? "drool" : "teary";
            case Behaviors.HAT_BRIM, Behaviors.VOYAGE_OUT, Behaviors.VOYAGE_PROUD, Behaviors.VOYAGE_PRAISE -> "happy";
            case Behaviors.VOYAGE_PREPARE, Behaviors.VOYAGE_WATCH, Behaviors.VOYAGE_RETURN -> "neutral";
            case Behaviors.VOYAGE_BALANCE -> "chewing_closed";
            case Behaviors.BOAT_FEED_YOUNG, Behaviors.AQUATIC_HAT_CARE -> "happy";
            case Behaviors.BOAT_BEG, Behaviors.BOAT_PROVISION -> "drool";
            case Behaviors.BOAT_LULLABY -> (e.level().getGameTime()-e.getBehaviorStartTick())%20 < 13 ? "open_smile" : "closed_smile";
            case Behaviors.BOAT_GATHER, Behaviors.BOAT_EAT -> "chewing";
            case Behaviors.BOAT_DRINK -> "chewing_closed";
            case Behaviors.BOAT_SLEEP -> "sleepy";
            case Behaviors.BOAT_CALL, Behaviors.BOAT_WAIT_YOUNG -> "teary";
            case Behaviors.BOAT_STEADY -> "surprise";
            case Behaviors.BOAT_ROCKED, Behaviors.HAT_STUMBLE, Behaviors.HAT_JOSTLE, Behaviors.HAT_SCRAMBLE, Behaviors.HAT_SLIPPING, Behaviors.BOAT_CROWD_GUARD -> "teary";
            case Behaviors.BOAT_REASSURE, Behaviors.BOAT_RESCUE_YOUNG -> "teary";
            case Behaviors.BOAT_RESCUE_APPROACH, Behaviors.WATER_HAT_REMOVE, Behaviors.WATER_HAT_RIGHT, Behaviors.WATER_HAT_REACH, Behaviors.WATER_HAT_GRIP, Behaviors.WATER_HAT_CLIMB, Behaviors.WATER_HAT_SLIP,
                 Behaviors.HAT_BOARD_ALIGN, Behaviors.HAT_BOARD_HOP, Behaviors.HAT_BOARD_GRIP, Behaviors.HAT_BOARD_LIFT, Behaviors.HAT_BOARD_SETTLE -> "teary";
            case Behaviors.WATER_HAT_SAFE -> "sad";
            case Behaviors.BOAT_ALARM_CALL, Behaviors.BOAT_ANSWER_CALL -> "surprise";
            case Behaviors.BOAT_ALARM_WAIT, Behaviors.BOAT_FLEE, Behaviors.BOAT_RETURN_CALLED, Behaviors.BRAID_RESCUE_REACH, Behaviors.BRAID_RESCUE_HOLD, Behaviors.BRAID_RESCUE_OFFER, Behaviors.BRAID_BE_RESCUED -> "teary";
            case Behaviors.BRAID_RESCUE_PUSH -> "closed_smile";
            case Behaviors.BOAT_CALM -> e.level().getGameTime()-e.aquaticAlarmStartTick()<40?"teary":"neutral";
            case Behaviors.BRAID_RESCUE_SETTLE -> "neutral";
            case Behaviors.WATER_IMPACT -> "cry";
            case Behaviors.WATER_STRUGGLE, Behaviors.WATER_EXHAUSTED, Behaviors.WATER_RECOVER -> "teary";
            case Behaviors.CHEW, Behaviors.FAMILY_CHEW -> (e.level().getGameTime()-e.getBehaviorStartTick())%8 < 4 ? "chewing" : "chewing_closed";
            case Behaviors.SWALLOW, Behaviors.DRINK -> "chewing_closed";
            case Behaviors.BULLY_PEER, Behaviors.PROTECT_PEER, Behaviors.SUMO_PUSH, Behaviors.SANCTION_APPROACH, Behaviors.SANCTION_DECLARE, Behaviors.SANCTION_BULLY -> "angry";
            case Behaviors.SHARD_SEEK, Behaviors.SHARD_LICK -> "teary";
            default -> e.contactPose().equals("RUB") ? "closed_smile"
                    : e.life().nutrition() < 30 ? "drool" : e.life().energy() < 25 ? "sleepy"
                    : e.dailyLife().affectionNeed() >= 80 && fallback.equals("neutral") ? "teary" : fallback;
        };
        // Predator authoring is a separate task and does not use these ordinary-species face atlases.
        if(e.getVariant().isPredator())return switch(expression){
            case "closed_smile", "satisfied", "open_smile" -> "happy";
            case "chewing", "chewing_closed" -> "neutral";
            default -> expression;
        };
        return expression;
    }
    private EcologyPresentation() {}
}
