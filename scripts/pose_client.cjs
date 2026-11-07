/* Synchronous callers share one warm JSON-lines Java process in an I/O worker. */
'use strict';
const {Worker,MessageChannel,receiveMessageOnPort}=require('worker_threads');
const path=require('path');
let worker,port,signal;
function request(value){
 if(!worker){
  const channel=new MessageChannel();port=channel.port1;signal=new Int32Array(new SharedArrayBuffer(4));
  worker=new Worker(path.join(__dirname,'pose_worker.cjs'),{workerData:{port:channel.port2,signal},transferList:[channel.port2]});
  worker.unref();port.unref();
 }
 Atomics.store(signal,0,0);port.postMessage(value);
 Atomics.wait(signal,0,0);
 const result=receiveMessageOnPort(port).message;
 if(result.error)throw Error(result.error);
 return result;
}
module.exports={request};
