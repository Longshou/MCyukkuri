"""Drive real dedicated clients against the latest frozen joint runtime.

Each phase records requests and assertions below that runtime. Worlds are disposable;
the launcher uses the existing explicit EULA acceptance and local port 25619.
"""
import argparse
import json
from pathlib import Path
import time
import run_p0 as control
import run_port_integration as runtime

RUN=Path(json.loads((runtime.WORK/'latest.json').read_text())['runtime'])
control.WORK=RUN/'runs'
EVIDENCE=RUN/'network'
EVIDENCE.mkdir(exist_ok=True)
events=[]
checks=[]

def req(target,op,**args):
    try:result=control.request(target,op,**args)
    except Exception as error:
        events.append({'target':target,'op':op,'arguments':args,'error':str(error)});flush()
        raise
    events.append({'target':target,'op':op,'arguments':args,'result':result})
    flush()
    return result

def check(name,condition,detail=None):
    checks.append({'name':name,'pass':bool(condition),'detail':detail})
    flush()
    if not condition: raise AssertionError(name+': '+str(detail))

def flush():
    runtime.write(EVIDENCE/(PHASE+'-events.json'),events)
    runtime.write(EVIDENCE/(PHASE+'-checks.json'),checks)

def wait_marker(target,marker,timeout=60):
    path=control.WORK/target/'p0-control'/f'{marker}.json'
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        if path.exists():return json.loads(path.read_text())
        time.sleep(.3)
    raise TimeoutError(str(path))

def compare_clients(snapshot,label):
    expected={e['network']['uuid']:e['network'] for e in snapshot['entities']}
    for target in ('client-a','client-b'):
        observed={e['uuid']:e for e in req(target,'integration-snapshot')}
        for identity,row in expected.items():
            check(f'{label}: {target} sees {identity}',identity in observed)
            for field,value in row.items():
                check(f'{label}: {target} {identity} {field}',observed[identity][field]==value,
                      {'server':value,'client':observed[identity][field]})

def simultaneous(op,allow_absent=False,**args):
    requests={target:control.send(target,op,**args) for target in ('client-a','client-b')}
    responses=[]
    for target,identity in requests.items():
        response=control.wait(target,identity)
        responses.append(response)
        events.append({'target':target,'op':op,'arguments':args,'result':response});flush()
        absent=allow_absent and response.get('result')=='java.lang.IllegalStateException: Target absent on client'
        label=f'{target} {op} packet handled'
        if allow_absent:label+=' or competing capture already synchronized'
        check(label,response['pass'] or absent,response)
    check(op+' sent by at least one real client',any(r['pass'] for r in responses),responses)

def subject(snapshot,index=0):
    return next(e for e in snapshot['entities'] if e['index']==f'integration_index_{index}')

def baseline():
    runtime.launch('server');wait_marker('server','ready')
    req('server','setup')
    results=req('server','checks',timeout=240)
    for result in results:check('dedicated baseline: '+result['name'],result['pass'],result.get('detail'))
    for target in ('client-a','client-b'):
        runtime.launch(target);wait_marker(target,'joined',90)
        time.sleep(1)
        state=req('server','snapshot');client=req(target,'snapshot')
        observed={e['uuid']:e for e in client['entities']}
        for row in state['entities']:
            for key in ('variant','action','start','expression'):
                check(f'{target} late-join {row["uuid"]} {key}',observed[row['uuid']][key]==row[key])
    feed=req('server','network-feed');time.sleep(.6);simultaneous('interact',target=feed['target'])
    time.sleep(.8)
    players=req('server','players');check('two real clients consume exactly one competing apple',sum(p['held'] for p in players)==3,players)
    req('client-b','reload',timeout=60)
    assets=req('client-b','assets',timeout=90);check('nine species decode/render after resource reload',assets['mandatoryAtlases']==45,assets)
    check('eight subspecies decode all sixteen expressions after reload',assets['subspeciesAtlases']==128,assets)

def start():
    runtime.launch('server');wait_marker('server','ready')
    for target in ('client-a','client-b'):
        runtime.launch(target);wait_marker(target,'joined',90)
    check('isolated dedicated server and two real clients connected',len(req('server','players'))==2)

