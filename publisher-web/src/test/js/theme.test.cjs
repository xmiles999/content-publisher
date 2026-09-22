const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const css = fs.readFileSync(path.join(__dirname, '../../main/resources/static/assets/app.css'), 'utf8');
const vars = block => Object.fromEntries([...block.matchAll(/--([\w-]+):\s*(#[\da-f]{6});/gi)]
    .map(match => [match[1], match[2]]));
const light = vars(css.match(/:root\s*\{([^}]+)\}/)[1]);
const dark = {...light, ...vars(css.match(/html\[data-theme="dark"\]\s*\{([^}]+)\}/)[1])};
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
