'use strict';

// ============================================================================
// State
// ============================================================================
let catalog = [];             // databases + versions from /api/database/catalog
let namingResult = null;      // result of step 1
let pendingImport = null;     // import payload waiting for the "replace table?" answer

const STORAGE_KEY = 'dataImporter.form';
const REMEMBERED_FIELDS = ['n-db', 'n-version', 'n-csv', 'i-db', 'i-url', 'i-user', 'i-data', 'i-mapping', 'i-table'];

// Version-check help for each database (shown in the "Not sure of the version?" window).
const VERSION_HELP = {
    mysql: {
        queries: [
            { sql: 'SELECT VERSION();', what: 'Returns e.g. 8.0.36, 8.4.2 or 9.1.0' },
            { sql: "SHOW VARIABLES LIKE 'version';", what: 'Same information, as a name/value row' }
        ],
        tools: [
            'MySQL Workbench: menu Server → Server Status → "Version".',
            'phpMyAdmin: the home page shows "Server version".'
        ],
        pick: [
            ['5.7.x or older', '5.7 and older (ended)'],
            ['8.0.x', '8.0 (ended)'],
            ['8.4.x', '8.4 LTS'],
            ['9.0.x to 9.6.x', '9.0 to 9.6 (Innovation)'],
            ['9.7.x', '9.7 LTS']
        ],
        note: 'Every MySQL version allows 64-character column names, so any choice gives the same result.'
    },
    oracle: {
        queries: [
            { sql: 'SELECT banner FROM v$version;', what: 'Returns e.g. "Oracle Database 19c Enterprise Edition ..."' },
            { sql: 'SELECT version FROM product_component_version;', what: 'Works for most users; returns e.g. 19.0.0.0.0' },
            { sql: "SELECT value FROM v$parameter WHERE name = 'compatible';", what: 'The COMPATIBLE setting (may need DBA rights). In SQL*Plus: SHOW PARAMETER compatible' }
        ],
        tools: [
            'SQL Developer: run a query above in a worksheet (Ctrl+Enter).',
            'SQL*Plus: the version is printed right after you log in.'
        ],
        pick: [
            ['7, 8i, 9i, 10g, 11g, 12.1', '12.1 and earlier'],
            ['12.2, 18c, 19c, 21c, 23ai', '12.2, 18c, 19c, 21c, 23ai and newer'],
            ['12.2 or newer, but COMPATIBLE below 12.2', '12.1 and earlier']
        ],
        note: 'This choice matters: 12.1 and older allow only 30 characters, newer versions 128. If you cannot see COMPATIBLE, choose by version - step 2 checks the real limit and tells you if it does not fit.'
    },
    postgresql: {
        queries: [
            { sql: 'SELECT version();', what: 'Returns e.g. "PostgreSQL 16.3 on x86_64..."' },
            { sql: 'SHOW server_version;', what: 'Just the number, e.g. 16.3' }
        ],
        tools: [
            'pgAdmin: right-click the server → Properties → the "General" tab shows the version.'
        ],
        pick: [['Any version', 'All versions']],
        note: 'All PostgreSQL versions allow 63-byte column names.'
    },
    sqlserver: {
        queries: [
            { sql: 'SELECT @@VERSION;', what: 'Returns e.g. "Microsoft SQL Server 2022 (RTM) - 16.0..."' },
            { sql: "SELECT SERVERPROPERTY('ProductVersion');", what: '16.x = 2022, 15.x = 2019, 14.x = 2017, 13.x = 2016' }
        ],
        tools: [
            'SSMS: the version is shown next to the server name in Object Explorer, e.g. (SQL Server 16.0.1000).'
        ],
        pick: [['Any version', 'All versions']],
        note: 'All SQL Server versions allow 128-character column names.'
    }
};

// ============================================================================
// Start-up
// ============================================================================
document.addEventListener('DOMContentLoaded', async () => {
    startHeartbeat();
    showThemeName();
    wirePathInputs();
    try {
        catalog = await (await fetch('/api/database/catalog')).json();
    } catch (e) {
        catalog = [];
    }
    fillDatabaseSelect('n-db');
    fillDatabaseSelect('i-db');
    restoreForm();
    onNamingDbChange(true);
    onImportDbChange(true);
    loadAiStatus();
    loadNameRules();
    buildHelpTabs();
    showStep(1);
    document.addEventListener('keydown', e => {
        if (e.key === 'Escape') closeModal('helpModal');
    });
});

// ============================================================================
// Themes: one button cycles through them; the choice is remembered in the browser.
// (The <head> script applies the saved theme before the page is drawn.)
// ============================================================================
const THEMES = [
    { id: 'light', name: 'Light' },
    { id: 'dark', name: 'Dark' },
    { id: 'ocean', name: 'Ocean' },
    { id: 'forest', name: 'Forest' },
    { id: 'sunset', name: 'Sunset' },
    { id: 'midnight', name: 'Midnight' }
];
const THEME_KEY = 'dataImporter.theme';