def human_trade():
    req('server','integration-human-setup',timeout=90);time.sleep(2)
    req('server','integration-human-freeze')
    state=req('server','integration-human-snapshot')
    check('five actual human building templates populated NPCs and animals',len(state['humans'])>=5 and len(state['animals'])>0,
          {'humans':len(state['humans']),'animals':len(state['animals'])})
    req('server','integration-human-menu');time.sleep(.8)
    menus={target:req(target,'integration-menu') for target in ('client-a','client-b')}
    owners=[target for target,menu in menus.items() if menu['type']=='HumanMenu']
    check('real shared shop admits one active customer',len(owners)==1,menus)
    first=owners[0];second='client-b' if first=='client-a' else 'client-a'
    before=req('server','integration-snapshot')
    req(first,'integration-menu-button',button=0);time.sleep(.6)
    after=req('server','integration-snapshot')
    check('real supply purchase debits exactly one emerald',sum(p['emeralds'] for p in before['players'])-sum(p['emeralds'] for p in after['players'])==1,after['players'])
    check('real supply purchase delivers eight oil grains',sum(any('8 mcyukkuri:oil_grain_regular'==item for item in p['inventory']) for p in after['players'])==1,after['players'])
    req(first,'capture',name='joint-human-shop',keepScreen=True)
    req(first,'integration-close-menu');time.sleep(.5)
    req('server','integration-human-menu-one',player='P0ObserverA' if second=='client-a' else 'P0ObserverB');time.sleep(.7)
    menu=req(second,'integration-menu');check('second real client can trade after first closes',menu['type']=='HumanMenu',menu)
    req(second,'integration-close-menu')
    runtime.write(EVIDENCE/'human-before-restart.json',req('server','integration-human-state'))
    runtime.write(EVIDENCE/'human-animals-before-restart.json',req('server','integration-human-snapshot'))

def joint():
    req('server','integration-prepare');time.sleep(2)
    state=req('server','integration-setup');check('all nine registered variants spawned',len(state['entities'])==9)
    time.sleep(1.5);state=req('server','integration-snapshot');compare_clients(state,'initial nine species')
    runtime.write(EVIDENCE/'initial.json',state)
    mother=subject(state);check('fixture has real allocated live brood',mother['brood']['kind']=='LIVE' and mother['brood']['children'])
    req('server','integration-hand',item='notebook');time.sleep(.5)
    simultaneous('interact',target=mother['id']);time.sleep(.8)
    for target in ('client-a','client-b'):
        pages=req(target,'book-page',page=14)
        check(target+' receives all fifteen server-authored notebook pages',len(pages['pageHeights'])==15,pages)
        req(target,'capture',name='joint-cognition-page',keepScreen=True)
        req(target,'close-screen')
    state=req('server','integration-hand',index=4,item='reproductive');target_id=subject(state,4)['id'];time.sleep(.5)
    simultaneous('interact',target=target_id);time.sleep(.8)
    state=req('server','integration-snapshot')
    check('one reproductive dose consumed across two real clients',sum(p['count'] for p in state['players'])==3,state['players'])
    end=time.monotonic()+25
    while 'brood' not in subject(state,4) and time.monotonic()<end:
        time.sleep(.5);state=req('server','integration-snapshot')
    check('actual assistance completes with one authoritative live brood','brood' in subject(state,4),subject(state,4))
    state=req('server','integration-freeze');time.sleep(.6);compare_clients(req('server','integration-snapshot'),'after actual dose')
    state=req('server','integration-hand',item='carrier');time.sleep(.6)
    simultaneous('interact',allow_absent=True,target=subject(state)['id']);time.sleep(.8)
    state=req('server','integration-snapshot');holders=[p for p in state['players'] if p.get('token')]
    check('competing capture issues exactly one capability',len(holders)==1,holders)
    check('captured pregnant mother absent from actual world',mother['network']['uuid'] not in [e['network']['uuid'] for e in state['entities']])
    winner='client-a' if holders[0]['name']=='P0ObserverA' else 'client-b'
    loser='client-b' if winner=='client-a' else 'client-a'
    runtime.write(EVIDENCE/'custody.json',{'winner':winner,'loser':loser,'mother':mother})
    req('server','integration-cabinet');time.sleep(.6)
    req(winner,'block-interact',x=6153,y=65,z=7);time.sleep(.5)
    req(loser,'block-interact',x=6153,y=65,z=7);time.sleep(.5)
    state=req('server','integration-snapshot')
    check('personal cold vault has one occupant; other player cannot retrieve it',sorted(p['vault'] for p in state['players'])==[0,1] and all(not p.get('token') for p in state['players']),state['players'])
    human_trade()
    req('server','integration-view');time.sleep(.7)
    compare_clients(state,'mother frozen')
    req('client-a','capture',name='joint-mother-in-vault')
    state=req('server','integration-save');runtime.write(EVIDENCE/'before-unload.json',state)
    req('server','integration-unload')
    end=time.monotonic()+50
    while True:
        unloaded=req('server','integration-unloaded')
        if not unloaded['chunk'] and unloaded['entities']==0:break
        if time.monotonic()>end:raise TimeoutError('Joint arena did not actually unload')
        time.sleep(1)
    check('actual joint chunk unload reached zero entities',True,unloaded)
    req('server','integration-load');time.sleep(2)
    after=req('server','integration-snapshot');compare_persisted(state,after,'chunk reload')
    compare_clients(after,'chunk reload')
    runtime.write(EVIDENCE/'before-restart.json',req('server','integration-save'))
    for target in ('client-a','client-b'):req(target,'stop')
    req('server','stop')

