const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const app = fs.readFileSync(path.join(__dirname, '../../main/resources/static/assets/app.js'), 'utf8');
const start = app.indexOf('    const copyFeedback');
const end = app.indexOf("    document.querySelectorAll('[data-channel-selection]')", start);
assert.ok(start >= 0 && end > start);
const source = app.slice(start, end);

test('legacy account bookmarks open API view without changing the normal personal-channel default', () => {
    const prefix = app.slice(app.indexOf('(() => {') + '(() => {'.length,
        app.indexOf("    document.querySelectorAll('time[data-local-time]')"));
    for (const [url, expected] of [
        ['https://example.com/channels#account-123', 'https://example.com/channels?view=api#account-123'],
        ['https://example.com/channels', undefined],
        ['https://example.com/channels?view=manual#account-123', undefined]
    ]) {
        let replacement;
        vm.runInNewContext('(() => {' + prefix + '})()', {URL,
            window: {location: {href: url, replace(value) { replacement = value; }}}});
        assert.equal(replacement, expected);
    }
});

function harness(clipboard, richSupported = true) {
    const feedback = {textContent: ''};
    const makeButton = dataset => ({dataset, textContent: '复制', listeners: {}, events: [],
        classList: {add() {}, remove() {}},
        addEventListener(type, listener) { this.listeners[type] = listener; },
        dispatchEvent(event) { this.events.push(event.type); }});
    const button = makeButton({copyTitle: '#title', copyContent: '#body'});
    const richButton = makeButton({copyRichText: '#rich'});
    let opened = 0;
    const selectors = {'#title': {value: '当前标题'}, '#body': {value: '编辑后的正文'},
        '#rich': {innerHTML: '<p><strong>主稿</strong></p>', content: {textContent: '主稿'}},
        '[data-copy-feedback]': feedback, '[data-platform-launch]': {click() { opened++; }}};
    const context = {document: {
        querySelector: selector => selectors[selector] || null,
        querySelectorAll: selector => selector === '[data-copy-and-open]' ? [button]
            : selector === '[data-copy-rich-text]' ? [richButton] : []
    }, navigator: {clipboard}, window: {setTimeout() {}}, Blob,
    CustomEvent: class { constructor(type) { this.type = type; } }};
    if (richSupported) context.ClipboardItem = class { constructor(items) { this.items = items; } };
    vm.runInNewContext(source, context);
    return {button, richButton, feedback, opened: () => opened};
}

test('copy-and-open: rejected clipboard leaves editor closed and does not mark copying complete', async () => {
    const h = harness({writeText: async () => { throw new Error('denied'); }});
    await h.button.listeners.click();
    assert.equal(h.opened(), 0);
    assert.deepEqual(h.button.events, []);
    assert.match(h.feedback.textContent, /复制失败.*剪贴板权限/);
});

test('copy-and-open: success copies current edits before opening and marking progress', async () => {
    let copied;
    const h = harness({writeText: async value => { copied = value; }});
    await h.button.listeners.click();
    assert.equal(copied, '当前标题\n\n编辑后的正文');
    assert.equal(h.opened(), 1);
    assert.deepEqual(h.button.events, ['publisher:copied']);
});

test('rich copying: unsupported or rejected clipboard gives a fallback without false progress', async () => {
    for (const [clipboard, supported, message] of [
        [{}, false, /不支持富文本复制/],
        [{write: async () => { throw new Error('denied'); }}, true, /富文本复制失败/]
    ]) {
        const h = harness(clipboard, supported);
        await h.richButton.listeners.click();
        assert.match(h.feedback.textContent, message);
        assert.deepEqual(h.richButton.events, []);
    }
});

test('rich copying: HTML and plain fallbacks refer to the same source and mark progress only after success', async () => {
    let copied;
    const h = harness({write: async items => { copied = items; }});
    await h.richButton.listeners.click();
    assert.equal(await copied[0].items['text/html'].text(), '<p><strong>主稿</strong></p>');
    assert.equal(await copied[0].items['text/plain'].text(), '主稿');
    assert.deepEqual(h.richButton.events, ['publisher:copied']);
});
