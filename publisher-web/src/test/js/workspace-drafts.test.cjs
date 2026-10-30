const {test} = require('node:test');
const assert = require('node:assert/strict');
const {initWorkspaceDraft} = require('../../main/resources/static/assets/workspace-drafts.js');
function fixture() {
    const listeners = {}, fields = {draftRevision:{value:'0'}, title:{value:''}, markdown:{value:''}}, pending=[];
    const state={textContent:''}, storage=new Map(), timers=[];
    let submitted=0;
    const form={dataset:{workspaceDraft:'new',workspaceOwner:'test'}, elements:{namedItem:n=>fields[n]},
        querySelector:s=>s==='[data-workspace-state]'?state:null, addEventListener:(n,f)=>listeners[n]=f,
        checkValidity:()=>true,requestSubmit:()=>{submitted++;}};
    const win={setTimeout:(callback,delay)=>{timers.push({callback,delay});return timers.length;},clearTimeout:()=>{},addEventListener:()=>{},confirm:()=>false,
        sessionStorage:{setItem:(k,v)=>storage.set(k,v),getItem:k=>storage.get(k),removeItem:k=>storage.delete(k)}};
    const saver=initWorkspaceDraft(form,{window:win,fetch:(u,o)=>new Promise(resolve=>pending.push({body:JSON.parse(o.body),resolve}))});
    return {...saver,pending,fields,state,storage,timers,submit:()=>listeners.submit({preventDefault:()=>{}}),
        submitted:()=>submitted,input:t=>{fields.title.value=t;listeners.input({target:{name:'title'}});}};
}
const ok=revision=>({ok:true,status:200,json:async()=>({revision,updatedAt:'2026-10-30T00:00:00Z'})});
test('saves incomplete input and carries server revision to subsequent writes',async()=>{
    const f=fixture();f.input('未完成');const first=f.save();
    assert.equal(f.pending[0].body.fields.markdown,'');f.pending[0].resolve(ok(1));await first;
    f.input('继续写');const second=f.save();assert.equal(f.pending[1].body.revision,1);
    f.pending[1].resolve(ok(2));await second;assert.equal(f.fields.draftRevision.value,'2');
});
test('conflict preserves input and blocks later overwrites',async()=>{
    const f=fixture();f.input('我的文案');const first=f.save();
    f.pending[0].resolve({ok:false,status:409,json:async()=>({message:'其他页面已有新草稿'})});
    assert.equal(await first,false);f.input('继续修改');assert.equal(await f.save(),false);
    assert.equal(f.pending.length,1);assert.ok(f.storage.size);
});
test('a late success cannot clear a newer emergency draft',async()=>{
    const f=fixture();f.input('旧');const first=f.save();f.input('新');f.pending[0].resolve(ok(1));await first;
    assert.equal(JSON.parse([...f.storage.values()][0]).title,'新');
});
test('already saved forms resume native submission in a new browser task',async()=>{
    const f=fixture();await f.submit();assert.equal(f.submitted(),0);
    f.timers.find(t=>t.delay===0).callback();assert.equal(f.submitted(),1);
});
test('edits before deferred submission must be saved before proceeding',async()=>{
    const f=fixture();await f.submit();f.input('刚刚修改');
    f.timers.find(t=>t.delay===0).callback();assert.equal(f.submitted(),0);
    assert.ok(f.state.textContent.includes('再次保存'));
});
