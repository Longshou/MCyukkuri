'use strict';
const {workerData}=require('worker_threads'),{spawn}=require('child_process'),path=require('path'),readline=require('readline');
const python=process.env.POSE_PYTHON||(process.platform==='win32'?'C:/Users/Administrator/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe':'python3');
const server=spawn(python,[path.join(__dirname,'pose_check.py'),'--serve'],{env:{...process.env,PYTHONUTF8:'1'},stdio:['pipe','pipe','inherit']});
let pending=false,failure=null;
function reply(result){workerData.port.postMessage(result);Atomics.store(workerData.signal,0,1);Atomics.notify(workerData.signal,0);pending=false;}
readline.createInterface({input:server.stdout}).on('line',line=>reply(JSON.parse(line)));
server.on('error',e=>{failure=e.message;if(pending)reply({error:failure});});
server.on('exit',code=>{failure='Pose server exited: '+code;if(pending)reply({error:failure});});
server.stdin.on('error',e=>{failure=e.message;if(pending)reply({error:failure});});
workerData.port.on('message',value=>{pending=true;if(failure)reply({error:failure});else server.stdin.write(JSON.stringify(value)+'\n');});
process.on('exit',()=>server.kill());
