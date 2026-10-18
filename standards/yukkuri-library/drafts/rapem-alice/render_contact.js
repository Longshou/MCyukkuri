'use strict';
// Combine only the two current, separately reviewable close-ups.
const fs=require('fs'),path=require('path');
const {decode,encode}=require('./png.js');
const out=path.resolve(__dirname,'../../../../.local/previews/rapem-alice');
const first=decode(path.join(out,'alice-agitated-closeup.png'));
const second=decode(path.join(out,'sukkiri-closeup.png'));
if(first.w!==second.w)throw new Error('Close-up widths must match');
const data=Buffer.concat([first.data,second.data]);
encode(first.w,first.h+second.h,data,path.join(out,'contact.png'));
console.log('wrote current close-up contact.png');
