const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/assets/channel-copy-optimization.js'), 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));

function harness({auto = false, title = '原标题', body = '文'.repeat(1100), fetch: request} = {}) {
    const element = (value = '') => ({value, hidden: false, disabled: false, textContent: '', listeners: {},
        addEventListener(name, callback) { this.listeners[name] = callback; },
        dispatchEvent(event) { this.listeners[event.type]?.(event); }, hasAttribute() { return false; }});
    const titleInput = element(title), bodyInput = element(body), optimize = element(), undo = element(), state = element();
    const copies = [element(), element()];
    copies[1].hasAttribute = key => key === 'data-copy-rich-text';
    const fields = {'#manual-title': titleInput, '#manual-content': bodyInput, '[data-optimize-copy]': optimize,
        '[data-undo-optimization]': undo, '[data-optimization-state]': state, '[data-optimization-controls]': element(),
        'input[name="_csrf"]': {value: 'csrf-local'}};
    const form = element();
    form.dataset = {channelType: 'XIAOHONGSHU', titleLimit: '20', bodyLimit: '1000', richCopyCurrent: 'true', optimizeUrl: '/local/optimize',
        autoOptimize: String(auto), language: 'original', articleVersion: '7'};
    form.querySelector = key => fields[key];
    form.querySelectorAll = () => copies;
    const calls = [];
    const window = {setTimeout() { return 1; }, clearTimeout() {}, confirm() { return true; }};
    vm.runInNewContext(source, {window, document: {addEventListener() {}}, Event, AbortController, SyntaxError,
        fetch: (...args) => { calls.push(args); return request ? request(...args) : Promise.resolve({ok: true,
            json: async () => ({title: '原标题', body: '精简后的核心步骤和限制。'})}); }});
    window.PublisherChannelCopy.init(form);
    return {window, form, title: titleInput, body: bodyInput, optimize, undo, state, copies, calls};
}

test('counts X Chinese, emoji, URLs and exact limits with the shared browser rule', () => {
    const {bodyLength, fits} = harness({body: '短文'}).window.PublisherChannelCopy;
    assert.equal(bodyLength('X', '中😀 a\nhttps://example.com/' + 'x'.repeat(400)), 30);
    assert.equal(bodyLength('XIAOHONGSHU', '😀中 a\n'), 5);
    assert.equal(bodyLength('MASTODON', '中😀 a\nhttps://t.co/x'), 28);
    assert.equal(bodyLength('X', 'https://example.com/。'), 25);
    assert.equal(bodyLength('MASTODON', 'https://example.com/。'), 24);
    assert.equal(bodyLength('X', 'https://example.com/。下一句'), 31);
    assert.equal(fits('X', '标题', '中'.repeat(140), 100, 280), true);
    assert.equal(fits('X', '标题', '中'.repeat(141), 100, 280), false);
});

test('automatically optimizes overflow once with CSRF and version, then supports confirmed undo', async () => {
    const h = harness({auto: true});
    assert.equal(h.optimize.disabled, true);
    assert.equal(h.copies[0].disabled, true);
    await tick();
    assert.equal(h.calls.length, 1);
    const request = h.calls[0][1];
    assert.equal(request.headers['X-CSRF-TOKEN'], 'csrf-local');
    assert.equal(JSON.parse(request.body).articleVersion, 7);
    assert.equal(JSON.parse(request.body).body, '文'.repeat(1100));
    assert.equal(h.body.value, '精简后的核心步骤和限制。');
    assert.equal(h.copies[0].disabled, false);
    assert.equal(h.copies[1].disabled, true, '富文本不可绕过渠道版本复制原主稿');
    assert.match(h.state.textContent, /主稿未修改/);
    h.window.confirm = () => false;
    h.undo.listeners.click();
    assert.equal(h.body.value, '精简后的核心步骤和限制。');
    h.window.confirm = () => true;
    h.undo.listeners.click();
    assert.equal(h.body.value, '文'.repeat(1100));
    assert.equal(h.calls.length, 1, '撤销不应再次自动调用付费 AI');
    assert.equal(h.copies[0].disabled, true);
});

test('does not call AI for content within limits or automatically retry a failed form', async () => {
    for (const options of [{auto: true, body: '短文'}, {auto: false}]) {
        const h = harness(options);
        await tick();
        assert.equal(h.calls.length, 0);
    }
});

test('retains input after AI failure and allows a deliberate retry without loops', async () => {
    const h = harness({auto: true, fetch: async () => ({ok: false, json: async () => ({message: 'AI 服务未启用'})})});
    await tick();
    assert.equal(h.body.value, '文'.repeat(1100));
    assert.match(h.state.textContent, /AI 服务未启用/);
    assert.equal(h.optimize.disabled, false);
    assert.equal(h.calls.length, 1);
    await h.optimize.listeners.click();
    assert.equal(h.calls.length, 2);
});

test('does not overwrite an edit made while AI is running even if text is restored', async () => {
    let resolve;
    const h = harness({auto: true, fetch: () => new Promise(r => { resolve = r; })});
    h.body.value = '用户编辑';
    h.body.dispatchEvent(new Event('input'));
    h.body.value = '文'.repeat(1100);
    h.body.dispatchEvent(new Event('input'));
    resolve({ok: true, json: async () => ({title: '原标题', body: 'AI 文案'})});
    await tick();
    assert.equal(h.body.value, '文'.repeat(1100));
    assert.match(h.state.textContent, /未覆盖你的输入/);
});

test('rejects oversized or malformed responses and keeps the editable source', async () => {
    for (const result of [{title: '标题', body: '文'.repeat(1001)}, {title: '', body: '正文'}, {title: '标题', body: []}]) {
        const h = harness({auto: true, fetch: async () => ({ok: true, json: async () => result})});
        await tick();
        assert.equal(h.body.value, '文'.repeat(1100));
        assert.match(h.state.textContent, /当前输入已保留/);
        assert.equal(h.copies[0].disabled, true);
    }
});

test('blocks oversized submission and disables old rich text after ordinary editing', () => {
    const h = harness();
    let blocked = false;
    h.form.listeners.submit({preventDefault() { blocked = true; }});
    assert.equal(blocked, true);
    h.body.value = '用户手动精简的正文';
    h.body.dispatchEvent(new Event('input'));
    assert.equal(h.copies[0].disabled, false);
    assert.equal(h.copies[1].disabled, true);
    blocked = false;
    h.form.listeners.submit({preventDefault() { blocked = true; }});
    assert.equal(blocked, false);
});

test('keeps master rich text disabled when a failed form restores edited channel content', () => {
    const h = harness({body: '服务端回显的渠道正文'});
    h.form.dataset.richCopyCurrent = 'false';
    h.body.dispatchEvent(new Event('input'));
    assert.equal(h.copies[0].disabled, false);
    assert.equal(h.copies[1].disabled, true);
});