function currentThemeIndex() {
    const id = document.documentElement.getAttribute('data-theme');
    return Math.max(0, THEMES.findIndex(t => t.id === id));
}

function cycleTheme() {
    const next = THEMES[(currentThemeIndex() + 1) % THEMES.length];
    document.documentElement.setAttribute('data-theme', next.id);
    try { localStorage.setItem(THEME_KEY, next.id); } catch (e) { /* storage blocked - theme lasts for this visit */ }
    showThemeName();
}

function showThemeName() {
    const current = THEMES[currentThemeIndex()];
    const next = THEMES[(currentThemeIndex() + 1) % THEMES.length];
    document.getElementById('themeName').textContent = current.name;
    document.getElementById('themeBtn').title = `Theme: ${current.name} - click for ${next.name}`;
}

// The app closes itself when no tab is open (frees the port). Pings come from a Worker so that
// they keep going while this tab is hidden; closing the tab tells the app right away.
function startHeartbeat() {
    let failures = 0;
    try {
        const worker = new Worker('/js/heartbeat-worker.js');
        worker.onmessage = e => {
            failures = e.data === 'ok' ? 0 : failures + 1;
            document.getElementById('appStopped').classList.toggle('hidden', failures < 3);
        };
    } catch (e) {
        setInterval(() => fetch('/api/ping', { method: 'POST' }).catch(() => {}), 5000);
    }
    window.addEventListener('pagehide', () => navigator.sendBeacon('/api/closing'));
}

// ============================================================================
// Steps, form memory, helpers
// ============================================================================
function showStep(n) {
    document.getElementById('step-1').classList.toggle('hidden', n !== 1);
    document.getElementById('step-2').classList.toggle('hidden', n !== 2);
    document.getElementById('pill-1').classList.toggle('active', n === 1);
    document.getElementById('pill-2').classList.toggle('active', n === 2);
    window.scrollTo({ top: 0, behavior: 'smooth' });
}

function saveForm() {
    try {
        const data = {};
        REMEMBERED_FIELDS.forEach(id => { data[id] = document.getElementById(id).value; });
        localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
    } catch (e) { /* storage unavailable - not important */ }
}

function restoreForm() {
    try {
        const data = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}');
        ['n-db', 'i-db'].forEach(id => { if (data[id]) document.getElementById(id).value = data[id]; });
        onNamingDbChange(true);
        REMEMBERED_FIELDS.forEach(id => {
            if (data[id] !== undefined && data[id] !== '') document.getElementById(id).value = data[id];
        });
    } catch (e) { /* ignore */ }
}

// Windows "Copy as path" gives "C:\folder\file.csv" with quotes. Same clean-up as the old app.
function formatPath(input) {
    if (!input) return '';
    return input.replace(/"/g, '').replace(/\\/g, '/').trim();
}

function wirePathInputs() {
    ['n-csv', 'i-data', 'i-mapping'].forEach(id => {
        document.getElementById(id).addEventListener('input', function () {
            const cleaned = formatPath(this.value);
            if (cleaned !== this.value) this.value = cleaned;
        });
    });
    REMEMBERED_FIELDS.forEach(id => document.getElementById(id).addEventListener('change', saveForm));
}

function escapeHtml(s) {
    return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

async function postJson(url, body) {
    const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    });
    let data = {};
    try { data = await response.json(); } catch (e) { /* empty body */ }
    return { ok: response.ok, data };
}

function setBusy(buttonId, spinnerId, busy) {
    document.getElementById(buttonId).disabled = busy;
    document.getElementById(spinnerId).classList.toggle('hidden', !busy);
}

function appendLog(boxId, line) {
    const box = document.getElementById(boxId);
    const row = document.createElement('div');
    row.className = 'log-line log-' + line.level.toLowerCase();
    row.innerHTML = `<span class="log-time">${escapeHtml(line.time)}</span><span class="log-msg">${escapeHtml(line.message)}</span>`;
    box.appendChild(row);
    box.scrollTop = box.scrollHeight;
}

function localLog(boxId, level, message) {
    appendLog(boxId, { time: new Date().toLocaleTimeString([], { hour12: false }), level, message });
}

