// Browser smoke test using Chrome's built-in DevTools protocol and Node APIs; no new test framework.
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, writeFileSync, openSync, closeSync } from 'node:fs'
import { resolve, join } from 'node:path'
import { createServer } from 'node:net'
import assert from 'node:assert/strict'

const root = resolve(import.meta.dirname, '..')
const output = join(root, 'backend/target/browser-test-' + Date.now())
mkdirSync(output, { recursive: true })
const children = []
const logs = []
let socket

async function port() {
  const server = createServer()
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const number = server.address().port
  await new Promise(resolve => server.close(resolve))
  return number
}

function start(command, args, name, env = process.env) {
  const log = openSync(join(output, name + '.log'), 'a')
  logs.push(log)
  const child = spawn(command, args, { cwd: root, env, windowsHide: true, stdio: ['ignore', log, log] })
  children.push(child)
  child.on('error', error => { console.error(`${name}: ${error.message}`) })
  return child
}

async function until(check, message, timeout = 45000) {
  const end = Date.now() + timeout
  while (Date.now() < end) {
    try { const result = await check(); if (result) return result } catch { /* Wait for startup/render. */ }
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  throw new Error('Timed out: ' + message)
}

const pending = new Map()
let messageId = 0
function cdp(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++messageId
    const timeout = setTimeout(() => { pending.delete(id); reject(new Error('CDP timeout: ' + method)) }, 15000)
    pending.set(id, { resolve, reject, timeout })
    socket.send(JSON.stringify({ id, method, params }))
  })
}

async function evaluate(expression) {
  const response = await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (response.exceptionDetails) throw new Error(response.exceptionDetails.text + ': ' + response.result?.description)
  return response.result.value
}

async function click(text) {
  await evaluate(`(() => {
    const button = [...document.querySelectorAll('button')].find(b => b.textContent.trim() === ${JSON.stringify(text)});
    if (!button || button.disabled) throw new Error('Button missing or disabled: ' + ${JSON.stringify(text)});
    button.click();
  })()`)
}

async function fill(label, value, scope = '') {
  await evaluate(`(() => {
    const root = ${scope ? `document.querySelector(${JSON.stringify(scope)})` : 'document'};
    const label = [...root.querySelectorAll('label')].find(l => l.textContent.trim().startsWith(${JSON.stringify(label)}));
    const input = label?.querySelector('input, select');
    if (!input) throw new Error('Input missing: ' + ${JSON.stringify(label)});
    const prototype = input instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(prototype, 'value').set.call(input, ${JSON.stringify(value)});
    input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }));
  })()`)
}