def compare_persisted(before,after,label):
    old={e['network']['uuid']:e for e in before['entities']};new={e['network']['uuid']:e for e in after['entities']}
    check(label+' entity UUID set',old.keys()==new.keys())
    for identity,row in old.items():
        for key in ('network','family','badges','cognition','subspecies','custody','brood'):
            check(f'{label} {identity} {key}',row.get(key)==new[identity].get(key),{'before':row.get(key),'after':new[identity].get(key)})
    for player in before['players']:
        restored=next(p for p in after['players'] if p['uuid']==player['uuid'])
        for key in ('token','vault','occupants'):
            check(f'{label} {player["name"]} {key}',player.get(key)==restored.get(key))

def restart():
    runtime.launch('server');wait_marker('server','ready')
    for target in ('client-a','client-b'):
        runtime.launch(target);wait_marker(target,'joined',90)
    req('server','integration-load');time.sleep(2)
    after=req('server','integration-snapshot')
    before=json.loads((EVIDENCE/'before-restart.json').read_text())
    compare_persisted(before,after,'normal process restart');compare_clients(after,'normal process restart')
    humans=req('server','integration-human-state')
    check('factory production and pipeline NBT survives normal disk restart',humans==json.loads((EVIDENCE/'human-before-restart.json').read_text(encoding='utf-8')),humans)
    human_animals=req('server','integration-human-snapshot');old_humans=json.loads((EVIDENCE/'human-animals-before-restart.json').read_text(encoding='utf-8'))
    check('human animal UUID and custody survives disk restart',sorted(human_animals['animals'],key=lambda e:e['uuid'])==sorted(old_humans['animals'],key=lambda e:e['uuid']))
    custody=json.loads((EVIDENCE/'custody.json').read_text());winner=custody['winner']
    req('server','integration-cabinet');time.sleep(.6)
    req(winner,'block-interact',x=6153,y=65,z=7);time.sleep(.6)
    state=req('server','integration-snapshot')
    check('same player retrieves a new live capability after disk restart',sum(bool(p.get('token')) for p in state['players'])==1 and all(p['vault']==0 for p in state['players']),state['players'])
    req('server','integration-release-view');time.sleep(.6)
    req(winner,'block-interact',x=6148,y=64,z=10);time.sleep(.8)
    state=req('server','integration-snapshot');mother=subject(state);original=custody['mother']
    for key in ('network','cognition','badges','subspecies','brood'):
        check('released original mother preserves '+key,mother[key]==original[key],{'before':original[key],'after':mother[key]})
    check('release cleared the single capability',all(not p.get('token') for p in state['players']))
    req('server','integration-view');time.sleep(.8);compare_clients(req('server','integration-snapshot'),'released original mother')
    req('server','integration-hand',item='notebook');time.sleep(.6)
    req('client-a','capture',name='joint-restored-pregnancy')
    recording=control.send('client-b','record',name='joint-birth-transition',ticks=200,every=4)
    time.sleep(.4)
    req('server','integration-birth')
    end=time.monotonic()+30
    while True:
        time.sleep(.5);state=req('server','integration-snapshot')
        children=[e for e in state['entities'] if e['index']=='born_child']
        if len(children)==len(original['brood']['children']):break
        if time.monotonic()>end:raise TimeoutError('Actual live-birth sequence did not complete')
    expected={c['id'] for c in original['brood']['children']}
    check('resumed brood births exactly its original UUID set',{e['network']['uuid'] for e in children}==expected)
    check('newborn cognition records original mother',all(e['cognition'].get('mother')==original['network']['uuid'] for e in children),children)
    check('newborns inherit innate cognition and no learned points',all(e['cognition']['learned']==0 for e in children),children)
    req('server','integration-freeze')
    recorded=control.wait('client-b',recording)
    events.append({'target':'client-b','op':'record','result':recorded});flush()
    check('client framebuffer sequence captured during actual birth',recorded['pass'],recorded)
    req('server','integration-view');time.sleep(.8)
    compare_clients(req('server','integration-snapshot'),'actual live newborns')
    req('server','integration-hand',item='notebook');time.sleep(.6)
    req('client-a','capture',name='joint-newborns')
    runtime.write(EVIDENCE/'after-birth.json',state)

def stem():
    state=req('server','integration-stem-setup',fullSize=True,timeout=60)
    check('real plain cut transfers exactly two allocated children',state['count']==2 and len(state['children'])==2,state)
    check('second mature child retains ground clearance',state['secondMatureBottom']>=.049,state)
    for side in (False,True):
        req('server','integration-stem-view',side=side);time.sleep(2)
        for target in ('client-a','client-b'):
            req(target,'capture',name='joint-stem-'+('side' if side else 'front'))
    runtime.write(EVIDENCE/'stem-preview.json',state)


def stop():
    for target in ('client-a','client-b','server'):
        try:req(target,'stop',timeout=10)
        except Exception as error:events.append({'target':target,'stopError':str(error)});flush()

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase',choices=['baseline','start','joint','restart','stem','stop'])
    PHASE=parser.parse_args().phase
    try:globals()[PHASE]()
    finally:flush()
    print(json.dumps({'phase':PHASE,'checks':len(checks),'failed':sum(not c['pass'] for c in checks)},indent=2))