/** Polls a background task until it finishes; streams its log lines into the given box. */
async function followTask(taskId, logBoxId, onProgress) {
    let from = 0;
    while (true) {
        let snap;
        try {
            snap = await (await fetch(`/api/tasks/${taskId}?from=${from}`)).json();
        } catch (e) {
            await sleep(1500);
            continue;
        }
        snap.logs.forEach(l => appendLog(logBoxId, l));
        from = snap.nextFrom;
        if (onProgress) onProgress(snap.progressDone, snap.progressTotal);
        if (snap.status !== 'RUNNING') return snap;
        await sleep(700);
    }
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

function copyText(inputId, button) {
    const input = document.getElementById(inputId);
    const done = () => { const old = button.textContent; button.textContent = 'Copied'; setTimeout(() => button.textContent = old, 1500); };
    if (navigator.clipboard) {
        navigator.clipboard.writeText(input.value).then(done).catch(() => { input.select(); document.execCommand('copy'); done(); });
    } else {
        input.select(); document.execCommand('copy'); done();
    }
}

function togglePassword(button) {
    const input = document.getElementById('i-pass');
    const show = input.type === 'password';
    input.type = show ? 'text' : 'password';
    button.textContent = show ? 'Hide' : 'Show';
}

// ============================================================================
// Database drop-downs
// ============================================================================
function fillDatabaseSelect(id) {
    const select = document.getElementById(id);
    select.innerHTML = '<option value="">Choose a database...</option>' +
        catalog.map(db => `<option value="${db.id}">${escapeHtml(db.label)}</option>`).join('');
}

function findDb(id) { return catalog.find(db => db.id === id); }

function onNamingDbChange(keepVersion) {
    const db = findDb(document.getElementById('n-db').value);
    const select = document.getElementById('n-version');
    const previous = select.value;
    if (!db) {
        select.innerHTML = '<option value="">Choose the database first</option>';
    } else {
        select.innerHTML = '<option value="">Choose the version...</option>' +
            db.versions.map(v => `<option value="${v.id}">${escapeHtml(v.label)}</option>`).join('');
        if (db.versions.length === 1) select.value = db.versions[0].id;
        if (keepVersion === true && db.versions.some(v => v.id === previous)) select.value = previous;
    }
    onNamingVersionChange();
    saveForm();
}

function onNamingVersionChange() {
    const db = findDb(document.getElementById('n-db').value);
    const versionId = document.getElementById('n-version').value;
    const chip = document.getElementById('n-limit');
    const version = db && db.versions.find(v => v.id === versionId);
    if (!version) {
        chip.classList.add('hidden');
    } else {
        chip.innerHTML = `<span>Maximum column name length: <strong>${version.maxColumnNameLength} ${version.unit}</strong></span>` +
            (version.note ? `<span class="chip-note">${escapeHtml(version.note)}</span>` : '');
        chip.classList.remove('hidden');
    }
    saveForm();
}

function onImportDbChange(keepUrl) {
    const db = findDb(document.getElementById('i-db').value);
    const driver = document.getElementById('i-driver');
    const url = document.getElementById('i-url');
    const hint = document.getElementById('i-url-hint');
    driver.value = db ? db.driverClassName : '';
    url.placeholder = db ? db.urlTemplate : 'Choose a database first';
    hint.textContent = db ? 'Example: ' + db.urlTemplate + ' (replace the capital-letter parts)' : '';
    const looksLikeOtherDb = url.value && db && !url.value.toLowerCase().startsWith(db.urlTemplate.substring(0, db.urlTemplate.indexOf(':', 5)));
    if (db && (!url.value || (keepUrl !== true && looksLikeOtherDb))) url.value = db.urlTemplate;
    document.getElementById('i-test-result').textContent = '';
    saveForm();
}

// ============================================================================
// Step 1: AI naming
// ============================================================================
async function loadAiStatus() {
    const banner = document.getElementById('aiStatus');
    try {
        const status = await (await fetch('/api/naming/ai-status')).json();
        if (status.available) {
            banner.className = 'banner banner-ok';
            banner.innerHTML = '<strong>Gemini AI is ready.</strong> Long names are shortened by AI first; only names AI cannot handle fall back to NLP / vowel removal / hashcode and are flagged for review.';
        } else {
            banner.className = 'banner banner-warn';
            banner.innerHTML = `<strong>AI is not available:</strong> ${escapeHtml(status.reason)}
                <details><summary>How to set up a free Gemini API key</summary>
                <ol>
                    <li>Open <a href="https://aistudio.google.com/apikey" target="_blank" rel="noopener">Google AI Studio</a>, sign in, click <strong>Create API key</strong> and copy it.</li>
                    <li>Open Command Prompt and run: <code>setx GEMINI_API_KEY "your-key-here"</code></li>
                    <li>Close this app and start it again (the key is read at start-up).</li>
                </ol>
                Without a key the app still works, but every long name is shortened mechanically and flagged for review.</details>`;
        }
    } catch (e) {
        banner.className = 'banner banner-warn';
        banner.textContent = 'Could not check the AI status.';
    }
}

async function startNaming() {
    const payload = {
        databaseId: document.getElementById('n-db').value,
        versionId: document.getElementById('n-version').value,
        csvPath: formatPath(document.getElementById('n-csv').value)
    };
    document.getElementById('n-log').innerHTML = '';
    document.getElementById('n-log-card').classList.remove('hidden');
    document.getElementById('n-result').classList.add('hidden');

    if (!payload.databaseId) return localLog('n-log', 'ERROR', 'Choose a database.');
    if (!payload.versionId) return localLog('n-log', 'ERROR', 'Choose the database version (use "Not sure of the version?" if needed).');
    if (!payload.csvPath) return localLog('n-log', 'ERROR', 'Paste the path of the data CSV file.');

    saveForm();
    setBusy('n-start', 'n-spinner', true);
    try {
        const { ok, data } = await postJson('/api/naming/start', payload);
        if (!ok) throw new Error(data.message || 'Could not start.');
        const snap = await followTask(data.taskId, 'n-log');
        if (snap.status === 'SUCCEEDED') {
            showNamingResult(snap.result);
        }
    } catch (e) {
        localLog('n-log', 'ERROR', 'The app did not respond: ' + e.message);
    } finally {
        setBusy('n-start', 'n-spinner', false);
    }
}

// ----------------------------------------------------------------------------
// Step 1 result: editable names with live checks, AI per row / for all flagged
// ----------------------------------------------------------------------------
let nameRules = { reserved: new Set(), rowIdColumn: 'csv_row_id', maxColumnsPerTable: 150 };
let edits = {};          // columnNo -> { name, source: 'user' | 'ai' }  (unsaved changes)
let rowMessages = {};    // columnNo -> text shown under the row (AI suggestion / AI failure)
let serverErrors = {};   // columnNo -> error returned by the server on save
let currentErrors = {};  // columnNo -> error from the live check
let aiBusy = false;

async function loadNameRules() {
    try {
        const r = await (await fetch('/api/naming/rules')).json();
        nameRules = { reserved: new Set(r.reservedWords), rowIdColumn: r.rowIdColumn, maxColumnsPerTable: r.maxColumnsPerTable };
    } catch (e) { /* the server checks again on save */ }
}

function showNamingResult(result) {
    namingResult = result;
    edits = {};
    rowMessages = {};
    serverErrors = {};
    renderNamingStats();
    document.getElementById('n-mapping-path').value = result.mappingFilePath;
    document.getElementById('n-download').href = `/api/naming/${result.sessionId}/mapping-file`;
    document.getElementById('n-flagged-only').checked = result.flaggedColumns > 0;
    document.getElementById('n-errors-only').checked = false;
    renderMappingTable();
    document.getElementById('n-result').classList.remove('hidden');
    document.getElementById('n-result').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function renderNamingStats() {
    const r = namingResult;
    const ai = r.methodCounts['AI (Gemini)'] || 0;
    const fits = r.methodCounts['Not needed (already fits)'] || 0;
    const edited = r.methodCounts['Edited by user'] || 0;
    document.getElementById('n-stats').innerHTML = `
        <div class="stat"><span class="stat-value">${r.totalColumns}</span><span class="stat-label">columns</span></div>
        <div class="stat"><span class="stat-value">${fits}</span><span class="stat-label">already short enough</span></div>
        <div class="stat"><span class="stat-value">${ai}</span><span class="stat-label">named by AI</span></div>
        ${edited ? `<div class="stat"><span class="stat-value">${edited}</span><span class="stat-label">edited by you</span></div>` : ''}
        <div class="stat ${r.flaggedColumns ? 'stat-warn' : 'stat-ok'}"><span class="stat-value">${r.flaggedColumns}</span><span class="stat-label">flagged for review</span></div>
        <div class="stat"><span class="stat-value">${r.maxColumnNameLength}</span><span class="stat-label">max length (${escapeHtml(r.databaseLabel)})</span></div>`;
}

function currentName(row) {
    return edits[row.columnNo] ? edits[row.columnNo].name : row.cleanedName;
}

// Same clean-up as the server (NameRules.sanitize): "Region Code" -> region_code
function normalizeName(s) {
    return (s || '')
        .normalize('NFD').replace(/[̀-ͯ]/g, '')
        .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
        .toLowerCase().trim()
        .replace(/[^a-z0-9]+/g, '_')
        .replace(/^_+|_+$/g, '');
}

// Same checks as the server (NameRules.problem + duplicates). Returns columnNo -> message.
function validateNames() {
    const max = namingResult.maxColumnNameLength;
    const wide = namingResult.totalColumns > nameRules.maxColumnsPerTable;
    const errors = {};
    const usedBy = {};
    namingResult.rows.forEach(row => {
        const name = currentName(row);
        let problem = null;
        if (!name) problem = 'Name is empty';
        else if (!/^[a-z0-9_]+$/.test(name)) problem = 'Use only lowercase letters a-z, digits 0-9 and _ (press Tab to fix automatically)';
        else if (!/^[a-z]/.test(name)) problem = 'Must start with a letter';
        else if (name.length > max) problem = `Too long: ${name.length} characters (max ${max})`;
        else if (nameRules.reserved.has(name)) problem = `'${name}' is a reserved SQL word`;
        else if (wide && name === nameRules.rowIdColumn) problem = `'${name}' is reserved for wide files`;
        if (problem) errors[row.columnNo] = problem;
        else (usedBy[name] = usedBy[name] || []).push(row.columnNo);
    });
    Object.entries(usedBy).forEach(([name, cols]) => {
        if (cols.length > 1) {
            cols.forEach(no => {
                errors[no] = `Duplicate: '${name}' is also used by column ${cols.filter(o => o !== no).join(', ')}`;
            });
        }
    });
    return errors;
}

function renderMappingTable() {
    if (!namingResult) return;
    currentErrors = validateNames();
    const flaggedOnly = document.getElementById('n-flagged-only').checked;
    const errorsOnly = document.getElementById('n-errors-only').checked;
    const filter = document.getElementById('n-filter').value.trim().toLowerCase();
    const rows = namingResult.rows.filter(r =>
        (!flaggedOnly || r.needsReview) &&
        (!errorsOnly || currentErrors[r.columnNo] || serverErrors[r.columnNo]) &&
        (!filter || r.rawName.toLowerCase().includes(filter) || currentName(r).includes(filter)));
    const shown = rows.slice(0, 1000);
    document.getElementById('n-table').innerHTML = shown.map(r => {
        const canAi = normalizeName(r.rawName) !== '';
        const edit = edits[r.columnNo];
        return `
        <tr id="row-${r.columnNo}" class="${r.needsReview && !edit ? 'row-flagged' : ''}">
            <td class="num">${r.columnNo}</td>
            <td class="raw" title="${escapeHtml(r.rawName)}">${escapeHtml(r.rawName) || '<em>(empty)</em>'}</td>
            <td class="name-cell">
                <div class="name-field">
                    <input type="text" class="name-input" data-col="${r.columnNo}" value="${escapeHtml(currentName(r))}"
                           spellcheck="false" autocomplete="off">
                    <span class="char-count" id="count-${r.columnNo}"></span>
                </div>
                <div class="row-error" id="err-${r.columnNo}"></div>
                <div class="row-note" id="note-${r.columnNo}">${escapeHtml(rowMessages[r.columnNo] || (edit ? '' : r.notes))}</div>
            </td>
            <td id="method-${r.columnNo}">${methodCellHtml(r)}</td>
            <td><button type="button" class="btn btn-ghost btn-small ai-btn" ${canAi ? '' : 'disabled title="Empty header - type a name"'}
                        onclick="aiRenameRows([${r.columnNo}])">✨ AI</button></td>
        </tr>`;
    }).join('') +
        (rows.length > shown.length ? `<tr><td colspan="5" class="more">... ${rows.length - shown.length} more rows - use the filter to find them</td></tr>` : '') +
        (rows.length === 0 ? '<tr><td colspan="5" class="more">No rows match.</td></tr>' : '');

    document.querySelectorAll('#n-table .name-input').forEach(input => {
        input.addEventListener('input', () => onNameEdited(input, false));
        input.addEventListener('change', () => onNameEdited(input, true));
    });
    document.querySelectorAll('#n-table .ai-btn').forEach(b => { if (aiBusy) b.disabled = true; });
    refreshValidation();
}

// Method badge + "Review" / "unsaved" tags for one row, reflecting unsaved edits.
function methodCellHtml(r) {
    const edit = edits[r.columnNo];
    const method = edit ? (edit.source === 'ai' ? 'AI (Gemini)' : 'Edited by user') : r.method;
    return `<span class="method method-${methodClass(method)}">${escapeHtml(method)}</span>` +
        (r.needsReview && !edit ? '<span class="flag">Review</span>' : '') +
        (edit ? '<span class="unsaved">unsaved</span>' : '');
}

function onNameEdited(input, normalize) {
    const no = Number(input.dataset.col);
    const row = namingResult.rows.find(r => r.columnNo === no);
    if (normalize) input.value = normalizeName(input.value);
    if (input.value === row.cleanedName) delete edits[no];
    else edits[no] = { name: input.value, source: 'user' };
    delete serverErrors[no];
    delete rowMessages[no];
    const note = document.getElementById('note-' + no);
    if (note) note.textContent = edits[no] ? '' : row.notes;
    const methodCell = document.getElementById('method-' + no);
    if (methodCell) methodCell.innerHTML = methodCellHtml(row);
    document.getElementById('row-' + no).classList.toggle('row-flagged', row.needsReview && !edits[no]);
    refreshValidation();
}

// Updates error texts, row colours, the summary bar and the buttons - without re-rendering the
// table, so the input being typed in keeps its focus.
function refreshValidation() {
    currentErrors = validateNames();
    namingResult.rows.forEach(r => {
        const tr = document.getElementById('row-' + r.columnNo);
        if (!tr) return;
        const error = currentErrors[r.columnNo] || serverErrors[r.columnNo] || '';
        tr.classList.toggle('row-error-state', !!error);
        document.getElementById('err-' + r.columnNo).textContent = error;
        // Live length counter: green while it fits the database limit, red when it is too long.
        const length = currentName(r).length;
        const count = document.getElementById('count-' + r.columnNo);
        count.textContent = `${length}/${namingResult.maxColumnNameLength}`;
        count.classList.toggle('too-long', length > namingResult.maxColumnNameLength);
    });

    const errorCount = new Set([...Object.keys(currentErrors), ...Object.keys(serverErrors)]).size;
    const dirty = Object.keys(edits).length;
    const flagged = namingResult.rows.filter(r => r.needsReview && !edits[r.columnNo]).length;
    const bar = document.getElementById('n-validation');
    if (errorCount) {
        bar.className = 'validation-bar bad';
        bar.innerHTML = `<strong>${errorCount} name(s) need fixing</strong> before you can continue. <a href="#" onclick="showOnlyProblems(); return false;">Show them</a>`;
    } else if (dirty) {
        bar.className = 'validation-bar warn';
        bar.innerHTML = `All names are valid. <strong>${dirty} unsaved change(s)</strong> - click Save changes (or Continue).`;
    } else {
        bar.className = 'validation-bar good';
        bar.innerHTML = flagged
            ? `All names are valid and saved. ${flagged} name(s) are still flagged - check them if you like.`
            : 'All names are valid and saved. Ready for step 2.';
    }

    document.getElementById('n-save').disabled = !dirty || errorCount > 0 || aiBusy;
    document.getElementById('n-continue').disabled = errorCount > 0 || aiBusy;
    const aiTargets = aiTargetColumns();
    document.getElementById('n-ai-all').disabled = aiBusy || aiTargets.length === 0;
    document.getElementById('n-ai-all-label').textContent = aiTargets.length
        ? `Rename ${aiTargets.length} flagged / invalid with AI` : 'Nothing to rename with AI';
}

function showOnlyProblems() {
    document.getElementById('n-flagged-only').checked = false;
    document.getElementById('n-errors-only').checked = true;
    renderMappingTable();
}

// Flagged rows the user has not changed yet, plus every row that currently has a problem.
function aiTargetColumns() {
    return namingResult.rows
        .filter(r => normalizeName(r.rawName) !== '')
        .filter(r => (r.needsReview && !edits[r.columnNo]) || currentErrors[r.columnNo] || serverErrors[r.columnNo])
        .map(r => r.columnNo);
}

function aiRenameAll() {
    const targets = aiTargetColumns();
    if (targets.length) aiRenameRows(targets);
}

async function aiRenameRows(columnNos) {
    if (aiBusy) return;
    const currentNames = {};
    namingResult.rows.forEach(r => { currentNames[r.columnNo] = currentName(r); });

    aiBusy = true;
    document.getElementById('n-ai-spinner').classList.remove('hidden');
    document.querySelectorAll('#n-table .ai-btn').forEach(b => b.disabled = true);
    refreshValidation();
    document.getElementById('n-log-card').classList.remove('hidden');
    localLog('n-log', 'INFO', `Sending ${columnNos.length} column(s) to AI...`);
    try {
        const { ok, data } = await postJson(`/api/naming/${namingResult.sessionId}/ai-rename`, { columnNos, currentNames });
        if (!ok) throw new Error(data.message || 'Could not start.');
        const snap = await followTask(data.taskId, 'n-log');
        if (snap.status === 'SUCCEEDED') {
            snap.result.suggestions.forEach(s => {
                const row = namingResult.rows.find(r => r.columnNo === s.columnNo);
                if (s.name) {
                    if (s.name === row.cleanedName) delete edits[s.columnNo];
                    else edits[s.columnNo] = { name: s.name, source: 'ai' };
                    rowMessages[s.columnNo] = 'Suggested by AI - not saved yet';
                    delete serverErrors[s.columnNo];
                } else {
                    rowMessages[s.columnNo] = 'AI could not rename it: ' + s.reason;
                }
            });
        }
    } catch (e) {
        localLog('n-log', 'ERROR', 'The app did not respond: ' + e.message);
    } finally {
        aiBusy = false;
        document.getElementById('n-ai-spinner').classList.add('hidden');
        renderMappingTable();
    }
}

/** @return true when everything was saved. */
async function saveNames() {
    const changes = Object.entries(edits).map(([no, e]) => ({ columnNo: Number(no), name: normalizeName(e.name), source: e.source }));
    if (!changes.length) return true;
    const msg = document.getElementById('n-continue-msg');
    msg.className = 'inline-result';
    msg.textContent = 'Saving...';
    try {
        const { ok, data } = await postJson(`/api/naming/${namingResult.sessionId}/save`, { changes });
        if (!ok) {
            serverErrors = {};
            Object.entries(data.errors || {}).forEach(([no, text]) => { serverErrors[no] = text; });
            msg.className = 'inline-result bad';
            msg.textContent = data.message || 'Could not save.';
            refreshValidation();
            return false;
        }
        namingResult = data;
        edits = {};
        rowMessages = {};
        serverErrors = {};
        renderNamingStats();
        document.getElementById('n-mapping-path').value = data.mappingFilePath;
        renderMappingTable();
        msg.className = 'inline-result good';
        msg.textContent = '✓ Saved to the mapping file.';
        return true;
    } catch (e) {
        msg.className = 'inline-result bad';
        msg.textContent = 'The app did not respond.';
        return false;
    }
}

function methodClass(label) {
    if (label.startsWith('AI')) return 'ai';
    if (label.startsWith('Edited')) return 'edited';
    if (label.startsWith('NLP')) return 'nlp';
    if (label.startsWith('Vowel')) return 'vowel';
    if (label.startsWith('Hash')) return 'hash';
    return 'none';
}

async function continueToImport() {
    if (namingResult) {
        if (Object.keys(validateNames()).length) {
            showOnlyProblems();
            return;
        }
        if (!(await saveNames())) return;
        const flagged = namingResult.rows.filter(r => r.needsReview).length;
        if (flagged && !confirm(`${flagged} name(s) are still flagged for review (they are valid, but were not named by AI). Continue anyway?`)) {
            return;
        }
        document.getElementById('i-data').value = namingResult.dataCsvPath;
        document.getElementById('i-mapping').value = namingResult.mappingFilePath;
        const dbId = document.getElementById('n-db').value;
        if (dbId && document.getElementById('i-db').value !== dbId) {
            document.getElementById('i-db').value = dbId;
            onImportDbChange();
        }
        if (!document.getElementById('i-table').value) {
            const file = namingResult.dataCsvPath.split('/').pop().replace(/\.csv$/i, '');
            document.getElementById('i-table').value = file.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
        }
        saveForm();
    }
    showStep(2);
}

// ============================================================================
// Step 2: connection + import
// ============================================================================
function connectionPayload() {
    return {
        driverClassName: document.getElementById('i-driver').value,
        url: document.getElementById('i-url').value.trim(),
        username: document.getElementById('i-user').value.trim(),
        password: document.getElementById('i-pass').value,
        tableName: document.getElementById('i-table').value.trim()
    };
}

async function testConnection() {
    const out = document.getElementById('i-test-result');
    const payload = connectionPayload();
    if (!payload.url || !payload.username) {
        out.className = 'inline-result bad';
        out.textContent = 'Enter the URL and username first.';
        return;
    }
    out.className = 'inline-result';
    out.textContent = 'Connecting...';
    setBusy('i-test', 'i-test-spinner', true);
    try {
        const { ok, data } = await postJson('/api/database/test-connection', payload);
        out.className = 'inline-result ' + (ok ? 'good' : 'bad');
        out.textContent = (ok ? '✓ ' : '✗ ') + (data.message || (ok ? 'Connected.' : 'Connection failed.'));
    } catch (e) {
        out.className = 'inline-result bad';
        out.textContent = '✗ The app did not respond.';
    } finally {
        setBusy('i-test', 'i-test-spinner', false);
        saveForm();
    }
}

async function startImport() {
    const payload = {
        ...connectionPayload(),
        dataCsvPath: formatPath(document.getElementById('i-data').value),
        mappingCsvPath: formatPath(document.getElementById('i-mapping').value)
    };
    document.getElementById('i-log').innerHTML = '';
    document.getElementById('i-result').classList.add('hidden');
    document.getElementById('i-log-card').classList.remove('hidden');
    setProgress(0, 0);

    const missing = [];
    if (!payload.url) missing.push('JDBC URL');
    if (!payload.username) missing.push('username');
    if (!payload.dataCsvPath) missing.push('data CSV file');
    if (!payload.mappingCsvPath) missing.push('mapping file');
    if (!payload.tableName) missing.push('table name');
    if (missing.length) return localLog('i-log', 'ERROR', 'Please fill in: ' + missing.join(', ') + '.');

    saveForm();
    setBusy('i-start', 'i-spinner', true);
    localLog('i-log', 'INFO', `Checking whether '${payload.tableName}' already exists...`);
    try {
        const { ok, data } = await postJson('/api/database/check-table', payload);
        if (!ok) {
            localLog('i-log', 'ERROR', data.message || 'Could not reach the database.');
            setBusy('i-start', 'i-spinner', false);
            return;
        }
        if (data.exists || data.partTablesExist) {
            pendingImport = payload;
            document.getElementById('existsName').textContent = data.tableName;
            openModal('existsModal');
            return;
        }
    } catch (e) {
        localLog('i-log', 'ERROR', 'The app did not respond.');
        setBusy('i-start', 'i-spinner', false);
        return;
    }
    await runImport(payload);
}

function replaceAndImport() {
    closeModal('existsModal');
    localLog('i-log', 'WARN', `Replacing the existing '${pendingImport.tableName}'.`);
    const payload = pendingImport;
    pendingImport = null;
    runImport(payload);
}

function renameInstead() {
    closeModal('existsModal');
    pendingImport = null;
    setBusy('i-start', 'i-spinner', false);
    const input = document.getElementById('i-table');
    input.select();
    input.focus();
    localLog('i-log', 'INFO', 'Type another table name and click Import again.');
}

async function runImport(payload) {
    setBusy('i-start', 'i-spinner', true);
    try {
        const { ok, data } = await postJson('/api/import/start', payload);
        if (!ok) throw new Error(data.message || 'Could not start.');
        const snap = await followTask(data.taskId, 'i-log', setProgress);
        if (snap.status === 'SUCCEEDED' && snap.result) {
            showImportResult(snap.result);
        }
    } catch (e) {
        localLog('i-log', 'ERROR', 'The app did not respond: ' + e.message);
    } finally {
        setBusy('i-start', 'i-spinner', false);
    }
}

function setProgress(done, total) {
    const pct = total > 0 ? Math.min(100, Math.round(done * 100 / total)) : 0;
    document.getElementById('i-progress').style.width = pct + '%';
    document.getElementById('i-progress-label').textContent =
        total > 0 ? `${done.toLocaleString()} of ${total.toLocaleString()} rows (${pct}%)` : '';
}

function showImportResult(r) {
    const box = document.getElementById('i-result');
    box.innerHTML = `
        <h2>Import finished</h2>
        <p><strong>${r.rowsImported.toLocaleString()}</strong> rows and <strong>${r.columns}</strong> columns are in
           <code>${escapeHtml(r.tableName)}</code> (${escapeHtml(r.database)}), in ${r.seconds.toFixed(1)} seconds.</p>
        ${r.splitIntoParts ? '<p class="muted">The file was wide, so it was loaded through part tables first - see the log above for whether the result is a table or a view.</p>' : ''}
        <p class="muted">Try it: <code>SELECT * FROM ${escapeHtml(r.tableName)}</code></p>`;
    box.classList.remove('hidden');
    box.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

// ============================================================================
// Modals + version help
// ============================================================================
function openModal(id) {
    document.getElementById(id).classList.remove('hidden');
    document.body.classList.add('modal-open');
}

function closeModal(id) {
    document.getElementById(id).classList.add('hidden');
    if (document.querySelectorAll('.modal:not(.hidden)').length === 0) document.body.classList.remove('modal-open');
}

function buildHelpTabs() {
    const tabs = document.getElementById('helpTabs');
    tabs.innerHTML = catalog.map(db =>
        `<button type="button" role="tab" class="tab" data-db="${db.id}" onclick="showHelpTab('${db.id}')">${escapeHtml(db.label)}</button>`).join('');
}

function openVersionHelp() {
    const selected = document.getElementById('n-db').value || (catalog[0] && catalog[0].id);
    showHelpTab(selected);
    openModal('helpModal');
}

function showHelpTab(dbId) {
    document.querySelectorAll('#helpTabs .tab').forEach(t => t.classList.toggle('active', t.dataset.db === dbId));
    const help = VERSION_HELP[dbId];
    const db = findDb(dbId);
    if (!help || !db) return;
    document.getElementById('helpBody').innerHTML = `
        <h3>Run this query</h3>
        ${help.queries.map((q, i) => `
            <div class="query">
                <div class="query-code"><code id="q-${dbId}-${i}">${escapeHtml(q.sql)}</code>
                    <button type="button" class="btn btn-ghost btn-small" onclick="copyCode('q-${dbId}-${i}', this)">Copy</button></div>
                <div class="query-what">${escapeHtml(q.what)}</div>
            </div>`).join('')}
        <h3>Or look it up in your tool</h3>
        <ul class="tool-list">${help.tools.map(t => `<li>${escapeHtml(t)}</li>`).join('')}</ul>
        <h3>Which option to choose</h3>
        <table class="data-table compact">
            <thead><tr><th>If the version is</th><th>Choose</th></tr></thead>
            <tbody>${help.pick.map(p => `<tr><td>${escapeHtml(p[0])}</td><td><strong>${escapeHtml(p[1])}</strong></td></tr>`).join('')}</tbody>
        </table>
        <p class="help-note">${escapeHtml(help.note)}</p>`;
}

function copyCode(id, button) {
    const text = document.getElementById(id).textContent;
    const done = () => { const old = button.textContent; button.textContent = 'Copied'; setTimeout(() => button.textContent = old, 1500); };
    if (navigator.clipboard) navigator.clipboard.writeText(text).then(done).catch(done);
}
