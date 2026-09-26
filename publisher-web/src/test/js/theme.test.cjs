const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const css = fs.readFileSync(path.join(__dirname, '../../main/resources/static/assets/app.css'), 'utf8');
const vars = block => Object.fromEntries([...block.matchAll(/--([\w-]+):\s*([^;]+);/gi)]
    .map(match => [match[1], match[2].trim()]));
const resolveVars = tokens => {
    const resolve = (value, seen = new Set()) => value.replace(/var\(--([\w-]+)\)/g, (_match, name) => {
        assert.ok(tokens[name] && !seen.has(name), `Missing or circular token: ${name}`);
        return resolve(tokens[name], new Set([...seen, name]));
    });
    return Object.fromEntries(Object.entries(tokens).map(([name, value]) => [name, resolve(value)]));
};
const lightVars = vars(css.match(/:root\s*\{([^}]+)\}/)[1]);
const light = resolveVars(lightVars);
const dark = resolveVars({...lightVars, ...vars(css.match(/html\[data-theme="dark"\]\s*\{([^}]+)\}/)[1])});
const luminance = hex => {
    const [r, g, b] = hex.match(/[\da-f]{2}/gi).map(value => {
        const n = parseInt(value, 16) / 255;
        return n <= 0.04045 ? n / 12.92 : ((n + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
};
const contrast = (a, b) => {
    const values = [luminance(a), luminance(b)].sort((x, y) => y - x);
    return (values[0] + 0.05) / (values[1] + 0.05);
};
for (const [theme, tokens] of Object.entries({light, dark})) {
    test(`${theme}: soft surfaces retain readable text and actions`, () => {
        const pairs = [
            ['text', 'bg'], ['text', 'surface'], ['muted', 'bg'], ['muted', 'surface-soft'],
            ['subtle-text', 'control-bg'], ['sidebar-text', 'sidebar-bg'],
            ['sidebar-muted', 'sidebar-bg'], ['primary-dark', 'sidebar-active'],
            ['on-action', 'action'], ['on-action', 'action-hover'],
            ['on-action', 'danger-action'], ['success-text', 'teal-soft'],
            ['warning-text', 'amber-soft'], ['error-text', 'danger-soft']
        ];
        for (const [foreground, background] of pairs) {
            const ratio = contrast(tokens[foreground], tokens[background]);
            assert.ok(ratio >= 4.5, `${theme} ${foreground}/${background}: ${ratio.toFixed(2)} < 4.5`);
        }
    });
    test(`${theme}: visible focus remains distinct`, () => {
        for (const background of ['bg', 'surface', 'sidebar-bg', 'sidebar-active']) {
            assert.ok(contrast(tokens.primary, tokens[background]) >= 3, `${theme} focus/${background}`);
        }
    });
    test(`${theme}: input boundaries remain visible`, () => {
        assert.ok(contrast(tokens['control-border'], tokens['control-bg']) >= 3);
    });
}

test('dark: workspace surfaces stay neutral rather than green-tinted', () => {
    for (const name of ['bg', 'surface', 'surface-soft', 'surface-alt', 'surface-hover',
        'sidebar-bg', 'sidebar-hover', 'sidebar-active', 'control-bg', 'code-bg', 'table-head']) {
        const [r, g, b] = dark[name].match(/[\da-f]{2}/gi).map(value => parseInt(value, 16));
        assert.ok(b >= g && g >= r && b - r <= 14, `${name} must remain neutral/cool gray`);
    }
    const levels = ['bg', 'sidebar-bg', 'surface', 'surface-soft', 'surface-hover'].map(name => luminance(dark[name]));
    assert.ok(levels.every((value, index) => index === 0 || value > levels[index - 1]));
});

test('dark: secondary, status and disabled text remains readable', () => {
    const pairs = [
        ['muted', 'surface-hover'], ['subtle-text', 'surface-soft'], ['label-text', 'surface'],
        ['text', 'code-bg'], ['text', 'table-head'], ['neutral-text', 'neutral-soft-strong'],
        ['sidebar-control', 'sidebar-control-hover'], ['sidebar-text', 'sidebar-hover'],
        ['primary', 'primary-soft'], ['teal', 'teal-soft'], ['amber', 'amber-soft'],
        ['danger', 'danger-soft'], ['danger', 'danger-surface'], ['disabled-text', 'disabled-bg']
    ];
    for (const [foreground, background] of pairs) {
        const ratio = contrast(dark[foreground], dark[background]);
        assert.ok(ratio >= 4.5, `${foreground}/${background}: ${ratio.toFixed(2)} < 4.5`);
    }
});

test('dark: chart series remain distinct from tracks', () => {
    for (const name of ['chart-primary', 'chart-success', 'chart-warning', 'chart-danger', 'chart-muted']) {
        assert.ok(contrast(dark[name], dark.track) >= 3, name);
    }
    assert.notEqual(dark['chart-primary'], dark['chart-success']);
});

const assets = path.join(__dirname, '../../main/resources/static/assets');
const initSource = fs.readFileSync(path.join(assets, 'theme-init.js'), 'utf8');
// Exercise the actual theme bootstrap, stopping before unrelated sidebar/editor wiring.
const appThemeSource = fs.readFileSync(path.join(assets, 'app.js'), 'utf8')
    .split('    const body = document.body;')[0] + '})();';
function themeHarness({stored = null, systemDark = false, blockedStorage = false, existingMeta = false} = {}) {
    const root = {dataset: {}};
    const media = {matches: systemDark, addEventListener: (_type, fn) => { media.change = fn; }};
    const metas = existingMeta ? [{name: 'theme-color', content: '#000000'}] : [];
    const label = {};
    const button = {
        dataset: {}, setAttribute(name, value) { this[name] = value; },
        querySelector: () => label, addEventListener: (_type, fn) => { button.click = fn; }
    };
    const listeners = {};
    const storage = {
        getItem: () => { if (blockedStorage) throw new Error('unavailable'); return stored; },
        setItem: (_key, value) => { if (blockedStorage) throw new Error('unavailable'); stored = value; }
    };
    const context = {
        document: {
            documentElement: root, head: {append: meta => metas.push(meta)},
            createElement: () => ({}), querySelector: () => metas[0] || null,
            querySelectorAll: selector => selector === '[data-theme-toggle]' ? [button] : []
        },
        window: {localStorage: storage, matchMedia: () => media,
            addEventListener: (type, fn) => { listeners[type] = fn; }},
        getComputedStyle: () => ({getPropertyValue: () => (root.dataset.theme === 'dark' ? dark : light).bg})
    };
    vm.runInNewContext(initSource, context);
    const initialTheme = root.dataset.theme;
    vm.runInNewContext(appThemeSource, context);
    return {root, media, metas, button, label, listeners, initialTheme, stored: () => stored};
}

test('theme boot: stored choice and system fallback are resolved before app initialization', () => {
    for (const [options, expected] of [
        [{stored: 'dark'}, 'dark'],
        [{stored: 'light', systemDark: true}, 'light'],
        [{stored: 'invalid', systemDark: true}, 'dark'],
        [{stored: 'light', systemDark: true, blockedStorage: true}, 'dark']
    ]) {
        const h = themeHarness(options);
        assert.equal(h.initialTheme, expected);
        assert.equal(h.metas[0].content, (expected === 'dark' ? dark : light).bg);
    }
});

test('theme-color follows cycle, persistence and accessible labels without duplicating metadata', () => {
    const h = themeHarness({existingMeta: true});
    for (const preference of ['light', 'dark', 'system']) {
        h.button.click();
        assert.equal(h.root.dataset.themePreference, preference);
        assert.equal(h.stored(), preference);
        assert.equal(h.metas.length, 1);
        assert.equal(h.metas[0].content, (preference === 'dark' ? dark : light).bg);
        assert.match(h.button['aria-label'], /当前外观：.+。切换为/);
    }
});

test('theme-color follows system and cross-tab changes but preserves explicit preference', () => {
    const h = themeHarness();
    h.media.matches = true;
    h.media.change();
    assert.equal(h.metas[0].content, dark.bg);
    h.listeners.storage({key: 'content-publisher:theme', newValue: 'light'});
    h.media.change();
    assert.equal(h.metas[0].content, light.bg);
    h.listeners.storage({key: 'unrelated', newValue: 'dark'});
    assert.equal(h.root.dataset.theme, 'light');
    h.listeners.storage({key: 'content-publisher:theme', newValue: null});
    assert.equal(h.root.dataset.themePreference, 'system');
    assert.equal(h.metas[0].content, dark.bg);
});

test('theme switching still works when storage writes fail', () => {
    const h = themeHarness({blockedStorage: true});
    h.button.click();
    h.button.click();
    assert.equal(h.root.dataset.theme, 'dark');
    assert.equal(h.metas[0].content, dark.bg);
});