const hasText = text => until(() => evaluate(`document.body.textContent.includes(${JSON.stringify(text)})`), text)
const enabled = text => until(() => evaluate(`[...document.querySelectorAll('button')].some(b => b.textContent.trim() === ${JSON.stringify(text)} && !b.disabled)`), 'enabled ' + text)
async function screenshot(name) {
  const result = await cdp('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
  writeFileSync(join(output, name + '.png'), Buffer.from(result.data, 'base64'))
}

async function browserApi(path, method = 'GET', body) {
  return evaluate(`(async () => {
    const headers = { 'Content-Type': 'application/json' };
    if (${JSON.stringify(method)} !== 'GET') {
      const token = await fetch('/api/auth/csrf').then(r => r.json()); headers[token.headerName] = token.token;
    }
    const response = await fetch(${JSON.stringify(path)}, { method: ${JSON.stringify(method)}, headers,
      body: ${body === undefined ? 'undefined' : JSON.stringify(JSON.stringify(body))} });
    return { status: response.status, body: response.status === 204 ? null : await response.json() };
  })()`)
}

try {
  assert.equal(process.env.RUN_DB_TESTS, 'true', 'Run through test-backend.ps1 -Browser against the isolated test database.')
  const backendPort = await port()
  const frontendPort = await port()
  const backend = `http://127.0.0.1:${backendPort}`
  const frontend = `http://127.0.0.1:${frontendPort}`
  // Use JAVA_HOME directly: Oracle's PATH launcher forks a second process on Windows.
  const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java'
  start(java, ['-jar', join(root, 'backend/target/waste-tracking-api-0.0.1-SNAPSHOT.jar'),
    `--server.port=${backendPort}`, '--debug=false', '--logging.level.root=INFO'], 'backend')
  await until(async () => (await fetch(backend + '/api/auth/csrf')).ok, 'backend startup', 60000)
  start(process.execPath, [join(root, 'frontend/node_modules/vite/bin/vite.js'), 'preview', join(root, 'frontend'), '--host', '127.0.0.1',
    '--port', String(frontendPort), '--strictPort'], 'frontend', { ...process.env, API_PROXY_TARGET: backend })
  await until(async () => (await fetch(frontend)).ok, 'frontend startup')
  const browser = process.env.CHROME_PATH ?? ['C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(existsSync)
  assert.ok(browser, 'Set CHROME_PATH to a Chrome or Edge executable.')
  const profile = join(output, 'chrome-profile')
  start(browser, ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    '--remote-debugging-port=0', '--remote-debugging-address=127.0.0.1', `--user-data-dir=${profile}`, 'about:blank'], 'browser')
  const debugPort = await until(() => {
    const active = join(profile, 'DevToolsActivePort')
    return existsSync(active) && Number(readFileSync(active, 'utf8').split('\n')[0])
  }, 'browser startup')
  const targets = await (await fetch(`http://127.0.0.1:${debugPort}/json/list`)).json()
  socket = new WebSocket(targets.find(target => target.type === 'page').webSocketDebuggerUrl)
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject })
  socket.onmessage = event => {
    const response = JSON.parse(event.data)
    const item = pending.get(response.id)
    if (!item) return
    clearTimeout(item.timeout); pending.delete(response.id)
    if (response.error) item.reject(new Error(response.error.message)); else item.resolve(response.result)
  }
  await cdp('Page.enable')
  await cdp('Runtime.enable')
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false })
  await cdp('Page.navigate', { url: frontend })
  await hasText('Sign in')
  await fill('Username', process.env.INITIAL_STAFF_USERNAME)
  await fill('Password', process.env.INITIAL_STAFF_PASSWORD)
  await click('Sign in')
  await hasText('Submitted records are read-only for Staff.')
  await click('New Crate / Pallet')
  await hasText('Material percentages')
  await until(() => evaluate("document.querySelector('select')?.options.length === 6"), 'reference data')
  assert.equal(await evaluate("[...document.querySelectorAll('label')].some(l => l.textContent.includes('E-waste'))"), false)
  const apple = await evaluate("[...document.querySelector('select').options].find(o => o.textContent.startsWith('Apple Crate')).value")
  await fill('Container type', apple)
  await fill('Gross weight', '160.00')
  await fill('Timber', '99.99')
  assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b => b.textContent === 'Review record').disabled"), true)
  await fill('Timber', '100.00')
  await enabled('Review record')
  await screenshot('desktop-entry')
  await cdp('Emulation.setDeviceMetricsOverride', { width: 768, height: 1024, deviceScaleFactor: 1, mobile: false })
  assert.equal(await evaluate('document.documentElement.scrollWidth <= window.innerWidth'), true)
  await screenshot('tablet-entry')
  await click('Review record')
  await hasText('Net material weight')
  assert.ok(await evaluate("document.querySelector('.net-weight dd').textContent.includes('100.00 kg')"))
  assert.ok(await evaluate("document.body.textContent.includes('60.00 kg')"))
  await click('Back to edit')
  assert.equal(await evaluate("[...document.querySelectorAll('label')].find(l => l.textContent.startsWith('Gross weight')).querySelector('input').value"), '160.00')
  await click('Review record')
  await hasText('Net material weight')

  // Lose the first successful POST response, then retry through the actual UI.
  await evaluate(`window.__submissionIds = []; window.__lostResponse = false;
    window.__realFetch = window.fetch;
    window.__submissionGate = new Promise(resolve => { window.__releaseSubmission = resolve; });
    window.__submissionStarted = false;
    window.fetch = async (...args) => {
      const [url, init] = args;
      const submission = url === '/api/crate-pallet-records' && init?.method === 'POST';
      if (submission) {
        window.__submissionIds.push(JSON.parse(init.body).submissionId);
        window.__submissionStarted = true;
        await window.__submissionGate;
      }
      const response = await window.__realFetch(...args);
      if (submission && response.ok && !window.__lostResponse) {
        window.__lostResponse = true; throw new TypeError('Simulated lost response');
      }
      return response;
    };`)
  await click('Confirm and submit')
  await until(() => evaluate('window.__submissionStarted'), 'submission paused before response')
  for (const label of ['Records', 'New Crate / Pallet', 'Sign out']) {
    assert.equal(await evaluate(`[...document.querySelectorAll('button')].find(b => b.textContent.trim() === ${JSON.stringify(label)}).disabled`), true)
  }
  assert.equal(await evaluate(`(() => {
    const event = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(event); return event.defaultPrevented;
  })()`), true)
  await evaluate('window.__releaseSubmission()')
  await enabled('Retry submission')
  await click('Check Records')
  await hasText('Submitted records are read-only for Staff.')
  await click('Resume submission')
  await enabled('Retry submission')
  await click('Retry submission')
  await hasText('saved successfully.')
  const ids = await evaluate('window.__submissionIds')
  assert.equal(ids.length, 2); assert.equal(ids[0], ids[1])
  assert.equal(await evaluate("[...document.querySelectorAll('button')].some(b => b.textContent === 'Correct record')"), false)
  const savedId = await evaluate("document.querySelector('.detail-heading h2').textContent.split('#')[1]")
  const duplicateCount = await evaluate(`fetch('/api/crate-pallet-records').then(r => r.json()).then(rows => rows.filter(r => r.submissionId === ${JSON.stringify(ids[0])}).length)`)
  assert.equal(duplicateCount, 1)
  await screenshot('tablet-saved-record')

  // A definitive ID/data conflict must not become an endless retry loop.
  await click('New Crate / Pallet')
  await until(() => evaluate("document.querySelector('select')?.options.length === 6"), 'conflict form references')
  await fill('Container type', apple)
  await fill('Gross weight', '161.00')
  await fill('Timber', '100.00')
  await click('Review record')
  await hasText('Net material weight')
  await evaluate(`window.fetch = async (url, init) => {
    if (url === '/api/crate-pallet-records' && init?.method === 'POST') {
      const body = JSON.parse(init.body);
      body.submissionId = ${JSON.stringify(ids[0])};
      init = { ...init, body: JSON.stringify(body) };
    }
    return window.__realFetch(url, init);
  };`)
  await click('Confirm and submit')
  await hasText('Conflict:')
  assert.equal(await evaluate("[...document.querySelectorAll('button')].some(b => b.textContent.trim() === 'Retry submission')"), false)
  assert.equal(await evaluate("document.body.textContent.includes('A submission is unresolved.')"), false)
  await click('Back to Records')
  await evaluate('window.fetch = window.__realFetch')
  await click('Sign out')
  await hasText('Sign in')
  await fill('Username', process.env.INITIAL_ADMIN_USERNAME)
  await fill('Password', process.env.INITIAL_ADMIN_PASSWORD)
  await click('Sign in')
  await hasText('View submitted loads, correct entries')
  await enabled('#' + savedId)
  await click('#' + savedId)
  await hasText('Correct record')
  await click('Correct record')
  await hasText('Material percentages')
  await fill('Gross weight', '170.00')
  await click('Review record')
  await hasText('Net material weight')
  await click('Confirm correction')
  await hasText('saved successfully.')
  assert.ok(await evaluate("document.querySelector('.net-weight dd').textContent.includes('110.00 kg')"))
  // Lose a successful delete response. Its retry receives 404 and must clear stale details/list rows.
  await evaluate(`window.__lostDeleteResponse = false;
    window.fetch = async (...args) => {
      const response = await window.__realFetch(...args);
      if (args[1]?.method === 'DELETE' && response.ok && !window.__lostDeleteResponse) {
        window.__lostDeleteResponse = true; throw new TypeError('Simulated lost delete response');
      }
      return response;
    };`)
  await click('Delete record')
  await click('Confirm deletion')
  await hasText('Simulated lost delete response')
  await enabled('Confirm deletion')
  await click('Confirm deletion')
  await hasText('Record #' + savedId + ' is already deleted or no longer available.')
  assert.equal(await evaluate(`[...document.querySelectorAll('tbody button')].some(b => b.textContent === '#${savedId}')`), false)
  assert.equal(await evaluate("document.querySelector('.detail-panel') !== null"), false)
  await evaluate('window.fetch = window.__realFetch')

  // A record removed by another request must also disappear from selected details on refresh.
  const refreshId = await evaluate(`(async () => {
    const token = await fetch('/api/auth/csrf').then(r => r.json());
    const user = await fetch('/api/auth/me').then(r => r.json());
    const materials = await fetch('/api/material-categories').then(r => r.json());
    const response = await fetch('/api/crate-pallet-records', {
      method: 'POST', headers: { 'Content-Type': 'application/json', [token.headerName]: token.token },
      body: JSON.stringify({ submissionId: crypto.randomUUID(), recordDate: user.operationalDate,
        containerTypeId: Number(${JSON.stringify(apple)}), grossWeight: '160.00',
        materials: [{ materialCategoryId: materials.find(m => m.name === 'Timber').id, percentage: '100.00' }] })
    });
    if (!response.ok) throw new Error('Refresh fixture creation failed');
    return (await response.json()).id;
  })()`)
  await enabled('Apply dates / refresh')
  await click('Apply dates / refresh')
  const remainingId = '#' + refreshId
  await enabled(remainingId)
  await click(remainingId)
  await hasText('Correct record')
  await evaluate(`(async () => {
    const token = await fetch('/api/auth/csrf').then(r => r.json());
    const response = await fetch('/api/crate-pallet-records/${remainingId.slice(1)}', {
      method: 'DELETE', headers: { [token.headerName]: token.token }
    });
    if (!response.ok) throw new Error('External deletion failed');
  })()`)
  await click('Apply dates / refresh')
  await until(() => evaluate("document.querySelector('.detail-panel') === null"), 'selected deleted record cleared after refresh')

  // Salvage has its own material contexts and one crew for the complete trip.
  await click('Sign out')
  await hasText('Sign in')
  await fill('Username', process.env.INITIAL_STAFF_USERNAME)
  await fill('Password', process.env.INITIAL_STAFF_PASSWORD)
  await click('Sign in')
  await hasText('Submitted records are read-only for Staff.')
  const upScope = 'fieldset[data-direction="UP"]'
  const downScope = 'fieldset[data-direction="DOWN"]'
  await click('New Salvage')
  await until(() => evaluate(`document.querySelector('${upScope} .material-grid')?.children.length === 9 && document.querySelector('${downScope} .material-grid')?.children.length === 14`), 'direction-specific material sets')
  assert.equal(await evaluate("[...document.querySelectorAll('form label')].filter(l => l.textContent.startsWith('Crew')).length"), 1)
  assert.equal(await evaluate(`document.querySelector('${upScope}').textContent.includes('Lead Acid Batteries')`), false)
  assert.equal(await evaluate(`document.querySelector('${downScope}').textContent.includes('Mattresses')`), false)
  await fill('Crew', 'Salvage smoke crew')
  await fill('Time Up', '15:30', upScope)
  await fill('Time Down', '09:15', downScope)
  await fill('Kg Up', '10.00', upScope)
  await fill('Kg Down', '20.00', downScope)
  await fill('E-waste', '100.00', upScope)
  await fill('E-waste', '100.00', downScope)
  await screenshot('tablet-salvage-entry')
  await click('Review trip')
  await hasText('Review both directions before confirming.')
  assert.equal(await evaluate("[...document.querySelectorAll('.salvage-review dt')].filter(n => n.textContent === 'Crew').length"), 1)
  await evaluate(`window.__salvagePayloads = []; window.__salvageLost = false;
    window.__salvageGate = new Promise(resolve => { window.__releaseSalvage = resolve; });
    window.fetch = async (...args) => {
      const submission = args[0] === '/api/salvage-records' && args[1]?.method === 'POST';
      if (submission) { window.__salvagePayloads.push(args[1].body); await window.__salvageGate; }
      const response = await window.__realFetch(...args);
      if (submission && response.ok && !window.__salvageLost) { window.__salvageLost = true; throw new TypeError('Simulated lost Salvage response'); }
      return response;
    };`)
  await click('Confirm and submit')
  await until(() => evaluate('window.__salvagePayloads.length === 1'), 'Salvage submission started')
  for (const label of ['Records', 'New Crate / Pallet', 'New Salvage', 'Sign out']) {
    assert.equal(await evaluate(`[...document.querySelectorAll('button')].find(b => b.textContent.trim() === ${JSON.stringify(label)}).disabled`), true)
  }
  await evaluate('window.__releaseSalvage()')
  await enabled('Retry submission')
  await click('Check Records')
  await until(() => evaluate("document.querySelector('select').value === 'SALVAGE'"), 'Salvage records selected')
  await fill('Workflow', 'CRATE_PALLET')
  await enabled('Apply dates / refresh')
  assert.equal(await evaluate("document.querySelector('.detail-panel') !== null"), false)
  await fill('Workflow', 'SALVAGE')
  await click('Resume submission')
  await enabled('Retry submission')
  await click('Retry submission')
  await hasText('saved successfully.')
  const salvagePayloads = await evaluate('window.__salvagePayloads')
  assert.equal(salvagePayloads.length, 2)
  assert.equal(salvagePayloads[0], salvagePayloads[1])
  const salvageSubmission = JSON.parse(salvagePayloads[0]).submissionId
  let trips = await browserApi('/api/salvage-records')
  assert.equal(trips.body.filter(r => r.submissionId === salvageSubmission).length, 1)
  const firstSalvageId = trips.body.find(r => r.submissionId === salvageSubmission).id
  assert.equal(await evaluate("[...document.querySelectorAll('button')].some(b => b.textContent === 'Correct record')"), false)
  await screenshot('tablet-salvage-saved')
  await evaluate('window.fetch = window.__realFetch')

  // Exercise the remaining zero-weight combinations through the actual form.
  for (const [up, down] of [['0.00', '20.00'], ['10.00', '0.00'], ['0.00', '0.00']]) {
    await click('New Salvage')
    await until(() => evaluate(`document.querySelector('${upScope} .material-grid')?.children.length === 9`), 'new Salvage form')
    await fill('Crew', `Zero combination ${up}/${down}`)
    await fill('Kg Up', up, upScope)
    await fill('Kg Down', down, downScope)
    for (const [weight, scope] of [[up, upScope], [down, downScope]]) {
      if (weight === '0.00') assert.equal(await evaluate(`document.querySelector('${scope} .material-grid') === null`), true)
      else await fill('Timber', '100.00', scope)
    }
    // Even the both-zero case requires both times.
    await click('Review trip')
    assert.equal(await evaluate("document.querySelector('form').checkValidity()"), false)
    await fill('Time Up', '15:30', upScope)
    await fill('Time Down', '09:15', downScope)
    await click('Review trip')
    await hasText('Review both directions before confirming.')
    await click('Confirm and submit')
    await hasText('saved successfully.')
    const id = await evaluate("document.querySelector('.detail-heading h2').textContent.split('#')[1]")
    const saved = (await browserApi('/api/salvage-records/' + id)).body
    assert.equal(saved.up.kg, up); assert.equal(saved.down.kg, down)
    assert.equal(saved.up.materials.length, up === '0.00' ? 0 : 1)
    assert.equal(saved.down.materials.length, down === '0.00' ? 0 : 1)
  }
  await click('Sign out')
  await hasText('Sign in')
  await fill('Username', process.env.INITIAL_ADMIN_USERNAME)
  await fill('Password', process.env.INITIAL_ADMIN_PASSWORD)
  await click('Sign in')
  await hasText('View submitted loads, correct entries')
  await fill('Workflow', 'SALVAGE')
  await enabled('#' + firstSalvageId)
  await click('#' + firstSalvageId)
  await hasText('Correct record')
  await click('Correct record')
  await until(() => evaluate(`document.querySelector('${upScope} .material-grid')?.children.length === 9`), 'Admin Salvage form')
  await fill('Kg Up', '0.00', upScope)
  await click('Review trip')
  await hasText('Review both directions before confirming.')
  await click('Confirm correction')
  await hasText('saved successfully.')
  let updated = (await browserApi('/api/salvage-records/' + firstSalvageId)).body
  assert.equal(updated.up.materials.length, 0)
  assert.equal(updated.down.materials.length, 1)
  await click('Correct record')
  await until(() => evaluate(`document.querySelector('${upScope} .material-grid') === null && document.querySelector('${upScope} input') !== null`), 'zero-weight Admin form')
  await fill('Kg Up', '10.00', upScope)
  assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b => b.textContent === 'Review trip').disabled"), true)
  await fill('Timber', '100.00', upScope)
  await click('Review trip')
  await hasText('Review both directions before confirming.')
  await click('Confirm correction')
  await hasText('saved successfully.')
  updated = (await browserApi('/api/salvage-records/' + firstSalvageId)).body
  assert.equal(updated.up.materials.length, 1)

  // Change the record from another request while an Admin has an older form open.
  await click('Correct record')
  await until(() => evaluate(`document.querySelector('${upScope} .material-grid')?.children.length === 9`), 'stale-correction form')
  const materialInputs = side => side.materials.map(({ materialCategoryId, percentage }) => ({ materialCategoryId, percentage }))
  const changed = await browserApi('/api/salvage-records/' + firstSalvageId, 'PUT', {
    submissionId: updated.submissionId, recordDate: updated.recordDate, crew: 'Other Admin correction', version: updated.version,
    up: { time: updated.up.time, kg: updated.up.kg, materials: materialInputs(updated.up) },
    down: { time: updated.down.time, kg: updated.down.kg, materials: materialInputs(updated.down) },
  })
  assert.equal(changed.status, 200)
  await click('Review trip')
  await hasText('This record has changed.')
  await click('Back to Records')
  await enabled('#' + firstSalvageId)
  await click('#' + firstSalvageId)
  await hasText('Other Admin correction')
  await enabled('Delete record')
  await evaluate(`window.__salvageDeleteLost = false;
    window.fetch = async (...args) => {
      const response = await window.__realFetch(...args);
      if (args[0].startsWith('/api/salvage-records/') && args[1]?.method === 'DELETE' && response.ok && !window.__salvageDeleteLost) {
        window.__salvageDeleteLost = true; throw new TypeError('Simulated lost Salvage delete');
      }
      return response;
    };`)
  await click('Delete record')
  await click('Confirm deletion')
  await hasText('Simulated lost Salvage delete')
  await enabled('Confirm deletion')
  await click('Confirm deletion')
  await hasText('is already deleted or no longer available.')
  assert.equal(await evaluate("document.querySelector('.detail-panel') !== null"), false)
  assert.equal((await browserApi('/api/salvage-records/' + firstSalvageId)).status, 404)
  await evaluate('window.fetch = window.__realFetch')
  // Pickup uses one final positive weight and its own reference context.
  await click('Sign out')
  await hasText('Sign in')
  await fill('Username', process.env.INITIAL_STAFF_USERNAME)
  await fill('Password', process.env.INITIAL_STAFF_PASSWORD)
  await click('Sign in')
  await enabled('New Pickup')
  await click('New Pickup')
  await until(() => evaluate("document.querySelector('.material-grid')?.children.length === 13"), 'Pickup categories loaded')
  const pickupLabels = await evaluate("[...document.querySelectorAll('.material-grid label')].map(l => l.textContent.replace('%', '').trim())")
  assert.equal(pickupLabels.includes('E-waste'), false)
  assert.equal(pickupLabels.includes('Lead Acid Batteries'), false)
  assert.equal(pickupLabels.includes('Whitegoods / Large Appliances'), false)
  assert.equal(await evaluate("[...document.querySelectorAll('form label')].some(l => /Container|Delivery|Pickup type|Crew/.test(l.textContent))"), false)
  await fill('Timber', '100.00')
  for (const weight of ['0', '-1']) {
    await fill('Final net weight', weight)
    assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b => b.textContent === 'Review pickup').disabled"), true)
  }
  await fill('Final net weight', '125.50')
  await click('Review pickup')
  assert.equal(await evaluate("document.querySelector('form').checkValidity()"), false)
  await fill('Pickup location', 'Pickup browser location')
  await fill('Timber', '99.99')
  assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b => b.textContent === 'Review pickup').disabled"), true)
  await fill('Timber', '100.00')
  await screenshot('tablet-pickup-entry')
  await click('Review pickup')
  await hasText('Review the calculated weights before confirming.')
  await hasText('125.50 kg')
  await click('Back to edit')
  await fill('Pickup location', 'Reviewed Pickup location')
  await click('Review pickup')
  await hasText('Reviewed Pickup location')
  await evaluate(`window.__pickupPayloads = []; window.__pickupLost = false;
    window.__pickupGate = new Promise(resolve => { window.__releasePickup = resolve; });
    window.fetch = async (...args) => {
      const submission = args[0] === '/api/pickup-records' && args[1]?.method === 'POST';
      if (submission) { window.__pickupPayloads.push(args[1].body); await window.__pickupGate; }
      const response = await window.__realFetch(...args);
      if (submission && response.ok && !window.__pickupLost) { window.__pickupLost = true; throw new TypeError('Simulated lost Pickup response'); }
      return response;
    };`)
  await click('Confirm and submit')
  await until(() => evaluate('window.__pickupPayloads.length === 1'), 'Pickup submission started')
  for (const label of ['Records', 'New Crate / Pallet', 'New Salvage', 'New Pickup', 'Sign out']) {
    assert.equal(await evaluate(`[...document.querySelectorAll('button')].find(b => b.textContent.trim() === ${JSON.stringify(label)}).disabled`), true)
  }
  await evaluate('window.__releasePickup()')
  await enabled('Retry submission')
  await click('Check Records')
  await until(() => evaluate("document.querySelector('select').value === 'PICKUP'"), 'Pickup Records selected')
  await fill('Workflow', 'SALVAGE')
  await enabled('Apply dates / refresh')
  await click('Resume submission')
  await enabled('Retry submission')
  await click('Retry submission')
  await hasText('saved successfully.')
  const pickupPayloads = await evaluate('window.__pickupPayloads')
  assert.equal(pickupPayloads.length, 2)
  assert.equal(pickupPayloads[0], pickupPayloads[1])
  await evaluate('window.fetch = window.__realFetch')
  const pickupInput = JSON.parse(pickupPayloads[0])
  const pickups = (await browserApi('/api/pickup-records')).body.filter(r => r.submissionId === pickupInput.submissionId)
  assert.equal(pickups.length, 1)
  const pickupId = pickups[0].id
  assert.equal(pickups[0].netWeight, '125.50')
  assert.equal(pickups[0].materials[0].materialWeight, '125.50')
  assert.equal(await evaluate("[...document.querySelectorAll('button')].some(b => b.textContent === 'Correct record')"), false)
  await screenshot('tablet-pickup-saved')
  const conflict = await browserApi('/api/pickup-records', 'POST', { ...pickupInput, pickupLocation: 'Conflicting location' })
  assert.equal(conflict.status, 409)
  const zeroPickup = await browserApi('/api/pickup-records', 'POST', { ...pickupInput, submissionId: crypto.randomUUID(), netWeight: '0.00' })
  assert.equal(zeroPickup.status, 400)
  await fill('To', '2000-01-01')
  await click('Apply dates / refresh')
  await hasText('No records found.')
  assert.equal(await evaluate("document.querySelector('.detail-panel') !== null"), false)
  await fill('To', '')
  await click('Apply dates / refresh')
  await enabled('#' + pickupId)
  await click('Sign out')
  await hasText('Sign in')
  await fill('Username', process.env.INITIAL_ADMIN_USERNAME)
  await fill('Password', process.env.INITIAL_ADMIN_PASSWORD)
  await click('Sign in')
  await enabled('#' + pickupId)
  await click('#' + pickupId)
  await enabled('Correct record')
  await click('Correct record')
  await until(() => evaluate("document.querySelector('.material-grid')?.children.length === 13"), 'Admin Pickup form')
  await fill('Final net weight', '0')
  assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b => b.textContent === 'Review pickup').disabled"), true)
  await fill('Final net weight', '200.00')
  await fill('Pickup location', 'Corrected Pickup location')
  await click('Review pickup')
  await hasText('Review the calculated weights before confirming.')
  await click('Confirm correction')
  await hasText('saved successfully.')
  let pickupUpdated = (await browserApi('/api/pickup-records/' + pickupId)).body
  assert.equal(pickupUpdated.netWeight, '200.00')
  assert.equal(pickupUpdated.materials[0].materialWeight, '200.00')
  await enabled('Correct record')
  await click('Correct record')
  await until(() => evaluate("document.querySelector('.material-grid')?.children.length === 13"), 'stale Pickup form')
  const pickupExternal = await browserApi('/api/pickup-records/' + pickupId, 'PUT', {
    ...pickupInput, version: pickupUpdated.version, netWeight: pickupUpdated.netWeight, pickupLocation: 'Other Pickup Admin',
  })
  assert.equal(pickupExternal.status, 200)
  await click('Review pickup')
  await hasText('This record has changed.')
  await click('Back to Records')
  await enabled('#' + pickupId)
  await click('#' + pickupId)
  await enabled('Delete record')
  await hasText('Other Pickup Admin')
  await evaluate(`window.__pickupDeleteLost = false;
    window.fetch = async (...args) => {
      const response = await window.__realFetch(...args);
      if (args[0].startsWith('/api/pickup-records/') && args[1]?.method === 'DELETE' && response.ok && !window.__pickupDeleteLost) {
        window.__pickupDeleteLost = true; throw new TypeError('Simulated lost Pickup delete');
      }
      return response;
    };`)
  await click('Delete record')
  await click('Confirm deletion')
  await hasText('Simulated lost Pickup delete')
  await enabled('Confirm deletion')
  await click('Confirm deletion')
  await hasText('is already deleted or no longer available.')
  assert.equal(await evaluate("document.querySelector('.detail-panel') !== null"), false)
  assert.equal((await browserApi('/api/pickup-records/' + pickupId)).status, 404)
  await evaluate('window.fetch = window.__realFetch')
  console.log('PASS: Pickup positive-weight and required-location validation, exact percentages, category restrictions, review/edit, retained retry payload/navigation, Staff permissions, date filters, Admin recalculation, stale correction and lost-delete reconciliation.')
  console.log('PASS: Salvage direction-specific categories, single crew, independent review, all four zero/positive combinations, mandatory times at zero, positive/zero correction transitions, stale Admin correction, retry/navigation across workflows, soft deletion and lost-delete reconciliation.')
  console.log('PASS: login/CSRF, validation/review, active-submission navigation/logout guards, retry UUID retained across Records navigation, definitive conflict handling, Admin correction, lost-delete/404 reconciliation, selected-detail refresh, and tablet layout.')
  console.log('Screenshots and logs: ' + output)
} catch (error) {
  console.error(error)
  if (socket?.readyState === WebSocket.OPEN) await screenshot('failure').catch(() => {})
  console.error('Browser test logs: ' + output)
  process.exitCode = 1
} finally {
  if (socket?.readyState === WebSocket.OPEN) {
    await cdp('Browser.close').catch(() => {})
    socket.close()
  }
  for (const child of children.reverse()) {
    if (child.exitCode === null) {
      child.kill()
      await Promise.race([
        new Promise(resolve => child.once('exit', resolve)),
        new Promise(resolve => setTimeout(resolve, 5000)),
      ])
    }
  }
  for (const log of logs) closeSync(log)
}
