(() => {
    const storageKey = 'content-publisher:theme';
    const allowed = new Set(['system', 'light', 'dark']);
    let preference = 'system';
    try {
        const stored = window.localStorage.getItem(storageKey);
        if (allowed.has(stored)) preference = stored;
    } catch (_error) {
        // The system preference remains available when storage is blocked.
    }
    const resolved = preference === 'system'
        ? (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
        : preference;
    document.documentElement.dataset.themePreference = preference;
    document.documentElement.dataset.theme = resolved;
})();
