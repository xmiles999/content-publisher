/* Durable incomplete forms; requests are serialized and guarded by server revisions. */
(() => {
    function initWorkspaceDraft(form, env = {window, fetch}) {
        const win = env.window, request = env.fetch;
        const state = form.querySelector('[data-workspace-state]');
        const revisionField = form.elements.namedItem('draftRevision');
        const names = form.dataset.workspaceDraft === 'new'
            ? ['title', 'summary', 'markdown', 'tags', 'keywords', 'language', 'titleEn', 'summaryEn', 'markdownEn', 'tagsEn', 'keywordsEn']
            : ['title', 'content', 'externalUrl'];
        const storageKey = 'content-publisher:workspace:' + JSON.stringify([form.dataset.workspaceOwner, form.dataset.workspaceDraft]);
        let revision = 0, saved = 0, timer, inFlight, conflict = false, submitting = false, nativeSubmit = false;
        const message = text => { if (state) state.textContent = text; };
        const fields = () => Object.fromEntries(names.map(name => [name, form.elements.namedItem(name)?.value || '']));
        const backup = () => {
            try { win.sessionStorage.setItem(storageKey, JSON.stringify(fields())); return true; }
            catch (_) { return false; }
        };
        const removeBackup = () => { try { win.sessionStorage.removeItem(storageKey); } catch (_) {} };
        const schedule = () => { win.clearTimeout(timer); timer = win.setTimeout(save, 900); };
        async function save() {
            if (conflict || saved === revision) return !conflict;
            if (inFlight) { await inFlight; return save(); }
            const sent = revision;
            const body = {revision: Number(revisionField.value || 0), fields: fields()};
            const controller = new AbortController();
            const timeout = win.setTimeout(() => controller.abort(), 15000);
            message('草稿保存中…');
            inFlight = (async () => {
                try {
                    const csrf = form.querySelector('input[name="_csrf"]')?.value;
                    const response = await request('/articles/workspace-drafts/' + encodeURIComponent(form.dataset.workspaceDraft), {
                        method: 'PUT', credentials: 'same-origin', signal: controller.signal,
                        headers: {'Content-Type': 'application/json', ...(csrf ? {'X-CSRF-TOKEN': csrf} : {})},
                        body: JSON.stringify(body)
                    });
                    const result = await response.json().catch(() => null);
                    if (response.status === 409) conflict = true;
                    if (!response.ok || response.redirected || !Number.isSafeInteger(result?.revision)
                            || result.revision <= body.revision || !Number.isFinite(Date.parse(result.updatedAt))) {
                        throw new Error(response.status === 401 || response.status === 403 || response.redirected
                            ? '登录已失效，请重新登录后继续。' : result?.message || '草稿保存失败，请检查网络后重试。');
                    }
                    revisionField.value = String(result.revision);
                    saved = sent;
                    if (saved === revision) {
                        removeBackup();
                        message('草稿已保存 · ' + new Date(result.updatedAt).toLocaleTimeString());
                    }
                    return true;
                } catch (error) {
                    const stored = backup();
                    message((controller.signal.aborted ? '草稿保存超时。' : error.message)
                        + (stored ? ' 当前输入已暂存本标签页。' : ' 请复制当前内容备份。'));
                    return false;
                } finally { win.clearTimeout(timeout); }
            })();
            const ok = await inFlight;
            inFlight = null;
            if (ok && sent !== revision && !submitting) schedule();
            return ok;
        }
        form.addEventListener('input', event => {
            if (event?.target?.name && !names.includes(event.target.name)) return;
            revision++;
            const stored = backup();
            message(conflict ? '其他页面已有更新，请复制当前内容后刷新核对。'
                : stored ? '等待自动保存…' : '本机暂存不可用，等待服务端保存…');
            if (!conflict) schedule();
        });
        form.addEventListener('submit', async event => {
            if (nativeSubmit || event.defaultPrevented) return;
            event.preventDefault();
            if (submitting || conflict) return;
            submitting = true;
            win.clearTimeout(timer);
            const submitter = event.submitter;
            const ok = await save();
            if (!ok) { submitting = false; return; }
            if (saved !== revision) { submitting = false; message('内容刚刚变化，请再次保存。'); return; }
            // Leave the original submit dispatch before requesting another native submission.
            // An already-saved draft resolves in a microtask while the browser still guards reentry.
            win.setTimeout(() => {
                if (saved !== revision || conflict) {
                    submitting = false;
                    message('内容刚刚变化，请再次保存。');
                    return;
                }
                nativeSubmit = true;
                form.requestSubmit(submitter || undefined);
                nativeSubmit = false;
                if (!form.checkValidity()) submitting = false;
            }, 0);
        });
        win.addEventListener('beforeunload', event => {
            if (revision === saved || submitting) return;
            event.preventDefault(); event.returnValue = '';
        });
        win.addEventListener('online', () => { if (!conflict && saved !== revision) schedule(); });
        try {
            const stored = JSON.parse(win.sessionStorage.getItem(storageKey) || 'null');
            if (stored && JSON.stringify(stored) !== JSON.stringify(fields())
                    && win.confirm('检测到当前标签页未同步的草稿，是否恢复到编辑区？请核对后再提交。')) {
                names.forEach(name => {
                    const field = form.elements.namedItem(name);
                    if (field && typeof stored[name] === 'string') field.value = stored[name];
                });
                revision++;
                schedule();
            }
        } catch (_) { /* Storage failure must not prevent editing. */ }
        if (Number(revisionField.value) > 0) message('草稿自动保存已就绪，可继续编辑。');
        return {save};
    }
    if (typeof module !== 'undefined' && module.exports) module.exports = {initWorkspaceDraft};
    else {
        const saved = document.body.dataset.workspaceSaved;
        if (saved) {
            try { sessionStorage.removeItem('content-publisher:workspace:' + JSON.stringify([
                document.body.dataset.workspaceOwner, saved])); } catch (_) {}
        }
        document.querySelectorAll('form[data-workspace-draft]').forEach(form => initWorkspaceDraft(form));
    }
})();
