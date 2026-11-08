"""Calibration controls derived from saved S2 timelines; injected breaks retain detection.

H source: instance-5a0da4a1c104, sleep-6 t99423..99432 and hat_push-16 t143807.
These minimal F reproductions preserve the relevant fields, not entire game recordings.
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import smoke_checks as checks
import timeline
from cases import record, state, log, HOST, ACTOR


class Calibration(unittest.TestCase):
    def test_daily_greeting_requires_actual_touching_countdown(self):
        # Main birth_boat_missed-184 t1151665 precedes NUZZLE contactTicks;
        # the existing greeting clock advances only at real touching contact.
        def rows(end, frozen=False):
            return [state(t, goals=['DailyGoal'], p=[(t//2)*.02,0,0],
                          dailyGreetingWait=20 if frozen else max(0,20-t)) for t in range(end)]
        self.assertFalse(checks.progress(timeline.states(rows(20))))
        self.assertTrue(any(f['verdict']=='fail' for f in checks.progress(timeline.states(rows(60)))))
        self.assertTrue(any(f['verdict']=='fail' for f in checks.progress(timeline.states(rows(20,True)))))
        self.assertFalse(checks.work_changed(state(0,dailyGreetingWait=5)['st'],state(1,dailyGreetingWait=20)['st']))
        _, issues=timeline.parse([log([state(0,dailyGreetingWait='not-a-counter')])])
        self.assertTrue(issues)

    def test_shared_phase_wait_does_not_hide_a_stalled_side_job(self):
        rows=[state(t,goals=['BirthGoal'],waitElapsed=t,int={'id':'attend'},
                    act={'id':'walk','owner':'catcher','metric':1,'win':40}) for t in range(402)]
        self.assertTrue(any(f['detail']=='no observed progress' and f['verdict']=='fail'
                            for f in checks.progress(timeline.states(rows))))
        for r in rows:r['st']['int']=None;r['st']['act']=None
        self.assertFalse(checks.progress(timeline.states(rows)))

    def test_only_actual_advancing_recovery_after_an_accepted_strike_is_work(self):
        def rows(end, frozen=False):
            return [state(t, goals=['PeerDefense'], p=[(t//2)*.02,0,0],
                          defenseWait=30 if frozen else max(0,30-t)) for t in range(end)]
        hit=record('ev',0,cat='social',msg='self defense strike attacker=peer accepted=true health=10.0 -> 9.0')
        self.assertFalse(checks.progress(timeline.states(rows(20)),[hit]))
        self.assertTrue(any(f['verdict']=='fail' for f in checks.progress(timeline.states(rows(60)),[hit])))
        self.assertTrue(any(f['verdict']=='fail' for f in checks.progress(timeline.states(rows(20,True)),[hit])))
        hit['msg']='self defense strike attacker=peer accepted=false health=10.0 -> 10.0'
        self.assertTrue(any(f['verdict']=='fail' for f in checks.progress(timeline.states(rows(20)),[hit])))

    def test_actual_greeting_separates_approaches_but_does_not_hide_later_stops(self):
        rows = [state(t, goals=['SocialGoal'], socialGreeting=HOST if t >= 5 else None,
                      p=[min(t,5)*.02 if t<12 else .1+((t-12)//2)*.02,0,0]) for t in range(18)]
        self.assertFalse(checks.progress(timeline.states(rows)))
        self.assertTrue(checks.work_changed(rows[4]['st'],rows[5]['st']))
        rows += [state(t, goals=['SocialGoal'], socialGreeting=HOST,
                       p=[.1+((t-12)//2)*.02,0,0]) for t in range(18,40)]
        self.assertTrue(any(f['verdict']=='fail' for f in checks.progress(timeline.states(rows))))
        self.assertFalse(checks.work_changed(rows[5]['st'],rows[6]['st']))
        _, issues=timeline.parse([log([state(0,socialGreeting=[])])])
        self.assertTrue(issues)

    def test_stationary_receiver_tracks_moving_caregiver_without_yaw_stutter(self):
        import math
        rows = []
        for t in range(40):
            yaw = 90 if t % 4 < 2 else 60
            rows.append(state(t, goals=['CareReceiverGoal'], yaw=yaw, careParent=HOST))
            parent = state(t, p=[-math.sin(math.radians(yaw)), 0, math.cos(math.radians(yaw))])
            parent['u'] = HOST
            rows.append(parent)
        self.assertFalse(checks.progress(timeline.states(rows)))
        for row in rows:
            if row['u'] == ACTOR: row['st']['careParent'] = None
        self.assertTrue(any(f['actor'] == ACTOR and f['verdict'] == 'fail'
                            for f in checks.progress(timeline.states(rows))))
        for row in rows:
            if row['u'] == ACTOR: row['st']['careParent'] = HOST
        for row in rows:
            if row['u'] == HOST: row['st']['p'] = [-1, 0, 0]
        self.assertTrue(any(f['actor'] == ACTOR and f['verdict'] == 'fail'
                            for f in checks.progress(timeline.states(rows))))
        _, issues = timeline.parse([log([state(0, careParent=[])])])
        self.assertTrue(issues)

    def test_care_agreement_does_not_hide_a_stalled_or_moving_receiver(self):
        rows = [state(t, goals=['CareReceiverGoal'], careParent=HOST) for t in range(1202)]
        self.assertTrue(any(f['detail'] == 'no observed progress' and f['verdict'] == 'fail'
                            for f in checks.progress(timeline.states(rows))))
        a, b = timeline.states([state(0, careParent=HOST), state(1, careParent=HOST, p=[.1, 0, 0])])
        self.assertFalse(checks.receiver_tracks_parent(a, b, {(HOST, 0): {'p': [1, 0, 0]},
                                                             (HOST, 1): {'p': [1, 0, 1]}}))

    def test_completed_work_closes_other_action_repeat_intervals(self):
        rows=[state(t,p=[t*.001,0,0]) for t in range(125)]
        events=[]
        for t in range(0,125,5):
            events += [record('ev',t,cat='action',msg='end id=face outcome=DONE detail=yaw=0',pos=[0,0,0]),
                       record('ev',t+1,cat='action',msg='start id=walk owner=travel',pos=[0,0,0]),
                       record('ev',t+3,cat='action',msg='end id=walk outcome=DONE detail=arrived',pos=[0,0,0])]
        self.assertFalse(checks.loops(events,timeline.states(rows)))
        for event in events:
            if event['msg'].startswith('end id=walk'):
                event['msg']='end id=walk outcome=BLOCKED reason=contact'
        self.assertTrue(any(f['verdict']=='fail' and f['t']%5==0 for f in checks.loops(events,timeline.states(rows))))

    def test_stationary_preparation_and_thermal_rest_use_actual_counters(self):
        for field,goal,behavior in (('careChewTicks','FamilyGoal@5','family_chew'),
                                    ('dailyContactTicks','DailyGoal@5','sing'),
                                    ('thermalRestTicks','LifeGoal@3','warm_up')):
            with self.subTest(field=field):
                rows=[state(t,p=[(t//2)*.02,0,0],goals=[goal],behavior=behavior,
                            **{field:t}) for t in range(40)]
                self.assertFalse(checks.progress(timeline.states(rows)))
                for row in rows:row['st'][field]=0
                self.assertTrue(any(f['verdict']=='fail' for f in checks.progress(timeline.states(rows))))

    def test_observed_work_counters_and_scale_reject_non_numeric_external_data(self):
        for field in ('careChewTicks', 'dailyContactTicks', 'thermalRestTicks', 'defenseWait', 'scale'):
            for value in ('invalid', float('nan'), True):
                _, issues = timeline.parse([log([state(0, **{field: value})])])
                self.assertTrue(issues, (field, value))

    def test_scope_flow_cannot_cancel_an_unrelated_creation_or_loss(self):
        apple={'item':'minecraft:apple','n':1}
        rows=[state(0),record('st',0,u=HOST,st=apple),state(1,hold={'mouth':[apple]})]
        events=[record('life',1,u=HOST,out='scope_exit')]
        findings=checks.ledger(events,timeline.states(rows))
        self.assertTrue(any(f['verdict']=='fail' and f['actor']==ACTOR and f['items']=={'minecraft:apple':1}
                            for f in findings))
        rows=[state(0,hold={'mouth':[apple]}),state(1),record('st',1,u=HOST,st=apple)]
        events=[record('life',1,u=HOST,**{'in':'chunk_load'})]
        findings=checks.ledger(events,timeline.states(rows))
        self.assertTrue(any(f['verdict']=='fail' and f['actor']==ACTOR and f['items']=={'minecraft:apple':-1}
                            for f in findings))

    def test_later_scope_entry_does_not_close_unexplained_inventory_loss(self):
        apple={'item':'minecraft:apple','n':1}
        rows=[state(0,hold={'mouth':[apple]}),state(1),state(10),record('st',10,u=HOST,st=apple)]
        events=[record('life',10,u=HOST,**{'in':'chunk_load'})]
        self.assertTrue(any(f['verdict']=='fail' and f['detail']=='unexplained permanent item total change'
                            for f in checks.ledger(events,timeline.states(rows))))

    def test_last_snapshot_precedes_same_tick_unload(self):
        # H hat_crowd-52 t346024: state seq2959677..2959692 precedes
        # unload seq2959694..2959709. Merging by kind resurrected the holders.
        apple={'item':'minecraft:apple','n':1}
        rows=[state(0,hold={'mouth':[apple]}),state(1,hold={'mouth':[apple]}),
              record('life',1,out='unloaded'),record('life',2,**{'in':'chunk_load'}),
              state(2,hold={'mouth':[apple]})]
        for seq,row in enumerate(rows):row['seq']=seq
        self.assertFalse(checks.ledger(rows,timeline.states(rows)))

    def test_cancelled_feed_return_requires_the_matching_item_receipt(self):
        rows=[state(0,hold={'mouth':[{'item':'minecraft:short_grass','n':1}]}),state(1)]
        prefix='feed reserved portion returned store=BlockPos{x=-746, y=64, z=305}'
        receipt=record('ev',1,cat='family',msg=prefix+' item=minecraft:short_grass count=1')
        self.assertFalse(checks.ledger([receipt],timeline.states(rows)))
        for message in (prefix,prefix+' item=minecraft:apple count=1'):
            receipt['msg']=message
            self.assertTrue(any(f['verdict']=='fail' for f in checks.ledger([receipt],timeline.states(rows))))

    def test_death_weapon_and_released_project_need_real_resource_receipts(self):
        rows=[state(0),state(1),record('st',1,u=HOST,st={'item':'minecraft:stick','n':1})]
        success=record('ev',1,cat='myon',msg=f'kanroken dropped cause=death; drop item=minecraft:stick count=1 drop={HOST}')
        failed=record('ev',1,cat='myon',msg='kanroken drop failed cause=death item=minecraft:stick count=1')
        self.assertFalse(checks.ledger([success],timeline.states(rows)))
        self.assertTrue(any(f['verdict']=='fail' for f in checks.ledger([failed],timeline.states(rows))))
        rows[-1]['st']['item']='minecraft:short_grass'
        release=record('ev',1,u=None,cat='habitat',msg='nest material dropped holder=BlockPos{x=-976, y=64, z=436} owner=owner item=minecraft:short_grass count=1')
        self.assertFalse(checks.ledger([release],timeline.states(rows)))
        release['msg']=release['msg'].replace('minecraft:short_grass','minecraft:apple')
        self.assertTrue(any(f['verdict']=='fail' for f in checks.ledger([release],timeline.states(rows))))

    def test_carried_and_released_motion_are_separate_stutter_intervals(self):
        # R colony-babies169: two carrier pauses then the child walks after
        # release. The child's unchanged LifeGoal does not make one motion job.
        rows=[state(t,p=[(t//2)*.1,0,0],goals=['LifeGoal@3'],behavior='return_home',
                    veh=HOST if t<6 else None) for t in range(12)]
        self.assertFalse(checks.progress(timeline.states(rows)))
        for row in rows:row['st']['veh']=None
        self.assertTrue(any(f['detail']=='dense stutter cycles' for f in checks.progress(timeline.states(rows))))

    def test_small_body_travelling_turns_are_not_in_place_oscillation(self):
        # R birth_boat-153, juvenile scale .2095: continuous approach covers
        # .44 blocks during that job (several body lengths), with steering corrections.
        rows=[state(t,scale=.2,p=[t*.003,0,0],yaw=(t%12)*2 if t%12<6 else (12-t%12)*2,
                    goals=['FamilyGoal@5'],behavior='follow_parent') for t in range(100)]
        self.assertFalse(checks.progress(timeline.states(rows)))
        for row in rows:row['st']['p']=[0,0,0]
        self.assertTrue(any(f['verdict']=='fail' for f in checks.progress(timeline.states(rows))))

    def test_scope_inventory_is_a_boundary_flow_not_creation(self):
        # R famine-98 scope-entering adult already chews grass; crowd-83
        # reloads a hull and its stored apples/sticks after a real unload.
        rows=[state(0),state(1),state(1,hold={'mouth':[{'item':'minecraft:apple','n':24}]}) | {'u':HOST}]
        entry=record('life',1,u=HOST,**{'in':'chunk_load'})
        self.assertFalse(checks.ledger([entry],timeline.states(rows)))
        self.assertTrue(any(f['verdict']=='fail' for f in checks.ledger([],timeline.states(rows))))
        rows[1]['st']['hold']={'mouth':[{'item':'minecraft:apple','n':1}]}
        self.assertTrue(any(f['verdict']=='fail' for f in checks.ledger([entry],timeline.states(rows))))
        # The same item's unrelated creation is not hidden by another holder's exit.
        rows=[state(0),state(0,hold={'mouth':[{'item':'minecraft:apple','n':24}]}) | {'u':HOST},
              state(1,hold={'mouth':[{'item':'minecraft:apple','n':1}]})]
        leaving=record('life',1,u=HOST,out='scope_exit')
        self.assertTrue(any(f['verdict']=='fail' for f in checks.ledger([leaving],timeline.states(rows))))
        rows[-1]['st']['hold']={'mouth':[]}
        self.assertFalse(checks.ledger([leaving],timeline.states(rows)))
        loose=[state(0),state(1),record('st',1,u=HOST,st={'item':'minecraft:apple','n':1})]
        discovered=record('life',1,u=HOST,**{'in':'scope_enter'})
        self.assertTrue(any(f['verdict']=='fail' for f in checks.ledger([discovered],timeline.states(loose))))
        receipt=record('ev',1,cat='habitat',msg='drop item=minecraft:apple count=1')
        self.assertFalse(checks.ledger([discovered,receipt],timeline.states(loose)))
        # R birth_hat_own-123: a kit transfers unchanged while stored apples
        # spill. Discovered drops must not be subtracted as external arrivals.
        third='00000000-0000-0000-0000-000000000003'
        spill=[record('st',0,st={'riders':[],'kit':{'item':'minecraft:stick','n':1}}),
               record('st',1,st={'riders':[],'kit':None}),
               record('st',1,u=HOST,st={'item':'minecraft:stick','n':1}),
               record('st',1,u=third,st={'item':'minecraft:apple','n':4})]
        events=[record('life',1,u=u,**{'in':'scope_enter'}) for u in (HOST,third)]
        events += [record('ev',1,cat='hat',msg='stores spilled kinds={minecraft:apple=4} -> {}')]
        self.assertFalse(checks.ledger(events,timeline.states(spill)))

    def test_death_display_cache_is_not_a_second_physical_holder(self):
        # R den-32: real habitat drop at199512, stale synced carry display
        # until corpse removal at199531. The transfer must still be logged.
        held={'mouth':[{'item':'minecraft:short_grass','n':1}]}
        rows=[state(t,hp=10 if t==0 else 0,hold=held) for t in range(20)]
        rows += [record('st',t,u=HOST,st={'item':'minecraft:short_grass','n':1}) for t in range(1,21)]
        samples=timeline.states(sorted(rows,key=lambda r:r['t']))
        death=record('life',20,out='killed')
        self.assertFalse(checks.ledger([death],samples))
        missing_drop=timeline.states([state(0,hold=held),state(1,hp=0,hold=held)])
        self.assertTrue(any(f['verdict']=='fail' for f in checks.ledger([],missing_drop)))
        drop=record('ev',1,cat='habitat',msg='drop item=minecraft:short_grass count=1')
        self.assertFalse(checks.ledger([drop],missing_drop))
        corpse=[state(0,hp=0,hold={'mouth':[{'item':'minecraft:apple','n':1}]*2}),state(1,hp=0,hold=held)]
        self.assertFalse(checks.ledger([],timeline.states(corpse)))

    def test_completed_short_local_move_is_work_but_holder_drift_is_not(self):
        # R birth_hat_own-92: take_seat completes five short walks (.005..026
        # blocks); endpoint snapshots omit the already-completed intent.
        rows=[state(t,p=[t*.02,0,0],att={'host':HOST,'spot':[t*.005,0,0]}) for t in range(101)]
        events=[]
        for t in range(1,100,4):
            events += [record('ev',t,cat='action',msg='start id=walk owner=fixture',pos=[0,0,0]),
                       record('ev',t+1,cat='intent',msg='end id=take_seat result=DONE reasons=[]',pos=[0,0,0]),
                       record('ev',t+1,cat='action',msg='end id=walk outcome=INTERRUPTED why=owner_inactive',pos=[0,0,0])]
        self.assertFalse(checks.loops(events,timeline.states(rows)))
        for row in rows:row['st']['p']=[0,0,0]
        blocked=[r | {'msg':r['msg'].replace('outcome=INTERRUPTED why=owner_inactive','outcome=BLOCKED reason=contact')} for r in events]
        self.assertTrue(any(f['verdict']=='fail' for f in checks.loops(blocked,timeline.states(rows))))
        for row in rows:
            row['st']['p']=[row['t']*.001,0,0]
            row['st']['att']['spot']=[0,0,0]
        self.assertTrue(any(f['verdict']=='fail' for f in checks.loops(events,timeline.states(rows))))

    def test_habitat_drop_explains_only_its_observed_item(self):
        rows=[state(0),state(1)]
        rows[1]['st']={'item':'minecraft:oak_leaves','n':1,'p':[0,0,0]}
        raw=f'[habitat] reimu #8 uuid={HOST} t=1 dim=minecraft:overworld pos=0, 0, 0 drop item=minecraft:oak_leaves count=1 task=DEPOSIT drop={ACTOR}'
        self.assertFalse(checks.ledger([],timeline.states(rows),raw=raw))
        self.assertTrue(any(f['verdict']=='fail' for f in checks.ledger([],timeline.states(rows),raw=raw.replace('minecraft:oak_leaves','minecraft:diamond'))))
        self.assertTrue(any(f['verdict']=='fail' for f in checks.ledger([],timeline.states(rows),raw=raw.replace(f'drop={ACTOR}',f'drop={HOST}'))))

    def test_completed_observed_wait_is_not_an_immediate_retry_loop(self):
        # R birth_hat_own-30: guard waits actually advance for forty ticks;
        # R hat_crowd-21: escape-water condition starts/ends in the same tick.
        rows = [state(t, act=None if t % 6 == 5 else
                      {'id':'await','owner':'guard','metric':5-t%6,'deadline':1000}) for t in range(150)]
        events = [record('ev', t, cat='action', msg='start id=await owner=guard' if t%6 == 0 else
                         'end id=await outcome=DONE detail=condition', pos=[0,0,0])
                  for t in range(150) if t%6 in (0,5)]
        self.assertFalse(checks.loops(events, timeline.states(rows)))
        interrupted = [r | {'msg':r['msg'].replace('outcome=DONE detail=condition','outcome=INTERRUPTED why=owner_inactive')} for r in events]
        completed = [record('ev',t,cat='intent',msg='end id=cruise result=DONE reasons=[]',pos=[0,0,0])
                     for t in range(150) if t%6==5]
        self.assertFalse(checks.loops(sorted(interrupted+completed,key=lambda r:r['t']),timeline.states(rows)))
        self.assertTrue(any(f['verdict']=='fail' for f in checks.loops(interrupted,timeline.states(rows))))
        blocked = [r | {'msg':r['msg'].replace('outcome=DONE','outcome=BLOCKED')} for r in events]
        self.assertTrue(any(f['verdict']=='fail' for f in checks.loops(blocked,timeline.states(rows))))
        for row in rows:
            if row['st']['act']:row['st']['act']['metric']=5
        self.assertTrue(any(f['verdict']=='fail' for f in checks.loops(events,timeline.states(rows))))

    def test_passive_motion_is_not_a_stuttering_job(self):
        # R carry_home-15: an idle held child inherits pauses while its parent
        # picks up siblings. R birth_boat-29: an idle child is pushed by peers.
        rows = [state(t, p=[(t // 10) * .02, 0, 0], goals=[], behavior='idle', act=None, **{'int': None})
                for t in range(100)]
        self.assertFalse(checks.progress(timeline.states(rows)))
        for row in rows:
            row['st']['goals'] = ['SocialGoal@6']
            row['st']['behavior'] = 'peer'
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.progress(timeline.states(rows))))

    def test_suppressed_refusals_do_not_cross_actual_progress(self):
        # R pit_slave-4 t43890..47408 moves over five blocks; the next
        # printed contact refusal includes ten attempts from before that move.
        rows = [state(t, p=[0 if t < 50 else 5, 0, 0]) for t in range(101)]
        events = [record('ev', t, cat='social', msg='contact stand unavailable',
                         pos=[0 if t < 50 else 5, 0, 0], sup=sup)
                  for t, sup in ((0, 0), (60, 10), (100, 24))]
        self.assertFalse(checks.loops(events[:2], timeline.states(rows[:61])))
        self.assertTrue(any(f['verdict'] == 'fail' and f['t'] == 100
                            for f in checks.loops(events, timeline.states(rows))))

    def test_resource_drop_from_untraced_actor_requires_observed_drop_id(self):
        rows = [state(0),state(1)]
        rows[1]['st']={'item':'minecraft:short_grass','n':1,'p':[0,0,0]}
        raw=f'[family] marisa #7 uuid={HOST} t=1 dim=minecraft:overworld pos=0, 0, 0 feed reserved portion dropped item=1 minecraft:short_grass drop={ACTOR}'
        self.assertFalse(checks.ledger([],timeline.states(rows),raw=raw))
        self.assertTrue(any(f['verdict']=='fail' for f in checks.ledger([],timeline.states(rows),raw=raw.replace(f'drop={ACTOR}',f'drop={HOST}'))))
        self.assertTrue(any(f['verdict']=='fail' for f in checks.ledger([],timeline.states(rows),raw=raw.replace('minecraft:short_grass','minecraft:diamond'))))
        pickup=f'[habitat] reimu #8 uuid={HOST} t=2 dim=minecraft:overworld pos=0, 0, 0 pickup item=minecraft:short_grass count=1 source={ACTOR} remaining=0 holder=mouth'
        rows.append(state(2))
        self.assertFalse(checks.ledger([],timeline.states(rows),raw=raw+'\n'+pickup))

    def test_actual_leaf_wrap_explains_only_its_consumed_leaves(self):
        rows=[state(0,hold={'mouth':[{'item':'minecraft:oak_leaves','n':1}]}),state(1)]
        event=record('ev',1,cat='family',msg='leaf swaddle wrapped child=fixture leaves=1xminecraft:oak_leaves unease=0.5 friendliness=90')
        self.assertFalse(checks.ledger([event],timeline.states(rows)))
        rows[0]['st']['hold']['mouth'][0]['item']='minecraft:diamond'
        self.assertTrue(any(f['verdict']=='fail' for f in checks.ledger([event],timeline.states(rows))))

    def test_actual_leaf_harvest_explains_only_recorded_resource(self):
        # Main colony_migrate_babies-45 t329247 already logs the real harvest.
        rows = [state(0), state(1, hold={'mouth': [{'item': 'minecraft:oak_leaves', 'n': 1}]})]
        event = record('ev', 1, cat='family', msg='leaf swaddle leaf harvested item=minecraft:oak_leaves at=BlockPos{x=0, y=0, z=0} carried=1 child=fixture')
        self.assertFalse(checks.ledger([event], timeline.states(rows)))
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.ledger([], timeline.states(rows))))
        wrong = event | {'msg': event['msg'].replace('minecraft:oak_leaves', 'minecraft:birch_leaves')}
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.ledger([wrong], timeline.states(rows))))
        refused = event | {'msg': 'leaf swaddle refused child=fixture reason=no_reachable_leaf_block carried=0'}
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.ledger([refused], timeline.states(rows))))

    def test_successful_defense_contacts_separate_attack_approaches(self):
        rows = [state(t,p=[(t // 10)*.02,0,0],goals=['PeerDefense@2']) for t in range(100)]
        events = [record('ev',t,cat='social',msg='self defense strike attacker=fixture accepted=true health=10.0 -> 9.0 energy=38.0') for t in range(0,100,30)]
        self.assertFalse(checks.progress(timeline.states(rows),events))
        for event in events:event['msg']=event['msg'].replace('accepted=true','accepted=false')
        self.assertTrue(any(f['verdict']=='fail' for f in checks.progress(timeline.states(rows),events)))

    def test_collision_erased_impulse_uses_observed_pre_travel_velocity(self):
        # R pit_slave-4 t27517: mob knockback moves the body before collision
        # zeroes its x velocity. Neither post-tick endpoint retains that input.
        rows = [state(0, v=[-.003669,-.0784,.016066]),
                state(1, p=[-.322923,.4,-.132909], v=[0,.3136,-.072568], g=False)]
        move = record('ev',1,cat='move',msg='travel action=none at=(0,0,0) velocity=(-.322923,.4,-.132909)')
        self.assertTrue(checks.jumps(timeline.states(rows)))
        self.assertFalse(checks.jumps(timeline.states(rows),[move]))
        self.assertTrue(checks.jumps(timeline.states(rows),[move | {'u':HOST}]))
        rows[1]['st']['p'][0] -= 2
        self.assertTrue(any(f['verdict']=='fail' for f in checks.jumps(timeline.states(rows),[move])))

    def test_two_executed_hop_segments_share_one_tick(self):
        # Main hat_crowd-145 t928787: air=1 and air=2 execute on dismount.
        first = [.0000617681, .2712160268, -.1400758949]
        second = [.0000562090, .1873917063, -.1274690644]
        rows = [state(0, g=False), state(1, g=False, v=second,
                p=[a+b for a,b in zip(first,second)])]
        moves = [record('ev',1,cat='move',msg='travel action=hop at=(0,0,0) velocity=('+','.join(map(str,v))+')')
                 for v in (first,second)]
        self.assertTrue(checks.jumps(timeline.states(rows)))
        self.assertFalse(checks.jumps(timeline.states(rows),moves))
        self.assertTrue(checks.jumps(timeline.states(rows),[moves[1]]))
        self.assertTrue(checks.jumps(timeline.states(rows),[r | {'u':HOST} for r in moves]))
        rows[1]['st']['p'][0] += 2
        self.assertTrue(any(f['verdict']=='fail' for f in checks.jumps(timeline.states(rows),moves)))

    def test_host_local_ballistic_segment_is_not_a_teleport(self):
        frame = dict(origin=[0,0,0],unit=2,yaw=0,pitch=0,roll=0)
        rows = [state(0,att={'host':HOST,'frame':frame}),
                state(1,p=[.3,.1,0],att={'host':HOST,'frame':frame})]
        move = record('ev',1,cat='move',msg=f'hop flight host={HOST} localDelta=(.3,.1,0)')
        self.assertTrue(checks.jumps(timeline.states(rows)))
        self.assertFalse(checks.jumps(timeline.states(rows),[move]))
        self.assertTrue(checks.jumps(timeline.states(rows),[move | {'msg':f'hop flight host={ACTOR} localDelta=(.3,.1,0)'}]))
        rows[1]['st']['p'][0] += 2
        self.assertTrue(any(f['verdict']=='fail' for f in checks.jumps(timeline.states(rows),[move])))

    def test_logged_meal_drop_and_loose_waste_are_item_scoped(self):
        for cat,message,item in (
                ('meal','interrupted item=minecraft:short_grass count=1 phaseTick=12 drop=fixture','minecraft:short_grass'),
                ('needs','poop stays a loose item reason=no_floor_cell meant=null','mcyukkuri:yukkuri_waste')):
            rows=[state(0),state(1,hold={'mouth':[{'item':item,'n':1}]}),record('ev',1,cat=cat,msg=message)]
            self.assertFalse(checks.ledger(rows,timeline.states(rows)))
            rows[1]['st']['hold']['mouth'][0]['item']='minecraft:diamond'
            self.assertTrue(any(f['verdict']=='fail' for f in checks.ledger(rows,timeline.states(rows))))

    def test_dead_actor_is_not_a_stalled_worker(self):
        rows = [state(t,goals=['ForageTrip@4'],hp=0) for t in range(1202)]
        self.assertFalse(checks.progress(timeline.states(rows)))
        for row in rows:row['st']['hp']=10
        self.assertTrue(any(f['verdict']=='fail' for f in checks.progress(timeline.states(rows))))

    def test_new_short_action_does_not_inherit_a_longer_goal_wait(self):
        rows=[state(t,goals=['FamilyGoal@5'],phase='SEARCH') for t in range(454)]
        rows[-1]['st']['act']={'id':'face','owner':'family_call','win':40}
        self.assertFalse(checks.progress(timeline.states(rows)))
        rows += [state(t,goals=['FamilyGoal@5'],phase='SEARCH',act={'id':'face','owner':'family_call','win':40}) for t in range(454,1202)]
        self.assertTrue(any(f['verdict']=='fail' for f in checks.progress(timeline.states(rows))))

    def test_unsafe_last_sample_before_death_is_not_the_window_end(self):
        rows = [state(0,dep=True,distress=True),state(1,dep=True,distress=True),state(1000)]
        rows[-1]['u'] = HOST
        death = record('life',2,**{'out':'killed'})
        self.assertFalse(checks.orphans(timeline.states(rows),[death]))
        self.assertEqual('no-evidence', checks.orphans(timeline.states(rows))[0]['verdict'])
        rows += [state(1000,dep=True,distress=True)]
        self.assertTrue(any(f['detail']=='dependent unsafe at window end' and f['verdict']=='fail'
                            for f in checks.orphans(timeline.states(rows))))

    def test_logged_host_contact_travel_explains_only_its_actual_displacement(self):
        # H shake_capsize-18 t144663: contact moves the hull .354 blocks,
        # while its retained drift velocity is only .001 blocks/tick.
        rows = [state(0), state(1)]
        for row in rows:
            row['st'] = {'p':[0,0,0], 'v':[.001,0,0], 'frame':{}, 'riders':[]}
        rows[1]['st']['p'] = [-.35448,0,-.08104]
        move = record('ev',1,cat='hat',msg='travel at=(-.35448,0,-.08104) delta=(-.35448,0,-.08104) velocity=(.001,0,0)')
        self.assertFalse(checks.jumps(timeline.states(rows),[move]))
        self.assertTrue(checks.jumps(timeline.states(rows),[move | {'u':HOST}]))
        rows[1]['st']['p'][0] += 2
        self.assertTrue(any(f['verdict']=='fail' for f in checks.jumps(timeline.states(rows),[move])))

    def test_short_completed_advances_accumulate_for_loop_progress(self):
        for stride, expected in ((.2, False), (0, True)):
            rows = []
            for t in range(25):
                rows += [state(t, p=[t * stride, 0, 0]), record('ev', t,
                    cat='action', msg='end id=paddle outcome=DONE detail=arrived', pos=[int(t * stride),0,0])]
            self.assertEqual(expected, any(f['verdict']=='fail' for f in checks.loops(rows,timeline.states(rows))))

    def test_reviewed_vantage_size_controls_keep_occlusion_and_offscreen_warnings(self):
        for visible, share, expected in ((9, .0697396, 'pass'), (9, .0498926, 'pass'), (9, .0392784, 'pass'), (9, .0337174, 'suspect'),
                                         (0, .13, 'suspect'), (9, 0, 'suspect')):
            rows = [record('geo', 1, player=True, vis=visible, scr=share)]
            report = checks.observations(rows)
            self.assertEqual(expected, next(f['verdict'] for f in report['summary'] if f['check']=='VANTAGE'))

    def test_verified_contact_advances_even_when_bodies_stop_and_push(self):
        # H birth_boat_missed-29: SUMO completes with contact=80 while the
        # bodies intentionally push/stop. Only actual contact increments count.
        rows = [state(t, p=[(t // 2) * .02, 0, 0], contactProgress=t, goals=['GroupGoal@5']) for t in range(80)]
        self.assertFalse(checks.progress(timeline.states(rows)))
        for row in rows:
            row['st']['contactProgress'] = 0
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.progress(timeline.states(rows))))

    def test_startup_refusal_with_real_progress_is_not_a_setup_block(self):
        # H: den_family-1/forage-2/carry_home-15 refuse optional leaf wrapping,
        # then walk 1.7–2.4 blocks within the same first 40 ticks.
        base = [record('win', 0, None), record('ev', 1, cat='family',
                    msg='leaf swaddle refused reason=no_reachable_leaf_block', pos=[0,0,0]),
                record('geo', 1, clear=True, supported=True, drop=0),
                record('geo', 20, clear=True, supported=True, drop=0)]
        for moved, expected in ((2, False), (0, True)):
            rows = base + [state(1, goals=['RoamingGoal']), state(40, p=[moved,0,0], goals=['RoamingGoal'])]
            findings = checks.observations(rows, [])['findings']
            self.assertEqual(expected, any(f['check']=='SPAWN' and f['detail']=='early scene refusal' for f in findings))
        rows = base + [state(1, goals=['RoamingGoal'])]
        self.assertTrue(any(f['check']=='SPAWN' and f['verdict']=='no-evidence' for f in checks.observations(rows, [])['findings']))

    def test_post_tick_velocity_explains_real_takeoff_acceleration(self):
        # H: sail-23 t160696, dp=(.26581,.08,.01418), v0=(0,-.0784,0),
        # v1 equals the actual takeoff displacement. Self-rescue's impulse is
        # sampled after ordinary drag, leaving .159 blocks of residual.
        for delta, velocity in (([.26581, .08, .01418], [.26581, .08, .01418]),
                                ([.35, .3, 0], [.1911, .2156, 0])):
            rows = [state(0, v=[0, -.0784, 0]), state(1, p=delta, v=velocity, g=False)]
            self.assertFalse(checks.jumps(timeline.states(rows)))
            rows[1]['st']['p'][0] += 2
            self.assertTrue(any(f['verdict'] == 'fail' for f in checks.jumps(timeline.states(rows))))

    def test_slow_continuous_motion_does_not_create_stops(self):
        # H: forage-2 father 01d69f8d, t12843..12867: continuously advances
        # .005..028 blocks/tick while the old .01 threshold counts six stops.
        rows, x = [], 0
        for t in range(100):
            rows.append(state(t, p=[x, 0, 0], goals=['DailyGoal']))
            x += .02 if t % 4 == 0 else .005
        self.assertFalse(any(f['detail'] == 'dense stutter cycles' for f in checks.progress(timeline.states(rows))))
        rows, x = [], 0
        for t in range(100):
            rows.append(state(t, p=[x, 0, 0], goals=['DailyGoal']))
            x += .02 if t % 4 == 0 else 0
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.progress(timeline.states(rows))))

    def test_completed_carries_are_not_ownership_churn(self):
        # H: colony_migrate_babies-45, child de4405b8: released at x=-675.46,
        # picked up there, then released at x=-649.28. Birth-land's father
        # also unloads three different newborns, rather than toggling one child.
        rows = [state(t, p=[t * 2, 0, 0], att={'kind': 'held' if t % 2 else 'free',
                      'host': HOST if t % 2 else None, 'place': 'ground'}) for t in range(20)]
        self.assertFalse(checks.loops(rows, timeline.states(rows)))
        rows = [state(t, hold={'carry': [{'child': str(t), 'hold': 'braid'}] if t % 2 else []}) for t in range(20)]
        self.assertFalse(checks.loops(rows, timeline.states(rows)))
        for row in rows:
            if row['st']['hold']['carry']:
                row['st']['hold']['carry'][0]['child'] = HOST
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.loops(rows, timeline.states(rows))))

    def test_actual_item_expiry_is_distinct_from_unexplained_discard(self):
        rows = [record('st', 0, st={'p': [0, 0, 0], 'item': 'minecraft:lily_pad', 'n': 1}),
                state(0), state(1), record('life', 1, out='discarded', item='minecraft:lily_pad', age=6000, lifespan=6000)]
        rows[1]['u'] = rows[2]['u'] = HOST
        self.assertFalse(checks.ledger(rows, timeline.states(rows)))
        for changed in (dict(age=5999), dict(item='minecraft:apple')):
            broken = rows[:-1] + [rows[-1] | changed]
            self.assertTrue(any(f['verdict'] == 'fail' for f in checks.ledger(broken, timeline.states(broken))))
        broken = rows[:-1] + [record('life', 1, out='discarded')]
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.ledger(broken, timeline.states(broken))))

    def test_ledger_evidence_names_a_changed_holder(self):
        rows = [state(0), state(1, hold={'mouth': [{'item': 'minecraft:apple', 'n': 1}]}), state(0), state(1)]
        for row in rows[2:]:
            row['u'] = HOST
        rows.sort(key=lambda r: r['t'])
        finding = next(f for f in checks.ledger(rows, timeline.states(rows)) if f['verdict'] == 'fail')
        self.assertEqual(finding['actor'], ACTOR)
        self.assertEqual(finding['items'], {'minecraft:apple': 1})

    def test_carrier_turn_is_not_child_displacement(self):
        rows = [state(0, by=180), state(1, by=90),
                state(0, p=[1, 1, 0], att={'kind': 'held', 'host': HOST}),
                state(1, p=[0, 1, -1], att={'kind': 'held', 'host': HOST})]
        for row in rows[:2]:
            row['u'] = HOST
        rows.sort(key=lambda r: r['t'])
        self.assertFalse(checks.jumps(timeline.states(rows)))
        next(r for r in rows if r['u'] == ACTOR and r['t'] == 1)['st']['p'] = [0, 1, -3]
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.jumps(timeline.states(rows))))

    def test_observed_idle_and_look_goals_are_not_stalled_jobs(self):
        for fields in (dict(behavior='idle', goals=['PitSlaveGoal@4']), dict(goals=['RandomLookAroundGoal@9'])):
            rows = [state(t, int=None, **fields) for t in range(1202)]
            self.assertFalse(checks.progress(timeline.states(rows)))
        rows = [state(t, behavior='idle', int={'id': 'travel'}) for t in range(1202)]
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.progress(timeline.states(rows))))

    def test_bounded_flight_timer_and_normal_airborne_contacts(self):
        rows = [state(t, act={'id': 'launch', 'owner': 'childhood_practice', 'metric': 30 - t},
                      att={'kind': 'airborne' if t % 2 else 'free', 'host': None, 'place': 'ground'},
                      p=[0, .02 * ((t // 2) % 2), 0]) for t in range(30)]
        self.assertFalse(checks.progress(timeline.states(rows)))
        self.assertFalse(checks.loops(rows, timeline.states(rows)))
        for row in rows:
            row['st']['act']['metric'] = 30
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.progress(timeline.states(rows))))

    def test_counted_store_transfer_explains_only_recorded_kinds(self):
        for msg in ('stores taken by=fixture use=feed kind=minecraft:apple count=4 -> 3',
                    'stores spilled by=fixture kinds={minecraft:apple=4} -> {} total=4 -> 0'):
            rows = [state(0), state(1, hold={'mouth': [{'item': 'minecraft:apple', 'n': 1}]}),
                    record('ev', 1, cat='hat', msg=msg)]
            self.assertFalse(checks.ledger(rows, timeline.states(rows)))
            rows[1]['st']['hold']['mouth'][0]['item'] = 'minecraft:stick'
            self.assertTrue(any(f['verdict'] == 'fail' for f in checks.ledger(rows, timeline.states(rows))))

    def test_status_and_diagnostic_fields_are_not_refusal_attempts(self):
        for category, message in (
                ('aquatic', 'drive evaluated current=none failed=[]'),
                ('aquatic', 'drive evaluated current=none failed=[take_seat]'),
                ('aquatic', 'stick rejected id=4 uuid=fixture reason=wet floor=null range=12.0 walk=false'),
                ('family', 'carry put down rejected=null'),
                ('status', 'behavior.protection: decision=waiting -> decision=unavailable'),
                ('status', 'body.last: outcome=BLOCKED -> outcome=BLOCKED')):
            self.assertFalse(checks.is_refusal(category, message))
            raw = f'[{category}] reimu #1 uuid={ACTOR} t=1 dim=minecraft:overworld pos=0, 0, 0 {message} (suppressed=499)'
            self.assertFalse(checks.fates(raw))
        raw = f'[approach] reimu #1 uuid={ACTOR} t=1 dim=minecraft:overworld pos=0, 0, 0 contact stand unavailable (suppressed=399)'
        self.assertTrue(checks.fates(raw))

    def test_recorded_step_capability_and_overshoot(self):
        rows = [state(0, step=1, v=[0, -.0784, 0]), state(1, step=1, p=[0, 1, 0])]
        self.assertFalse(checks.jumps(timeline.states(rows)))
        rows[1]['st']['p'][1] = 2.5
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.jumps(timeline.states(rows))))

    def test_retained_velocity_while_held_still_is_not_a_position_jump(self):
        # birth_stem_caught-39 t303143: take_prop holds position while
        # the post-tick velocity remains (.266, -.0784, -.160).
        rows = [state(0, v=[.232, -.0784, -.151]), state(1, v=[.266, -.0784, -.160])]
        self.assertFalse(checks.jumps(timeline.states(rows)))
        rows[1]['st']['p'] = [0, 0, 3]
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.jumps(timeline.states(rows))))
        rows = [state(0, step=1, g=False), state(1, step=1, g=False, p=[0, 1.1, 0])]
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.jumps(timeline.states(rows))))

    def test_explicit_setup_placement_does_not_exempt_other_jumps(self):
        rows = [state(0), state(1, p=[3, 0, 0]), state(2, p=[6, 0, 0])]
        placement = record('ev', 1, cat='body', msg='relocate cause=scenario_initial_seat from=(0.00,0.00,0.00) to=(3.00,0.00,0.00)')
        findings = checks.jumps(timeline.states(rows), [placement])
        self.assertEqual([(f['t'], f['verdict']) for f in findings], [(2, 'fail')])
        for changed in (dict(u=HOST), dict(t=2), dict(msg=placement['msg'].replace('3.00', '4.00')),
                        dict(msg=placement['msg'].replace('scenario_initial_seat', 'agitation_pin'))):
            self.assertTrue(any(f['t'] == 1 for f in checks.jumps(timeline.states(rows), [placement | changed])))

    def test_observed_wait_counter_must_actually_advance(self):
        rows = [state(t, goals=['BirthGoal@2'], phase='term', waitElapsed=t) for t in range(1202)]
        self.assertFalse(checks.progress(timeline.states(rows)))
        # R rain_huddle: the leader first walks, then starts the bounded huddle
        # clock. An earlier null anchor must not hide subsequent clock progress.
        rows = [state(0, goals=['WeatherGoal'], waitElapsed=None)] + [
            state(t, goals=['WeatherGoal'], waitElapsed=t) for t in range(1, 1202)]
        self.assertFalse(checks.progress(timeline.states(rows)))
        rows = [state(t, goals=['BirthGoal@2'], phase='term', waitElapsed=0) for t in range(1202)]
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.progress(timeline.states(rows))))
        _, issues = timeline.parse([log([state(0, waitElapsed='bad counter')])])
        self.assertTrue(issues)

    def test_declared_death_does_not_duplicate_as_unexpected_terminal_damage(self):
        death = 'death settled into remains; cause=genericKill'
        damage = 'accepted damage cause=genericKill health=10.0 -> 0.0 loss=10.0'
        rows = [record('ev', 1, cat='body', msg=msg) for msg in (death, damage)]
        raw = '\n'.join(f'[body] reimu #1 uuid={ACTOR} t=1 dim=minecraft:overworld pos=0, 0, 0 {msg}'
                        for msg in (death, damage))
        allow = [{'kind': 'death', 'who': ACTOR, 'match': 'cause=genericKill', 'why': 'declared smoke widowhood'}]
        report = checks.observations(rows, raw=raw, allow=allow)
        self.assertEqual(next(f['verdict'] for f in report['summary'] if f['check'] == 'FATE'), 'pass')
        report = checks.observations(rows, raw=raw)
        self.assertEqual(next(f['verdict'] for f in report['summary'] if f['check'] == 'FATE'), 'fail')
        # The allowance is for death, not an arbitrary hit without a matching death event.
        report = checks.observations(rows[1:], raw=raw.splitlines()[1], allow=allow)
        self.assertEqual(next(f['verdict'] for f in report['summary'] if f['check'] == 'FATE'), 'suspect')

    def test_lifecycle_before_inventory_baseline_and_missing_snapshots(self):
        rows = [record('life', 0, **{'in': 'spawn'}), state(1), state(2)]
        self.assertFalse(checks.ledger(rows, timeline.states(rows)))
        rows = [record('life', t, **{'in': 'spawn'}) for t in range(3)]
        self.assertTrue(any(f['verdict'] == 'no-evidence' for f in checks.ledger(rows, [])))

    def test_life_goal_recovery_without_intent_is_progress(self):
        for key in ('nut', 'en'):
            rows = [state(t, int=None, goals=['LifeGoal@3'], need={key: t / 20}) for t in range(1202)]
            self.assertFalse(checks.progress(timeline.states(rows)))
        rows = [state(t, int=None, goals=['LifeGoal@3'], need={'en': 100}) for t in range(1202)]
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.progress(timeline.states(rows))))

    def test_logged_acquisition_and_stem_are_explanations(self):
        for message in ('received item=minecraft:short_grass count=1 holder=mouth',
                        'gathered item=minecraft:short_grass count=1 source=BlockPos{x=0,y=0,z=0} holder=mouth',
                        'shed stem delivered brood=fixture item=fixture existing=false'):
            rows = [state(0), state(1, hold={'mouth': [{'item': 'minecraft:short_grass', 'n': 1}]}),
                    record('ev', 1, cat='meal', msg=message)]
            self.assertFalse(checks.ledger(rows, timeline.states(rows)))
        # The same physical change with no successful transaction must remain a failure.
        rows = [state(0), state(1, hold={'mouth': [{'item': 'minecraft:short_grass', 'n': 1}]})]
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.ledger(rows, timeline.states(rows))))

    def test_logged_loose_accessory_pickup_explains_only_the_hat(self):
        item = 'mcyukkuri:marisa_aquatic_hat'
        rows = [state(0, hold={'mouth':[{'item':item,'n':1}]}), state(1),
                record('ev',1,cat='headwear',msg=f'received loose accessory item={item} state=worn damage=0 player=null')]
        self.assertFalse(checks.ledger(rows,timeline.states(rows)))
        rows[-1]['msg'] = 'received loose accessory item=mcyukkuri:reimu_bow state=worn damage=0 player=null'
        self.assertTrue(any(f['verdict']=='fail' for f in checks.ledger(rows,timeline.states(rows))))

    def test_acquisition_cannot_explain_a_different_item_or_a_late_change(self):
        for item, tick in (('minecraft:apple', 1), ('minecraft:short_grass', 4)):
            rows = [state(0), state(tick, hold={'mouth': [{'item': item, 'n': 1}]}),
                    record('ev', 1, cat='meal', msg='received item=minecraft:short_grass count=1 holder=mouth')]
            self.assertTrue(any(f['verdict'] == 'fail' for f in checks.ledger(rows, timeline.states(rows))))

    def test_attachment_geometry_is_not_ownership_flipping(self):
        rows = [state(t, att={'kind': 'on_host', 'host': HOST, 'place': 'cabin',
                              'spot': [0, t % 2, 0], 'frame': {'yaw': t % 2}}) for t in range(20)]
        self.assertFalse(checks.loops(rows, timeline.states(rows)))
        rows = [state(t, att={'kind': 'on_host', 'host': HOST if t % 2 else None, 'place': 'cabin'})
                for t in range(20)]
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.loops(rows, timeline.states(rows))))

    def test_evidence_index_preserves_original_window_and_order(self):
        rows = [record('win', 0, None), state(1), state(2, p=[3, 0, 0]),
                record('lag', 2, None, ms=25), record('lag', 103, None, ms=25)]
        report = checks.observations(rows)
        for finding in report['findings']:
            expected = [r for r in rows if finding['t'] is not None and abs(r['t'] - finding['t']) <= 100
                        and r['u'] in (finding['actor'], None)]
            self.assertEqual(expected, finding['timeline'])

    def test_small_turns_do_not_repeat_one_yaw_reversal(self):
        # Sleep's roaming actor kept moving: twelve reported reversals, only two >1 degree.
        rows = [state(0, yaw=0, goals=['RoamingGoal@7']), state(1, yaw=2, goals=['RoamingGoal@7'])]
        rows += [state(t, yaw=2 - (t - 1) * .2, p=[t * .05, 0, 0], goals=['RoamingGoal@7']) for t in range(2, 31)]
        self.assertFalse(checks.progress(timeline.states(rows)))

    def test_real_large_yaw_reversals_still_fail(self):
        rows = [state(t, yaw=10 * (t % 2), goals=['SocialGoal@6']) for t in range(20)]
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.progress(timeline.states(rows))))

    def test_path_steering_while_advancing_is_not_in_place_oscillation(self):
        # sleep-6 t101012..101112 advances about seven blocks through HabitatGoal path nodes.
        rows = [state(t, yaw=225 + 2 * ((t // 10) % 2), p=[t * .07, 0, 0], goals=['HabitatGoal@4']) for t in range(101)]
        self.assertFalse(checks.progress(timeline.states(rows)))

    def test_translating_stop_go_still_fails(self):
        rows = [state(t, p=[(t // 2) * .1, 0, 0], goals=['SocialGoal@6']) for t in range(40)]
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.progress(timeline.states(rows))))

    def test_advancing_gesture_is_bounded_work_but_frozen_gesture_is_not(self):
        rows = [state(t, p=[0, .1 * (t % 4 // 2), 0],
                      act={'id': 'gesture', 'owner': 'body', 'metric': 100 - t, 'win': 40})
                for t in range(40)]
        self.assertFalse(checks.progress(timeline.states(rows)))
        for row in rows:
            row['st']['act']['metric'] = 100
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.progress(timeline.states(rows))))

    def test_passive_pit_stay_uses_actual_phase_despite_illness_presentation(self):
        rows = [state(t, goals=['PitSlaveGoal@4'], int=None,
                      behavior='non_yukkuri_wither', pitPhase='STAY') for t in range(1202)]
        self.assertFalse(checks.progress(timeline.states(rows)))
        for row in rows:
            row['st']['pitPhase'] = 'RETURN'
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.progress(timeline.states(rows))))
        for row in rows:
            row['st']['pitPhase'] = 'STAY'
            row['st']['int'] = {'id': 'travel'}
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.progress(timeline.states(rows))))

    def test_separate_completed_jobs_do_not_accumulate_stutter(self):
        rows = [state(t, p=[(t // 2) * .1, 0, 0], phase=str(t // 4), goals=['FamilyGoal@5']) for t in range(40)]
        self.assertFalse(checks.progress(timeline.states(rows)))

    def test_surface_support_is_not_depth_to_underwater_floor(self):
        rows = [record('win', 0, None), state(1), state(20)]
        rows += [record('geo', t, clear=True, supported=True, drop=1.47943115234375)
                 for t in (1, 20)]
        report = checks.observations(rows)
        self.assertEqual(next(f['verdict'] for f in report['summary'] if f['check'] == 'SPAWN'), 'pass')

    def test_unsupported_drop_and_obstruction_still_fail(self):
        for clear, supported, drop in ((True, False, .0626), (False, True, 0)):
            with self.subTest(clear=clear, supported=supported, drop=drop):
                rows = [record('win', 0, None), state(1), state(20)]
                rows += [record('geo', t, clear=clear, supported=supported, drop=drop) for t in (1, 20)]
                report = checks.observations(rows)
                self.assertEqual(next(f['verdict'] for f in report['summary'] if f['check'] == 'SPAWN'), 'fail')


if __name__ == '__main__':
    unittest.main(verbosity=2)
