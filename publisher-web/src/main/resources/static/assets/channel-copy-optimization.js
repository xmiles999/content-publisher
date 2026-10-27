(() => {
    'use strict';
    const weighted = value => [...value].reduce((sum, char) => {
        const cp = char.codePointAt(0);
        return sum + (cp <= 0x10ff || cp >= 0x2000 && cp <= 0x200d
            || cp >= 0x2010 && cp <= 0x201f || cp >= 0x2032 && cp <= 0x2037 ? 1 : 2);
    }, 0);
    const bodyLength = (channel, value) => {
        if (channel !== 'X' && channel !== 'MASTODON') return [...value].length;
        const length = text => channel === 'X' ? weighted(text) : [...text].length;
        let count = 0, start = 0;
        for (const match of value.matchAll(/https?:\/\/[^\s<>，。！？；：、）】》」』]+/gi)) {
            const url = match[0].replace(/[.,!?;:)\]}，。！？；：、）】》」』]+$/u, '');
            count += length(value.slice(start, match.index)) + 23;
            start = match.index + url.length;
        }
        return count + length(value.slice(start));
    };
    const fits = (channel, title, body, titleLimit, bodyLimit) =>
        [...title].length <= titleLimit && bodyLength(channel, body) <= bodyLimit;

    const init = form => {
        const title = form.querySelector('#manual-title');
        const body = form.querySelector('#manual-content');
        const button = form.querySelector('[data-optimize-copy]');
        const undo = form.querySelector('[data-undo-optimization]');
        const state = form.querySelector('[data-optimization-state]');
        if (!title || !body || !button || !undo || !state) return;
        form.querySelector('[data-optimization-controls]').hidden = false;
        const channel = form.dataset.channelType;
        const titleLimit = Number(form.dataset.titleLimit), bodyLimit = Number(form.dataset.bodyLimit);
        const initialBody = body.value;
        let busy = false, revision = 0, previous;
        const withinLimit = () => fits(channel, title.value, body.value, titleLimit, bodyLimit);
        const refresh = () => {
            const valid = withinLimit();
            button.disabled = busy || valid;
            undo.disabled = busy;
            form.querySelectorAll('[data-copy-target], [data-copy-combined], [data-copy-tags], [data-copy-rich-text]').forEach(copy => {
                copy.disabled = busy || !valid || (copy.hasAttribute('data-copy-rich-text')
                    && (form.dataset.richCopyCurrent !== 'true' || body.value !== initialBody));
            });
        };
        const changed = () => {
            revision += 1;
            refresh();
        };
        title.addEventListener('input', changed);
        body.addEventListener('input', changed);
        const apply = value => {
            title.value = value.title;
            body.value = value.body;
            title.dispatchEvent(new Event('input', {bubbles: true}));
            body.dispatchEvent(new Event('input', {bubbles: true}));
        };
        const optimize = async () => {
            if (busy || withinLimit()) return;
            const snapshot = {title: title.value, body: body.value};
            const startedAt = revision;
            const controller = new AbortController();
            const timeout = window.setTimeout(() => controller.abort(), 70000);
            busy = true;
            refresh();
            state.textContent = '正在按平台字数优化，主稿保持不变…';
            try {
                const csrf = form.querySelector('input[name="_csrf"]')?.value;
                const response = await fetch(form.dataset.optimizeUrl, {
                    method: 'POST', credentials: 'same-origin', signal: controller.signal,
                    headers: {'Content-Type': 'application/json', ...(csrf ? {'X-CSRF-TOKEN': csrf} : {})},
                    body: JSON.stringify({...snapshot, language: form.dataset.language,
                        articleVersion: Number(form.dataset.articleVersion)})
                });
                if (response.redirected) throw new Error('登录已失效，请重新登录；当前输入已保留。');
                const result = await response.json();
                if (!response.ok) throw new Error(result.message || '优化失败，请手动精简或稍后重试。');
                if (revision !== startedAt || title.value !== snapshot.title || body.value !== snapshot.body) {
                    state.textContent = '优化期间内容已修改，未覆盖你的输入；请核对后重新优化。';
                    return;
                }
                if (typeof result.title !== 'string' || typeof result.body !== 'string'
                    || !result.title.trim() || !result.body.trim()
                    || !fits(channel, result.title, result.body, titleLimit, bodyLimit)) {
                    throw new Error('优化结果仍超限或格式无效，当前输入已保留；请手动精简或重试。');
                }
                previous = snapshot;
                apply(result);
                undo.hidden = false;
                state.textContent = '已生成符合字数的渠道文案，请核对事实与排版后再复制；主稿未修改。富文本仍来自主稿，请使用普通复制。';
            } catch (error) {
                state.textContent = error.name === 'AbortError' ? '优化超时，当前输入已保留；请手动精简或稍后重试。'
                    : error instanceof SyntaxError ? '优化服务未返回有效结果，当前输入已保留；请检查登录或稍后重试。'
                    : error.message;
            } finally {
                window.clearTimeout(timeout);
                busy = false;
                refresh();
            }
        };
        button.addEventListener('click', optimize);
        undo.addEventListener('click', () => {
            if (!previous || busy) return;
            if (!window.confirm('恢复优化前的平台文案？这会替换当前标题和正文。')) return;
            apply(previous);
            previous = undefined;
            undo.hidden = true;
            state.textContent = '已恢复优化前的文案，主稿未修改；超限内容请手动精简或重新优化。';
            refresh();
        });
        form.addEventListener('submit', event => {
            if (busy || !withinLimit()) {
                event.preventDefault();
                state.textContent = busy ? '请等待优化完成再保存记录。' : '平台文案仍超限，请先精简或优化后再保存记录。';
            }
        }, true);
        refresh();
        if (form.dataset.autoOptimize === 'true') void optimize();
    };
    window.PublisherChannelCopy = {bodyLength, fits, init};
    document.addEventListener('DOMContentLoaded', () => document.querySelectorAll('form[data-copy-optimization]').forEach(init));
})();
