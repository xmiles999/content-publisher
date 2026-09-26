// Opt-in browser regression against an isolated, DISABLED-mode local test database.
// Never point this at your daily-use database: this script creates test manuscripts.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

async function main() {
    assert.equal(process.env.PUBLISHER_UI_TEST_ALLOW_WRITES, 'true',
        '请使用隔离测试库，并设置 PUBLISHER_UI_TEST_ALLOW_WRITES=true');
    const base = new URL(process.env.PUBLISHER_UI_TEST_URL);
    assert.ok(['127.0.0.1', '[::1]', 'localhost'].includes(base.hostname), '只允许回环测试地址');
    const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
    const output = process.env.PUBLISHER_UI_TEST_OUTPUT;
    assert.ok(output && path.isAbsolute(output), '截图和报告必须指定仓库外的绝对目录');
    const root = path.resolve(__dirname, '..');
    assert.ok(!path.resolve(output).startsWith(root + path.sep) && path.resolve(output) !== root);
    fs.mkdirSync(output, {recursive: true});
    const browser = await chromium.launch({
        headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE
    });
    const checks = [], errors = [];
    try {
        const page = await browser.newPage({viewport: {width: 1440, height: 1000}});
        page.on('pageerror', error => errors.push(error.message));
        page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
        const visit = async route => {
            const response = await page.goto(new URL(route, base).href);
            assert.equal(response.status(), 200, route);
            assert.equal(await page.locator('meta[name=robots]').getAttribute('content'), 'noindex,nofollow');
            assert.ok((await page.locator('body').innerText()).length > 60);
        };
        const guarded = () => page.evaluate(() => {
            const event = new Event('beforeunload', {cancelable: true});
            window.dispatchEvent(event);
            return event.defaultPrevented;
        });
        await visit('/projects?source=custom');
        await page.getByLabel('文章标题', {exact: true}).fill('工作区回归 ' + Date.now());
        await page.locator('#custom-markdown').fill('# 测试文稿\n\n这是隔离环境的测试正文。');
        assert.ok(await guarded());
        checks.push('新建表单离开保护');
        await page.getByRole('button', {name: '保存并进入主稿', exact: true}).click();
        await page.waitForURL(/\/articles\/[0-9a-f-]+$/);
        const articlePath = new URL(page.url()).pathname;
        const apiPath = '/api/v1' + articlePath;
        const title = await page.title();
        assert.ok(title.includes('工作区回归'));
        assert.equal(await page.locator('.editor-metadata[open]').count(), 0);
        // Collapsed required fields must reveal themselves before browser validation focuses them.
        await page.locator('#editor-panel-zh .editor-metadata > summary').click();
        await page.locator('#article-summary').fill('');
        await page.locator('#editor-panel-zh .editor-metadata > summary').click();
        await page.getByRole('button', {name: '保存版本', exact: true}).click();
        assert.equal(await page.locator('#editor-panel-zh .editor-metadata').getAttribute('open'), '');
        await page.locator('#article-summary').fill('恢复摘要，继续验证自动保存。');
        checks.push('折叠字段验证可见且可聚焦');

        let translations = 0, releaseTranslation, startedTranslation;
        let holdTranslation = false;
        const generated = {titleEn: 'Generated title', summaryEn: 'Generated summary',
            markdownEn: '# Generated body', tagsEn: ['test'], keywordsEn: ['test']};
        await page.route('**/english-translations', async route => {
            translations++;
            if (holdTranslation) {
                startedTranslation?.();
                await new Promise(resolve => { releaseTranslation = resolve; });
            }
            await route.fulfill({json: generated});
        });
        await page.locator('[data-editor-tab=en]').click();
        const draftSaved = page.waitForResponse(response => response.url().endsWith(apiPath + '/draft')
            && response.request().method() === 'PUT' && response.request().postDataJSON().titleEn === generated.titleEn);
        await page.locator('[data-translate-english]').click();
        assert.equal((await draftSaved).status(), 200);
        await page.waitForFunction(() => document.querySelector('[data-draft-state]').textContent.includes('草稿已保存'));
        const draft = await (await page.request.get(new URL(apiPath + '/draft', base).href)).json();
        assert.equal(draft.titleEn, generated.titleEn);
        checks.push('生成英文稿无需再次键入即可自动保存（AI 响应为本地替身）');

        page.once('dialog', dialog => dialog.dismiss());
        await page.locator('[data-translate-english]').click();
        assert.equal(translations, 1);
        assert.equal(await page.locator('#article-title-en').inputValue(), generated.titleEn);
        holdTranslation = true;
        const translationStarted = new Promise(resolve => { startedTranslation = resolve; });
        page.once('dialog', dialog => dialog.accept());
        await page.locator('[data-translate-english]').click();
        await translationStarted;
        await page.locator('#article-markdown-en').fill('生成期间的人工修改');
        releaseTranslation();
        await page.locator('[data-translate-state]').filter({hasText: '未覆盖你的输入'}).waitFor();
        assert.equal(await page.locator('#article-markdown-en').inputValue(), '生成期间的人工修改');
        checks.push('英文替换确认、取消及请求期间的编辑保护');

        await page.locator('[data-editor-tab=zh]').click();
        let releaseOld, oldStarted;
        const oldPending = new Promise(resolve => { oldStarted = resolve; });
        await page.route('**/markdown/preview', async route => {
            const markdown = route.request().postDataJSON().markdown;
            if (markdown === '旧预览') {
                oldStarted();
                await new Promise(resolve => { releaseOld = resolve; });
            }
            await route.fulfill({json: {html: `<p>${markdown === '旧预览' ? '旧预览' : '最新预览'}</p>`}});
        });
        await page.locator('#article-markdown').fill('旧预览');
        await oldPending;
        const newPreview = page.waitForResponse(response => response.url().endsWith('/markdown/preview')
            && response.request().postDataJSON().markdown === '最新预览');
        await page.locator('#article-markdown').fill('最新预览');
        await newPreview;
        await page.waitForFunction(() => document.querySelector('[data-live-preview=zh]').textContent === '最新预览');
        const oldResponse = page.waitForResponse(response => response.url().endsWith('/markdown/preview')
            && response.request().postDataJSON().markdown === '旧预览');
        releaseOld();
        await oldResponse;
        await page.waitForTimeout(150);
        assert.equal(await page.locator('[data-live-preview=zh]').innerText(), '最新预览');
        checks.push('预览乱序响应不会覆盖新正文');
        await page.unroute('**/markdown/preview');
        await page.locator('#article-markdown').fill('# 正文优先\n\n文章信息按需展开，正文保留完整空间。');
        await page.waitForFunction(() => document.querySelector('[data-draft-state]').textContent.includes('草稿已保存'));
        await page.locator('#editor-panel-zh .editor-metadata > summary').click();
        for (const theme of ['light', 'dark']) {
            await page.evaluate(theme => {
                localStorage.setItem('content-publisher:theme', theme);
                document.documentElement.dataset.theme = theme;
            }, theme);
            for (const width of [1440, 768, 414, 375, 320]) {
                await page.setViewportSize({width, height: 1000});
                await page.locator('[data-editor-view=edit]').click();
                assert.ok(await page.locator('[data-live-preview=zh]').isHidden());
                await page.locator('[data-editor-view=preview]').click();
                assert.ok(await page.locator('#article-markdown').isHidden());
                await page.locator('[data-editor-view=edit]').click();
                if (width > 820) await page.locator('[data-editor-view=split]').click();
                assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1),
                    `${theme}/${width} 不应横向溢出`);
                await page.screenshot({path: path.join(output, `editor-${theme}-${width}.png`)});
            }
        }
        checks.push('明暗主题 1440/768/414/375/320px、编辑/分栏/预览和横向溢出');
        await page.setViewportSize({width: 1440, height: 1000});
        await page.getByRole('button', {name: '保存并准备发布', exact: true}).click();
        await page.waitForURL(url => url.pathname === articlePath);
        await page.getByRole('link', {name: '去发布', exact: true}).waitFor();
        await visit(articlePath + '/manual/CSDN');
        await page.getByLabel('平台标题', {exact: true}).fill('未提交的平台标题');
        assert.ok(await guarded());
        await page.locator('[data-progress-check=checkedFormat]').check();
        await page.locator('[data-progress-state]').filter({hasText: '标题、正文和链接尚未提交'}).waitFor();
        checks.push('人工发布表单标签、离开保护与步骤保存边界');
        assert.deepEqual(errors, []);
        fs.writeFileSync(path.join(output, 'result.json'), JSON.stringify({checks, errors}, null, 2));
        console.log(JSON.stringify({checks, errors}, null, 2));
    } finally {
        await browser.close();
    }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
