/* Serial draft writes; a response can acknowledge only the snapshot it saved. */
(() => {
    function initDraftAutosave(form, env = {window, document, fetch}) {
        const {window: win, document: doc, fetch: request} = env;
        const state = doc.querySelector('[data-draft-state]');
        const fields = ['title', 'summary', 'markdown', 'tags', 'keywords',
            'titleEn', 'summaryEn', 'markdownEn', 'tagsEn', 'keywordsEn'];
        const key = `content-publisher:draft:${JSON.stringify([
            form.dataset.draftOwner, form.dataset.draftUrl])}`;
        let timer, inFlight = null, revision = 0, savedRevision = 0;
        let conflict = false, submitting = false, resubmitting = false;
        const message = text => { if (state) state.textContent = text; };
        const field = name => form.elements.namedItem(name);
        const payload = () => Object.fromEntries([
            ['baseVersion', Number(field('expectedVersion')?.value || 0)],
            ...fields.map(name => {
                const value = field(name)?.value || '';
                return [name, /^(tags|keywords)/.test(name)
                    ? [...new Set(value.split(/[\n,，]+/).map(item => item.trim()).filter(Boolean))]
                    : value];
            })
        ]);
        const backup = () => {
            try {
                win.sessionStorage.setItem(key, JSON.stringify({payload: payload()}));
                return true;
            } catch (_) { return false; }
        };
        const clearBackup = () => {
            try { win.sessionStorage.removeItem(key); } catch (_) { /* Storage may be disabled. */ }
        };
        const schedule = () => {
            win.clearTimeout(timer);
            timer = win.setTimeout(save, 900);
        };
        async function save() {
            if (submitting || conflict || revision === savedRevision) return;
            if (inFlight) return; // Its completion schedules the newest snapshot.
            if (!form.checkValidity()) {
                message('尚有未填或超限字段；自动保存暂停，请补全后继续。');
                return;
            }
            const savingRevision = revision;
            const body = payload();
            message('草稿保存中…');
            const controller = new AbortController();
            const timeout = win.setTimeout(() => controller.abort(), 15000);
            inFlight = (async () => {
                try {
                    const csrf = form.querySelector('input[name="_csrf"]')?.value;
                    const response = await request(form.dataset.draftUrl, {
                        method: 'PUT', credentials: 'same-origin', signal: controller.signal,
                        headers: {'Content-Type': 'application/json', ...(csrf ? {'X-CSRF-TOKEN': csrf} : {})},
                        body: JSON.stringify(body)
                    });
                    const result = await response.json().catch(() => null);
                    if (response.status === 409) {
                        conflict = true;
                        message(result?.message || '版本冲突，自动保存已暂停。请复制当前内容后刷新。');
                        return;
                    }
                    if (!response.ok || response.redirected || !result?.updatedAt
                            || !Number.isFinite(Date.parse(result.updatedAt))) {
                        throw new Error(response.status === 401 || response.status === 403 || response.redirected
                            ? '登录已失效或无保存权限，请重新登录。'
                            : result?.message || '保存失败，请检查网络后重试。');
                    }
                    savedRevision = savingRevision;
                    if (revision === savingRevision && !submitting) {
                        clearBackup();
                        message(`草稿已保存 · ${new Date(result.updatedAt).toLocaleTimeString()}`);
                    }
                } catch (error) {
                    const stored = backup();
                    const reason = controller.signal.aborted ? '草稿保存超时，请检查网络后重试。' : error.message;
                    message(`${reason} ${stored ? '最新内容已暂存当前标签页。' : '本机暂存不可用，请复制内容备份。'}`);
                } finally {
                    win.clearTimeout(timeout);
                }
            })();
            await inFlight;
            inFlight = null;
            if (revision !== savingRevision && !conflict && !submitting) schedule();
        }
        form.addEventListener('input', () => {
            if (submitting) return;
            revision++;
            const stored = backup();
            if (conflict) {
                message(`版本冲突，自动保存已暂停。${stored ? '最新内容已暂存当前标签页。' : '请复制内容备份。'}`);
                return;
            }
            message(stored ? '等待自动保存…' : '本机暂存不可用，正在等待服务端保存…');
            schedule();
        });
        form.addEventListener('submit', async event => {
            if (event.defaultPrevented || resubmitting) return;
            if (submitting) {
                event.preventDefault();
                return;
            }
            submitting = true;
            win.clearTimeout(timer);
            backup(); // Keep until the following page confirms the formal save succeeded.
            // Let a normal submission proceed. requestSubmit() inside its own
            // synchronous submit handler is ignored by the browser.
            if (!inFlight) return;
            event.preventDefault();
            const submitter = event.submitter;
            message('等待草稿同步完成后保存版本…');
            await inFlight;
            resubmitting = true;
            form.requestSubmit(submitter || undefined);
            resubmitting = false;
            if (!form.checkValidity()) submitting = false;
        });
        win.addEventListener('beforeunload', event => {
            if (revision === savedRevision || submitting || resubmitting) return;
            event.preventDefault();
            event.returnValue = '';
        });
        win.addEventListener('online', () => { if (revision !== savedRevision) schedule(); });
        if (form.dataset.saveSucceeded === 'true') {
            clearBackup();
        } else {
            try {
                const saved = JSON.parse(win.sessionStorage.getItem(key) || 'null')?.payload;
                if (saved && JSON.stringify(saved) !== JSON.stringify(payload())) {
                    const changed = saved.baseVersion !== payload().baseVersion;
                    const question = changed
                        ? '文稿基线已变化。是否将当前标签页的应急草稿恢复到编辑区？请核对差异后再保存，不会自动覆盖正式版本。'
                        : '检测到当前标签页尚未同步的内容，是否恢复？';
                    if (win.confirm(question)) {
                        fields.forEach(name => {
                            if (field(name) && saved[name] != null) {
                                field(name).value = Array.isArray(saved[name]) ? saved[name].join('\n') : saved[name];
                                field(name).dispatchEvent(new Event('input', {bubbles: true}));
                            }
                        });
                    }
                }
            } catch (_) { /* Unavailable or malformed backup must not block editing. */ }
        }
        return {save}; // Deterministic regression tests without real timers or network.
    }
    if (typeof module !== 'undefined' && module.exports) module.exports = {initDraftAutosave};
    else {
        const form = document.querySelector('form[data-draft-url]');
        if (form && form.dataset.draftEditable === 'true') initDraftAutosave(form);
        else if (form?.dataset.saveSucceeded === 'true') {
            try {
                sessionStorage.removeItem(`content-publisher:draft:${JSON.stringify([
                    form.dataset.draftOwner, form.dataset.draftUrl])}`);
            } catch (_) { /* Storage may be disabled. */ }
        }
    }
})();
