(() => {
    const themeStorageKey = 'content-publisher:theme';
    const themeMedia = window.matchMedia('(prefers-color-scheme: dark)');
    const themeOrder = ['system', 'light', 'dark'];
    const themeNames = {system: '跟随系统', light: '浅色', dark: '深色'};
    const normalizeThemePreference = value => themeOrder.includes(value) ? value : 'system';
    const resolveTheme = preference => preference === 'system'
        ? (themeMedia.matches ? 'dark' : 'light')
        : preference;
    let themePreference = normalizeThemePreference(document.documentElement.dataset.themePreference);
    const themeButtons = [...document.querySelectorAll('[data-theme-toggle]')];
    const syncThemeButtons = () => {
        const currentIndex = themeOrder.indexOf(themePreference);
        const nextPreference = themeOrder[(currentIndex + 1) % themeOrder.length];
        themeButtons.forEach(button => {
            button.dataset.themePreference = themePreference;
            button.setAttribute('aria-label', `当前外观：${themeNames[themePreference]}。切换为${themeNames[nextPreference]}`);
            button.setAttribute('title', `当前：${themeNames[themePreference]}；点击切换为${themeNames[nextPreference]}`);
            const label = button.querySelector('[data-theme-label]');
            if (label) label.textContent = themeNames[themePreference];
        });
    };
    const applyThemePreference = (preference, persist = false) => {
        themePreference = normalizeThemePreference(preference);
        document.documentElement.dataset.themePreference = themePreference;
        document.documentElement.dataset.theme = resolveTheme(themePreference);
        if (persist) {
            try { window.localStorage.setItem(themeStorageKey, themePreference); }
            catch (_error) { /* Theme switching still works without persisted state. */ }
        }
        syncThemeButtons();
    };
    themeButtons.forEach(button => button.addEventListener('click', () => {
        const currentIndex = themeOrder.indexOf(themePreference);
        applyThemePreference(themeOrder[(currentIndex + 1) % themeOrder.length], true);
    }));
    const handleSystemThemeChange = () => {
        if (themePreference === 'system') applyThemePreference('system');
    };
    if (themeMedia.addEventListener) themeMedia.addEventListener('change', handleSystemThemeChange);
    else themeMedia.addListener(handleSystemThemeChange);
    window.addEventListener('storage', event => {
        if (event.key === themeStorageKey) applyThemePreference(event.newValue);
    });
    applyThemePreference(themePreference);

    const body = document.body;
    const sidebar = document.querySelector('.app-sidebar');
    const sidebarOpeners = [...document.querySelectorAll('[data-sidebar-open]')];
    const sidebarClosers = [...document.querySelectorAll('[data-sidebar-close]')];
    const sidebarMedia = window.matchMedia('(max-width: 820px)');
    const sidebarNav = sidebar?.querySelector('.sidebar-nav');
    const sidebarFocusableSelector = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]';
    const sidebarTabIndexes = new Map();
    let sidebarOpener = null;
    let sidebarBackdrop = null;

    if (sidebar) {
        sidebarBackdrop = document.createElement('button');
        sidebarBackdrop.type = 'button';
        sidebarBackdrop.tabIndex = -1;
        sidebarBackdrop.className = 'sidebar-backdrop';
        sidebarBackdrop.setAttribute('aria-label', '关闭导航');
        sidebar.insertAdjacentElement('afterend', sidebarBackdrop);
    }

    const setSidebarFallbackDisabled = disabled => {
        if (!sidebar || 'inert' in sidebar) return;
        if (disabled) {
            sidebar.querySelectorAll(sidebarFocusableSelector).forEach(element => {
                if (!sidebarTabIndexes.has(element)) {
                    sidebarTabIndexes.set(element, element.getAttribute('tabindex'));
                }
                element.setAttribute('tabindex', '-1');
            });
            return;
        }
        sidebarTabIndexes.forEach((tabIndex, element) => {
            if (tabIndex === null) element.removeAttribute('tabindex');
            else element.setAttribute('tabindex', tabIndex);
        });
        sidebarTabIndexes.clear();
    };
    const syncSidebarAccessibility = () => {
        if (!sidebar) return;
        const mobileOpen = sidebarMedia.matches && body.classList.contains('sidebar-open');
        sidebarOpeners.forEach(button => button.setAttribute('aria-expanded', String(mobileOpen)));
        if (sidebarMedia.matches) {
            sidebar.setAttribute('aria-hidden', String(!mobileOpen));
            if ('inert' in sidebar) sidebar.inert = !mobileOpen;
            setSidebarFallbackDisabled(!mobileOpen);
        } else {
            sidebar.removeAttribute('aria-hidden');
            if ('inert' in sidebar) sidebar.inert = false;
            setSidebarFallbackDisabled(false);
        }
    };
    const closeSidebar = (restoreFocus = false) => {
        body.classList.remove('sidebar-open');
        syncSidebarAccessibility();
        if (restoreFocus) sidebarOpener?.focus();
    };
    const openSidebar = button => {
        sidebarOpener = button;
        body.classList.add('sidebar-open');
        syncSidebarAccessibility();
        sidebarClosers[0]?.focus();
    };

    sidebarOpeners.forEach(button => button.addEventListener('click', () => openSidebar(button)));
    sidebarClosers.forEach(button => button.addEventListener('click', () => closeSidebar(true)));
    sidebarBackdrop?.addEventListener('click', () => closeSidebar(true));
    sidebar?.querySelectorAll('a[href]').forEach(link => link.addEventListener('click', () => {
        if (sidebarMedia.matches) closeSidebar(false);
    }));
    document.addEventListener('keydown', event => {
        if (!sidebarMedia.matches || !body.classList.contains('sidebar-open') || !sidebar) return;
        if (event.key === 'Escape') {
            event.preventDefault();
            closeSidebar(true);
            return;
        }
        if (event.key !== 'Tab') return;
        const focusable = [...sidebar.querySelectorAll(sidebarFocusableSelector)]
            .filter(element => element.offsetParent !== null);
        if (!focusable.length) return;
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first.focus();
        }
    });
    const compactButton = document.querySelector('[data-sidebar-compact]');
    const compactStorageKey = 'content-publisher:sidebar:compact';
    let compactPreference = false;
    try { compactPreference = window.localStorage.getItem(compactStorageKey) === 'true'; }
    catch (_error) { /* Compact mode still works without persisted state. */ }
    const syncCompactSidebar = () => {
        const compact = Boolean(sidebar && compactButton && !sidebarMedia.matches && compactPreference);
        body.classList.toggle('sidebar-compact', compact);
        compactButton?.setAttribute('aria-expanded', String(!compact));
        compactButton?.setAttribute('aria-label', compact ? '展开导航文字' : '切换为紧凑导航');
        compactButton?.setAttribute('title', compact ? '展开侧栏' : '收起侧栏');
    };
    compactButton?.addEventListener('click', () => {
        if (sidebarMedia.matches) return;
        compactPreference = !compactPreference;
        try { window.localStorage.setItem(compactStorageKey, String(compactPreference)); }
        catch (_error) { /* Compact mode still works without persisted state. */ }
        syncCompactSidebar();
    });

    const accountMenu = document.querySelector('[data-account-menu]');
    const accountToggle = accountMenu?.querySelector('[data-account-toggle]');
    const accountPanel = accountMenu?.querySelector('[data-account-panel]');
    const closeAccountMenu = (restoreFocus = false) => {
        if (!accountToggle || !accountPanel || accountPanel.hidden) return;
        accountPanel.hidden = true;
        accountToggle.setAttribute('aria-expanded', 'false');
        accountMenu.classList.remove('open');
        if (restoreFocus) accountToggle.focus();
    };
    const openAccountMenu = () => {
        if (!accountToggle || !accountPanel) return;
        accountPanel.hidden = false;
        accountToggle.setAttribute('aria-expanded', 'true');
        accountMenu.classList.add('open');
        accountPanel.querySelector('a[href], button:not([disabled])')?.focus();
    };
    accountToggle?.addEventListener('click', () => {
        if (accountPanel?.hidden) openAccountMenu();
        else closeAccountMenu(false);
    });
    document.addEventListener('pointerdown', event => {
        if (accountMenu && !accountMenu.contains(event.target)) closeAccountMenu(false);
    });
    document.addEventListener('keydown', event => {
        if (event.key === 'Escape' && accountPanel && !accountPanel.hidden) {
            event.preventDefault();
            closeAccountMenu(true);
        }
    });

    const handleSidebarViewport = () => {
        if (!sidebarMedia.matches) body.classList.remove('sidebar-open');
        syncSidebarAccessibility();
        syncCompactSidebar();
    };
    if (sidebarMedia.addEventListener) sidebarMedia.addEventListener('change', handleSidebarViewport);
    else sidebarMedia.addListener(handleSidebarViewport);
    syncSidebarAccessibility();
    syncCompactSidebar();

    const sidebarGroups = [...document.querySelectorAll('[data-sidebar-group]')];
    const activeSidebarGroup = sidebarGroups.find(group => group.dataset.sidebarActive === 'true');
    const readSidebarGroupState = group => {
        try { return window.localStorage.getItem(`content-publisher:sidebar:${group.dataset.sidebarGroup}`); }
        catch (_error) { return null; }
    };
    const setSidebarGroupState = (group, expanded, persist = false) => {
        group.classList.toggle('expanded', expanded);
        group.querySelector('[data-sidebar-toggle]')?.setAttribute('aria-expanded', String(expanded));
        group.querySelector('.sidebar-subnav')?.setAttribute('aria-hidden', String(!expanded));
        if (!persist) return;
        try {
            window.localStorage.setItem(`content-publisher:sidebar:${group.dataset.sidebarGroup}`,
                expanded ? 'expanded' : 'collapsed');
        } catch (_error) { /* The menu still works without persisted state. */ }
    };
    const savedSidebarGroup = activeSidebarGroup ? null
        : sidebarGroups.find(group => readSidebarGroupState(group) === 'expanded');
    sidebarGroups.forEach(group => {
        setSidebarGroupState(group, group === activeSidebarGroup || group === savedSidebarGroup);
        group.querySelector('[data-sidebar-toggle]')?.addEventListener('click', () => {
            const shouldExpand = !group.classList.contains('expanded');
            if (shouldExpand) {
                sidebarGroups.filter(candidate => candidate !== group)
                    .forEach(candidate => setSidebarGroupState(candidate, false, true));
            }
            setSidebarGroupState(group, shouldExpand, true);
        });
    });
    const activeSidebarLink = sidebar?.querySelector('a[aria-current="page"]');
    if (activeSidebarLink && sidebarNav) {
        window.requestAnimationFrame(() => {
            const navRect = sidebarNav.getBoundingClientRect();
            const linkRect = activeSidebarLink.getBoundingClientRect();
            if (linkRect.top < navRect.top) {
                sidebarNav.scrollTop -= navRect.top - linkRect.top;
            } else if (linkRect.bottom > navRect.bottom) {
                sidebarNav.scrollTop += linkRect.bottom - navRect.bottom;
            }
        });
    }

    document.querySelectorAll('[data-history-back]').forEach(button =>
        button.addEventListener('click', () => window.history.back()));

    const bindConfirmForms = (root = document) => {
        root.querySelectorAll('form[data-confirm]').forEach(form => {
            if (form.dataset.confirmBound === 'true') return;
            form.dataset.confirmBound = 'true';
            form.addEventListener('submit', event => {
                if (!window.confirm(form.dataset.confirm || '确认执行此操作？')) event.preventDefault();
            });
        });
    };
    bindConfirmForms();

    document.querySelectorAll('[data-platform-launch]').forEach(link => link.addEventListener('click', event => {
        event.preventDefault();
        const platformWindow = window.open(link.href, link.dataset.platformWindow || link.target || 'publisher-platform');
        if (platformWindow) {
            try { platformWindow.opener = null; } catch (_error) { /* Cross-origin windows may deny access. */ }
            platformWindow.focus();
        }
    }));

    const copy = async (value, button) => {
        try {
            await navigator.clipboard.writeText(value);
            const label = button.textContent;
            button.textContent = '已复制';
            button.classList.add('copied');
            window.setTimeout(() => {
                button.textContent = label;
                button.classList.remove('copied');
            }, 1600);
            button.dispatchEvent(new CustomEvent('publisher:copied', {bubbles: true}));
            return true;
        } catch (_error) {
            button.textContent = '复制失败';
            return false;
        }
    };
    document.querySelectorAll('[data-copy-target]').forEach(button => button.addEventListener('click', async () => {
        const target = document.querySelector(button.dataset.copyTarget);
        if (target) await copy(target.value || target.textContent || '', button);
    }));
    document.querySelectorAll('[data-copy-combined]').forEach(button => button.addEventListener('click', async () => {
        const title = document.querySelector(button.dataset.copyTitle);
        const content = document.querySelector(button.dataset.copyContent);
        if (title && content) await copy(`${title.value}\n\n${content.value}`, button);
    }));
    document.querySelectorAll('[data-copy-tags]').forEach(button => button.addEventListener('click', async () => {
        const tags = [...document.querySelectorAll(button.dataset.copyTags)]
            .map(tag => tag.textContent.trim()).filter(Boolean);
        if (tags.length) await copy(tags.join(' '), button);
    }));

    const channelSelect = document.querySelector('[data-channel-select]');
    const credentialFields = [...document.querySelectorAll('[data-credential-field]')];
    const channelFormNote = document.querySelector('[data-channel-form-note]');
    const channelNote = channelFormNote?.querySelector('[data-channel-note]');
    const channelGuide = channelFormNote?.querySelector('[data-channel-guide]');
    const syncCredentials = () => {
        const option = channelSelect?.selectedOptions[0];
        const labels = option?.dataset.credentialLabels?.split('|').filter(Boolean) || [];
        credentialFields.forEach((field, index) => {
            const visible = index < labels.length;
            field.hidden = !visible;
            const label = field.querySelector('label');
            const input = field.querySelector('input');
            if (label) label.textContent = labels[index] || '';
            if (input) input.required = visible;
        });
        const note = option?.dataset.channelNote || '';
        const guideUrl = option?.dataset.guideUrl || '';
        if (channelFormNote) channelFormNote.hidden = !note && !guideUrl;
        if (channelNote) channelNote.textContent = note;
        if (channelGuide) {
            channelGuide.hidden = !guideUrl;
            if (guideUrl) channelGuide.href = guideUrl;
        }
    };
    if (channelSelect) {
        channelSelect.addEventListener('change', syncCredentials);
        syncCredentials();
    }

    const channelDialogOpeners = [...document.querySelectorAll('[data-channel-dialog-open]')];
    const channelDialogs = [...document.querySelectorAll('[data-channel-dialog]')];
    const channelDialogOpenersByDialog = new Map();
    const closeChannelDialog = dialog => {
        if (!dialog?.open) return;
        if (typeof dialog.close === 'function') dialog.close();
        else {
            dialog.removeAttribute('open');
            channelDialogOpenersByDialog.get(dialog)?.focus();
        }
    };
    channelDialogOpeners.forEach(opener => {
        const dialog = document.getElementById(opener.getAttribute('aria-controls'));
        if (!dialog) return;
        channelDialogOpenersByDialog.set(dialog, opener);
        opener.addEventListener('click', () => {
            channelDialogs.filter(candidate => candidate !== dialog).forEach(closeChannelDialog);
            if (typeof dialog.showModal === 'function') dialog.showModal();
            else dialog.setAttribute('open', '');
            dialog.querySelector('[data-channel-dialog-close]')?.focus();
        });
        dialog.querySelector('[data-channel-dialog-close]')?.addEventListener('click', () =>
            closeChannelDialog(dialog));
        dialog.addEventListener('click', event => {
            if (event.target !== dialog) return;
            const shell = dialog.querySelector('.channel-management-dialog-shell');
            const bounds = shell?.getBoundingClientRect();
            if (!bounds || event.clientX < bounds.left || event.clientX > bounds.right
                    || event.clientY < bounds.top || event.clientY > bounds.bottom) {
                closeChannelDialog(dialog);
            }
        });
        dialog.addEventListener('close', () => channelDialogOpenersByDialog.get(dialog)?.focus());
    });
    document.addEventListener('keydown', event => {
        if (event.key !== 'Escape') return;
        const fallbackDialog = channelDialogs.find(dialog =>
            dialog.open && typeof dialog.showModal !== 'function');
        if (fallbackDialog) {
            event.preventDefault();
            closeChannelDialog(fallbackDialog);
        }
    });

    document.querySelectorAll('[data-count-source]').forEach(counter => {
        const source = document.querySelector(counter.dataset.countSource);
        const limit = Number(counter.dataset.countLimit || 0);
        const refresh = () => {
            const count = [...(source?.value || '')].length;
            counter.textContent = limit ? `${count} / ${limit}` : String(count);
            counter.classList.toggle('over-limit', limit > 0 && count > limit);
        };
        source?.addEventListener('input', refresh);
        refresh();
    });

    document.querySelectorAll('[data-dirty-form]').forEach(form => {
        let dirty = false;
        form.addEventListener('input', () => { dirty = true; });
        form.addEventListener('change', () => { dirty = true; });
        form.addEventListener('submit', () => { dirty = false; });
        window.addEventListener('beforeunload', event => {
            if (!dirty) return;
            event.preventDefault();
            event.returnValue = '';
        });
    });

    document.querySelectorAll('[data-generation-preset]').forEach(select => {
        const form = select.closest('[data-generation-form]');
        const state = form?.querySelector('[data-preset-state]');
        select.addEventListener('change', () => {
            const option = select.selectedOptions[0];
            if (!form || !select.value || !option) {
                if (state) state.textContent = '保留当前设置。';
                return;
            }
            Object.entries(option.dataset).forEach(([name, value]) => {
                if (!value) return;
                const field = form.elements.namedItem(name);
                if (!field) return;
                field.value = value;
                field.dispatchEvent(new Event('input', {bubbles: true}));
                field.dispatchEvent(new Event('change', {bubbles: true}));
            });
            if (state) state.textContent = `${select.selectedOptions[0].textContent}预设已应用，可继续调整。`;
        });
    });

    const csrfHeaders = root => {
        const token = root?.querySelector('input[name="_csrf"]')?.value
            || document.querySelector('input[name="_csrf"]')?.value;
        return token ? {'X-CSRF-TOKEN': token} : {};
    };
    const splitEditorValues = value => (value || '').split(/[\n,，]+/)
        .map(item => item.trim()).filter((item, index, values) => item && values.indexOf(item) === index);
    const draftForm = document.querySelector('form[data-draft-url]');
    if (draftForm) {
        const state = document.querySelector('[data-draft-state]');
        const storageKey = `content-publisher:draft:${draftForm.dataset.draftUrl}`;
        let timer = null;
        let conflict = false;
        let submitting = false;
        const fields = ['title', 'summary', 'markdown', 'tags', 'keywords',
            'titleEn', 'summaryEn', 'markdownEn', 'tagsEn', 'keywordsEn'];
        const payload = () => ({
            baseVersion: Number(draftForm.elements.namedItem('expectedVersion')?.value || 0),
            title: draftForm.elements.namedItem('title')?.value || '',
            summary: draftForm.elements.namedItem('summary')?.value || '',
            markdown: draftForm.elements.namedItem('markdown')?.value || '',
            tags: splitEditorValues(draftForm.elements.namedItem('tags')?.value),
            keywords: splitEditorValues(draftForm.elements.namedItem('keywords')?.value),
            titleEn: draftForm.elements.namedItem('titleEn')?.value || '',
            summaryEn: draftForm.elements.namedItem('summaryEn')?.value || '',
            markdownEn: draftForm.elements.namedItem('markdownEn')?.value || '',
            tagsEn: splitEditorValues(draftForm.elements.namedItem('tagsEn')?.value),
            keywordsEn: splitEditorValues(draftForm.elements.namedItem('keywordsEn')?.value)
        });
        const restoreLocal = () => {
            if (draftForm.dataset.hasServerDraft === 'true') return;
            try {
                const saved = JSON.parse(window.localStorage.getItem(storageKey) || 'null');
                if (!saved?.payload || !window.confirm('检测到上次网络失败时保存在本机的编辑内容，是否恢复？')) return;
                fields.forEach(name => {
                    const field = draftForm.elements.namedItem(name);
                    const value = saved.payload[name];
                    if (!field || value == null) return;
                    field.value = Array.isArray(value) ? value.join('\n') : value;
                    field.dispatchEvent(new Event('input', {bubbles: true}));
                });
                if (state) state.textContent = '已恢复本机应急草稿，正在同步到服务端。';
            } catch (_error) { /* Invalid or unavailable local storage is ignored. */ }
        };
        const saveDraft = async () => {
            if (submitting || conflict || !draftForm.checkValidity()) return;
            if (state) state.textContent = '草稿保存中…';
            const bodyPayload = payload();
            try {
                const response = await fetch(draftForm.dataset.draftUrl, {
                    method: 'PUT', credentials: 'same-origin',
                    headers: {'Content-Type': 'application/json', ...csrfHeaders(draftForm)},
                    body: JSON.stringify(bodyPayload)
                });
                const result = await response.json().catch(() => ({}));
                if (!response.ok) {
                    if (response.status === 409) {
                        conflict = true;
                        if (state) state.textContent = result.message || '文章已有新版本，自动保存已暂停。';
                        return;
                    }
                    throw new Error(result.message || `HTTP ${response.status}`);
                }
                try { window.localStorage.removeItem(storageKey); } catch (_error) { /* Ignore. */ }
                if (state) state.textContent = `草稿已保存 · ${new Date(result.updatedAt).toLocaleTimeString()}`;
            } catch (_error) {
                try { window.localStorage.setItem(storageKey, JSON.stringify({savedAt: Date.now(), payload: bodyPayload})); }
                catch (_storageError) { /* Ignore. */ }
                if (state) state.textContent = '网络异常，内容已暂存本机并将在继续编辑时重试。';
            }
        };
        draftForm.addEventListener('input', () => {
            if (conflict || submitting) return;
            window.clearTimeout(timer);
            if (state) state.textContent = '等待自动保存…';
            timer = window.setTimeout(saveDraft, 900);
        });
        draftForm.addEventListener('submit', () => {
            submitting = true;
            window.clearTimeout(timer);
            try { window.localStorage.removeItem(storageKey); } catch (_error) { /* Ignore. */ }
        });
        restoreLocal();
    }

    document.querySelectorAll('form[data-schedule-form]').forEach(form => {
        const localInput = form.querySelector('input[data-schedule-local]');
        const offsetInput = form.elements.namedItem('scheduledAtOffset');
        const zoneInput = form.elements.namedItem('timeZone');
        const state = form.querySelector('[data-schedule-state]');
        const refresh = () => {
            if (zoneInput) zoneInput.value = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
            if (!localInput?.value) {
                if (offsetInput) offsetInput.value = '';
                if (state) state.textContent = '留空立即进入队列；最长可预约一年。';
                return;
            }
            const local = new Date(localInput.value);
            if (Number.isNaN(local.getTime())) return;
            if (offsetInput) offsetInput.value = local.toISOString();
            if (state) state.textContent = `本地 ${local.toLocaleString()} · UTC ${local.toISOString()}`;
        };
        localInput?.addEventListener('input', refresh);
        form.addEventListener('submit', refresh);
        refresh();
    });

    document.querySelectorAll('[data-local-time]').forEach(element => {
        const date = new Date(element.getAttribute('datetime') || '');
        if (!Number.isNaN(date.getTime())) {
            element.textContent = date.toLocaleString([], {dateStyle: 'medium', timeStyle: 'short'});
            element.title = date.toISOString();
        }
    });

    document.querySelectorAll('form[data-manual-progress]').forEach(form => {
        const progress = {
            copiedTitle: form.dataset.copiedTitle === 'true',
            copiedContent: form.dataset.copiedContent === 'true',
            openedEditor: form.dataset.openedEditor === 'true',
            checkedFormat: form.dataset.checkedFormat === 'true',
            published: form.dataset.published === 'true'
        };
        const state = form.querySelector('[data-progress-state]');
        const csrf = form.querySelector('input[name="_csrf"]')?.value;
        let timer;
        const save = async () => {
            const headers = {'Content-Type': 'application/json'};
            if (csrf) headers['X-CSRF-TOKEN'] = csrf;
            if (state) state.textContent = '正在保存操作进度…';
            try {
                const response = await fetch(form.dataset.progressUrl, {
                    method: 'PUT', credentials: 'same-origin', headers, body: JSON.stringify(progress)
                });
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                if (state) state.textContent = '操作进度已保存。确认平台发布成功后再提交记录。';
            } catch (_error) {
                if (state) state.textContent = '进度保存失败，不影响当前页面操作；请保持页面打开后重试。';
            }
        };
        const queue = () => {
            window.clearTimeout(timer);
            timer = window.setTimeout(save, 250);
        };
        form.addEventListener('publisher:copied', event => {
            const step = event.target.dataset.progressStep;
            if (step === 'copiedAll') {
                progress.copiedTitle = true;
                progress.copiedContent = true;
            } else if (step) {
                progress[step] = true;
            }
            queue();
        });
        form.querySelector('[data-progress-step="openedEditor"]')?.addEventListener('click', () => {
            progress.openedEditor = true;
            queue();
        });
        form.querySelector('[data-progress-check="checkedFormat"]')?.addEventListener('change', event => {
            progress.checkedFormat = event.target.checked;
            queue();
        });
    });

    const editorWorkspace = document.querySelector('[data-editor-workspace]');
    if (editorWorkspace) {
        const tabs = [...editorWorkspace.querySelectorAll('[data-editor-tab]')];
        const panels = [...editorWorkspace.querySelectorAll('[data-editor-panel]')];
        let activeLanguage = 'zh';
        const activateEditorTab = (tab, moveFocus = false) => {
            const name = tab.dataset.editorTab;
            if (name === 'zh' || name === 'en') activeLanguage = name;
            tabs.forEach(item => {
                const selected = item === tab;
                item.setAttribute('aria-selected', String(selected));
                item.tabIndex = selected ? 0 : -1;
            });
            panels.forEach(panel => { panel.hidden = panel.dataset.editorPanel !== name; });
            if (moveFocus) tab.focus();
        };
        tabs.forEach((tab, index) => {
            tab.addEventListener('click', () => activateEditorTab(tab));
            tab.addEventListener('keydown', event => {
                if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
                event.preventDefault();
                let next = index;
                if (event.key === 'ArrowLeft') next = (index - 1 + tabs.length) % tabs.length;
                if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
                if (event.key === 'Home') next = 0;
                if (event.key === 'End') next = tabs.length - 1;
                activateEditorTab(tabs[next], true);
            });
        });
        activateEditorTab(tabs.find(tab => tab.getAttribute('aria-selected') === 'true') || tabs[0]);

        const previewPanel = editorWorkspace.querySelector('[data-editor-panel="preview"]');
        const previewTarget = previewPanel?.querySelector('[data-markdown-preview]');
        const previewState = previewPanel?.querySelector('[data-preview-state]');
        const renderPreview = async language => {
            const source = editorWorkspace.querySelector(`[data-preview-source="${language || activeLanguage}"]`);
            if (!source || !previewPanel || !previewTarget) return;
            const previewTab = tabs.find(tab => tab.dataset.editorTab === 'preview');
            if (previewTab) activateEditorTab(previewTab);
            previewState?.classList.remove('error');
            if (previewState) previewState.textContent = '正在生成预览…';
            const csrf = editorWorkspace.querySelector('input[name="_csrf"]')?.value;
            const headers = { 'Content-Type': 'application/json' };
            if (csrf) headers['X-CSRF-TOKEN'] = csrf;
            try {
                const response = await fetch(previewPanel.dataset.previewUrl, {
                    method: 'POST', headers, credentials: 'same-origin',
                    body: JSON.stringify({ markdown: source.value || '' })
                });
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                const payload = await response.json();
                previewTarget.innerHTML = payload.html || '<p>暂无内容。</p>';
                if (previewState) previewState.textContent = language === 'en' ? '英文预览已更新' : '中文预览已更新';
            } catch (error) {
                previewTarget.textContent = '预览生成失败。';
                previewState?.classList.add('error');
                if (previewState) previewState.textContent = '请检查登录状态或稍后重试。';
            }
        };
        editorWorkspace.querySelectorAll('[data-render-preview]').forEach(button =>
            button.addEventListener('click', () => renderPreview(button.dataset.renderPreview)));
    }

    const publishedUrlInput = document.querySelector('#manual-external-url');
    const publishedUrlButton = document.querySelector('[data-validate-published-url]');
    const publishedUrlState = document.querySelector('[data-published-url-state]');
    if (publishedUrlInput && publishedUrlButton && publishedUrlState) {
        const initialHint = publishedUrlState.textContent;
        const showState = (message, state) => {
            publishedUrlState.textContent = message;
            publishedUrlState.classList.toggle('valid', state === 'valid');
            publishedUrlState.classList.toggle('invalid', state === 'invalid');
        };
        publishedUrlInput.addEventListener('input', () => {
            publishedUrlInput.setCustomValidity('');
            showState(initialHint, 'idle');
        });
        publishedUrlButton.addEventListener('click', async () => {
            const value = publishedUrlInput.value.trim();
            if (!value) {
                publishedUrlInput.setCustomValidity('请先填写发布后的文章链接');
                publishedUrlInput.reportValidity();
                return;
            }
            publishedUrlButton.disabled = true;
            showState('正在验证链接…', 'idle');
            try {
                const url = new URL(publishedUrlButton.dataset.validationUrl, window.location.origin);
                url.searchParams.set('channelType', publishedUrlButton.dataset.channelType);
                url.searchParams.set('url', value);
                const response = await fetch(url, {
                    credentials: 'same-origin', cache: 'no-store', headers: {'Accept': 'application/json'}
                });
                const result = await response.json().catch(() => ({}));
                if (!response.ok) throw new Error(result.message || '链接校验失败');
                publishedUrlInput.value = result.normalizedUrl || value;
                publishedUrlInput.setCustomValidity('');
                showState(`链接有效 · ${new URL(publishedUrlInput.value).hostname}`, 'valid');
            } catch (error) {
                const message = error.message || '链接校验失败';
                publishedUrlInput.setCustomValidity(message);
                showState(message, 'invalid');
                publishedUrlInput.reportValidity();
            } finally {
                publishedUrlButton.disabled = false;
            }
        });
    }

    const jobLive = document.querySelector('[data-job-live]');
    if (jobLive?.dataset.jobActive === 'true') {
        const jobStatusLabels = {
            PENDING: '等待执行', RUNNING: '执行中', RETRY_WAIT: '等待重试',
            SUCCEEDED: '执行成功', FAILED: '执行失败', CANCELLED: '已取消'
        };
        const progressCard = document.querySelector('.job-progress-card');
        const progressTrack = document.querySelector('.job-progress-track');
        const progressBar = document.querySelector('[data-job-progress-bar]');
        const progressPercent = document.querySelector('[data-job-progress-percent]');
        const progressLabel = document.querySelector('[data-job-progress-label]');
        const progressDetail = document.querySelector('[data-job-progress-detail]');
        const liveNote = document.querySelector('[data-job-live-note]');
        const status = document.querySelector('[data-job-status]');
        const attempt = document.querySelector('[data-job-attempt]');
        let displayedProgress = Number(progressTrack?.getAttribute('aria-valuenow') || 8);
        let syncing = false;
        const renderProgress = value => {
            displayedProgress = Math.max(0, Math.min(100, Number(value) || 0));
            if (progressBar) progressBar.style.width = `${displayedProgress}%`;
            if (progressPercent) progressPercent.textContent = `${Math.round(displayedProgress)}%`;
            progressTrack?.setAttribute('aria-valuenow', String(Math.round(displayedProgress)));
            document.querySelectorAll('[data-stage-threshold]').forEach(stage => {
                stage.classList.toggle('done', displayedProgress >= Number(stage.dataset.stageThreshold));
            });
        };
        const pollJob = async () => {
            if (syncing) return;
            syncing = true;
            try {
                const response = await fetch(jobLive.dataset.jobStatusUrl, {
                    credentials: 'same-origin', cache: 'no-store', headers: {'Accept': 'application/json'}
                });
                if (!response.ok) throw new Error(`任务状态请求失败: ${response.status}`);
                const job = await response.json();
                progressCard?.classList.remove('job-progress-sync-error');
                if (progressLabel) progressLabel.textContent = job.progressLabel;
                if (progressDetail) progressDetail.textContent = job.progressDetail;
                if (attempt) attempt.textContent = `${job.attempt}/${job.maxAttempts}`;
                if (status) {
                    status.className = `status-pill status-${job.status.toLowerCase()}`;
                    status.textContent = jobStatusLabels[job.status] || job.status;
                }
                renderProgress(job.progressPercent);
                if (liveNote) liveNote.textContent = `刚刚同步 · ${new Date().toLocaleTimeString()}`;
                if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(job.status)) {
                    progressCard?.classList.toggle('job-progress-failed', job.status === 'FAILED');
                    window.setTimeout(() => window.location.reload(), 500);
                }
            } catch (_error) {
                progressCard?.classList.add('job-progress-sync-error');
                if (liveNote) liveNote.textContent = '状态同步暂时中断，正在自动重试';
            } finally {
                syncing = false;
            }
        };
        window.setInterval(pollJob, 2000);
        pollJob();
    }

    let publicationBatches = document.querySelector('[data-publication-batches]');
    if (publicationBatches?.dataset.batchesActive === 'true') {
        let refreshingBatches = false;
        let batchRefreshTimer = null;
        const refreshPublicationBatches = async () => {
            if (refreshingBatches || document.hidden || publicationBatches.contains(document.activeElement)) return;
            refreshingBatches = true;
            const state = publicationBatches.querySelector('[data-batch-refresh-state]');
            if (state) state.textContent = '正在同步…';
            try {
                const response = await fetch(window.location.href, {
                    credentials: 'same-origin', cache: 'no-store', headers: {'Accept': 'text/html'}
                });
                if (!response.ok) throw new Error(`批次状态请求失败: ${response.status}`);
                const nextDocument = new DOMParser().parseFromString(await response.text(), 'text/html');
                const nextBatches = nextDocument.querySelector('[data-publication-batches]');
                if (!nextBatches) throw new Error('批次区域不存在');
                publicationBatches.replaceWith(nextBatches);
                publicationBatches = nextBatches;
                bindConfirmForms(publicationBatches);
                const currentSummary = document.querySelector('.publishing-summary');
                const nextSummary = nextDocument.querySelector('.publishing-summary');
                if (currentSummary && nextSummary) currentSummary.innerHTML = nextSummary.innerHTML;
                const currentTabs = document.querySelector('.workspace-tabs');
                const nextTabs = nextDocument.querySelector('.workspace-tabs');
                if (currentTabs && nextTabs) currentTabs.innerHTML = nextTabs.innerHTML;
                const refreshedState = publicationBatches.querySelector('[data-batch-refresh-state]');
                const remainsActive = publicationBatches.dataset.batchesActive === 'true';
                if (refreshedState) refreshedState.textContent = remainsActive
                    ? `刚刚同步 · ${new Date().toLocaleTimeString()}` : '批次已完成';
                if (!remainsActive && batchRefreshTimer) window.clearInterval(batchRefreshTimer);
            } catch (_error) {
                const failedState = publicationBatches.querySelector('[data-batch-refresh-state]');
                if (failedState) failedState.textContent = '同步失败，稍后自动重试';
            } finally {
                refreshingBatches = false;
            }
        };
        batchRefreshTimer = window.setInterval(refreshPublicationBatches, 5000);
    }

    const monitorScreen = document.querySelector('[data-monitor-screen]');
    if (monitorScreen) {
        const clock = monitorScreen.querySelector('[data-monitor-clock]');
        const countdown = monitorScreen.querySelector('[data-monitor-countdown]');
        const refreshState = monitorScreen.querySelector('[data-monitor-refresh-state]');
        const refreshSeconds = Number(monitorScreen.dataset.refreshSeconds || 60);
        const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
        const chartNumberFormat = new Intl.NumberFormat('zh-CN');
        let remaining = refreshSeconds;
        let refreshing = false;
        const readChartValue = element => {
            const value = Number(element.dataset.chartValue || 0);
            return Number.isFinite(value) ? Math.max(0, value) : 0;
        };
        const renderChartNumber = element => {
            const target = readChartValue(element);
            const suffix = element.dataset.chartSuffix || '';
            const format = value => `${chartNumberFormat.format(Math.round(value))}${suffix}`;
            if (reducedMotion.matches) {
                element.textContent = format(target);
                return;
            }
            const startedAt = performance.now();
            const duration = 720;
            element.textContent = format(0);
            const step = now => {
                const progress = Math.min(1, (now - startedAt) / duration);
                const eased = 1 - Math.pow(1 - progress, 3);
                element.textContent = format(target * eased);
                if (progress < 1) window.requestAnimationFrame(step);
            };
            window.requestAnimationFrame(step);
        };
        const renderMonitorCharts = region => {
            if (!region) return;
            region.querySelectorAll('[data-monitor-gauge]').forEach(gauge => {
                const value = Math.min(100, readChartValue(gauge));
                const gaugeValue = gauge.querySelector('.monitor-gauge-value');
                if (!gaugeValue) return;
                gaugeValue.style.strokeDasharray = reducedMotion.matches ? `${value} 100` : '0 100';
                if (!reducedMotion.matches) window.requestAnimationFrame(() => {
                    window.requestAnimationFrame(() => { gaugeValue.style.strokeDasharray = `${value} 100`; });
                });
            });
            const columns = [...region.querySelectorAll('[data-monitor-column]')];
            const columnMax = Math.max(1, ...columns.map(readChartValue));
            columns.forEach(column => {
                const value = readChartValue(column);
                const columnFill = column.querySelector('.monitor-column-track i');
                if (!columnFill) return;
                const height = value === 0 ? 0 : Math.max(4, Math.min(100, value / columnMax * 100));
                columnFill.style.height = reducedMotion.matches ? `${height}%` : '0%';
                if (!reducedMotion.matches) window.requestAnimationFrame(() => {
                    window.requestAnimationFrame(() => { columnFill.style.height = `${height}%`; });
                });
            });
            region.querySelectorAll('[data-chart-number]').forEach(renderChartNumber);
        };
        const readMonitorTabSelection = region => {
            const selection = new Map();
            if (!region) return selection;
            region.querySelectorAll('[data-monitor-tabs]').forEach(group => {
                const groupName = group.dataset.monitorTabGroup;
                const selectedTab = group.querySelector('[data-monitor-tab][aria-selected="true"]');
                if (groupName && selectedTab) selection.set(groupName, selectedTab.dataset.monitorTab);
            });
            return selection;
        };
        const initializeMonitorTabs = (region, preferredSelection = new Map()) => {
            if (!region) return;
            region.querySelectorAll('[data-monitor-tabs]').forEach(group => {
                const tabs = [...group.querySelectorAll('[data-monitor-tab]')];
                const panels = [...group.querySelectorAll('[data-monitor-tab-panel]')];
                if (!tabs.length || !panels.length) return;
                const requestedTab = preferredSelection.get(group.dataset.monitorTabGroup);
                const initialTab = tabs.find(tab => tab.dataset.monitorTab === requestedTab)
                    || tabs.find(tab => tab.getAttribute('aria-selected') === 'true')
                    || tabs[0];
                const activateTab = (tab, moveFocus = false) => {
                    tabs.forEach(item => {
                        const selected = item === tab;
                        item.setAttribute('aria-selected', String(selected));
                        item.tabIndex = selected ? 0 : -1;
                    });
                    panels.forEach(panel => {
                        panel.hidden = panel.dataset.monitorTabPanel !== tab.dataset.monitorTab;
                    });
                    if (moveFocus) tab.focus();
                };
                activateTab(initialTab);
                tabs.forEach((tab, index) => {
                    tab.addEventListener('click', () => activateTab(tab));
                    tab.addEventListener('keydown', event => {
                        let nextIndex;
                        if (event.key === 'ArrowRight' || event.key === 'ArrowDown') nextIndex = (index + 1) % tabs.length;
                        else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') nextIndex = (index - 1 + tabs.length) % tabs.length;
                        else if (event.key === 'Home') nextIndex = 0;
                        else if (event.key === 'End') nextIndex = tabs.length - 1;
                        else return;
                        event.preventDefault();
                        activateTab(tabs[nextIndex], true);
                    });
                });
            });
        };
        const renderMonitorRegion = (region, preferredSelection) => {
            initializeMonitorTabs(region, preferredSelection);
            renderMonitorCharts(region);
        };
        const refreshMonitor = async () => {
            if (refreshing) return;
            refreshing = true;
            monitorScreen.classList.add('monitor-refreshing');
            monitorScreen.classList.remove('monitor-refresh-failed', 'monitor-refresh-ok');
            if (refreshState) refreshState.textContent = '正在同步';
            try {
                const url = new URL(monitorScreen.dataset.monitorLiveUrl, window.location.origin);
                url.searchParams.set('range', monitorScreen.dataset.monitorRange || '24h');
                const response = await fetch(url, {
                    credentials: 'same-origin',
                    cache: 'no-store',
                    headers: {'Accept': 'text/html', 'X-Requested-With': 'XMLHttpRequest'}
                });
                if (!response.ok) throw new Error(`刷新请求失败: ${response.status}`);
                const documentFragment = new DOMParser().parseFromString(await response.text(), 'text/html');
                const nextRegion = documentFragment.querySelector('[data-monitor-live-region]');
                const currentRegion = monitorScreen.querySelector('[data-monitor-live-region]');
                if (!nextRegion || !currentRegion) throw new Error('刷新内容不完整');
                const selectedTabs = readMonitorTabSelection(currentRegion);
                currentRegion.replaceWith(nextRegion);
                renderMonitorRegion(nextRegion, selectedTabs);
                remaining = refreshSeconds;
                monitorScreen.classList.add('monitor-refresh-ok');
                if (refreshState) refreshState.textContent = '刚刚更新';
            } catch (_error) {
                remaining = Math.min(15, refreshSeconds);
                monitorScreen.classList.add('monitor-refresh-failed');
                if (refreshState) refreshState.textContent = '更新失败，准备重试';
            } finally {
                monitorScreen.classList.remove('monitor-refreshing');
                refreshing = false;
            }
        };
        const tick = () => {
            if (clock) clock.textContent = new Date().toISOString().replace('T', ' ').replace(/\.\d{3}Z$/, ' UTC');
            remaining -= 1;
            if (remaining <= 0) refreshMonitor();
            if (countdown) countdown.textContent = String(Math.max(remaining, 0));
        };
        window.setInterval(tick, 1000);
        tick();
        monitorScreen.querySelector('[data-monitor-refresh-now]')?.addEventListener('click', refreshMonitor);
        monitorScreen.querySelector('[data-monitor-fullscreen]')?.addEventListener('click', async () => {
            try {
                if (document.fullscreenElement) await document.exitFullscreen();
                else await document.documentElement.requestFullscreen();
            } catch (_error) {
                // Browsers may deny fullscreen when the page is embedded.
            }
        });
        renderMonitorRegion(monitorScreen.querySelector('[data-monitor-live-region]'));
    }

    if (body.classList.contains('app-page') && !document.querySelector('[data-support-bot]')) {
        const supportBot = document.createElement('script');
        supportBot.src = '/support-bot/static/widget.js';
        supportBot.dataset.apiBase = '/support-bot';
        supportBot.dataset.supportBot = 'true';
        supportBot.async = true;
        document.body.appendChild(supportBot);
    }
})();
