const {test} = require('node:test');
const assert = require('node:assert/strict');
const {initDraftAutosave} = require('../../main/resources/static/assets/draft-autosave.js');

function fixture({storageFails = false, valid = true} = {}) {
    const listeners = {}, events = {}, storage = new Map(), pending = [], timers = new Map();
    const fields = Object.fromEntries(['expectedVersion', 'title', 'summary', 'markdown'].map(
        name => [name, {value: name === 'expectedVersion' ? '1' : name}]));
    const state = {textContent: ''};
    let id = 0, submitted = 0, prevented = false;
    const form = {
        dataset: {draftUrl: '/api/v1/articles/test/draft', draftOwner: 'personal:owner'},
        elements: {namedItem: name => fields[name]},
        querySelector: () => null,
        checkValidity: () => valid,
        addEventListener: (type, fn) => { listeners[type] = fn; },
        requestSubmit: () => { submitted++; }
    };
    const win = {
        sessionStorage: {
            setItem: (key, value) => { if (storageFails) throw Error('denied'); storage.set(key, value); },
            getItem: key => storage.get(key),
            removeItem: key => storage.delete(key)
        },
        setTimeout: fn => { timers.set(++id, fn); return id; },
        clearTimeout: key => timers.delete(key),
        addEventListener: (name, fn) => { events[name] = fn; },
        confirm: () => false
    };
    const saver = initDraftAutosave(form, {
        window: win, document: {querySelector: () => state},
        fetch: (_url, options) => new Promise((resolve, reject) =>
            pending.push({body: JSON.parse(options.body), resolve, reject}))
    });
    return {
        ...saver, pending, storage, state, fields, events, timers,
        input: title => { fields.title.value = title; listeners.input(); },
        submit: () => listeners.submit({preventDefault() { prevented = true; }, submitter: {name: 'intent', value: 'ready'}}),
        prevented: () => prevented,
        submitted: () => submitted
    };
}
const ok = () => ({ok: true, status: 200, json: async () => ({updatedAt: '2026-09-22T06:00:00Z'})});

test('serializes requests and does not acknowledge newer edits with an old response', async () => {
    const f = fixture();
    f.input('旧内容');
    const first = f.save();
    f.input('最新内容');
    await f.save();
    assert.equal(f.pending.length, 1);
    f.pending[0].resolve(ok());
    await first;
    assert.equal(f.storage.size, 1);
    assert.doesNotMatch(f.state.textContent, /草稿已保存/);
    const second = f.save();
    assert.equal(f.pending[1].body.title, '最新内容');
    f.pending[1].resolve(ok());
    await second;
    assert.equal(f.storage.size, 0);
    assert.match(f.state.textContent, /草稿已保存/);
});

test('a failed old request backs up the latest editor content, not its stale snapshot', async () => {
    const f = fixture();
    f.input('旧内容');
    const saving = f.save();
    f.input('最新内容');
    f.pending[0].reject(Error('offline'));
    await saving;
    assert.equal(JSON.parse([...f.storage.values()][0]).payload.title, '最新内容');
});

test('login HTML must never look like a successful save', async () => {
    const f = fixture();
    f.input('未保存');
    const saving = f.save();
    f.pending[0].resolve({ok: true, status: 200, redirected: true, json: async () => { throw Error(); }});
    await saving;
    assert.match(f.state.textContent, /登录已失效/);
    assert.equal(f.storage.size, 1);
});

test('conflict pauses further writes but continues protecting local edits', async () => {
    const f = fixture();
    f.input('第一次');
    const saving = f.save();
    f.pending[0].resolve({ok: false, status: 409, json: async () => ({message: '版本冲突'})});
    await saving;
    f.input('冲突后的修改');
    await f.save();
    assert.equal(f.pending.length, 1);
    assert.equal(JSON.parse([...f.storage.values()][0]).payload.title, '冲突后的修改');
});

test('formal save waits for autosave and keeps backup until server acknowledgement', async () => {
    const f = fixture();
    f.input('待提交');
    const saving = f.save();
    const submit = f.submit();
    assert.equal(f.submitted(), 0);
    f.pending[0].resolve(ok());
    await saving;
    await submit;
    assert.equal(f.submitted(), 1);
    assert.equal(f.storage.size, 1);
});

test('without an in-flight draft, formal submission uses the native browser path', async () => {
    const f = fixture();
    f.input('直接提交');
    await f.submit();
    assert.equal(f.prevented(), false);
    assert.equal(f.submitted(), 0); // No synchronous recursive requestSubmit.
    assert.equal(f.storage.size, 1);
});

test('blocked storage is reported honestly', async () => {
    const f = fixture({storageFails: true});
    f.input('重要内容');
    const saving = f.save();
    f.pending[0].reject(Error('offline'));
    await saving;
    assert.match(f.state.textContent, /本机暂存不可用/);
    assert.doesNotMatch(f.state.textContent, /已暂存/);
});

test('invalid forms keep backup without sending a doomed request', async () => {
    const f = fixture({valid: false});
    f.input('');
    await f.save();
    assert.equal(f.pending.length, 0);
    assert.equal(f.storage.size, 1);
    assert.match(f.state.textContent, /自动保存暂停/);
});

test('network restoration schedules retry, and unsaved changes guard navigation', () => {
    const f = fixture();
    f.input('离线内容');
    f.events.online();
    assert.equal(f.timers.size, 1);
    let prevented = false;
    f.events.beforeunload({preventDefault: () => { prevented = true; }});
    assert.equal(prevented, true);
});
