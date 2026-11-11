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

async function reopenForAdminActions() {
  if (await evaluate("!!document.querySelector('.detail-heading h2') && ![...document.querySelectorAll('button')].some(b=>b.textContent==='Correct record')")) {
    const id=await evaluate("document.querySelector('.detail-heading h2').textContent.split('#')[1]")
    assert.equal(await evaluate("[...document.querySelectorAll('button')].some(b=>b.textContent==='Delete record')"),false)
    await enabled('#'+id);await click('#'+id)
    await enabled('Correct record');await enabled('Delete record')
  }
}

async function click(text, scope = '') {
  await evaluate(`(() => {
    const root = ${scope ? `document.querySelector(${JSON.stringify(scope)})` : 'document'};
    const button = [...root.querySelectorAll('button')].find(b => b.textContent.trim() === ${JSON.stringify(text)});
    if (!button || button.disabled) throw new Error('Button missing or disabled: ' + ${JSON.stringify(text)});
    button.click();
  })()`)
}

// Staff workflow access now starts from Operations cards, never a persistent shortcut row.
async function staffWorkflow(name) {
  await click('Operations')
  await until(()=>evaluate("document.querySelectorAll('.staff-operations .workflow-card').length===9"),'Staff workflow cards')
  const ids={'Crate / Pallet':'CRATE_PALLET',Salvage:'SALVAGE',Pickup:'PICKUP','Electrical Crate / Pallet':'ELECTRICAL_CRATE_PALLET',Collectables:'COLLECTABLES',Whitegoods:'WHITEGOODS','Electrical Testing':'ELECTRICAL_TESTING','E-waste Pickup':'EWASTE_PICKUP',Scrap:'SCRAP'}
  await click(name==='Electrical Testing'?'Open':'New Entry',`.staff-operations [data-workflow="${ids[name]}"]`)
}
async function adminWorkflow(name) {
  await click('Workflows')
  const ids={'Crate / Pallet':'CRATE_PALLET',Salvage:'SALVAGE',Pickup:'PICKUP','Electrical Crate / Pallet':'ELECTRICAL_CRATE_PALLET',Collectables:'COLLECTABLES',Whitegoods:'WHITEGOODS','Electrical Testing':'ELECTRICAL_TESTING','E-waste Pickup':'EWASTE_PICKUP',Scrap:'SCRAP'}
  await click(name==='Electrical Testing'?'Open':'New Entry',`.workflow-card[data-workflow="${ids[name]}"]`)
}
async function openSettings(label) {
  await click('Settings')
  await click(label,'#admin-settings-menu')
  await until(()=>evaluate(`document.querySelector('main h1')?.textContent===${JSON.stringify(label)}`),'Settings '+label)
}

async function resumeStaffSubmission() {
  await click('Operations')
  assert.equal(await evaluate("document.querySelectorAll('.staff-operations .workflow-card button:not(:disabled)').length"),1)
  await click('Resume submission','.staff-operations')
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

const orderedWorkflows=['CRATE_PALLET','SALVAGE','ELECTRICAL_CRATE_PALLET','PICKUP','EWASTE_PICKUP','COLLECTABLES','WHITEGOODS','ELECTRICAL_TESTING','SCRAP']
async function checkBranding(view) {
  await until(()=>evaluate("[...document.querySelectorAll('.brand-logo')].length===1 && [...document.querySelectorAll('.brand-logo')].every(image=>image.complete&&image.naturalWidth>0)"),'branding logo loaded '+view)
  assert.equal(await evaluate('document.title'),'Waste Tracking Management System')
  assert.equal(await evaluate("document.querySelector('footer').textContent"),'© Resource Work Cooperative 2026')
  assert.equal(await evaluate("document.querySelector('.brand-mark')===null"),true)
  if(view==='login')assert.equal(await evaluate("document.querySelector('h1').textContent"),'Waste Tracking Management System')
  else {
    assert.equal(await evaluate("document.querySelector('.brand strong').textContent"),'Resource Work Cooperative')
    assert.equal(await evaluate("document.querySelector('.brand span').textContent"),'Waste Tracking Management System')
    assert.equal(await evaluate("document.querySelector('main h1').textContent"),'Workflows')
    assert.deepEqual(await evaluate("[...document.querySelectorAll('.workflow-card')].map(card=>card.dataset.workflow)"),orderedWorkflows)
    assert.equal(await evaluate("document.querySelector('[data-workflow=ELECTRICAL_TESTING] button').textContent"),'Open')
  }
  const assets=await evaluate(`(async()=>{const paths=[document.querySelector('.brand-logo').src,document.querySelector('link[rel=icon]').href];return Promise.all(paths.map(async path=>{const response=await fetch(path);const bytes=new Uint8Array(await response.arrayBuffer());return {path:new URL(path).pathname,status:response.status,type:response.headers.get('content-type'),signature:[...bytes.slice(0,2)]}}))})()`)
  assert.deepEqual(assets.map(asset=>asset.path),['/assets/branding/rwc_logo.png','/assets/branding/rwc_favicon.jpg'])
  assert.deepEqual(assets.map(asset=>asset.status),[200,200])
  assert.equal(assets[0].type,'image/png');assert.equal(assets[1].type,'image/jpeg')
  assert.deepEqual(assets[0].signature,[137,80]);assert.deepEqual(assets[1].signature,[255,216])
  for(const width of [1280,1024,768]) {
    await cdp('Emulation.setDeviceMetricsOverride',{width,height:1024,deviceScaleFactor:1,mobile:false})
    await until(()=>evaluate('document.documentElement.scrollWidth<=window.innerWidth'),'branding overflow '+view+' '+width)
    assert.equal(await evaluate("(()=>{const image=document.querySelector('.brand-logo');const box=image.getBoundingClientRect();return Math.abs(box.width/box.height-image.naturalWidth/image.naturalHeight)<0.02})()"),true)
    if(view!=='login')assert.equal(await evaluate("getComputedStyle(document.querySelector('.workflow-card-grid')).gridTemplateColumns.split(' ').length"),3)
    await screenshot('branding-'+view+'-'+width)
  }
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
  await enabled('Sign in')
  await checkBranding('login')
  await fill('Username', process.env.INITIAL_STAFF_USERNAME)
  await fill('Password', process.env.INITIAL_STAFF_PASSWORD)
  await click('Sign in')
  await enabled('Records')
  await hasText('Workflows')
  await checkBranding('staff')
  // Standalone browser runs start with an empty isolated database, unlike integration runs.
  if((await browserApi('/api/operations/recent-submissions')).body.length===0) {
    const today=(await browserApi('/api/auth/me')).body.operationalDate
    assert.equal((await browserApi('/api/whitegoods-records','POST',{submissionId:crypto.randomUUID(),recordDate:today,count:0})).status,201)
    await click('Records');await click('Operations')
  }
  await until(()=>evaluate("document.querySelector('.staff-recent tbody')!==null"),'existing recent submissions')
  const initialRecent=(await browserApi('/api/operations/recent-submissions')).body
  assert.equal(await evaluate("document.querySelectorAll('.staff-recent tbody tr').length"),initialRecent.length)
  // Exercise empty state without deleting regression fixtures.
  await evaluate(`window.__operationsFetch=window.fetch; window.fetch=(...args)=>args[0]==='/api/operations/recent-submissions'
    ? Promise.resolve(new Response('[]',{status:200,headers:{'Content-Type':'application/json'}})) : window.__operationsFetch(...args);`)
  await click('Records')
  await click('Operations')
  await hasText('No recent submissions.')
  assert.deepEqual(await evaluate("[...document.querySelectorAll('nav button')].map(b=>b.textContent.trim())"),['Operations','Records'])
  assert.equal(await evaluate("document.querySelector('.workflow-navigation')===null"),true)
  assert.equal(await evaluate("document.querySelectorAll('.staff-operations .workflow-card').length"),9)
  assert.equal(await evaluate("document.querySelector('.staff-recent tbody')===null"),true)
  await screenshot('desktop-staff-operations-empty')
  await evaluate('window.fetch=window.__operationsFetch; delete window.__operationsFetch')
  for(const name of ['Crate / Pallet','Salvage','Pickup','Electrical Crate / Pallet','Collectables','Whitegoods','Electrical Testing','E-waste Pickup','Scrap']) {
    await staffWorkflow(name)
    await until(()=>evaluate(`document.querySelector('main h1')?.textContent===${JSON.stringify(name)}`),'existing '+name+' form')
    await click('Operations')
  }
  for(const width of [768,1024,1280]) {
    await cdp('Emulation.setDeviceMetricsOverride',{width,height:1024,deviceScaleFactor:1,mobile:false})
    await until(()=>evaluate("document.documentElement.scrollWidth<=window.innerWidth"),'Staff Operations overflow at '+width)
    await screenshot('staff-operations-'+width)
  }
  await click('View Records')
  await hasText('Submitted records are read-only for Staff.')
  assert.equal(await evaluate("[...document.querySelectorAll('nav button')].some(b => ['Overview','Reports','Waste Levy Summary','Users','Settings'].includes(b.textContent.trim()))"), false)
  for (const path of ['/api/admin/overview','/api/admin/reports/summary','/api/admin/reports/export?format=csv','/api/admin/reports/export?format=xlsx','/api/admin/waste-levy-summary?month=2004-02','/api/admin/settings','/api/admin/settings/container-types','/api/admin/settings/material-categories']) {
    assert.equal((await browserApi(path)).status,403)
  }

  await staffWorkflow('Crate / Pallet')
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
  for (const label of ['Operations', 'Records', 'Sign out']) {
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
  await resumeStaffSubmission()
  await enabled('Retry submission')
  await click('Retry submission')
  await hasText('saved successfully.')
  const ids = await evaluate('window.__submissionIds')
  assert.equal(ids.length, 2); assert.equal(ids[0], ids[1])
  assert.equal(await evaluate("[...document.querySelectorAll('button')].some(b => b.textContent === 'Correct record')"), false)
  const savedId = await evaluate("document.querySelector('.detail-heading h2').textContent.split('#')[1]")
  await click('Operations')
  const recentStaff=(await browserApi('/api/operations/recent-submissions')).body
  assert.ok(recentStaff.length>0&&recentStaff.length<=5)
  await until(()=>evaluate(`document.querySelectorAll('.staff-recent tbody tr').length===${recentStaff.length}`),'recent Staff submission')
  assert.equal(String(recentStaff[0].id),savedId)
  assert.equal(await evaluate("document.querySelector('.staff-recent tbody tr').textContent.includes('100.00 kg')"),true)
  assert.equal(await evaluate("[...document.querySelectorAll('.staff-recent button')].some(b=>/edit|delete|correct/i.test(b.textContent))"),false)
  for(const width of [1280,768,1024]) {
    await cdp('Emulation.setDeviceMetricsOverride',{width,height:1024,deviceScaleFactor:1,mobile:false})
    await until(()=>evaluate("document.documentElement.scrollWidth<=window.innerWidth"),'populated Staff Operations overflow at '+width)
    await screenshot('staff-operations-recent-'+width)
  }
  console.log('PASS: Staff Operations nine workflow cards, primary navigation only, read-only recent submissions, empty state and desktop/tablet overflow checks.')
  await screenshot('staff-operations-with-recent')
  await click('View Records')
  await hasText('Submitted records are read-only for Staff.')
  const duplicateCount = await evaluate(`fetch('/api/crate-pallet-records').then(r => r.json()).then(rows => rows.filter(r => r.submissionId === ${JSON.stringify(ids[0])}).length)`)
  assert.equal(duplicateCount, 1)
  await screenshot('tablet-saved-record')

  // A definitive ID/data conflict must not become an endless retry loop.
  await staffWorkflow('Crate / Pallet')
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
  await enabled('Sign in')
  await fill('Username', process.env.INITIAL_ADMIN_USERNAME)
  await fill('Password', process.env.INITIAL_ADMIN_PASSWORD)
  await click('Sign in')
  await enabled('Records')
  await click('Records')
  await hasText('View submitted loads, correct entries')
  // Settings is the sole Admin dropdown; operational access remains on Workflows.
  assert.equal(await evaluate("document.querySelector('[aria-label=\"Admin navigation\"] .workflow-navigation')===null"),true)
  assert.deepEqual(await evaluate("[...document.querySelectorAll('.management-navigation button')].map(b=>b.textContent.trim())"),['Overview','Workflows','Reports','Waste Levy Summary','Records'])
  assert.equal(await evaluate("[...document.querySelectorAll('button')].some(b=>b.textContent.trim()==='+ New Entry')"),false)
  await click('Settings')
  await until(()=>evaluate("document.querySelectorAll('[role=menuitem]').length===6"),'six Settings choices')
  const settingsLabels=['Container Types','Material Categories','User Management','Organisation Details','Reporting Defaults','Whitegoods Weight Factor']
  assert.deepEqual(await evaluate("[...document.querySelectorAll('[role=menuitem]')].map(b=>b.textContent)"),settingsLabels)
  await click('Workflows')
  await until(()=>evaluate("document.querySelector('[role=menu]')===null"),'menu closes outside')
  assert.equal(await evaluate("document.querySelectorAll('.workflow-card').length"),9)
  await checkBranding('admin')
  await evaluate("document.querySelector('.settings-menu-trigger').focus();document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowDown',bubbles:true}))")
  await until(()=>evaluate("document.activeElement?.getAttribute('role')==='menuitem'"),'Settings keyboard focus')
  await evaluate("document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{key:'End',bubbles:true}))")
  assert.equal(await evaluate('document.activeElement.textContent'),'Whitegoods Weight Factor')
  await evaluate("document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{key:'Home',bubbles:true}))")
  assert.equal(await evaluate('document.activeElement.textContent'),'Container Types')
  await evaluate("document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}))")
  await until(()=>evaluate("document.querySelector('[role=menu]')===null&&document.activeElement.classList.contains('settings-menu-trigger')"),'Settings Escape restores focus')
  const settingsPaths=['container-types','material-categories','users','organisation','reporting','whitegoods']
  for(let i=0;i<settingsLabels.length;i++) {
    await openSettings(settingsLabels[i])
    await until(()=>evaluate("document.querySelector('main [role=status]')?.textContent!=='Loading settings...'"),'focused Settings loaded')
    assert.equal(await evaluate('location.pathname'),'/admin/settings/'+settingsPaths[i])
    assert.equal(await evaluate("document.querySelector('[role=menu]')===null"),true)
    if(i===2) {
      await hasText('Manage sign-in access.');await enabled('Add User')
      assert.equal(await evaluate("document.querySelectorAll('.settings-page .user-editor').length"),0)
    } else {
      await until(()=>evaluate("document.querySelectorAll('.settings-page section[aria-label]').length===1"),'one Settings section only')
      assert.deepEqual(await evaluate("[...document.querySelectorAll('.settings-page section[aria-label]')].map(e=>e.getAttribute('aria-label'))"),[settingsLabels[i]])
    }
  }
  for(const workflow of ['Salvage','Pickup','Electrical Crate / Pallet','Collectables','Whitegoods','Electrical Testing','E-waste Pickup','Scrap','Crate / Pallet']) {
    await adminWorkflow(workflow)
    await until(()=>evaluate(`document.querySelector('main h1')?.textContent===${JSON.stringify(workflow)}`),'existing '+workflow+' form')
  }
  await adminWorkflow('Electrical Testing')
  await until(()=>evaluate("document.querySelectorAll('.testing-category').length===15 && !document.querySelector('.quick-tally input').disabled"),'pending session guard fixture')
  await evaluate("document.querySelector('.testing-category .quick-tally button:last-child').click()")
  await openSettings('User Management')
  await openSettings('Organisation Details')
  assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent==='Sign out').disabled"),true)
  await click('Workflows')
  await click('Resume submission','.workflow-card[data-workflow="ELECTRICAL_TESTING"]')
  assert.equal(await evaluate("document.querySelector('.testing-category .quick-tally input').value"),'1')
  await evaluate("document.querySelector('.testing-category .quick-tally button').click()")
  await enabled('Sign out')
  await click('Records')
  await fill('Workflow','CRATE_PALLET')

  await enabled('#' + savedId)
  await click('#' + savedId)
  await reopenForAdminActions()
  await hasText('Correct record')
  await reopenForAdminActions()
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
  await reopenForAdminActions()
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
  await reopenForAdminActions()
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
  await enabled('Sign in')
  await fill('Username', process.env.INITIAL_STAFF_USERNAME)
  await fill('Password', process.env.INITIAL_STAFF_PASSWORD)
  await click('Sign in')
  await enabled('Records')
  await click('Records')
  await hasText('Submitted records are read-only for Staff.')
  const upScope = 'fieldset[data-direction="UP"]'
  const downScope = 'fieldset[data-direction="DOWN"]'
  await staffWorkflow('Salvage')
  await until(() => evaluate(`document.querySelector('${upScope} .material-grid')?.children.length === 9`), 'Salvage Up material set')
  const salvageScreenshots = async step => {
    for(const width of [1280,768,1024]) {
      await cdp('Emulation.setDeviceMetricsOverride',{width,height:1024,deviceScaleFactor:1,mobile:false})
      await until(()=>evaluate("document.documentElement.scrollWidth<=window.innerWidth"),'Salvage '+step+' overflow at '+width)
      await screenshot('salvage-'+step+'-'+width)
    }
  }
  await evaluate(`window.__salvageWrites=0;window.__salvagePreviews=0;
    window.fetch=(...args)=>{
      if(args[0]==='/api/salvage-records' && args[1]?.method==='POST') window.__salvageWrites++;
      if(args[0].startsWith('/api/salvage-records/preview')) window.__salvagePreviews++;
      return window.__realFetch(...args);
    };`)
  assert.equal(await evaluate(`document.querySelector('${downScope}')===null`),true)
  assert.equal(await evaluate("document.querySelector('.salvage-steps [aria-current=step]').textContent"),'1 - Salvage Up')
  assert.equal(await evaluate("document.querySelectorAll('.salvage-steps button').length"),0)
  assert.equal(await evaluate("[...document.querySelectorAll('form label')].filter(l=>l.textContent.startsWith('Crew')).length"),1)
  assert.equal(await evaluate(`document.querySelector('${upScope}').textContent.includes('Lead Acid Batteries')`),false)
  await fill('Record date','2003-04-05')
  await fill('Crew','Salvage smoke crew')
  await fill('Kg Up','10.00',upScope)
  await fill('E-waste','99.99',upScope)
  assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent==='Continue to Salvage Down').disabled"),true)
  await fill('E-waste','100.00',upScope)
  // A valid breakdown cannot bypass the mandatory time or operational date.
  assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent==='Continue to Salvage Down').disabled"),true)
  await fill('Time Up','15:30',upScope)
  await fill('Record date','2999-01-01')
  assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent==='Continue to Salvage Down').disabled"),true)
  await fill('Record date','2003-04-05')
  await salvageScreenshots('up')
  await click('Continue to Salvage Down')
  assert.equal(await evaluate('window.__salvageWrites+window.__salvagePreviews'),0)
  assert.equal(await evaluate(`document.querySelector('${upScope}')===null`),true)
  assert.equal(await evaluate("document.querySelector('.salvage-steps [aria-current=step]').textContent"),'2 - Salvage Down')
  assert.equal(await evaluate("[...document.querySelectorAll('form label')].some(l=>/^(Crew|Record date)/.test(l.textContent))"),false)
  assert.equal(await evaluate(`document.querySelector('${downScope} .material-grid').children.length`),14)
  assert.equal(await evaluate(`document.querySelector('${downScope}').textContent.includes('Mattresses')`),false)
  await fill('Kg Down','20.00',downScope)
  await fill('E-waste','99.99',downScope)
  await fill('Time Down','09:15',downScope)
  assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent==='Review Trip').disabled"),true)
  await fill('E-waste','100.00',downScope)
  await salvageScreenshots('down')
  await click('Back to Salvage Up')
  assert.equal(await evaluate("[...document.querySelectorAll('label')].find(l=>l.textContent.startsWith('Record date')).querySelector('input').value"),'2003-04-05')
  assert.equal(await evaluate("[...document.querySelectorAll('label')].find(l=>l.textContent.startsWith('Crew')).querySelector('input').value"),'Salvage smoke crew')
  assert.equal(await evaluate(`document.querySelector('${upScope} input[type=time]').value`),'15:30')
  assert.equal(await evaluate(`document.querySelector('${upScope} input[type=number]').value`),'10.00')
  await click('Continue to Salvage Down')
  assert.equal(await evaluate(`document.querySelector('${downScope} input[type=time]').value`),'09:15')
  assert.equal(await evaluate(`document.querySelector('${downScope} input[type=number]').value`),'20.00')
  await click('Review Trip')
  await hasText('Review both directions before confirming.')
  assert.equal(await evaluate("document.querySelectorAll('.salvage-review dt').length"),6)
  assert.equal(await evaluate("[...document.querySelectorAll('.salvage-review dt')].filter(n=>n.textContent==='Crew').length"),1)
  assert.equal(await evaluate("document.querySelectorAll('.salvage-review .percentage-total.valid').length"),2)
  assert.equal(await evaluate("document.querySelector('.salvage-review').textContent.includes('2003-04-05')"),true)
  await click('Back to edit')
  assert.equal(await evaluate(`document.querySelector('${downScope} input[type=number]').value`),'20.00')
  await evaluate("document.querySelector('.salvage-steps button').click()")
  assert.equal(await evaluate("[...document.querySelectorAll('label')].find(l=>l.textContent.startsWith('Crew')).querySelector('input').value"),'Salvage smoke crew')
  await click('Continue to Salvage Down')
  await click('Review Trip')
  await hasText('Review both directions before confirming.')
  assert.equal(await evaluate('window.__salvageWrites'),0)
  assert.equal(await evaluate('window.__salvagePreviews'),2)
  await salvageScreenshots('review')
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
  for (const label of ['Operations', 'Records', 'Sign out']) {
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
  await resumeStaffSubmission()
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
    await staffWorkflow('Salvage')
    await until(() => evaluate(`document.querySelector('${upScope} .material-grid')?.children.length === 9`), 'new Salvage form')
    await fill('Crew', `Zero combination ${up}/${down}`)
    await fill('Kg Up',up,upScope)
    if(up==='0.00') assert.equal(await evaluate(`document.querySelector('${upScope} .material-grid')===null`),true)
    else await fill('Timber','100.00',upScope)
    assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent==='Continue to Salvage Down').disabled"),true)
    await fill('Time Up','15:30',upScope)
    await click('Continue to Salvage Down')
    await fill('Kg Down',down,downScope)
    if(down==='0.00') assert.equal(await evaluate(`document.querySelector('${downScope} .material-grid')===null`),true)
    else await fill('Timber','100.00',downScope)
    // Each zero direction still requires its time, but does not require percentages.
    assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent==='Review Trip').disabled"),true)
    await fill('Time Down','09:15',downScope)
    await click('Review Trip')
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
  await enabled('Sign in')
  await fill('Username', process.env.INITIAL_ADMIN_USERNAME)
  await fill('Password', process.env.INITIAL_ADMIN_PASSWORD)
  await click('Sign in')
  await enabled('Records')
  await click('Records')
  await hasText('View submitted loads, correct entries')
  await fill('Workflow', 'SALVAGE')
  await enabled('#' + firstSalvageId)
  await click('#' + firstSalvageId)
  await reopenForAdminActions()
  await hasText('Correct record')
  await reopenForAdminActions()
  await click('Correct record')
  await until(() => evaluate(`document.querySelector('${upScope} .material-grid')?.children.length === 9`), 'Admin Salvage form')
  await fill('Kg Up', '0.00', upScope)
  await click('Continue to Salvage Down')
  await click('Review Trip')
  await hasText('Review both directions before confirming.')
  await click('Confirm correction')
  await hasText('saved successfully.')
  let updated = (await browserApi('/api/salvage-records/' + firstSalvageId)).body
  assert.equal(updated.up.materials.length, 0)
  assert.equal(updated.down.materials.length, 1)
  await reopenForAdminActions()
  await click('Correct record')
  await until(() => evaluate(`document.querySelector('${upScope} .material-grid') === null && document.querySelector('${upScope} input') !== null`), 'zero-weight Admin form')
  await fill('Kg Up', '10.00', upScope)
  assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b => b.textContent === 'Continue to Salvage Down').disabled"), true)
  await fill('Timber', '100.00', upScope)
  await click('Continue to Salvage Down')
  await click('Review Trip')
  await hasText('Review both directions before confirming.')
  await click('Confirm correction')
  await hasText('saved successfully.')
  updated = (await browserApi('/api/salvage-records/' + firstSalvageId)).body
  assert.equal(updated.up.materials.length, 1)

  // Change the record from another request while an Admin has an older form open.
  await reopenForAdminActions()
  await click('Correct record')
  await until(() => evaluate(`document.querySelector('${upScope} .material-grid')?.children.length === 9`), 'stale-correction form')
  const materialInputs = side => side.materials.map(({ materialCategoryId, percentage }) => ({ materialCategoryId, percentage }))
  const changed = await browserApi('/api/salvage-records/' + firstSalvageId, 'PUT', {
    submissionId: updated.submissionId, recordDate: updated.recordDate, crew: 'Other Admin correction', version: updated.version,
    up: { time: updated.up.time, kg: updated.up.kg, materials: materialInputs(updated.up) },
    down: { time: updated.down.time, kg: updated.down.kg, materials: materialInputs(updated.down) },
  })
  assert.equal(changed.status, 200)
  await click('Continue to Salvage Down')
  await click('Review Trip')
  await hasText('This record has changed.')
  await click('Back to Records')
  await enabled('#' + firstSalvageId)
  await click('#' + firstSalvageId)
  await hasText('Other Admin correction')
  await reopenForAdminActions()
  await enabled('Delete record')
  await evaluate(`window.__salvageDeleteLost = false;
    window.fetch = async (...args) => {
      const response = await window.__realFetch(...args);
      if (args[0].startsWith('/api/salvage-records/') && args[1]?.method === 'DELETE' && response.ok && !window.__salvageDeleteLost) {
        window.__salvageDeleteLost = true; throw new TypeError('Simulated lost Salvage delete');
      }
      return response;
    };`)
  await reopenForAdminActions()
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
  await enabled('Sign in')
  await fill('Username', process.env.INITIAL_STAFF_USERNAME)
  await fill('Password', process.env.INITIAL_STAFF_PASSWORD)
  await click('Sign in')
  await enabled('Records')
  await click('Records')
  await staffWorkflow('Pickup')
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
  for (const label of ['Operations', 'Records', 'Sign out']) {
    assert.equal(await evaluate(`[...document.querySelectorAll('button')].find(b => b.textContent.trim() === ${JSON.stringify(label)}).disabled`), true)
  }
  await evaluate('window.__releasePickup()')
  await enabled('Retry submission')
  await click('Check Records')
  await until(() => evaluate("document.querySelector('select').value === 'PICKUP'"), 'Pickup Records selected')
  await fill('Workflow', 'SALVAGE')
  await enabled('Apply dates / refresh')
  await resumeStaffSubmission()
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
  await enabled('Sign in')
  await fill('Username', process.env.INITIAL_ADMIN_USERNAME)
  await fill('Password', process.env.INITIAL_ADMIN_PASSWORD)
  await click('Sign in')
  await enabled('Records')
  await click('Records')
  await enabled('#' + pickupId)
  await click('#' + pickupId)
  await reopenForAdminActions()
  await enabled('Correct record')
  await reopenForAdminActions()
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
  await reopenForAdminActions()
  await enabled('Correct record')
  await reopenForAdminActions()
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
  await reopenForAdminActions()
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
  await reopenForAdminActions()
  await click('Delete record')
  await click('Confirm deletion')
  await hasText('Simulated lost Pickup delete')
  await enabled('Confirm deletion')
  await click('Confirm deletion')
  await hasText('is already deleted or no longer available.')
  assert.equal(await evaluate("document.querySelector('.detail-panel') !== null"), false)
  assert.equal((await browserApi('/api/pickup-records/' + pickupId)).status, 404)
  await evaluate('window.fetch = window.__realFetch')
  // Electrical Crate/Pallet requires gross strictly above tare and both approved categories.
  await click('Sign out')
  await enabled('Sign in')
  await fill('Username', process.env.INITIAL_STAFF_USERNAME)
  await fill('Password', process.env.INITIAL_STAFF_PASSWORD)
  await click('Sign in')
  await enabled('Records')
  await click('Records')
  await staffWorkflow('Electrical Crate / Pallet')
  await until(() => evaluate("document.querySelector('.material-grid')?.children.length === 2"), 'electrical categories')
  const electricalLabels = await evaluate("[...document.querySelectorAll('.material-grid label')].map(l => l.textContent.replace('%', '').trim())")
  assert.deepEqual(electricalLabels, ['TVs / Monitors / Projectors', 'Everything Else'])
  const electricalOptions = await evaluate("[...document.querySelector('form select').options].map(o => o.textContent)")
  assert.equal(electricalOptions.length, 5) // Placeholder plus four allowed containers.
  assert.equal(electricalOptions.some(name => name.includes('Apple Crate')), false)
  assert.equal(await evaluate("[...document.querySelectorAll('form label')].some(l => /Location|Source|Donor|Count/.test(l.textContent))"), false)
  const electricalRefs = (await browserApi('/api/container-types?context=ELECTRICAL_CRATE_PALLET')).body
  const electricalCage = electricalRefs.find(c => c.name === 'Stillage Cage')
  const electricalPlastic = electricalRefs.find(c => c.name === 'Plastic Pallet')
  const electricalPercentages = () => evaluate("[...document.querySelectorAll('.material-grid input')].map(i=>i.value)")
  assert.deepEqual(await electricalPercentages(),['0.00','0.00'])
  await fill('Container type', String(electricalCage.id))
  await fill('TVs / Monitors / Projectors', '100.00')
  await fill('Everything Else', '0.00')
  for (const gross of ['49.99', '50.00']) {
    await fill('Gross weight', gross)
    assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b => b.textContent.trim() === 'Review record').disabled"), true)
  }
  await fill('Gross weight', '150.00')
  for(const [label,entered,expected] of [
    ['TVs / Monitors / Projectors','35',['35','65.00']],
    ['Everything Else','22.5',['77.50','22.5']],
    ['TVs / Monitors / Projectors','33.33',['33.33','66.67']],
    ['Everything Else','62.5',['37.50','62.5']],
    ['TVs / Monitors / Projectors','100',['100','0.00']],
    ['TVs / Monitors / Projectors','0',['0','100.00']],
    ['Everything Else','0',['100.00','0']],
    ['Everything Else','100',['0.00','100']],
  ]) {
    await fill(label,entered)
    assert.deepEqual(await electricalPercentages(),expected)
    assert.equal(await evaluate("document.querySelector('.percentage-total').textContent.includes('100.00%')"),true)
  }
  // Invalid edits keep the other value unchanged and block Review; no clamping/complementing.
  for(const invalid of ['-1','100.01','33.333','not-a-number','']) {
    await fill('TVs / Monitors / Projectors',invalid)
    assert.equal((await electricalPercentages())[1],'100')
    assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent.trim()==='Review record').disabled"),true)
  }
  await fill('TVs / Monitors / Projectors','33.33')
  for(const width of [1280,768,1024]) {
    await cdp('Emulation.setDeviceMetricsOverride',{width,height:1024,deviceScaleFactor:1,mobile:false})
    await until(()=>evaluate('document.documentElement.scrollWidth<=window.innerWidth'),'electrical complement overflow at '+width)
    await screenshot('electrical-complement-'+width)
  }
  await click('Review record')
  await hasText('Review the calculated weights before confirming.')
  assert.deepEqual(await evaluate("[...document.querySelectorAll('.record-review tbody tr')].map(r=>r.cells[1].textContent)"),['33.33%','66.67%'])
  await click('Back to edit')
  assert.deepEqual(await electricalPercentages(),['33.33','66.67'])
  await fill('TVs / Monitors / Projectors','100.00')
  await click('Review record')
  await hasText('Review the calculated weights before confirming.')
  await hasText('100.00 kg')
  assert.equal(await evaluate("document.querySelector('.record-review tbody').children.length"), 2)
  await evaluate(`window.__electricalPayloads = []; window.__electricalLost = false;
    window.__electricalGate = new Promise(resolve => { window.__releaseElectrical = resolve; });
    window.fetch = async (...args) => {
      const submission = args[0] === '/api/electrical-crate-pallet-records' && args[1]?.method === 'POST';
      if (submission) { window.__electricalPayloads.push(args[1].body); await window.__electricalGate; }
      const response = await window.__realFetch(...args);
      if (submission && response.ok && !window.__electricalLost) { window.__electricalLost = true; throw new TypeError('Simulated lost electrical response'); }
      return response;
    };`)
  await click('Confirm and submit')
  await until(() => evaluate('window.__electricalPayloads.length === 1'), 'electrical submission active')
  for (const label of ['Operations', 'Records', 'Sign out']) {
    assert.equal(await evaluate(`[...document.querySelectorAll('button')].find(b => b.textContent.trim() === ${JSON.stringify(label)}).disabled`), true)
  }
  await evaluate('window.__releaseElectrical()')
  await enabled('Retry submission')
  await click('Check Records')
  await until(() => evaluate("document.querySelector('select').value === 'ELECTRICAL_CRATE_PALLET'"), 'electrical Records selected')
  await fill('Workflow', 'PICKUP')
  await enabled('Apply dates / refresh')
  await resumeStaffSubmission()
  await enabled('Retry submission')
  await click('Retry submission')
  await hasText('saved successfully.')
  const electricalPayloads = await evaluate('window.__electricalPayloads')
  assert.equal(electricalPayloads.length, 2)
  assert.equal(electricalPayloads[0], electricalPayloads[1])
  await evaluate('window.fetch = window.__realFetch')
  const electricalInput = JSON.parse(electricalPayloads[0])
  const electricalRecords = (await browserApi('/api/electrical-crate-pallet-records')).body.filter(r => r.submissionId === electricalInput.submissionId)
  assert.equal(electricalRecords.length, 1)
  const electricalId = electricalRecords[0].id
  assert.equal(electricalRecords[0].tareWeight, '50.00')
  assert.equal(electricalRecords[0].netWeight, '100.00')
  assert.equal(electricalRecords[0].materials.length, 2)
  assert.equal(await evaluate("[...document.querySelectorAll('button')].some(b => b.textContent === 'Correct record')"), false)
  await screenshot('tablet-electrical-saved')
  const zeroElectrical = await browserApi('/api/electrical-crate-pallet-records', 'POST', { ...electricalInput, submissionId: crypto.randomUUID(), grossWeight: '50.00' })
  assert.equal(zeroElectrical.status, 400)
  const electricalConflict = await browserApi('/api/electrical-crate-pallet-records', 'POST', { ...electricalInput, grossWeight: '151.00' })
  assert.equal(electricalConflict.status, 409)
  await fill('To', '2000-01-01')
  await click('Apply dates / refresh')
  await hasText('No records found.')
  assert.equal(await evaluate("document.querySelector('.detail-panel') !== null"), false)
  await fill('To', '')
  await click('Apply dates / refresh')
  await enabled('#' + electricalId)
  await click('Sign out')
  await enabled('Sign in')
  await fill('Username', process.env.INITIAL_ADMIN_USERNAME)
  await fill('Password', process.env.INITIAL_ADMIN_PASSWORD)
  await click('Sign in')
  await enabled('Records')
  await click('Records')
  await enabled('#' + electricalId)
  await click('#' + electricalId)
  await reopenForAdminActions()
  await enabled('Correct record')
  await reopenForAdminActions()
  await click('Correct record')
  await until(() => evaluate("document.querySelector('.material-grid')?.children.length === 2"), 'Admin electrical form')
  assert.deepEqual(await electricalPercentages(),electricalRecords[0].materials.map(m=>m.percentage))
  await fill('Gross weight', '50.00')
  assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b => b.textContent.trim() === 'Review record').disabled"), true)
  await fill('Container type', String(electricalPlastic.id))
  await fill('Gross weight', '16.00')
  await fill('TVs / Monitors / Projectors', '60.00')
  await fill('Everything Else', '40.00')
  await click('Review record')
  await hasText('Review the calculated weights before confirming.')
  await hasText('10.00 kg')
  await click('Confirm correction')
  await hasText('saved successfully.')
  const electricalUpdated = (await browserApi('/api/electrical-crate-pallet-records/' + electricalId)).body
  assert.equal(electricalUpdated.tareWeight, '6.00')
  assert.equal(electricalUpdated.netWeight, '10.00')
  assert.equal(electricalUpdated.materials[0].materialWeight, '6.00')
  await reopenForAdminActions()
  await enabled('Correct record')
  await reopenForAdminActions()
  await click('Correct record')
  await until(() => evaluate("document.querySelector('.material-grid')?.children.length === 2"), 'stale electrical form')
  assert.deepEqual(await electricalPercentages(),electricalUpdated.materials.map(m=>m.percentage))
  const electricalChanged = await browserApi('/api/electrical-crate-pallet-records/' + electricalId, 'PUT', {
    ...electricalInput, containerTypeId: electricalPlastic.id, grossWeight: '26.00', version: electricalUpdated.version,
  })
  assert.equal(electricalChanged.status, 200)
  await click('Review record')
  await hasText('This record has changed.')
  await click('Back to Records')
  await enabled('#' + electricalId)
  await click('#' + electricalId)
  await reopenForAdminActions()
  await enabled('Delete record')
  await hasText('20.00 kg')
  await evaluate(`window.__electricalDeleteLost = false;
    window.fetch = async (...args) => {
      const response = await window.__realFetch(...args);
      if (args[0].startsWith('/api/electrical-crate-pallet-records/') && args[1]?.method === 'DELETE' && response.ok && !window.__electricalDeleteLost) {
        window.__electricalDeleteLost = true; throw new TypeError('Simulated lost electrical delete');
      }
      return response;
    };`)
  await reopenForAdminActions()
  await click('Delete record')
  await click('Confirm deletion')
  await hasText('Simulated lost electrical delete')
  await enabled('Confirm deletion')
  await click('Confirm deletion')
  await hasText('is already deleted or no longer available.')
  assert.equal(await evaluate("document.querySelector('.detail-panel') !== null"), false)
  assert.equal((await browserApi('/api/electrical-crate-pallet-records/' + electricalId)).status, 404)
  await evaluate('window.fetch = window.__realFetch')
  // Collectables: one tub, fixed 2 kg tare, operational duplicate avoidance only.
  await click('Sign out')
  await enabled('Sign in')
  await fill('Username', process.env.INITIAL_STAFF_USERNAME)
  await fill('Password', process.env.INITIAL_STAFF_PASSWORD)
  await click('Sign in')
  await enabled('Records')
  await click('Records')
  await staffWorkflow('Collectables')
  await until(() => evaluate("document.querySelector('.material-grid')?.children.length === 13"), 'Collectables categories loaded')
  const collectableReminder = 'Only record collectables brought directly from the driveway/outside area. Do not record tubs from the pricing bench or material already counted through another process.'
  await hasText(collectableReminder)
  assert.equal(await evaluate("document.querySelector('form select') === null"), true)
  assert.equal(await evaluate("[...document.querySelectorAll('form label')].some(l => /Source|Customer|Location|Count|Eligibility/.test(l.textContent))"), false)
  const collectableLabels = await evaluate("[...document.querySelectorAll('.material-grid label')].map(l => l.textContent.replace('%', '').trim())")
  assert.equal(collectableLabels.length, 13)
  for (const name of ['E-waste', 'Lead Acid Batteries', 'Whitegoods / Large Appliances', 'TVs / Monitors / Projectors', 'Everything Else']) assert.equal(collectableLabels.includes(name), false)
  await fill('Timber', '100.00')
  for (const gross of ['0', '1.99', '2.00']) {
    await fill('Gross tub weight', gross)
    assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b => b.textContent.trim() === 'Review collectables').disabled"), true)
  }
  await fill('Gross tub weight', '12.00')
  await fill('Timber', '99.99')
  assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b => b.textContent.trim() === 'Review collectables').disabled"), true)
  await fill('Timber', '100.00')
  assert.equal(await evaluate('document.documentElement.scrollWidth <= window.innerWidth'), true)
  await screenshot('tablet-collectables-entry')
  await click('Review collectables')
  await hasText('Review the calculated weights before confirming.')
  await hasText('10.00 kg')
  await hasText(collectableReminder)
  await click('Back to edit')
  await fill('Gross tub weight', '22.00')
  await click('Review collectables')
  await enabled('Confirm and submit')
  await hasText('20.00 kg')
  await evaluate(`window.__collectablePayloads = []; window.__collectableLost = false;
    window.__collectableGate = new Promise(resolve => { window.__releaseCollectable = resolve; });
    window.fetch = async (...args) => {
      const submission = args[0] === '/api/collectable-records' && args[1]?.method === 'POST';
      if (submission) { window.__collectablePayloads.push(args[1].body); await window.__collectableGate; }
      const response = await window.__realFetch(...args);
      if (submission && response.ok && !window.__collectableLost) { window.__collectableLost = true; throw new TypeError('Simulated lost Collectables response'); }
      return response;
    };`)
  await click('Confirm and submit')
  await until(() => evaluate('window.__collectablePayloads.length === 1'), 'Collectables submission active')
  for (const label of ['Operations', 'Records', 'Sign out']) {
    assert.equal(await evaluate(`[...document.querySelectorAll('button')].find(b => b.textContent.trim() === ${JSON.stringify(label)}).disabled`), true)
  }
  await evaluate('window.__releaseCollectable()')
  await enabled('Retry submission')
  await click('Check Records')
  await until(() => evaluate("document.querySelector('select').value === 'COLLECTABLES'"), 'Collectables Records selected')
  await fill('Workflow', 'ELECTRICAL_CRATE_PALLET')
  await enabled('Apply dates / refresh')
  await resumeStaffSubmission()
  await enabled('Retry submission')
  await click('Retry submission')
  await hasText('saved successfully.')
  await hasText('Mark the physical tub label as recorded.')
  const collectablePayloads = await evaluate('window.__collectablePayloads')
  assert.equal(collectablePayloads.length, 2)
  assert.equal(collectablePayloads[0], collectablePayloads[1])
  await evaluate('window.fetch = window.__realFetch')
  const collectableInput = JSON.parse(collectablePayloads[0])
  const collectableRecords = (await browserApi('/api/collectable-records')).body.filter(r => r.submissionId === collectableInput.submissionId)
  assert.equal(collectableRecords.length, 1)
  const collectableId = collectableRecords[0].id
  assert.equal(collectableRecords[0].grossWeight, '22.00')
  assert.equal(collectableRecords[0].tareWeight, '2.00')
  assert.equal(collectableRecords[0].netWeight, '20.00')
  assert.equal(collectableRecords[0].materials[0].materialWeight, '20.00')
  assert.equal(await evaluate("[...document.querySelectorAll('button')].some(b => b.textContent === 'Correct record')"), false)
  await screenshot('tablet-collectables-saved')
  const collectableZero = await browserApi('/api/collectable-records', 'POST', { ...collectableInput, submissionId: crypto.randomUUID(), grossWeight: '2.00' })
  assert.equal(collectableZero.status, 400)
  const collectableConflict = await browserApi('/api/collectable-records', 'POST', { ...collectableInput, grossWeight: '23.00' })
  assert.equal(collectableConflict.status, 409)
  await fill('To', '2000-01-01')
  await click('Apply dates / refresh')
  await hasText('No records found.')
  assert.equal(await evaluate("document.querySelector('.detail-panel') !== null"), false)
  await fill('To', '')
  await click('Apply dates / refresh')
  await enabled('#' + collectableId)
  await click('Sign out')
  await enabled('Sign in')
  await fill('Username', process.env.INITIAL_ADMIN_USERNAME)
  await fill('Password', process.env.INITIAL_ADMIN_PASSWORD)
  await click('Sign in')
  await enabled('Records')
  await click('Records')
  await enabled('#' + collectableId)
  await click('#' + collectableId)
  await reopenForAdminActions()
  await enabled('Correct record')
  await reopenForAdminActions()
  await click('Correct record')
  await until(() => evaluate("document.querySelector('.material-grid')?.children.length === 13"), 'Admin Collectables form')
  await hasText(collectableReminder)
  await fill('Gross tub weight', '2.00')
  assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b => b.textContent.trim() === 'Review collectables').disabled"), true)
  await fill('Gross tub weight', '32.00')
  await click('Review collectables')
  await hasText('Review the calculated weights before confirming.')
  await click('Confirm correction')
  await hasText('saved successfully.')
  const collectableUpdated = (await browserApi('/api/collectable-records/' + collectableId)).body
  assert.equal(collectableUpdated.tareWeight, '2.00')
  assert.equal(collectableUpdated.netWeight, '30.00')
  await reopenForAdminActions()
  await enabled('Correct record')
  await reopenForAdminActions()
  await click('Correct record')
  await until(() => evaluate("document.querySelector('.material-grid')?.children.length === 13"), 'stale Collectables form')
  const collectableChanged = await browserApi('/api/collectable-records/' + collectableId, 'PUT', {
    ...collectableInput, grossWeight: '42.00', version: collectableUpdated.version,
  })
  assert.equal(collectableChanged.status, 200)
  await click('Review collectables')
  await hasText('This record has changed.')
  await click('Back to Records')
  await enabled('#' + collectableId)
  await click('#' + collectableId)
  await reopenForAdminActions()
  await enabled('Delete record')
  await hasText('40.00 kg')
  await evaluate(`window.__collectableDeleteLost = false;
    window.fetch = async (...args) => {
      const response = await window.__realFetch(...args);
      if (args[0].startsWith('/api/collectable-records/') && args[1]?.method === 'DELETE' && response.ok && !window.__collectableDeleteLost) {
        window.__collectableDeleteLost = true; throw new TypeError('Simulated lost Collectables delete');
      }
      return response;
    };`)
  await reopenForAdminActions()
  await click('Delete record')
  await click('Confirm deletion')
  await hasText('Simulated lost Collectables delete')
  await enabled('Confirm deletion')
  await click('Confirm deletion')
  await hasText('is already deleted or no longer available.')
  assert.equal(await evaluate("document.querySelector('.detail-panel') !== null"), false)
  assert.equal((await browserApi('/api/collectable-records/' + collectableId)).status, 404)
  await evaluate('window.fetch = window.__realFetch')
  // Whitegoods: daily quantities, zero and same-day entries, derived calendar-month summaries.
  await click('Sign out')
  await enabled('Sign in')
  await fill('Username', process.env.INITIAL_STAFF_USERNAME)
  await fill('Password', process.env.INITIAL_STAFF_PASSWORD)
  await click('Sign in')
  await enabled('Records')
  await click('Records')
  await staffWorkflow('Whitegoods')
  await hasText('Whitegoods entry')
  assert.equal(await evaluate("document.querySelectorAll('form label').length"), 2)
  for (const invalid of ['-1', '1.5', '2147483648']) {
    await fill('Whitegoods count', invalid)
    assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b => b.textContent.trim() === 'Review Whitegoods').disabled"), true)
  }
  await fill('Record date', '2001-01-15')
  await fill('Whitegoods count', '5')
  await screenshot('tablet-whitegoods-entry')
  await click('Review Whitegoods')
  await enabled('Confirm and submit')
  await hasText('240.00 kg')
  await evaluate(`window.__whitegoodsPayloads = []; window.__whitegoodsLost = false;
    window.__whitegoodsGate = new Promise(resolve => { window.__releaseWhitegoods = resolve; });
    window.fetch = async (...args) => {
      const submission = args[0] === '/api/whitegoods-records' && args[1]?.method === 'POST';
      if (submission) { window.__whitegoodsPayloads.push(args[1].body); await window.__whitegoodsGate; }
      const response = await window.__realFetch(...args);
      if (submission && response.ok && !window.__whitegoodsLost) { window.__whitegoodsLost = true; throw new TypeError('Simulated lost Whitegoods response'); }
      return response;
    };`)
  await click('Confirm and submit')
  await until(() => evaluate('window.__whitegoodsPayloads.length === 1'), 'Whitegoods submission active')
  for (const label of ['Operations', 'Records', 'Sign out']) {
    assert.equal(await evaluate(`[...document.querySelectorAll('button')].find(b => b.textContent.trim() === ${JSON.stringify(label)}).disabled`), true)
  }
  await evaluate('window.__releaseWhitegoods()')
  await enabled('Retry submission')
  await click('Check Records')
  await until(() => evaluate("document.querySelector('select').value === 'WHITEGOODS'"), 'Whitegoods Records selected')
  await fill('Workflow', 'COLLECTABLES')
  await enabled('Apply dates / refresh')
  await resumeStaffSubmission()
  await enabled('Retry submission')
  await click('Retry submission')
  await hasText('saved successfully.')
  const whitegoodsPayloads = await evaluate('window.__whitegoodsPayloads')
  assert.equal(whitegoodsPayloads.length, 2)
  assert.equal(whitegoodsPayloads[0], whitegoodsPayloads[1])
  await evaluate('window.fetch = window.__realFetch')
  const whitegoodsInput = JSON.parse(whitegoodsPayloads[0])
  const whitegoodsRecords = (await browserApi('/api/whitegoods-records')).body.filter(r => r.submissionId === whitegoodsInput.submissionId)
  assert.equal(whitegoodsRecords.length, 1)
  const whitegoodsId = whitegoodsRecords[0].id
  assert.equal(whitegoodsRecords[0].kgPerItem, '48.00')
  assert.equal(whitegoodsRecords[0].estimatedWeight, '240.00')
  assert.equal(await evaluate("[...document.querySelectorAll('button')].some(b => b.textContent === 'Correct record')"), false)
  async function expectWhitegoodsSummary(month, count, weight) {
    await fill('Summary month', month)
    await until(() => evaluate(`(() => {
      const values = [...document.querySelectorAll('.whitegoods-summary dd')].map(n => n.textContent);
      return values[0] === ${JSON.stringify(month)} && values[1] === ${JSON.stringify(String(count))} && values[2] === ${JSON.stringify(weight + ' kg')};
    })()`), 'Whitegoods monthly summary ' + month + ' count ' + count)
  }
  await expectWhitegoodsSummary('2001-01', 5, '240.00')
  await screenshot('tablet-whitegoods-summary')
  for (const quantity of ['0', '3']) {
    await staffWorkflow('Whitegoods')
    await fill('Record date', '2001-01-15')
    await fill('Whitegoods count', quantity)
    await click('Review Whitegoods')
    await enabled('Confirm and submit')
    await click('Confirm and submit')
    await hasText('saved successfully.')
    await expectWhitegoodsSummary('2001-01', quantity === '0' ? 5 : 8, quantity === '0' ? '240.00' : '384.00')
  }
  assert.equal((await browserApi('/api/whitegoods-records?from=2001-01-15&to=2001-01-15')).body.length, 3)
  await fill('To', '1999-12-31')
  await click('Apply dates / refresh')
  await hasText('No records found.')
  await expectWhitegoodsSummary('2001-01', 8, '384.00') // Summary is independent of list dates.
  await fill('To', '')
  await click('Apply dates / refresh')
  await enabled('#' + whitegoodsId)
  await evaluate(`window.fetch = async (...args) => {
    if (args[0].startsWith('/api/whitegoods-records/monthly-summary')) throw new TypeError('Simulated monthly summary failure');
    return window.__realFetch(...args);
  };`)
  await fill('Summary month', '2001-03')
  await hasText('Simulated monthly summary failure')
  assert.equal(await evaluate("document.querySelectorAll('.whitegoods-summary dd').length"), 0)
  await evaluate('window.fetch = window.__realFetch')
  await click('Retry monthly summary')
  await expectWhitegoodsSummary('2001-03', 0, '0.00')
  await click('Sign out')
  await enabled('Sign in')
  await fill('Username', process.env.INITIAL_ADMIN_USERNAME)
  await fill('Password', process.env.INITIAL_ADMIN_PASSWORD)
  await click('Sign in')
  await enabled('Records')
  await click('Records')
  await enabled('#' + whitegoodsId)
  await click('#' + whitegoodsId)
  await reopenForAdminActions()
  await enabled('Correct record')
  await reopenForAdminActions()
  await click('Correct record')
  await hasText('Whitegoods entry')
  await fill('Record date', '2001-02-01')
  await fill('Whitegoods count', '10')
  await click('Review Whitegoods')
  await enabled('Confirm correction')
  await click('Confirm correction')
  await hasText('saved successfully.')
  await expectWhitegoodsSummary('2001-01', 3, '144.00')
  await expectWhitegoodsSummary('2001-02', 10, '480.00')
  const whitegoodsUpdated = (await browserApi('/api/whitegoods-records/' + whitegoodsId)).body
  await reopenForAdminActions()
  await enabled('Correct record')
  await reopenForAdminActions()
  await click('Correct record')
  await hasText('Whitegoods entry')
  const whitegoodsChanged = await browserApi('/api/whitegoods-records/' + whitegoodsId, 'PUT', {
    ...whitegoodsInput, recordDate: '2001-02-01', count: 11, version: whitegoodsUpdated.version,
  })
  assert.equal(whitegoodsChanged.status, 200)
  await click('Review Whitegoods')
  await hasText('This record has changed.')
  await click('Back to Records')
  await expectWhitegoodsSummary('2001-02', 11, '528.00')
  await enabled('#' + whitegoodsId)
  await click('#' + whitegoodsId)
  await reopenForAdminActions()
  await enabled('Delete record')
  await evaluate(`window.__whitegoodsDeleteLost = false;
    window.fetch = async (...args) => {
      const response = await window.__realFetch(...args);
      if (args[0].startsWith('/api/whitegoods-records/') && args[1]?.method === 'DELETE' && response.ok && !window.__whitegoodsDeleteLost) {
        window.__whitegoodsDeleteLost = true; throw new TypeError('Simulated lost Whitegoods delete');
      }
      return response;
    };`)
  await reopenForAdminActions()
  await click('Delete record')
  await click('Confirm deletion')
  await hasText('Simulated lost Whitegoods delete')
  await enabled('Confirm deletion')
  await click('Confirm deletion')
  await hasText('is already deleted or no longer available.')
  await expectWhitegoodsSummary('2001-02', 0, '0.00')
  await expectWhitegoodsSummary('2001-01', 3, '144.00')
  assert.equal((await browserApi('/api/whitegoods-records/' + whitegoodsId)).status, 404)
  await evaluate('window.fetch = window.__realFetch')
  // Electrical Testing: category counters, independent overall diversions, monthly sales references.
  await click('Sign out')
  await enabled('Sign in')
  await fill('Username', process.env.INITIAL_STAFF_USERNAME)
  await fill('Password', process.env.INITIAL_STAFF_PASSWORD)
  await click('Sign in')
  await enabled('Records')
  await click('Records')
  await staffWorkflow('Electrical Testing')
  await until(() => evaluate("document.querySelectorAll('.testing-category').length === 15"), '15 electrical item categories')
  await fill('Tally month', '2002-01')
  await fill('Tally date', '2002-01-15')
  await until(() => evaluate("document.querySelectorAll('.testing-category').length === 15 && document.querySelector('input[type=month]').value === '2002-01'"), 'January counter board')
  const testingCategories = (await browserApi('/api/electrical-testing/categories')).body
  const testingCategory = testingCategories[0]
  const testingScope = `[data-reference-category="${testingCategory.id}"]`
  const testingScope2 = `[data-reference-category="${testingCategories[1].id}"]`
  const testingScope3 = `[data-reference-category="${testingCategories[2].id}"]`
  const testingScope4 = `[data-reference-category="${testingCategories[3].id}"]`
  await hasText("Historical tallies are read-only for Staff. Only today's tally can be changed.")
  assert.equal(await evaluate("[...document.querySelectorAll('.quick-tally button')].every(b=>b.disabled)"),true)
  const tallyToday=(await browserApi('/api/auth/me')).body.operationalDate
  const tallyMonth=tallyToday.slice(0,7)
  const tallyTomorrow=new Date(new Date(tallyToday+'T12:00:00Z').getTime()+86400000).toISOString().slice(0,10)
  await fill('Tally date',tallyTomorrow)
  assert.equal(await evaluate("document.querySelector('label.tally-date input[type=date]').value"),'2002-01-15')
  await click('Monthly Sales Reference')
  await fill('Reporting month','2002-01')
  await until(()=>evaluate("document.querySelectorAll('[data-reference-category]').length===15"),'sales reference rows')
  assert.equal(await evaluate(`document.querySelector('${testingScope} .sales-reference').textContent.includes('Not entered')`), true)
  assert.equal(await evaluate('document.documentElement.scrollWidth <= window.innerWidth'), true)
  await click('Enter sales reference', testingScope)
  await hasText('Sold Last Month reference')
  await fill('Sold Last Month count', '0')
  await click('Review entry')
  await enabled('Confirm and submit')
  await click('Confirm and submit')
  await until(() => evaluate(`document.querySelector('${testingScope} .sales-reference strong')?.textContent === '0'`), 'explicit zero reference')
  assert.equal(await evaluate(`[...document.querySelectorAll('${testingScope} button')].some(b => b.textContent === 'Correct sales reference')`), false)
  await click('Enter sales reference', testingScope2)
  await fill('Sold Last Month count', '7')
  await click('Review entry')
  await enabled('Confirm and submit')
  await click('Confirm and submit')
  await until(() => evaluate(`document.querySelector('${testingScope2} .sales-reference strong')?.textContent === '7'`), 'second category sales reference')
  await click('Daily Tallies')
  await fill('Tally month',tallyMonth)
  await until(()=>evaluate("!document.querySelector('.quick-tally input').disabled"),'today tally totals loaded')
  assert.equal(await evaluate("document.querySelector('label.tally-date input[type=date]').value"),tallyToday)
  assert.equal(await evaluate("document.querySelector('.testing-summary').open"),false)
  assert.equal(await evaluate("document.querySelectorAll('.quick-tally input').length"),47)
  assert.equal(await evaluate("[...document.querySelectorAll('.quick-tally input')].every(i=>i.value==='0')"),true)
  assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent==='Save Tallies').disabled"),true)
  const pendingCell=(categoryId,type)=>categoryId===null?`.testing-diversions [data-tally="${type}"]`:`.testing-category[data-category="${categoryId}"] [data-tally="${type}"]`
  const tapTally=async(categoryId,type,sign)=>click(sign,pendingCell(categoryId,type))
  const setTally=async(categoryId,type,value)=>evaluate(`(() => {const i=document.querySelector(${JSON.stringify(pendingCell(categoryId,type)+' input')});Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(i,${JSON.stringify(value)});i.dispatchEvent(new Event('input',{bubbles:true}));})()`)
  const pendingCount=(categoryId,type)=>evaluate(`document.querySelector(${JSON.stringify(pendingCell(categoryId,type)+' input')}).value`)
  const cull='STOCK_CULL',tested='TESTED_AND_PRICED'
  assert.equal(await evaluate(`document.querySelector(${JSON.stringify(pendingCell(testingCategory.id,tested)+' button')}).disabled`),true)
  // The first cell will succeed before the next cell's response is deliberately lost.
  for(let i=0;i<4;i++)await tapTally(testingCategories[1].id,cull,'+')
  await tapTally(testingCategories[1].id,cull,'-')
  assert.equal(await pendingCount(testingCategories[1].id,cull),'3')
  for(const invalid of ['-1','1.5','not-a-number']) {
    await setTally(testingCategory.id,tested,invalid)
    assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent==='Save Tallies').disabled"),true)
  }
  await setTally(testingCategory.id,tested,'2')
  await tapTally(testingCategory.id,'UNTESTED_BROKEN_FAILED','+')
  await setTally(null,'DIVERTED_TO_OLS','3')
  await setTally(null,'DIVERTED_TO_COLL','4')
  assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent==='Sign out').disabled"),true)
  assert.equal(await evaluate(`(() => {const e=new Event('beforeunload',{cancelable:true});window.dispatchEvent(e);return e.defaultPrevented})()`),true)
  await click('Records')
  await resumeStaffSubmission()
  assert.equal(await pendingCount(testingCategory.id,tested),'2')
  assert.equal(await pendingCount(testingCategories[1].id,cull),'3')
  for(const width of [1280,768,1024]) {
    await cdp('Emulation.setDeviceMetricsOverride',{width,height:1024,deviceScaleFactor:1,mobile:false})
    await until(()=>evaluate('document.documentElement.scrollWidth<=window.innerWidth'),'quick tally overflow at '+width)
    await tapTally(testingCategory.id,tested,'+')
    await tapTally(testingCategory.id,tested,'-')
    assert.equal(await pendingCount(testingCategory.id,tested),'2')
    await screenshot('electrical-testing-grid-'+width)
  }
  await evaluate(`window.__testingPayloads=[];window.__testingLost=false;
    window.__testingGate=new Promise(resolve=>{window.__releaseTesting=resolve});
    window.fetch=async(...args)=>{
      const submission=args[0]==='/api/electrical-testing/tallies'&&args[1]?.method==='POST';
      if(submission){window.__testingPayloads.push(args[1].body);if(JSON.parse(args[1].body).tallyType==='TESTED_AND_PRICED')await window.__testingGate}
      const response=await window.__realFetch(...args);
      if(submission&&JSON.parse(args[1].body).tallyType==='TESTED_AND_PRICED'&&response.ok&&!window.__testingLost){window.__testingLost=true;throw new TypeError('Simulated lost testing tally response')}
      return response;
    };`)
  await click('Save Tallies')
  await until(()=>evaluate('window.__testingPayloads.length===2'),'first cell confirmed, second in progress')
  assert.equal(await pendingCount(testingCategories[1].id,cull),'3')
  for(const label of ['Operations','Records','Sign out']) assert.equal(await evaluate(`[...document.querySelectorAll('button')].find(b=>b.textContent.trim()===${JSON.stringify(label)}).disabled`),true)
  await evaluate('window.__releaseTesting()')
  await enabled('Retry Save Tallies')
  await hasText('1 of 5 tally records confirmed saved')
  await click('Records')
  await fill('Workflow','WHITEGOODS')
  await enabled('Apply dates / refresh')
  await resumeStaffSubmission()
  await click('Retry Save Tallies')
  await hasText('Tallies saved successfully.')
  await until(()=>evaluate("!document.querySelector('.quick-tally input').disabled"),'saved monthly counts refreshed')
  assert.equal(await pendingCount(testingCategory.id,tested),'2')
  assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent==='Save Tallies').disabled"),true)
  assert.equal(await evaluate(`document.querySelector(${JSON.stringify(pendingCell(testingCategory.id,tested))}).nextElementSibling.textContent`),'Today: 2')
  const testingPayloads=await evaluate('window.__testingPayloads')
  assert.equal(testingPayloads.length,6)
  const testedAttempts=testingPayloads.filter(p=>JSON.parse(p).tallyType==='TESTED_AND_PRICED')
  assert.equal(testedAttempts.length,2);assert.equal(testedAttempts[0],testedAttempts[1])
  assert.equal(testingPayloads.filter(p=>JSON.parse(p).tallyType==='STOCK_CULL').length,1)
  await evaluate('window.fetch=window.__realFetch')
  const testingInput=JSON.parse(testedAttempts[0])
  const testingSaved=(await browserApi('/api/electrical-testing/tallies')).body.filter(r=>r.submissionId===testingInput.submissionId)
  assert.equal(testingSaved.length,1)
  const testingId=testingSaved[0].id
  const testingSummary=(await browserApi('/api/electrical-testing/monthly-summary?month='+tallyMonth)).body
  assert.equal(testingSummary.divertedToOls,3);assert.equal(testingSummary.divertedToColl,4)
  assert.equal(testingSummary.categories[0].testedAndPriced,2)
  assert.equal(testingSummary.categories[0].untestedBrokenFailed,1)
  assert.equal(testingSummary.categories[1].stockCull,3)
  await tapTally(testingCategory.id,tested,'+')
  await tapTally(testingCategory.id,tested,'-')
  assert.equal(await pendingCount(testingCategory.id,tested),'2')
  assert.equal((await browserApi('/api/electrical-testing/monthly-summary?month='+tallyMonth)).body.categories[0].testedAndPriced,2)
  await tapTally(testingCategory.id,tested,'+')
  await evaluate(`window.fetch=(...args)=>args[0]==='/api/electrical-testing/tallies'&&args[1]?.method==='POST'?Promise.resolve(new Response(JSON.stringify({detail:'Simulated tally conflict'}),{status:409,headers:{'Content-Type':'application/problem+json'}})):window.__realFetch(...args)`)
  await click('Save Tallies')
  await hasText('Conflict:')
  assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent==='Retry Save Tallies').disabled"),true)
  await click('Clear remaining session')
  await evaluate('window.fetch=window.__realFetch')
  await until(()=>evaluate("!document.querySelector('.quick-tally input').disabled"),'conflict totals refreshed')
  assert.equal(await pendingCount(testingCategory.id,tested),'2')
  // Saved reductions are date-scoped and a lost response must not reduce twice.
  await tapTally(testingCategories[1].id,cull,'-')
  assert.equal(await pendingCount(testingCategories[1].id,cull),'2')
  assert.equal(await evaluate("document.querySelector('input[type=month]').disabled"),true)
  assert.equal(await evaluate("document.querySelector('input[type=date]').disabled"),true)
  await evaluate(`window.__reductionLost=false;window.__reductions=[];window.fetch=async(...args)=>{const reducing=String(args[0]).endsWith('/reduction');if(reducing)window.__reductions.push(args[1].body);const response=await window.__realFetch(...args);if(reducing&&response.ok&&!window.__reductionLost){window.__reductionLost=true;throw new TypeError('Simulated lost reduction response')}return response}`)
  await click('Save Tallies');await enabled('Retry Save Tallies');await click('Retry Save Tallies')
  await hasText('Tallies saved successfully.')
  await until(()=>evaluate("!document.querySelector('.quick-tally input').disabled"),'reduction refreshed')
  assert.equal(await pendingCount(testingCategories[1].id,cull),'2')
  const reductions=await evaluate('window.__reductions');assert.equal(reductions.length,2);assert.equal(reductions[0],reductions[1])
  await evaluate('window.fetch=window.__realFetch')
  await tapTally(testingCategories[1].id,cull,'-');await tapTally(testingCategories[1].id,cull,'-')
  assert.equal(await pendingCount(testingCategories[1].id,cull),'0')
  assert.equal(await evaluate(`document.querySelector(${JSON.stringify(pendingCell(testingCategories[1].id,cull)+' button')}).disabled`),true)
  // Undo the unsaved reduction; historical records are unchanged until Save.
  await tapTally(testingCategories[1].id,cull,'+');await tapTally(testingCategories[1].id,cull,'+')
  // Cancelling an adjustment must not retain an old target across a fresh shared-account submission.
  assert.equal((await browserApi('/api/electrical-testing/tallies','POST',{submissionId:crypto.randomUUID(),recordDate:tallyToday,categoryId:testingCategories[1].id,tallyType:cull,count:1})).status,201)
  await click('Refresh counters')
  await until(()=>pendingCount(testingCategories[1].id,cull).then(value=>value==='3'),'cancelled edits do not override refreshed totals')
  assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent==='Save Tallies').disabled"),true)
  await tapTally(testingCategories[1].id,cull,'-');await click('Save Tallies');await hasText('Tallies saved successfully.')
  await until(()=>pendingCount(testingCategories[1].id,cull).then(value=>value==='2'),'shared-account fixture restored')
  await click('Monthly Sales Reference')
  await until(()=>evaluate("document.querySelectorAll('[data-reference-category]').length===15"),'references refreshed after session conflict')
  await click('Enter sales reference',testingScope3)
  await fill('Sold Last Month count','5')
  await click('Review entry')
  await enabled('Confirm and submit')
  assert.equal((await browserApi('/api/electrical-testing/sales-references','POST',{submissionId:crypto.randomUUID(),reportingMonth:'2002-01',categoryId:testingCategories[2].id,soldLastMonthCount:5})).status,201)
  await click('Confirm and submit')
  await hasText('Conflict:')
  assert.equal(await evaluate("[...document.querySelectorAll('button')].some(b=>b.textContent === 'Retry submission')"),false)
  await click('Reload counters')
  await until(()=>evaluate(`document.querySelector('${testingScope3} .sales-reference strong')?.textContent === '5'`),'reference conflict reconciled')
  await click('Enter sales reference',testingScope4)
  await fill('Sold Last Month count','0')
  await click('Review entry')
  await enabled('Confirm and submit')
  await evaluate(`window.__referencePayloads=[];window.__referenceLost=false;
    window.fetch=async(...args)=>{
      const submission=args[0]==='/api/electrical-testing/sales-references'&&args[1]?.method==='POST';
      if(submission)window.__referencePayloads.push(args[1].body);
      const response=await window.__realFetch(...args);
      if(submission&&response.ok&&!window.__referenceLost){window.__referenceLost=true;throw new TypeError('Simulated lost sales reference response')}
      return response;
    };`)
  await click('Confirm and submit')
  await enabled('Retry submission')
  await click('Retry submission')
  await until(()=>evaluate(`document.querySelector('${testingScope4} .sales-reference strong')?.textContent === '0'`),'reference retry resolved')
  const referencePayloads=await evaluate('window.__referencePayloads');assert.equal(referencePayloads.length,2);assert.equal(referencePayloads[0],referencePayloads[1])
  await evaluate('window.fetch=window.__realFetch')
  await screenshot('tablet-testing-counters')
  await click('Records')
  await enabled('#'+testingId)
  await click('#'+testingId)
  await hasText('This is an additive operational tally')
  assert.equal(await evaluate("[...document.querySelectorAll('button')].some(b=>b.textContent === 'Correct record')"),false)
  await click('Sign out')
  await enabled('Sign in')
  await fill('Username',process.env.INITIAL_ADMIN_USERNAME)
  await fill('Password',process.env.INITIAL_ADMIN_PASSWORD)
  await click('Sign in')
  await enabled('Records')
  // Admin monthly counters change only the selected historical day's contributions.
  const historicalTally=async(date,count)=>browserApi('/api/electrical-testing/tallies','POST',{submissionId:crypto.randomUUID(),recordDate:date,categoryId:testingCategory.id,tallyType:'STOCK_CULL',count})
  const earlierTally=(await historicalTally('2003-11-05',12)).body
  const selectedTally=(await historicalTally('2003-11-09',2)).body
  const deletedTally=(await historicalTally('2003-11-09',100)).body
  assert.equal((await browserApi('/api/electrical-testing/tallies/'+deletedTally.id,'DELETE')).status,204)
  await adminWorkflow('Electrical Testing')
  await fill('Tally month','2003-11')
  assert.equal(await evaluate("document.querySelector('label.tally-date input[type=date]').value"),'2003-11-01')
  await fill('Tally date','2003-11-09')
  await until(()=>pendingCount(testingCategory.id,cull).then(v=>v==='14'),'historical monthly total')
  assert.equal(await evaluate(`document.querySelector(${JSON.stringify(pendingCell(testingCategory.id,cull))}).nextElementSibling.textContent`),'09 Nov: 2')
  for(const width of [1280,1024,768]) {
    await cdp('Emulation.setDeviceMetricsOverride',{width,height:1024,deviceScaleFactor:1,mobile:false})
    await until(()=>evaluate('document.documentElement.scrollWidth<=window.innerWidth'),'monthly tally overflow '+width)
    await screenshot('electrical-testing-monthly-totals-'+width)
  }
  await tapTally(testingCategory.id,cull,'-');await tapTally(testingCategory.id,cull,'-')
  assert.equal(await pendingCount(testingCategory.id,cull),'12')
  assert.equal(await evaluate(`document.querySelector(${JSON.stringify(pendingCell(testingCategory.id,cull)+' button')}).disabled`),true)
  assert.equal((await browserApi('/api/electrical-testing/monthly-summary?month=2003-11')).body.categories[0].stockCull,14)
  await click('Save Tallies');await hasText('Tallies saved successfully.')
  await until(()=>evaluate("!document.querySelector('.quick-tally input').disabled"),'historical reduction refreshed')
  assert.equal(await pendingCount(testingCategory.id,cull),'12')
  assert.equal((await browserApi('/api/electrical-testing/tallies/'+earlierTally.id)).body.count,12)
  assert.equal((await browserApi('/api/electrical-testing/tallies/'+selectedTally.id)).status,404)
  await tapTally(testingCategory.id,cull,'+');await click('Save Tallies');await hasText('Tallies saved successfully.')
  await until(()=>pendingCount(testingCategory.id,cull).then(v=>v==='13'),'historical addition refreshed')
  await fill('Tally date','2003-11-05')
  await until(()=>evaluate(`document.querySelector(${JSON.stringify(pendingCell(testingCategory.id,cull))}).nextElementSibling.textContent==='05 Nov: 12'`),'selected date contribution refreshed')
  await tapTally(testingCategory.id,cull,'-');await click('Save Tallies');await hasText('Tallies saved successfully.')
  await until(()=>pendingCount(testingCategory.id,cull).then(v=>v==='12'),'only selected date reduced')
  await fill('Tally month','2003-10')
  await until(()=>evaluate("!document.querySelector('.quick-tally input').disabled"),'empty month loaded')
  assert.equal(await evaluate("[...document.querySelectorAll('.quick-tally input')].every(i=>i.value==='0')"),true)
  await fill('Tally month',tallyMonth)
  assert.equal(await evaluate("document.querySelector('label.tally-date input[type=date]').value"),tallyToday)
  assert.equal(await evaluate("document.querySelector('label.tally-date input[type=date]').max"),tallyToday)
  await fill('Tally date',tallyTomorrow)
  assert.equal(await evaluate("document.querySelector('label.tally-date input[type=date]').value"),tallyToday)
  for(const row of (await browserApi('/api/electrical-testing/tallies?from=2003-11-01&to=2003-11-30')).body)assert.equal((await browserApi('/api/electrical-testing/tallies/'+row.id,'DELETE')).status,204)
  await click('Records')
  await enabled('#'+testingId)
  await click('#'+testingId)
  await reopenForAdminActions()
  await enabled('Correct record')
  await reopenForAdminActions()
  await click('Correct record')
  await hasText('Processing / diversion tally')
  await fill('Record date','2002-02-01')
  await fill('Tally type','DIVERTED_TO_OLS')
  await fill('Tally quantity','5')
  await click('Review entry')
  await enabled('Confirm correction')
  await click('Confirm correction')
  await hasText('saved successfully.')
  assert.equal((await browserApi('/api/electrical-testing/monthly-summary?month='+tallyMonth)).body.categories[0].testedAndPriced,0)
  assert.equal((await browserApi('/api/electrical-testing/monthly-summary?month=2002-02')).body.divertedToOls,5)
  await adminWorkflow('Electrical Testing')
  await until(()=>evaluate("document.querySelectorAll('.testing-category').length === 15"),'Admin testing board')
  await fill('Tally date','2002-01-15')
  await click('Monthly Sales Reference')
  await fill('Reporting month','2002-01')
  await until(()=>evaluate(`document.querySelector('${testingScope} .sales-reference strong')?.textContent === '0'`),'Admin reference loaded')
  await click('Correct sales reference',testingScope)
  await fill('Sold Last Month count','11')
  await click('Review entry')
  await enabled('Confirm correction')
  await click('Confirm correction')
  await until(()=>evaluate(`document.querySelector('${testingScope} .sales-reference strong')?.textContent === '11'`),'reference replacement')
  let testingReference=(await browserApi('/api/electrical-testing/sales-references?month=2002-01')).body.find(r=>r.categoryId===testingCategory.id)
  await click('Correct sales reference',testingScope)
  assert.equal((await browserApi('/api/electrical-testing/sales-references/'+testingReference.id,'PUT',{submissionId:testingReference.submissionId,reportingMonth:'2002-01',categoryId:testingCategory.id,soldLastMonthCount:12,version:testingReference.version})).status,200)
  await click('Review entry')
  await hasText('This record has changed.')
  await click('Reload counters')
  await until(()=>evaluate(`document.querySelector('${testingScope} .sales-reference strong')?.textContent === '12'`),'stale reference reload')
  await click('Delete sales reference',testingScope)
  await evaluate(`window.__testingDeleteLost=false;window.fetch=async(...args)=>{const response=await window.__realFetch(...args);if(args[1]?.method==='DELETE'&&response.ok&&!window.__testingDeleteLost){window.__testingDeleteLost=true;throw new TypeError('Simulated lost reference delete')}return response};`)
  await click('Confirm reference deletion')
  await hasText('Simulated lost reference delete')
  await enabled('Confirm reference deletion')
  await click('Confirm reference deletion')
  await until(()=>evaluate(`document.querySelector('${testingScope} .sales-reference strong')?.textContent === 'Not entered'`),'deleted reference returns to missing')
  await evaluate('window.fetch=window.__realFetch')
  await click('Records')
  await enabled('#'+testingId)
  await click('#'+testingId)
  await reopenForAdminActions()
  await enabled('Delete record')
  await reopenForAdminActions()
  await click('Delete record')
  await click('Confirm deletion')
  await hasText('Record #'+testingId+' deleted.')
  assert.equal((await browserApi('/api/electrical-testing/monthly-summary?month=2002-02')).body.divertedToOls,0)
  // Dashboard V2 uses exact backend aggregates, with all fixture workflows in one selected month.
  const dashboardContainers=(await browserApi('/api/container-types?context=CRATE_PALLET')).body
  const dashboardPallet=dashboardContainers.find(c=>c.name==='Plastic Pallet').id
  const dashboardMaterials=(await browserApi('/api/material-categories?context=CRATE_PALLET')).body
  const dashboardIron=dashboardMaterials.find(c=>c.name==='Iron and Steel').id
  const dashMaterial=[{materialCategoryId:dashboardIron,percentage:'100.00'}]
  const dashDate='2009-06-15'
  assert.equal((await browserApi('/api/crate-pallet-records','POST',{submissionId:crypto.randomUUID(),recordDate:dashDate,containerTypeId:dashboardPallet,grossWeight:'106.00',materials:dashMaterial})).status,201)
  assert.equal((await browserApi('/api/collectable-records','POST',{submissionId:crypto.randomUUID(),recordDate:dashDate,grossWeight:'22.00',materials:dashMaterial})).status,201)
  const dashPickup=await browserApi('/api/pickup-records','POST',{submissionId:crypto.randomUUID(),recordDate:dashDate,pickupLocation:'Dashboard pickup',netWeight:'30.00',materials:dashMaterial})
  assert.equal(dashPickup.status,201)
  const dashboardElectrical=(await browserApi('/api/material-categories?context=ELECTRICAL_CRATE_PALLET')).body
  assert.equal((await browserApi('/api/electrical-crate-pallet-records','POST',{submissionId:crypto.randomUUID(),recordDate:dashDate,containerTypeId:dashboardPallet,grossWeight:'56.00',materials:dashboardElectrical.map(c=>({materialCategoryId:c.id,percentage:c.name==='Everything Else'?'100.00':'0.00'}))})).status,201)
  assert.equal((await browserApi('/api/salvage-records','POST',{submissionId:crypto.randomUUID(),recordDate:dashDate,crew:'Dashboard crew',up:{time:'10:00',kg:'10.00',materials:dashMaterial},down:{time:'11:00',kg:'40.00',materials:dashMaterial}})).status,201)
  assert.equal((await browserApi('/api/whitegoods-records','POST',{submissionId:crypto.randomUUID(),recordDate:dashDate,count:2})).status,201)
  const dashCategory=(await browserApi('/api/electrical-testing/categories')).body[0].id
  assert.equal((await browserApi('/api/electrical-testing/tallies','POST',{submissionId:crypto.randomUUID(),recordDate:dashDate,categoryId:dashCategory,tallyType:'TESTED_AND_PRICED',count:'3'})).status,201)
  assert.equal((await browserApi('/api/electrical-testing/tallies','POST',{submissionId:crypto.randomUUID(),recordDate:dashDate,categoryId:null,tallyType:'DIVERTED_TO_OLS',count:'2'})).status,201)
  assert.equal((await browserApi('/api/electrical-testing/sales-references','POST',{submissionId:crypto.randomUUID(),reportingMonth:'2009-06',categoryId:dashCategory,soldLastMonthCount:'50'})).status,201)
  await click('Overview')
  await hasText('Operational overview for')
  await fill('Overview month','2009-06')
  await hasText('Operational overview for June 2009')
  await until(()=>evaluate("document.querySelectorAll('canvas').length===6"),'six dashboard charts')
  assert.deepEqual(await evaluate("[...document.querySelectorAll('[data-chart]')].map(e=>e.dataset.chart)"),['trend','activity','salvage','testing','intake','recovered-trend'])
  assert.equal(await evaluate("document.querySelectorAll('[data-chart-values=recovered-trend] li').length"),6)
  assert.equal(await evaluate("document.querySelector('[data-chart-values=recovered-trend] li:last-child').dataset.value"),'40.00')
  assert.equal(await evaluate("document.querySelector('[data-chart=intake]').compareDocumentPosition(document.querySelector('.dashboard-recent')) & Node.DOCUMENT_POSITION_FOLLOWING"),4)
  const dashBackend=(await browserApi('/api/admin/overview?month=2009-06')).body
  const readTodayCards=()=>evaluate("[...document.querySelectorAll('[data-today-workflow]')].map(card=>({workflow:card.dataset.todayWorkflow,metrics:[...card.querySelectorAll('dl > div')].map(row=>({label:row.querySelector('dt').textContent,value:row.querySelector('dd').textContent}))}))")
  const expectedToday=dashBackend.todayActivity.map(card=>({workflow:card.workflow,metrics:card.metrics.map(metric=>({label:metric.label,value:metric.value+(metric.unit?' '+metric.unit:'')}))}))
  assert.equal(expectedToday.length,9);assert.deepEqual(await readTodayCards(),expectedToday)
  assert.equal(await evaluate("document.querySelector('.dashboard-secondary').compareDocumentPosition(document.querySelector('.dashboard-today')) & Node.DOCUMENT_POSITION_FOLLOWING"),4)
  assert.equal(await evaluate("document.querySelector('.dashboard-today').compareDocumentPosition(document.querySelector('[data-chart=trend]')) & Node.DOCUMENT_POSITION_FOLLOWING"),4)
  assert.equal(await evaluate("document.querySelectorAll('.dashboard-today input').length"),0)
  assert.deepEqual(dashBackend.todayActivity.find(card=>card.workflow==='ELECTRICAL_TESTING').metrics.map(metric=>metric.label),['Working','Untested / Broken / Failed'])
  await evaluate(`window.__todayFetch=window.fetch;window.fetch=async(...args)=>{const response=await window.__todayFetch(...args);if(String(args[0]).startsWith('/api/admin/overview?')){const body=await response.json();body.todayActivity=body.todayActivity.map(card=>({...card,metrics:card.metrics.map(metric=>({...metric,value:metric.unit==='kg'?'0.00':'0'}))}));return new Response(JSON.stringify(body),{status:200,headers:{'Content-Type':'application/json'}})}return response}`)
  await click('Refresh');await enabled('Refresh')
  assert.equal(await evaluate("[...document.querySelectorAll('.dashboard-today dd')].every(cell=>['0','0.00 kg'].includes(cell.textContent))"),true)
  assert.equal(await evaluate("document.querySelectorAll('[data-today-workflow]').length"),9)
  await evaluate('window.fetch=window.__todayFetch;delete window.__todayFetch')
  await click('Refresh');await enabled('Refresh');assert.deepEqual(await readTodayCards(),expectedToday)

  assert.equal(dashBackend.recordsThisMonth,8)
  assert.equal(await evaluate("document.querySelector('[data-kpi=records]').dataset.value"),'8')
  assert.equal(await evaluate("document.querySelector('[data-kpi=recovered]').dataset.value"),'40.00')
  assert.equal(await evaluate("document.querySelector('[data-kpi=landfill]').dataset.value"),'10.00')
  assert.equal(await evaluate("document.querySelector('[data-kpi=whitegoods]').dataset.value"),'2')
  assert.equal(await evaluate("document.querySelector('[data-chart-values=intake] [data-name=\"Iron and Steel\"]').dataset.value"),'190.00')
  assert.equal(await evaluate("document.querySelectorAll('[data-chart-values=intake] li').length"),1)
  assert.equal(await evaluate("[...document.querySelectorAll('[data-chart-values=testing] li')].find(l=>l.dataset.name==='Tested and Priced').dataset.value"),'3')
  assert.equal(await evaluate("[...document.querySelectorAll('[data-chart-values=testing] li')].find(l=>l.dataset.name==='Diverted to OLS').dataset.value"),'2')
  assert.equal(await evaluate("document.querySelectorAll('[data-chart-values=trend] li').length"),54)
  assert.equal(await evaluate("document.querySelectorAll('.dashboard-recent tbody tr').length"),5)
  assert.equal(await evaluate("document.querySelector('.dashboard details').open"),false)
  assert.deepEqual(await evaluate("[...document.querySelectorAll('[data-chart-values=activity] li')].map(l=>l.dataset.value)"),dashBackend.activity.map(a=>String(a.count)))
  await cdp('Emulation.setDeviceMetricsOverride',{width:1280,height:900,deviceScaleFactor:1,mobile:false})
  await until(()=>evaluate("document.documentElement.scrollWidth<=window.innerWidth"),'desktop dashboard overflow')
  await screenshot('desktop-dashboard-v2')
  for(const width of [768,1024]) {
    await cdp('Emulation.setDeviceMetricsOverride',{width,height:1024,deviceScaleFactor:1,mobile:false})
    await until(()=>evaluate("document.documentElement.scrollWidth<=window.innerWidth"),'dashboard overflow at '+width)
    await until(()=>evaluate("[...document.querySelectorAll('canvas')].every(c=>c.getBoundingClientRect().width>100)"),'chart resize at '+width)
    await until(()=>evaluate(`(() => {
      const intake=document.querySelector('[data-chart=intake]').getBoundingClientRect();
      const recovered=document.querySelector('[data-chart=recovered-trend]').getBoundingClientRect();
      return ${width}===768 ? recovered.top>=intake.bottom : Math.abs(intake.top-recovered.top)<2 && Math.abs(intake.width-recovered.width)<2;
    })()`),'paired/stacked new charts at '+width)
    await click('Settings')
    await until(()=>evaluate("document.querySelector('[role=menu]')?.getBoundingClientRect().right<=window.innerWidth"),'tablet menu fits')
    await screenshot('tablet-dashboard-v2-'+width)
    await evaluate("document.body.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true}))")
    await until(()=>evaluate("document.querySelector('[role=menu]')===null"),'outside tap closes menu')
  }
  await browserApi('/api/pickup-records/'+dashPickup.body.id,'DELETE')
  await click('Refresh')
  await until(()=>evaluate("document.querySelector('[data-kpi=records]')?.dataset.value==='7'"),'dashboard delete refresh')
  assert.equal(await evaluate("document.querySelector('[data-chart-values=intake] [data-name=\"Iron and Steel\"]').dataset.value"),'160.00')
  await fill('Overview month','2008-06')
  await until(()=>evaluate("document.querySelector('[data-kpi=records]')?.dataset.value==='0'"),'empty dashboard period')
  await until(()=>evaluate("document.querySelectorAll('.dashboard-empty').length===6"),'six clean empty charts')
  assert.equal(await evaluate("document.querySelectorAll('canvas').length"),0)
  assert.deepEqual(await readTodayCards(),expectedToday) // Today's values are independent of the selected dashboard month.
  await screenshot('tablet-dashboard-v2-empty')
  await click('View all records')
  await hasText('View submitted loads, correct entries')
  await click('Workflows')
  assert.equal(await evaluate("document.querySelectorAll('.workflow-card').length"),9)
  await screenshot('tablet-admin-workflows')
  // Exercise the card action as well as the global dropdown.
  await evaluate("document.querySelector('[data-workflow=CRATE_PALLET] button').click()")
  await hasText('Material percentages')
  await click('Records')
  console.log('PASS: Dashboard V2 KPI/chart values, six-month buckets, safe intake exclusions, separate testing counts, five recent submissions, delete refresh, clean empty states, responsive charts, keyboard/outside-close menu and workflow cards.')
  // Admin reporting: independent fixtures, applied filters, download buttons and monthly working aid.
  const reportCats = (await browserApi('/api/material-categories?context=PICKUP')).body
  const reportIron = reportCats.find(c => c.name === 'Iron and Steel').id
  const reportPickup = {submissionId:crypto.randomUUID(),recordDate:'2004-02-29',pickupLocation:'=Warehouse Cafe',netWeight:'12.34',materials:[{materialCategoryId:reportIron,percentage:'100.00'}]}
  const reportCreated = await browserApi('/api/pickup-records','POST',reportPickup)
  assert.equal(reportCreated.status,201)
  assert.equal((await browserApi('/api/pickup-records','POST',{...reportPickup,submissionId:crypto.randomUUID(),pickupLocation:'Other place',netWeight:'7.66'})).status,201)
  assert.equal((await browserApi('/api/whitegoods-records','POST',{submissionId:crypto.randomUUID(),recordDate:'2004-02-29',count:'3'})).status,201)
  await click('Overview')
  await hasText('Submitted today')
  await hasText('Recent submissions')
  assert.equal(await evaluate("[...document.querySelectorAll('nav button')].some(b => ['Users'].includes(b.textContent.trim()))"),false)
  await screenshot('tablet-admin-overview')
  await click('Reports')
  await hasText('Applied period:')
  await fill('From date','2004-02-29')
  await fill('Reporting period','daily')
  assert.equal(await evaluate("[...document.querySelectorAll('label')].find(l=>l.textContent.startsWith('To date')).querySelector('input').value"),'2004-02-29')
  await fill('Reporting period','weekly')
  assert.equal(await evaluate("[...document.querySelectorAll('label')].find(l=>l.textContent.startsWith('From date')).querySelector('input').value"),'2004-02-23')
  await fill('Reporting period','financial')
  assert.equal(await evaluate("[...document.querySelectorAll('label')].find(l=>l.textContent.startsWith('From date')).querySelector('input').value"),'2003-07-01')
  await fill('From date','2004-02-01')
  await fill('To date','2004-02-29')
  await fill('Report workflow','PICKUP')
  await fill('Pickup location contains','Warehouse')
  await hasText('Filters changed.')
  assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent==='Export CSV').disabled"),true)
  await click('Apply report filters')
  await hasText('12.34')
  await enabled('Export CSV')
  await evaluate(`(() => {
    window.reportDownloads=[];
    const original=window.fetch;
    window.fetch=async (...args)=>{
      const response=await original(...args);
      if(String(args[0]).includes('/export?')) {
        const clone=response.clone();
        window.reportDownloads.push({url:String(args[0]),status:response.status,filename:response.headers.get('Content-Disposition'),size:(await clone.arrayBuffer()).byteLength,text:response.headers.get('Content-Type')?.includes('csv')?await response.clone().text():null});
      }
      return response;
    };
  })()`)
  await click('Export CSV')
  await until(()=>evaluate('window.reportDownloads.length===1'),'CSV downloaded')
  let downloadResult=await evaluate('window.reportDownloads[0]')
  assert.equal(downloadResult.status,200)
  assert.match(downloadResult.text,/12.34/)
  assert.match(downloadResult.url,/location=Warehouse/)
  await enabled('Export Excel')
  await click('Export Excel')
  await until(()=>evaluate('window.reportDownloads.length===2'),'XLSX downloaded')
  downloadResult=await evaluate('window.reportDownloads[1]')
  assert.equal(downloadResult.status,200)
  assert.ok(downloadResult.size>1000)
  assert.match(downloadResult.filename,/report-summary-2004-02-01-to-2004-02-29.xlsx/)
  await fill('Report view','details')
  await hasText('=Warehouse Cafe')
  await screenshot('tablet-admin-reports')
  await click('#'+reportCreated.body.id)
  await hasText('Record #'+reportCreated.body.id)
  await click('Waste Levy Summary')
  await fill('Reporting month','2004-02')
  await hasText('20.00')
  await hasText('144.00')
  assert.equal(await evaluate(`document.querySelector('.notice').textContent`), 'Monthly working summary for manual entry into the existing Waste Levy workbook. Not a completed Waste Levy return, NRE reconciliation, or regulatory/certified report. E-waste Pickup and legacy E-waste reconciliation/carry-over calculations are excluded.')
  await fill('Summary units','t')
  await hasText('0.02000')
  await screenshot('tablet-monthly-waste-levy')
  await enabled('Export CSV')
  await click('Export CSV')
  await until(()=>evaluate('window.reportDownloads.length===3'),'monthly CSV downloaded')
  downloadResult=await evaluate('window.reportDownloads[2]')
  assert.match(downloadResult.filename,/waste-levy-summary-2004-02.csv/)
  assert.match(downloadResult.text,/GENERAL_INTAKE/)
  await enabled('Export Excel')
  await click('Export Excel')
  await until(()=>evaluate('window.reportDownloads.length===4'),'monthly XLSX downloaded')
  assert.match((await evaluate('window.reportDownloads[3]')).filename,/waste-levy-summary-2004-02.xlsx/)
  await browserApi('/api/pickup-records/'+reportCreated.body.id,'DELETE')
  await click('Refresh monthly figures')
  await hasText('0.00766')
  assert.equal(await evaluate('document.documentElement.scrollWidth <= window.innerWidth'),true)
  // Settings are Admin-only; configuration affects future entries while snapshots remain unchanged.
  const oldWhitegoods = await browserApi('/api/whitegoods-records','POST',{submissionId:crypto.randomUUID(),recordDate:'2007-02-10',count:2})
  assert.equal(oldWhitegoods.body.kgPerItem,'48.00')
  await openSettings('Container Types')
  await enabled('Add container type')
  assert.equal(await evaluate("[...document.querySelectorAll('label')].some(l=>l.textContent.includes('Timezone')&&l.querySelector('select,input'))"),false)
  await click('Add container type')
  const containerSettings='section[aria-label="Container Types"]'
  await fill('Waste Levy summary group','OTHER');await fill('Container name','Browser Settings Container',containerSettings)
  await fill('Tare weight','-1',containerSettings)
  assert.equal(await evaluate(`document.querySelector(${JSON.stringify(containerSettings)}+' input[type=number]').checkValidity()`),false)
  await fill('Tare weight','2.00',containerSettings)
  await evaluate(`document.querySelector(${JSON.stringify(containerSettings)}+' input[type=checkbox]').click()`)
  await click('Save container type',containerSettings)
  await hasText('Configuration saved. Historical records are unchanged.')
  let configuredContainer=(await browserApi('/api/admin/settings/container-types')).body.find(c=>c.name==='Browser Settings Container')
  assert.ok(configuredContainer)
  const settingsMaterial=(await browserApi('/api/material-categories?context=CRATE_PALLET')).body[0].id
  const historicalCrate=await browserApi('/api/crate-pallet-records','POST',{submissionId:crypto.randomUUID(),recordDate:'2007-02-10',containerTypeId:configuredContainer.id,grossWeight:'12.00',materials:[{materialCategoryId:settingsMaterial,percentage:'100.00'}]})
  assert.equal(historicalCrate.status,201)
  await click('Edit Browser Settings Container')
  await fill('Waste Levy summary group','OTHER');await fill('Container name','Renamed Browser Container',containerSettings)
  await fill('Tare weight','5.00',containerSettings)
  await fill('Status','inactive',containerSettings)
  await click('Save container type',containerSettings)
  await until(async()=>!(await browserApi('/api/container-types?context=CRATE_PALLET')).body.some(c=>c.id===configuredContainer.id),'container inactive for new entries')
  const historicalAfter=(await browserApi('/api/crate-pallet-records/'+historicalCrate.body.id)).body
  assert.equal(historicalAfter.containerTypeName,'Browser Settings Container')
  assert.equal(historicalAfter.tareWeight,'2.00')
  assert.equal(historicalAfter.netWeight,'10.00')
  await openSettings('Material Categories')
  await enabled('Add material category')
  await click('Add material category')
  const materialSettings='section[aria-label="Material Categories"]'
  await fill('Category name','Browser Settings Material',materialSettings)
  await evaluate(`document.querySelector(${JSON.stringify(materialSettings)}+' input[type=checkbox]').click()`)
  await click('Save material category',materialSettings)
  await enabled('Edit Browser Settings Material')
  await click('Edit Browser Settings Material')
  assert.equal(await evaluate(`document.querySelector(${JSON.stringify(materialSettings)}+' input[maxlength="100"]').readOnly`),true)
  await fill('Status','inactive',materialSettings)
  await click('Save material category',materialSettings)
  await until(async()=>!(await browserApi('/api/material-categories?context=CRATE_PALLET')).body.some(c=>c.name==='Browser Settings Material'),'category inactive')
  await openSettings('Organisation Details')
  await enabled('Save organisation details')
  await fill('Organisation name','Browser RWC')
  await fill('Site display name','Main depot')
  await fill('Report/export display name','=Browser Report')
  await click('Save organisation details')
  await until(()=>evaluate("document.querySelector('.brand strong').textContent==='Browser RWC'"),'organisation header updated')
  await openSettings('Reporting Defaults')
  await enabled('Save reporting defaults')
  // A concurrent settings save must still produce 409 and recover through section reload.
  const concurrentSettings=(await browserApi('/api/admin/settings')).body
  assert.equal((await browserApi('/api/admin/settings','PUT',concurrentSettings)).status,200)
  await click('Save reporting defaults')
  await hasText('Settings have changed. Reload before saving.')
  await click('Reload settings')
  await enabled('Save reporting defaults')
  await fill('Default display unit','tonnes')
  await fill('Default report period','CURRENT_FINANCIAL_YEAR')
  await fill('Financial year start month','4')
  await click('Save reporting defaults')
  await until(async()=>(await browserApi('/api/admin/settings')).body.financialYearStartMonth===4,'report defaults saved')
  await openSettings('Whitegoods Weight Factor')
  await enabled('Save whitegoods weight factor')
  await fill('Estimated kg per Whitegood','0')
  assert.equal(await evaluate("document.querySelector('section[aria-label=\"Whitegoods Weight Factor\"] input').checkValidity()"),false)
  await fill('Estimated kg per Whitegood','52.25')
  await click('Save whitegoods weight factor')
  await until(async()=>(await browserApi('/api/admin/settings')).body.whitegoodsKgPerItem==='52.25','new factor saved')
  for(const width of [1280,768,1024]) {
    await cdp('Emulation.setDeviceMetricsOverride',{width,height:1024,deviceScaleFactor:1,mobile:false})
    for(const label of ['Container Types','Material Categories','Organisation Details','Reporting Defaults','Whitegoods Weight Factor','User Management']) {
      await openSettings(label)
      if(label!=='User Management') await until(()=>evaluate("document.querySelectorAll('.settings-page section[aria-label]').length===1"),'Settings loaded at '+width)
      await until(()=>evaluate('document.documentElement.scrollWidth<=window.innerWidth'),'Settings overflow at '+width)
    }
    await click('Settings')
    await until(()=>evaluate("document.querySelector('[role=menu]')?.getBoundingClientRect().right<=window.innerWidth"),'Settings dropdown fits '+width)
    await screenshot('settings-menu-'+width)
    await evaluate("document.body.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true}))")
    await openSettings('Material Categories')
    await until(()=>evaluate("document.querySelector('.settings-page table')!==null"),'material Settings table')
    await screenshot('settings-materials-'+width)
  }
  await cdp('Page.navigate',{url:frontend+'/admin/settings/reporting'})
  await hasText('Reporting Defaults')
  await enabled('Save reporting defaults')

  await click('Reports')
  await hasText('Applied period:')
  const fyStart=await evaluate("[...document.querySelectorAll('label')].find(l=>l.textContent.startsWith('From date')).querySelector('input').value")
  assert.equal(fyStart.slice(5),'04-01')
  assert.equal(await evaluate("[...document.querySelectorAll('label')].find(l=>l.textContent.startsWith('Weight display')).querySelector('select').value"),'t')
  await adminWorkflow('Whitegoods')
  await fill('Record date','2007-02-10')
  await fill('Whitegoods count','2')
  await hasText('104.50 kg')
  await click('Review Whitegoods')
  await hasText('52.25 kg per whitegood')
  await enabled('Confirm and submit')
  await click('Confirm and submit')
  await hasText('saved successfully.')
  assert.equal(await evaluate("document.querySelector('.detail-panel .record-facts')!==null"),true)
  assert.equal(await evaluate("[...document.querySelectorAll('.detail-panel button')].some(b=>['Correct record','Delete record'].includes(b.textContent))"),false)
  const savedWhitegoodsDetails=await evaluate("document.querySelector('.detail-panel .record-facts').textContent")
  await reopenForAdminActions()
  assert.equal(await evaluate("document.querySelector('.detail-panel .record-facts').textContent"),savedWhitegoodsDetails)
  assert.equal((await browserApi('/api/whitegoods-records/'+oldWhitegoods.body.id)).body.kgPerItem,'48.00')
  const mixedMonth=(await browserApi('/api/whitegoods-records/monthly-summary?month=2007-02')).body
  assert.equal(mixedMonth.estimatedWeight,'200.50')
  assert.equal(mixedMonth.kgPerItem,null)
  // Dedicated E-waste collection is independent of internal electrical processing.
  await click('Sign out');await enabled('Sign in')
  await fill('Username',process.env.INITIAL_STAFF_USERNAME);await fill('Password',process.env.INITIAL_STAFF_PASSWORD);await click('Sign in');await enabled('Operations')
  await staffWorkflow('E-waste Pickup')
  await hasText('No active E-waste Pickup containers are configured.')
  await fill('Record date','2008-03-15');await fill('Organisation',' Next Band ');await fill('Entered weight','137.25')
  assert.equal(await evaluate("document.querySelector('.net-weight dd').textContent"),'137.25 kg')
  const beforeEwaste=(await browserApi('/api/ewaste-pickups')).body.length
  await click('Review record');await hasText('Review the calculated weights before confirming.')
  assert.equal((await browserApi('/api/ewaste-pickups')).body.length,beforeEwaste)
  await click('Back to edit');assert.equal(await evaluate("[...document.querySelectorAll('label')].find(l=>l.textContent.startsWith('Entered weight')).querySelector('input').value"),'137.25')
  await click('Review record');await enabled('Confirm and submit');await click('Confirm and submit');await hasText('saved successfully.')
  assert.equal(await evaluate("document.querySelector('.ewaste-review').textContent.includes('Next Band')"),true)
  assert.equal(await evaluate("[...document.querySelectorAll('button')].some(b=>b.textContent==='Correct record')"),false)
  await click('Sign out');await enabled('Sign in');await fill('Username',process.env.INITIAL_ADMIN_USERNAME);await fill('Password',process.env.INITIAL_ADMIN_PASSWORD);await click('Sign in');await enabled('Records')
  const ewasteRefs=(await browserApi('/api/admin/settings/container-types')).body
  const ewastePlastic=ewasteRefs.find(c=>c.name==='Plastic Pallet'),ewastePine=ewasteRefs.find(c=>c.name==='Pine Pallet')
  for(const c of [ewastePlastic,ewastePine])assert.equal((await browserApi('/api/admin/settings/container-types/'+c.id,'PUT',{summaryGroup:c.summaryGroup,version:c.version,name:c.name,tareWeight:c.tareWeight,active:true,contexts:[...new Set([...c.contexts,'EWASTE_PICKUP'])]})).status,200)
  await openSettings('Container Types');await enabled('Edit Plastic Pallet');await click('Edit Plastic Pallet')
  assert.equal(await evaluate("[...document.querySelectorAll('.settings-contexts label')].some(l=>l.textContent==='EWASTE PICKUP'&&l.querySelector('input').checked)"),true)
  await click('Cancel edit')
  await click('Sign out');await enabled('Sign in');await fill('Username',process.env.INITIAL_STAFF_USERNAME);await fill('Password',process.env.INITIAL_STAFF_PASSWORD);await click('Sign in');await enabled('Operations')
  await staffWorkflow('E-waste Pickup');await until(()=>evaluate("document.querySelector('form select')?.options.length>=3"),'configured E-waste containers')
  await fill('Record date','2008-03-15');await fill('Organisation','nExT b')
  await until(()=>evaluate("[...document.querySelectorAll('#ewaste-organisations option')].some(o=>o.value==='Next Band')"),'case-insensitive organisation suggestion')
  await fill('Organisation','Next Band');await fill('Entered weight','100.55')
  await fill('Add container type',String(ewastePlastic.id));await fill('Quantity: Plastic Pallet','2')
  await fill('Add container type',String(ewastePine.id));await fill('Quantity: Pine Pallet','3')
  await fill('Entered weight','66.00');assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent==='Review record').disabled"),true)
  await fill('Entered weight','100.55');await fill('Quantity: Plastic Pallet','0');assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent==='Review record').disabled"),true)
  await fill('Quantity: Plastic Pallet','2');assert.equal(await evaluate("document.querySelector('.net-weight dd').textContent"),'34.55 kg')
  for(const width of [1280,768,1024]){await cdp('Emulation.setDeviceMetricsOverride',{width,height:1024,deviceScaleFactor:1,mobile:false});await until(()=>evaluate('document.documentElement.scrollWidth<=window.innerWidth'),'E-waste overflow '+width);await screenshot('ewaste-pickup-entry-'+width)}
  await click('Review record');await hasText('Review the calculated weights before confirming.');await screenshot('ewaste-pickup-review')
  await click('Back to edit');assert.equal(await evaluate("document.querySelectorAll('.ewaste-container').length"),2)
  await click('Review record');await enabled('Confirm and submit')
  await evaluate(`window.__realFetch=window.fetch;window.__ewastePayloads=[];window.__ewasteLost=false;window.__ewasteGate=new Promise(r=>window.__releaseEwaste=r);window.fetch=async(...args)=>{const submission=args[0]==='/api/ewaste-pickups'&&args[1]?.method==='POST';if(submission){window.__ewastePayloads.push(args[1].body);await window.__ewasteGate}const response=await window.__realFetch(...args);if(submission&&response.ok&&!window.__ewasteLost){window.__ewasteLost=true;throw new TypeError('Simulated lost E-waste response')}return response};`)
  await click('Confirm and submit');await until(()=>evaluate('window.__ewastePayloads.length===1'),'E-waste request started')
  for(const label of ['Operations','Records','Sign out'])assert.equal(await evaluate(`[...document.querySelectorAll('button')].find(b=>b.textContent.trim()===${JSON.stringify(label)}).disabled`),true)
  await evaluate('window.__releaseEwaste()');await enabled('Retry submission');await click('Check Records');await resumeStaffSubmission();await enabled('Retry submission');await click('Retry submission');await hasText('saved successfully.')
  const ewastePayloads=await evaluate('window.__ewastePayloads');assert.equal(ewastePayloads.length,2);assert.equal(ewastePayloads[0],ewastePayloads[1]);await evaluate('window.fetch=window.__realFetch')
  const ewasteInput=JSON.parse(ewastePayloads[0]);const ewasteRows=(await browserApi('/api/ewaste-pickups')).body.filter(r=>r.submissionId===ewasteInput.submissionId);assert.equal(ewasteRows.length,1);const ewasteId=ewasteRows[0].id
  await click('Sign out');await enabled('Sign in');await fill('Username',process.env.INITIAL_ADMIN_USERNAME);await fill('Password',process.env.INITIAL_ADMIN_PASSWORD);await click('Sign in');await enabled('Records')
  const currentEwastePlastic=(await browserApi('/api/admin/settings/container-types')).body.find(c=>c.id===ewastePlastic.id)
  assert.equal((await browserApi('/api/admin/settings/container-types/'+ewastePlastic.id,'PUT',{summaryGroup:currentEwastePlastic.summaryGroup,version:currentEwastePlastic.version,name:'Renamed E-waste Pallet',tareWeight:'9.00',active:false,contexts:currentEwastePlastic.contexts})).status,200)
  const historicalEwaste=(await browserApi('/api/ewaste-pickups/'+ewasteId)).body
  assert.equal(historicalEwaste.netWeight,'34.55');assert.equal(historicalEwaste.containers.find(c=>c.containerTypeId===ewastePlastic.id).name,'Plastic Pallet')
  await click('Records');await fill('Workflow','EWASTE_PICKUP');await enabled('#'+ewasteId);await click('#'+ewasteId);await enabled('Correct record');await click('Correct record')
  await until(()=>evaluate("document.querySelectorAll('.ewaste-container').length===2"),'historical correction lines')
  await fill('Quantity: Plastic Pallet','3');await fill('Organisation','Next Band corrected');await click('Review record');await enabled('Confirm correction');await click('Confirm correction');await hasText('saved successfully.')
  const correctedEwaste=(await browserApi('/api/ewaste-pickups/'+ewasteId)).body;assert.equal(correctedEwaste.netWeight,'28.55');assert.equal(correctedEwaste.containers.find(c=>c.containerTypeId===ewastePlastic.id).tarePerUnit,'6.00')
  await reopenForAdminActions()
  await enabled('Correct record');await click('Correct record');await until(()=>evaluate("document.querySelectorAll('.ewaste-container').length===2"),'stale E-waste correction form')
  assert.equal((await browserApi('/api/ewaste-pickups/'+ewasteId,'PUT',{...ewasteInput,organisation:'Next Band corrected',containers:correctedEwaste.containers.map(c=>({containerTypeId:c.containerTypeId,quantity:c.quantity})),version:correctedEwaste.version})).status,200)
  await click('Review record');await hasText('This record has changed.');await click('Back to Records')
  await click('Reports');await hasText('Applied period:');await fill('Report workflow','EWASTE_PICKUP');await fill('From date','2008-03-01');await fill('To date','2008-03-31');await fill('Organisation contains','corrected');await click('Apply report filters');await fill('Weight display','kg');await hasText('28.55')
  assert.equal(await evaluate("[...document.querySelectorAll('label')].some(l=>l.textContent.startsWith('Material category'))"),false)
  await evaluate(`window.__ewasteExports=[];window.fetch=async(...args)=>{const r=await window.__realFetch(...args);if(String(args[0]).includes('/export?')){const copy=r.clone();window.__ewasteExports.push({status:r.status,url:String(args[0]),size:(await copy.arrayBuffer()).byteLength})}return r}`)
  await click('Export CSV');await until(()=>evaluate('window.__ewasteExports.length===1'),'E-waste CSV export');await click('Export Excel');await until(()=>evaluate('window.__ewasteExports.length===2'),'E-waste Excel export');assert.equal(await evaluate('window.__ewasteExports.every(r=>r.status===200&&r.url.includes("workflow=EWASTE_PICKUP")&&r.url.includes("location=corrected"))'),true);await evaluate('window.fetch=window.__realFetch')
  await click('Records');await fill('Workflow','EWASTE_PICKUP');await enabled('#'+ewasteId);await click('#'+ewasteId);await enabled('Delete record');await click('Delete record');await click('Confirm deletion');await hasText('Record #'+ewasteId+' deleted.')
  assert.equal((await browserApi('/api/ewaste-pickups/'+ewasteId)).status,404)
  assert.equal((await browserApi('/api/admin/reports/records?from=2008-03-01&to=2008-03-31&workflow=EWASTE_PICKUP&location=corrected')).body.totalRows,0)
  console.log('PASS: E-waste Pickup no-container and multi-quantity intake, organisation suggestions, exact tare, review/back, UUID retry/guards, historical inactive snapshots, Admin correction/stale/delete, separate filtered reports/exports and responsive layouts.')
  // Scrap is monthly additive weight recording, entirely separate from electrical intake/processing.
  await click('Sign out');await enabled('Sign in');await fill('Username',process.env.INITIAL_STAFF_USERNAME);await fill('Password',process.env.INITIAL_STAFF_PASSWORD);await click('Sign in');await enabled('Operations')
  await staffWorkflow('Scrap');await hasText('Monthly Scrap entry')
  assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent==='Review record').disabled"),true)
  const scrapSeed={submissionId:crypto.randomUUID(),reportingMonth:'2009-09',ironAndSteelWeight:'250',aluminiumWeight:'0',otherNonFerrousWeight:'75',mixedMetalsWeight:'10',ewasteWeight:'477'}
  assert.equal((await browserApi('/api/scrap-records','POST',scrapSeed)).status,201)
  await fill('Reporting month','2009-09');await fill('Iron and Steel','100');await fill('Other Non-Ferrous','25');await fill('E-waste','100')
  await fill('Aluminium','-1');assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent==='Review record').disabled"),true);await fill('Aluminium','0')
  const beforeScrap=(await browserApi('/api/scrap-records')).body.length
  for(const width of [1280,1024,768]){await cdp('Emulation.setDeviceMetricsOverride',{width,height:1024,deviceScaleFactor:1,mobile:false});await until(()=>evaluate('document.documentElement.scrollWidth<=window.innerWidth'),'Scrap entry overflow '+width);await screenshot('scrap-entry-'+width)}
  await click('Review record');await enabled('Confirm and submit');await hasText('1037.00 kg')
  const scrapReview=await evaluate("[...document.querySelectorAll('.scrap-review tbody tr')].map(r=>[...r.cells].map(c=>c.textContent))")
  assert.deepEqual(scrapReview[0],['Iron and Steel','250.00 kg','100.00 kg','350.00 kg']);assert.deepEqual(scrapReview[2],['Other Non-Ferrous','75.00 kg','25.00 kg','100.00 kg']);assert.deepEqual(scrapReview[4],['E-waste','477.00 kg','100.00 kg','577.00 kg'])
  assert.equal((await browserApi('/api/scrap-records')).body.length,beforeScrap)
  for(const width of [1280,1024,768]){await cdp('Emulation.setDeviceMetricsOverride',{width,height:1024,deviceScaleFactor:1,mobile:false});await until(()=>evaluate('document.documentElement.scrollWidth<=window.innerWidth'),'Scrap review overflow '+width);await screenshot('scrap-review-'+width)}
  await click('Back to edit');assert.equal(await evaluate("[...document.querySelectorAll('label')].find(l=>l.textContent.startsWith('Other Non-Ferrous')).querySelector('input').value"),'25');await click('Review record');await enabled('Confirm and submit')
  await evaluate(`window.__realFetch=window.fetch;window.__scrapPayloads=[];window.__scrapLost=false;window.__scrapGate=new Promise(r=>window.__releaseScrap=r);window.fetch=async(...args)=>{const submission=args[0]==='/api/scrap-records'&&args[1]?.method==='POST';if(submission){window.__scrapPayloads.push(args[1].body);await window.__scrapGate}const response=await window.__realFetch(...args);if(submission&&response.ok&&!window.__scrapLost){window.__scrapLost=true;throw new TypeError('Simulated lost Scrap response')}return response};`)
  await click('Confirm and submit');await until(()=>evaluate('window.__scrapPayloads.length===1'),'Scrap request started')
  for(const label of ['Operations','Records','Sign out'])assert.equal(await evaluate(`[...document.querySelectorAll('button')].find(b=>b.textContent.trim()===${JSON.stringify(label)}).disabled`),true)
  await evaluate('window.__releaseScrap()');await enabled('Retry submission');await click('Check Records');await resumeStaffSubmission();await enabled('Retry submission');await click('Retry submission');await hasText('saved successfully.')
  const scrapPayloads=await evaluate('window.__scrapPayloads');assert.equal(scrapPayloads.length,2);assert.equal(scrapPayloads[0],scrapPayloads[1]);await evaluate('window.fetch=window.__realFetch')
  const scrapInput=JSON.parse(scrapPayloads[0]),scrapRows=(await browserApi('/api/scrap-records')).body.filter(r=>r.submissionId===scrapInput.submissionId);assert.equal(scrapRows.length,1);const scrapId=scrapRows[0].id
  assert.equal((await browserApi('/api/scrap-records/monthly-summary?month=2009-09')).body.totalWeight,'1037.00')
  assert.equal(await evaluate("[...document.querySelectorAll('button')].some(b=>b.textContent==='Correct record'||b.textContent==='Delete record')"),false)
  await click('Sign out');await enabled('Sign in');await fill('Username',process.env.INITIAL_ADMIN_USERNAME);await fill('Password',process.env.INITIAL_ADMIN_PASSWORD);await click('Sign in');await enabled('Records')
  await click('Records');await fill('Workflow','SCRAP');await enabled('#'+scrapId);await click('#'+scrapId);await enabled('Correct record');await click('Correct record');await hasText('Monthly Scrap entry')
  await fill('Iron and Steel','125');await click('Review record');await enabled('Confirm correction');await hasText('1062.00 kg');assert.equal(await evaluate("document.querySelector('.scrap-review tbody tr td:nth-child(2)').textContent"),'250.00 kg')
  await click('Back to edit');await fill('Reporting month','2009-10');await click('Review record');await enabled('Confirm correction');await hasText('250.00 kg');await click('Confirm correction');await hasText('saved successfully.')
  assert.equal((await browserApi('/api/scrap-records/monthly-summary?month=2009-09')).body.totalWeight,'812.00');assert.equal((await browserApi('/api/scrap-records/monthly-summary?month=2009-10')).body.totalWeight,'250.00')
  await click('Reports');await hasText('Applied period:');await fill('Report workflow','SCRAP');await fill('From date','2009-10-20');await fill('To date','2009-10-21');await click('Apply report filters');await fill('Weight display','kg');await hasText('250.00')
  assert.equal(await evaluate("[...document.querySelectorAll('label')].some(l=>l.textContent.startsWith('Material category'))"),false)
  await evaluate(`window.__scrapExports=[];window.fetch=async(...args)=>{const r=await window.__realFetch(...args);if(String(args[0]).includes('/export?'))window.__scrapExports.push({status:r.status,url:String(args[0])});return r}`)
  await enabled('Export CSV');await click('Export CSV');await until(()=>evaluate('window.__scrapExports.length===1'),'Scrap CSV export');await enabled('Export Excel');await click('Export Excel');await until(()=>evaluate('window.__scrapExports.length===2'),'Scrap XLSX export');assert.equal(await evaluate('window.__scrapExports.every(r=>r.status===200&&r.url.includes("workflow=SCRAP"))'),true);await evaluate('window.fetch=window.__realFetch')
  await click('Waste Levy Summary');await hasText('Monthly Waste Levy Summary');await fill('Reporting month','2009-10');await until(()=>evaluate("[...document.querySelectorAll('.report-section h2')].some(h=>h.textContent==='SCRAP')"),'Scrap monthly summary loaded');await fill('Summary units','kg');await hasText('250.00');assert.equal(await evaluate("[...document.querySelectorAll('.report-section')].some(s=>s.querySelector('h2')?.textContent==='SCRAP'&&s.textContent.includes('Other Non-Ferrous'))"),true)
  await click('Records');await fill('Workflow','SCRAP');await enabled('#'+scrapId);await click('#'+scrapId);await enabled('Delete record');await click('Delete record');await click('Confirm deletion');await hasText('Record #'+scrapId+' deleted.');assert.equal((await browserApi('/api/scrap-records/monthly-summary?month=2009-10')).body.totalWeight,'0.00')
  console.log('PASS: Scrap five-field additive monthly weights, current/entry/projected review, no preview persistence, validation, retained UUID retry/guards, Staff read-only, Admin correction/month move/delete, independent filtered reports/exports, Waste Levy Summary and desktop/tablet layouts.')
  // Server-side paging: one combined dataset, stable filters, full exports, retained correction page.
  const pageFixtures=await Promise.all(Array.from({length:95},(_,i)=>browserApi('/api/whitegoods-records','POST',{submissionId:crypto.randomUUID(),recordDate:'2011-01-10',count:i+1})))
  assert.equal(pageFixtures.every(r=>r.status===201),true)
  for(let i=0;i<5;i++)assert.equal((await browserApi('/api/ewaste-pickups','POST',{submissionId:crypto.randomUUID(),recordDate:'2011-01-20',organisation:'Pagination source '+i,enteredWeight:'10.25',containers:[]})).status,201)
  const pageScrap=(await browserApi('/api/scrap-records','POST',{submissionId:crypto.randomUUID(),reportingMonth:'2011-01',ironAndSteelWeight:'1',aluminiumWeight:'0',otherNonFerrousWeight:'0',mixedMetalsWeight:'0',ewasteWeight:'0'})).body
  const recordsPager='nav[aria-label="Records pagination"]',reportsPager='nav[aria-label="Report details pagination"]'
  await click('Records');await fill('Workflow','');await fill('From','2011-01-01');await fill('To','2011-01-31');await enabled('Apply dates / refresh');await click('Apply dates / refresh')
  await hasText('Showing 1-10 of 101')
  assert.equal(await evaluate("document.querySelector('#records-heading').closest('section').querySelectorAll('.table-scroll tbody tr').length"),10)
  assert.equal(await evaluate("new Set([...document.querySelectorAll('#records-heading')][0].closest('section').querySelector('table').querySelectorAll('tbody tr')?[...document.querySelector('#records-heading').closest('section').querySelectorAll('table tbody tr')].map(r=>r.cells[2].textContent):[]).size"),2)
  assert.equal(await evaluate(`document.querySelector('${recordsPager} button').disabled`),true)
  assert.equal(await evaluate(`document.querySelector('${recordsPager} [aria-current="page"]').textContent`),'1')
  assert.equal(await evaluate(`document.querySelectorAll('${recordsPager} button').length<=7&&document.querySelector('${recordsPager}').textContent.includes('...')`),true)
  const firstPageKeys=await evaluate("[...document.querySelector('#records-heading').closest('section').querySelectorAll('table tbody tr')].map(r=>r.cells[2].textContent+r.cells[0].textContent)")
  await click('Next',recordsPager);await hasText('Showing 11-20 of 101')
  const secondPageKeys=await evaluate("[...document.querySelector('#records-heading').closest('section').querySelectorAll('table tbody tr')].map(r=>r.cells[2].textContent+r.cells[0].textContent)");assert.equal(secondPageKeys.some(k=>firstPageKeys.includes(k)),false)
  await fill('Rows per page','25',recordsPager);await hasText('Showing 1-25 of 101');await fill('Rows per page','50',recordsPager);await hasText('Showing 1-50 of 101');await fill('Rows per page','10',recordsPager);await hasText('Showing 1-10 of 101')
  await enabled('Apply dates / refresh')
  for(const width of [1280,1024,768]){await cdp('Emulation.setDeviceMetricsOverride',{width,height:1024,deviceScaleFactor:1,mobile:false});await until(()=>evaluate('document.documentElement.scrollWidth<=window.innerWidth'),'Records pagination overflow '+width);await screenshot('records-pagination-'+width)}
  await click('11',recordsPager);await hasText('Showing 101-101 of 101');await enabled('#'+pageScrap.id);await click('#'+pageScrap.id);await enabled('Delete record');await click('Delete record');await click('Confirm deletion');await hasText('Showing 91-100 of 100')
  assert.equal(await evaluate(`document.querySelector('${recordsPager} [aria-current="page"]').textContent`),'10')
  const correctPagingId=await evaluate("Number(document.querySelector('#records-heading').closest('section').querySelector('table tbody button').textContent.slice(1))")
  await enabled('#'+correctPagingId);await click('#'+correctPagingId);await enabled('Correct record');await click('Correct record');await fill('Whitegoods count','2');await enabled('Review Whitegoods');await click('Review Whitegoods');await enabled('Confirm correction');await click('Confirm correction');await hasText('saved successfully.');await hasText('Showing 91-100 of 100')
  assert.equal(await evaluate(`document.querySelector('${recordsPager} [aria-current="page"]').textContent`),'10')
  await reopenForAdminActions()
  await enabled('Correct record');await click('Correct record');await fill('Record date','2011-02-10');await enabled('Review Whitegoods');await click('Review Whitegoods');await enabled('Confirm correction');await click('Confirm correction');await hasText('Showing 91-99 of 99')
  await fill('Workflow','WHITEGOODS');await hasText('Showing 1-10 of 94')
  await fill('From','2012-01-01');await fill('To','2012-01-31');await enabled('Apply dates / refresh');await click('Apply dates / refresh');await hasText('No records found.');await hasText('Showing 0-0 of 0')
  assert.equal(await evaluate(`[...document.querySelectorAll('${recordsPager} button')].every(b=>b.disabled)`),true)
  await click('Reports');await hasText('Applied period:');await fill('Report workflow','WHITEGOODS');await fill('From date','2011-01-01');await fill('To date','2011-01-31');await click('Apply report filters');await fill('Report view','details');await hasText('Showing 1-10 of 94')
  await click('Next',reportsPager);await hasText('Showing 11-20 of 94');await fill('Rows per page','25',reportsPager);await hasText('Showing 1-25 of 94');await fill('Rows per page','50',reportsPager);await hasText('Showing 1-50 of 94')
  await fill('Rows per page','10',reportsPager);await hasText('Showing 1-10 of 94')
  for(const width of [1280,1024,768]){await cdp('Emulation.setDeviceMetricsOverride',{width,height:1024,deviceScaleFactor:1,mobile:false});await until(()=>evaluate('document.documentElement.scrollWidth<=window.innerWidth'),'Report pagination overflow '+width);await screenshot('report-pagination-'+width)}
  await click('Next',reportsPager);await hasText('Showing 11-20 of 94')
  const reportPagingId=await evaluate("Number(document.querySelector('.report-section table tbody button').textContent.slice(1))")
  await click('#'+reportPagingId,'.report-section');await enabled('Correct record');await click('Correct record');await fill('Whitegoods count','3');await enabled('Review Whitegoods');await click('Review Whitegoods');await enabled('Confirm correction');await click('Confirm correction');await hasText('saved successfully.')
  await click('Reports');await hasText('Showing 11-20 of 94');assert.equal(await evaluate(`[...document.querySelectorAll('${reportsPager} select')][0].value`),'10')
  await evaluate(`window.__pageDownloads=[];window.__pageFetch=window.fetch;window.fetch=async(...args)=>{const response=await window.__pageFetch(...args);if(String(args[0]).includes('/reports/export?'))window.__pageDownloads.push({url:String(args[0]),status:response.status,text:String(args[0]).includes('format=csv')?await response.clone().text():null});return response}`)
  await enabled('Export CSV');await click('Export CSV');await until(()=>evaluate('window.__pageDownloads.length===1'),'paged CSV export');await enabled('Export Excel');await click('Export Excel');await until(()=>evaluate('window.__pageDownloads.length===2'),'paged XLSX export')
  const pageDownloads=await evaluate('window.__pageDownloads');assert.equal(pageDownloads[0].text.trim().split('\n').length,95);assert.equal(pageDownloads.every(r=>r.status===200&&!r.url.includes('&page=')&&!r.url.includes('&size=')),true);await evaluate('window.fetch=window.__pageFetch')
  await fill('From date','2012-01-01');await fill('To date','2012-01-31');await click('Apply report filters');await hasText('Showing 0-0 of 0');await hasText('No matching records.')
  console.log('PASS: server-side unified Records and detailed Reports paging, 10/25/50 sizes, compact controls, global ordering, filter reset, empty states, correction-page retention, deleted-last-page recovery, all-filtered-row exports and desktop/tablet overflow.')
  // Aggregate paging: server-side report summary, independent monthly sections, full exports.
  const aggregateMaterials=(await browserApi('/api/material-categories?context=PICKUP')).body
  const aggregateBreakdown=aggregateMaterials.map((c,i)=>({materialCategoryId:c.id,percentage:String(i===aggregateMaterials.length-1?101-aggregateMaterials.length:1)}))
  assert.equal((await browserApi('/api/pickup-records','POST',{submissionId:crypto.randomUUID(),recordDate:'2013-03-10',pickupLocation:'Aggregate paging',netWeight:'100',materials:aggregateBreakdown})).status,201)
  const aggregateElectrical=(await browserApi('/api/material-categories?context=ELECTRICAL_CRATE_PALLET')).body
  for(let i=0;i<3;i++){
    const container=await browserApi('/api/admin/settings/container-types','POST',{name:'Aggregate container '+i,summaryGroup:'OTHER',tareWeight:'1.00',active:true,contexts:['ELECTRICAL_CRATE_PALLET']});assert.equal(container.status,200)
    assert.equal((await browserApi('/api/electrical-crate-pallet-records','POST',{submissionId:crypto.randomUUID(),recordDate:'2013-03-10',containerTypeId:container.body.id,grossWeight:'101',materials:aggregateElectrical.map(c=>({materialCategoryId:c.id,percentage:'50'}))})).status,201)
  }
  const summaryFilter='/api/admin/reports/summary?from=2013-03-01&to=2013-03-31',summaryAll=(await browserApi(summaryFilter+'&size=50')).body
  assert.ok(summaryAll.totalItems>10);assert.ok(summaryAll.totalItems<=50)
  await click('Reports');await hasText('Applied period:');await fill('Report workflow','');await fill('From date','2013-03-01');await fill('To date','2013-03-31');await click('Apply report filters');await fill('Report view','summary')
  const summaryPager='nav[aria-label="Report summary pagination"]'
  await hasText('Showing 1-10 of '+summaryAll.totalItems);assert.equal(await evaluate("document.querySelector('.report-section tbody').rows.length"),10)
  const summaryFirst=await evaluate("document.querySelector('.report-section tbody').textContent")
  await click('Next',summaryPager);await hasText('Showing 11-20 of '+summaryAll.totalItems);assert.notEqual(await evaluate("document.querySelector('.report-section tbody').textContent"),summaryFirst)
  await fill('Rows per page','25',summaryPager);await hasText('Showing 1-25 of '+summaryAll.totalItems);await fill('Rows per page','50',summaryPager);await enabled('Export CSV')
  assert.equal(await evaluate("document.querySelector('.report-section tbody').rows.length"),summaryAll.totalItems)
  await fill('Rows per page','10',summaryPager);await hasText('Showing 1-10 of '+summaryAll.totalItems);await click('Next',summaryPager);await hasText('Showing 11-20 of '+summaryAll.totalItems);await enabled('Export CSV')
  await evaluate(`window.__aggregateDownloads=[];window.__aggregateFetch=window.fetch;window.fetch=async(...args)=>{const r=await window.__aggregateFetch(...args);if(String(args[0]).includes('/export?'))window.__aggregateDownloads.push({url:String(args[0]),status:r.status,text:String(args[0]).includes('format=csv')?await r.clone().text():null});return r}`)
  await click('Export CSV');await until(()=>evaluate('window.__aggregateDownloads.length===1'),'summary full CSV');await enabled('Export Excel');await click('Export Excel');await until(()=>evaluate('window.__aggregateDownloads.length===2'),'summary full XLSX')
  assert.equal((await evaluate('window.__aggregateDownloads[0].text')).trim().split('\n').length,summaryAll.totalItems+1)
  assert.equal(await evaluate('window.__aggregateDownloads.every(r=>r.status===200&&!r.url.includes("&page="))'),true)
  for(const width of [1280,1024,768]){await cdp('Emulation.setDeviceMetricsOverride',{width,height:1024,deviceScaleFactor:1,mobile:false});await until(()=>evaluate('document.documentElement.scrollWidth<=window.innerWidth'),'Summary pagination overflow '+width);await screenshot('report-summary-pagination-'+width)}
  await fill('Report workflow','PICKUP');await click('Apply report filters');await until(()=>evaluate(`document.querySelector('${summaryPager} [aria-current="page"]')?.textContent==='1'`),'summary filter resets page')
  await fill('Pickup location contains','no matching aggregate source');await click('Apply report filters');await hasText('No records for this period.');assert.equal(await evaluate(`document.querySelector('${summaryPager}')===null`),true)
  // Monthly worksheet: presentation-only matrices over the complete existing response.
  const worksheetIron=aggregateMaterials.find(c=>c.name==='Iron and Steel').id
  const worksheetAluminium=aggregateMaterials.find(c=>c.name==='Aluminium').id
  const worksheetContainer=await browserApi('/api/admin/settings/container-types','POST',{summaryGroup:'OTHER',name:'Worksheet intake container',tareWeight:'1.00',active:true,contexts:['CRATE_PALLET']})
  assert.equal(worksheetContainer.status,200)
  assert.equal((await browserApi('/api/crate-pallet-records','POST',{submissionId:crypto.randomUUID(),recordDate:'2013-03-10',containerTypeId:worksheetContainer.body.id,grossWeight:'11.01',materials:[{materialCategoryId:worksheetIron,percentage:'33.33'},{materialCategoryId:worksheetAluminium,percentage:'66.67'}]})).status,201)
  assert.equal((await browserApi('/api/collectable-records','POST',{submissionId:crypto.randomUUID(),recordDate:'2013-03-10',grossWeight:'4.00',materials:[{materialCategoryId:worksheetIron,percentage:'100'}]})).status,201)
  assert.equal((await browserApi('/api/salvage-records','POST',{submissionId:crypto.randomUUID(),recordDate:'2013-03-10',crew:'Worksheet crew',up:{time:'09:00',kg:'10.00',materials:[{materialCategoryId:worksheetIron,percentage:'100'}]},down:{time:'10:00',kg:'0.01',materials:[{materialCategoryId:worksheetIron,percentage:'50'},{materialCategoryId:worksheetAluminium,percentage:'50'}]}})).status,201)
  assert.equal((await browserApi('/api/scrap-records','POST',{submissionId:crypto.randomUUID(),reportingMonth:'2013-03',ironAndSteelWeight:'1.11',aluminiumWeight:'2.22',otherNonFerrousWeight:'3.33',mixedMetalsWeight:'4.44',ewasteWeight:'5.55'})).status,201)
  const worksheetWhitegoods=(await browserApi('/api/whitegoods-records','POST',{submissionId:crypto.randomUUID(),recordDate:'2013-03-10',count:2})).body
  const worksheetEwaste=(await browserApi('/api/ewaste-pickups','POST',{submissionId:crypto.randomUUID(),recordDate:'2013-03-10',organisation:'Worksheet excluded intake',enteredWeight:'12345.67',containers:[]}));assert.equal(worksheetEwaste.status,201)
  const worksheetDeleted=(await browserApi('/api/pickup-records','POST',{submissionId:crypto.randomUUID(),recordDate:'2013-03-10',pickupLocation:'Deleted worksheet fixture',netWeight:'999.00',materials:[{materialCategoryId:worksheetIron,percentage:'100'}]})).body
  assert.equal((await browserApi('/api/pickup-records/'+worksheetDeleted.id,'DELETE')).status,204)
  // All approved groups plus an unknown container; names are matched exactly, not guessed.
  const worksheetNames=['Apple Crate','Stillage Cage','Plastic Pallet','Pine Pallet','Hardwood Pallet']
  for(const [index,name] of worksheetNames.entries()) {
    let container=(await browserApi('/api/admin/settings/container-types')).body.find(c=>c.name===name)
    if(!container) container=(await browserApi('/api/admin/settings/container-types','POST',{name,summaryGroup:index<2?'CRATE':'PALLET',tareWeight:'1.00',active:true,contexts:['CRATE_PALLET']})).body
    else container=(await browserApi('/api/admin/settings/container-types/'+container.id,'PUT',{summaryGroup:index<2?'CRATE':'PALLET',version:container.version,name:container.name,tareWeight:container.tareWeight,active:true,contexts:[...new Set([...container.contexts,'CRATE_PALLET'])]})).body
    const gross=(BigInt(container.tareWeight.replace('.',''))+BigInt(index+1)*100n)
    const payload={submissionId:crypto.randomUUID(),recordDate:'2013-03-10',containerTypeId:container.id,grossWeight:(gross/100n)+'.'+(gross%100n).toString().padStart(2,'0'),materials:[{materialCategoryId:worksheetIron,percentage:'100'}]}
    assert.equal((await browserApi('/api/crate-pallet-records','POST',payload)).status,201)
    if(index===0) assert.equal((await browserApi('/api/crate-pallet-records','POST',{...payload,submissionId:crypto.randomUUID(),recordDate:'2013-05-10'})).status,201)
    assert.equal((await browserApi('/api/admin/settings/container-types/'+container.id,'PUT',{version:container.version,name:container.name,tareWeight:container.tareWeight,active:container.active,contexts:container.contexts,summaryGroup:'OTHER'})).status,200) // Existing monthly rows must retain their submitted group.
  }
  const worksheetBefore=(await browserApi('/api/admin/waste-levy-summary?month=2013-03')).body
  assert.equal(worksheetBefore.totals.some(row=>row.workflow==='EWASTE_PICKUP'),false)
  await click('Waste Levy Summary');await fill('Reporting month','2013-03');await enabled('Export CSV');await fill('Summary units','kg')
  const levySection=id=>'[data-levy-section="'+id+'"]'
  const levyRows=id=>evaluate(`[...document.querySelectorAll('${levySection(id)} tbody tr')].map(row=>[...row.cells].map(cell=>cell.textContent.trim()))`)
  const levyFooter=id=>evaluate(`[...document.querySelectorAll('${levySection(id)} tfoot tr')].map(row=>[...row.cells].map(cell=>cell.textContent.trim()))`)
  const levyFacts=id=>evaluate(`Object.fromEntries([...document.querySelectorAll('${levySection(id)} dl > div')].map(row=>[row.querySelector('dt').textContent,row.querySelector('dd').textContent]))`)
  assert.equal(await evaluate("document.querySelectorAll('.levy-worksheet .pagination').length"),0)
  assert.equal(await evaluate("[...document.querySelectorAll('.levy-worksheet thead th')].some(cell=>['Workflow / direction','Metric','Unit'].includes(cell.textContent))"),false)
  const downRows=await levyRows('owf');assert.equal(downRows.length,14)
  assert.deepEqual(downRows.find(row=>row[0]==='Iron and Steel'),['Iron and Steel','0.01'])
  assert.deepEqual(downRows.find(row=>row[0]==='Aluminium'),['Aluminium','0.01'])
  assert.equal(downRows.some(row=>/records|net weight/i.test(row[0])),false)
  assert.deepEqual(await levyFacts('owf'),{'Total weight':'0.01 kg',Records:'1'}) // measured total, not rounded breakdown sum
  assert.deepEqual((await levyRows('rtl')).find(row=>row[0]==='Iron and Steel'),['Iron and Steel','10.00'])
  assert.deepEqual(await levyFacts('rtl'),{'Total weight':'10.00 kg',Records:'1'})
  assert.deepEqual(await evaluate(`[...document.querySelectorAll('${levySection('msw')} thead th')].map(cell=>cell.textContent)`),['Category','Crates','Pallets','Other containers','Collectables','Pickup','Total'])
  const matrixRows=await levyRows('msw')
  assert.ok(matrixRows.length>10)
  assert.deepEqual(matrixRows.find(row=>row[0]==='Iron and Steel'),['Iron and Steel','3.00','12.00','3.34','2.00','1.00','21.34'])
  assert.deepEqual(matrixRows.find(row=>row[0]==='Aluminium'),['Aluminium','0.00','0.00','6.67','0.00','1.00','7.67'])
  const worksheetCents=value=>BigInt(value.replace('.',''))
  for(const row of matrixRows)assert.equal(worksheetCents(row.at(-1)),row.slice(1,-1).reduce((total,value)=>total+worksheetCents(value),0n))
  assert.deepEqual(await levyFooter('msw'),[['Total weight','3.00','12.00','10.01','2.00','100.00','127.01']])
  assert.deepEqual((await levyRows('combined')).find(row=>row[0]==='Iron and Steel'),['Iron and Steel','21.34'])
  assert.equal((await levyRows('combined')).every(row=>row.length===2),true)
  assert.deepEqual(await levyFooter('combined'),[['Total weight','127.01']])
  assert.deepEqual(await evaluate(` [...document.querySelectorAll('${levySection('electrical')} thead th')].map(cell=>cell.textContent)`),['Container','TVs / Monitors / Projectors','Everything Else','Total'])
  assert.deepEqual(await levyRows('electrical'),[0,1,2].map(i=>['Aggregate container '+i,'50.00','50.00','100.00']))
  assert.deepEqual(await levyFooter('electrical'),[['Total weight','150.00','150.00','300.00']])
  assert.deepEqual(await levyRows('scrap'),[['Iron and Steel','1.11'],['Aluminium','2.22'],['Other Non-Ferrous Metals','3.33'],['Mixed Metals','4.44'],['E-waste','5.55']])
  assert.deepEqual(await levyFooter('scrap'),[['Total weight','16.65']])
  assert.deepEqual(await levyFacts('whitegoods'),{'Whitegoods count':'2','Estimated weight':worksheetWhitegoods.estimatedWeight+' kg','Applied factor':worksheetWhitegoods.kgPerItem+' kg/item'})
  await fill('Summary units','t')
  assert.deepEqual((await levyRows('msw')).find(row=>row[0]==='Iron and Steel'),['Iron and Steel','0.00300','0.01200','0.00334','0.00200','0.00100','0.02134'])
  assert.equal((await levyFacts('owf'))['Total weight'],'0.00001 tonnes')
  await fill('Summary units','kg')
  const fullMonthly=worksheetBefore.totals.length
  await click('Export CSV');await until(()=>evaluate('window.__aggregateDownloads.length===3'),'monthly full CSV');await enabled('Export Excel');await click('Export Excel');await until(()=>evaluate('window.__aggregateDownloads.length===4'),'monthly full XLSX')
  assert.equal((await evaluate('window.__aggregateDownloads[2].text')).trim().split('\n').length,fullMonthly+1)
  assert.equal(await evaluate('window.__aggregateDownloads.slice(2).every(row=>row.status===200&&!row.url.includes("page="))'),true)
  for(const width of [1280,1024,768]){await cdp('Emulation.setDeviceMetricsOverride',{width,height:1024,deviceScaleFactor:1,mobile:false});await until(()=>evaluate('document.documentElement.scrollWidth<=window.innerWidth'),'Monthly worksheet overflow '+width);await screenshot('waste-levy-worksheet-'+width)}
  await fill('Reporting month','2013-05');await enabled('Export CSV')
  assert.deepEqual(await evaluate(`[...document.querySelectorAll('${levySection('msw')} thead th')].map(cell=>cell.textContent)`),['Category','Crates','Pallets','Collectables','Pickup','Total'])
  assert.deepEqual((await levyRows('msw')).find(row=>row[0]==='Iron and Steel'),['Iron and Steel','1.00','0.00','0.00','0.00','1.00'])
  await fill('Reporting month','2013-04');await enabled('Export CSV')
  assert.equal((await levyRows('msw')).every(row=>row.slice(1).every(value=>value==='0.00')),true)
  await hasText('No records for this period.')
  assert.deepEqual(await levyFacts('whitegoods'),{'Whitegoods count':'0','Estimated weight':'0.00 kg','Applied factor':'No entries'})
  await fill('Reporting month','2007-02');await enabled('Export CSV')
  const mixedFacts=await levyFacts('whitegoods');assert.equal(mixedFacts['Whitegoods count'],'4');assert.equal(mixedFacts['Estimated weight'],'200.50 kg');assert.equal(mixedFacts['Applied factors'],'48.00 / 52.25 kg/item')
  assert.deepEqual((await browserApi('/api/admin/waste-levy-summary?month=2013-03')).body.totals,worksheetBefore.totals)
  await evaluate('window.fetch=window.__aggregateFetch')
  console.log('PASS: Report Summary pagination unchanged; monthly worksheet direction/category totals, intake/electrical matrices, Scrap/Whitegoods, mixed historical factors, kg/tonnes, full CSV/XLSX exports, empty months, no section pagination and 1280/1024/768 overflow.')
  // Admin account management uses the existing Settings destination and pagination controls.
  await openSettings('User Management');await hasText('Manage sign-in access.');await enabled('Add User')
  const managedName='aaa-browser-'+crypto.randomUUID().slice(0,8),managedPassword=crypto.randomUUID(),resetManagedPassword=crypto.randomUUID()
  await click('Add User');await fill('Username','  '+managedName+'  ');await fill('Password',managedPassword);await fill('Confirm password','mismatch');await hasText('Passwords do not match.')
  assert.equal(await evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent==='Create user').disabled"),true)
  await fill('Confirm password',managedPassword);await enabled('Create user');await click('Create user');await hasText('User created successfully.');await enabled('Add User')
  const managed=(await browserApi('/api/admin/users?size=50')).body.items.find(u=>u.username===managedName);assert.ok(managed);assert.equal(managed.role,'STAFF');assert.equal(managed.active,true)
  await click('Add User');await fill('Username',managedName.toUpperCase());await fill('Password',managedPassword);await fill('Confirm password',managedPassword);await click('Create user');await until(()=>evaluate("[...document.querySelectorAll('[role=alert]')].some(e=>e.textContent.includes('unique regardless of case'))"),'duplicate username error');await enabled('Cancel');await click('Cancel');await click('Reload users');await enabled('Add User')
  const managedRow='tr[data-user-id="'+managed.id+'"]'
  await click('Change Role',managedRow);await fill('Role','ADMIN');await hasText('from STAFF to ADMIN?');await click('Confirm role change');await hasText('User updated successfully.');await enabled('Add User')
  await click('Reset Password',managedRow);await fill('New password',resetManagedPassword);await fill('Confirm password',resetManagedPassword);await click('Reset password');await hasText('User updated successfully.');await enabled('Add User')
  await click('Deactivate',managedRow);await hasText('Historical records will remain unchanged.');await click('Cancel');assert.equal((await browserApi('/api/admin/users?size=50')).body.items.find(u=>u.id===managed.id).active,true)
  await click('Deactivate',managedRow);await click('Confirm deactivation');await hasText('User updated successfully.');await enabled('Add User');await hasText('Inactive')
  await click('Activate',managedRow);await click('Confirm activation');await hasText('User updated successfully.');await enabled('Add User')
  // A concurrent update must surface a recoverable stale-version error.
  await click('Change Role',managedRow);await fill('Role','STAFF');const latestManaged=(await browserApi('/api/admin/users?size=50')).body.items.find(u=>u.id===managed.id)
  assert.equal((await browserApi('/api/admin/users/'+managed.id+'/password','PUT',{password:resetManagedPassword,version:latestManaged.version})).status,200)
  await click('Confirm role change');await hasText('This user has changed.');await click('Reload users');await enabled('Add User')
  for(let i=0;i<51;i++)assert.equal((await browserApi('/api/admin/users','POST',{username:'zzz-browser-'+i,password:crypto.randomUUID(),role:'STAFF'})).status,201)
  await click('Reload users');await enabled('Add User');const usersPager='nav[aria-label="Users pagination"]'
  assert.equal(await evaluate("document.querySelectorAll('section[aria-label=Users] tbody tr').length"),10)
  await click('Next',usersPager);await hasText('Showing 11-20 of');await fill('Rows per page','25',usersPager);await hasText('Showing 1-25 of');await fill('Rows per page','50',usersPager);await hasText('Showing 1-50 of')
  await click('Next',usersPager);await hasText('Showing 51-');await fill('Search username',managedName.toUpperCase());await click('Search users');await hasText('Showing 1-1 of 1');await hasText(managedName)
  assert.equal(await evaluate("document.querySelectorAll('section[aria-label=Users] tbody tr').length"),1)
  await fill('Search username','no-matching-managed-account');await click('Search users');await hasText('No users found.');await click('Clear search');await hasText('Showing 1-50 of')
  for(const width of [1280,1024,768]){await cdp('Emulation.setDeviceMetricsOverride',{width,height:1024,deviceScaleFactor:1,mobile:false});await until(()=>evaluate('document.documentElement.scrollWidth<=window.innerWidth'),'User Management overflow '+width);await screenshot('user-management-'+width)}
  await click('Add User');for(const width of [1280,1024,768]){await cdp('Emulation.setDeviceMetricsOverride',{width,height:1024,deviceScaleFactor:1,mobile:false});await until(()=>evaluate('document.documentElement.scrollWidth<=window.innerWidth'),'User editor overflow '+width);await screenshot('user-management-editor-'+width)}await click('Cancel')
  console.log('PASS: User Management creation/password confirmation, role changes, password reset, deactivate/cancel/reactivate confirmations, stale conflict/reload, case-insensitive duplicate rejection and username search, server pagination 10/25/50 and desktop/tablet layouts.')
  await click('Sign out')
  await enabled('Sign in')
  await fill('Username',process.env.INITIAL_STAFF_USERNAME)
  await fill('Password',process.env.INITIAL_STAFF_PASSWORD)
  await click('Sign in')
  await enabled('Operations')
  assert.equal(await evaluate("[...document.querySelectorAll('nav button')].some(b=>b.textContent.trim()==='Settings')"),false)
  for(const path of ['/api/admin/settings','/api/admin/settings/container-types','/api/admin/settings/material-categories','/api/admin/users']) assert.equal((await browserApi(path)).status,403)
  for(const path of ['container-types','material-categories','users','organisation','reporting','whitegoods']) {
    await cdp('Page.navigate',{url:frontend+'/admin/settings/'+path})
    await enabled('Operations')
    assert.equal(await evaluate("document.querySelector('.settings-page')===null"),true)
    assert.equal(await evaluate("document.querySelector('.settings-menu-trigger')===null"),true)
  }
  console.log('PASS: Admin Settings navigation/authorization, container/category configuration and validation, immutable history, organisation header, reporting defaults, configurable future Whitegoods factors, mixed-factor summaries and direct Staff navigation denial.')
  // Whole-row selection reuses existing details and permissions for every workflow.
  async function checkRecordRows(admin) {
    await click('Records');await enabled('Apply dates / refresh')
    await fill('From','');await fill('To','');await click('Apply dates / refresh');await enabled('Apply dates / refresh')
    for(const workflow of ['CRATE_PALLET','SALVAGE','PICKUP','ELECTRICAL_CRATE_PALLET','COLLECTABLES','WHITEGOODS','ELECTRICAL_TESTING','EWASTE_PICKUP','SCRAP']) {
      await fill('Workflow',workflow);await enabled('Apply dates / refresh')
      const items=(await browserApi('/api/records?workflow='+workflow+'&size=10')).body.items
      assert.ok(items.length>0,'row fixture for '+workflow)
      const record=items[0].record
      await until(()=>evaluate(`document.querySelector('.selectable-record button')?.textContent==='#'+${record.id}`),'loaded '+workflow+' rows')
      await evaluate("document.querySelector('.selectable-record td:nth-child(2)').click()")
      await until(()=>evaluate(`document.querySelector('.detail-heading h2')?.textContent==='Record #'+${record.id} && document.querySelector('.selected-row button')?.textContent==='#'+${record.id}`),'selected '+workflow)
      await enabled('Close details')
      const details=await evaluate("document.querySelector('.detail-panel').textContent")
      assert.ok(details.includes('Created by '+record.createdBy),workflow+' creator')
      assert.ok(details.includes('Last updated by '+record.updatedBy),workflow+' updater')
      for(const value of [record.createdAt,record.updatedAt]) {
        const formatted=await evaluate(`new Date(${JSON.stringify(value)}).toLocaleString('en-AU',{timeZone:'Australia/Hobart'})`)
        assert.ok(details.includes(formatted),workflow+' Hobart timestamp')
      }
      assert.equal((details.match(/\(Hobart\)/g)||[]).length,2)
      assert.equal(await evaluate("[...document.querySelectorAll('.detail-panel button')].some(b=>b.textContent==='Correct record')"),admin)
      assert.equal(await evaluate("[...document.querySelectorAll('.detail-panel button')].some(b=>b.textContent==='Delete record')"),admin)
      assert.equal(await evaluate("document.querySelector('.detail-panel input,.detail-panel select')===null"),true)
      if(workflow==='WHITEGOODS') {
        assert.ok(items.length>1)
        await evaluate("document.querySelectorAll('.selectable-record')[1].querySelector('td:nth-child(2)').click()")
        await until(()=>evaluate(`document.querySelector('.detail-heading h2')?.textContent==='Record #'+${items[1].record.id}`),'switch row by clicking another cell')
        await evaluate("(() => {const row=document.querySelector('.selectable-record');row.focus();row.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}))})()")
        await until(()=>evaluate(`document.querySelector('.detail-heading h2')?.textContent==='Record #'+${items[0].record.id}`),'switch row with keyboard')
        assert.equal(await evaluate("document.querySelectorAll('.selected-row').length"),1)
      }
      await click('Close details')
      assert.equal(await evaluate("document.querySelector('.detail-panel')===null && document.querySelectorAll('.selected-row').length===0"),true)
      assert.equal(await evaluate("document.querySelector('#records-heading').textContent"),'Records')
    }
  }
  await checkRecordRows(false)
  await click('Sign out');await enabled('Sign in');await fill('Username',process.env.INITIAL_ADMIN_USERNAME);await fill('Password',process.env.INITIAL_ADMIN_PASSWORD);await click('Sign in');await enabled('Records')
  await checkRecordRows(true)
  console.log('PASS: whole-row and keyboard selection, switching/highlight/close, inline Hobart metadata and Staff/Admin detail permissions across all nine workflows.')

  // ET-16: model an authoritative server rollover while the client's pending board is still open.
  // ElectricalTestingMidnightIntegrationTests separately advances the real injected backend Clock.
  await click('Sign out');await enabled('Sign in');await fill('Username',process.env.INITIAL_STAFF_USERNAME);await fill('Password',process.env.INITIAL_STAFF_PASSWORD);await click('Sign in');await enabled('Operations')
  await staffWorkflow('Electrical Testing')
  await until(()=>evaluate("!!document.querySelector('.quick-tally input')&&!document.querySelector('.quick-tally input').disabled"),'ET-16 board ready')
  const midnightDate=await evaluate("document.querySelector('label.tally-date input[type=date]').value")
  const midnightBefore=(await browserApi('/api/electrical-testing/tallies?from='+midnightDate+'&to='+midnightDate)).body
  const midnightValue=Number(await evaluate("document.querySelector('.quick-tally input').value"))
  await click('+','.quick-tally')
  await evaluate(`window.__midnightFetch=window.fetch;window.__midnightPayloads=[];window.fetch=(...args)=>{
    if(String(args[0])==='/api/electrical-testing/tallies'&&args[1]?.method==='POST'){
      window.__midnightPayloads.push(JSON.parse(args[1].body));
      return Promise.resolve(new Response(JSON.stringify({detail:"Historical tallies are read-only for Staff. Only today's tally can be changed."}),{status:403,headers:{'Content-Type':'application/problem+json'}}));
    }return window.__midnightFetch(...args);
  }`)
  await click('Save Tallies');await hasText('Historical tallies are read-only for Staff.')
  assert.equal(await evaluate("document.querySelector('label.tally-date input[type=date]').value"),midnightDate)
  assert.equal(Number(await evaluate("document.querySelector('.quick-tally input').value")),midnightValue+1)
  await hasText('1 tally adjustments pending')
  await enabled('Retry Save Tallies');await click('Retry Save Tallies');await enabled('Clear remaining session')
  const midnightPayloads=await evaluate('window.__midnightPayloads')
  assert.equal(midnightPayloads.length,2);assert.deepEqual(midnightPayloads[0],midnightPayloads[1]);assert.equal(midnightPayloads[0].recordDate,midnightDate)
  assert.deepEqual((await browserApi('/api/electrical-testing/tallies?from='+midnightDate+'&to='+midnightDate)).body,midnightBefore)
  await evaluate('window.fetch=window.__midnightFetch');await click('Clear remaining session')
  await until(()=>evaluate("![...document.querySelectorAll('button')].find(b=>b.textContent==='Sign out').disabled"),'ET-16 explicit recovery clears pending guard')
  console.log('PASS ET-16: rejected historical save retains pending value/date/UUID, retry stays date-scoped, explicit clear recovers without a write.')

  // SEC-03: use supported inputs and real API storage; never inject directly into application DOM.
  await click('Sign out');await enabled('Sign in');await fill('Username',process.env.INITIAL_ADMIN_USERNAME);await fill('Password',process.env.INITIAL_ADMIN_PASSWORD);await click('Sign in');await enabled('Records')
  const hostileTexts=['<script>alert(1)</script>','<img src=x onerror=alert(1)>','=2+2','+SUM(1,1)','@test']
  await evaluate('window.__hostileAlerts=0;window.__originalAlert=window.alert;window.alert=()=>{window.__hostileAlerts++}')
  const hostileCategory=(await browserApi('/api/material-categories?context=PICKUP')).body[0].id
  const hostileRecords=[]
  for(const text of hostileTexts){const result=await browserApi('/api/pickup-records','POST',{submissionId:crypto.randomUUID(),recordDate:'2005-12-12',pickupLocation:text,netWeight:'1.00',materials:[{materialCategoryId:hostileCategory,percentage:'100'}]});assert.equal(result.status,201);hostileRecords.push(result.body)}
  async function inertHostileDom(scope){
    assert.equal(await evaluate(`document.querySelector(${JSON.stringify(scope)}).querySelectorAll('script,img[onerror],iframe,svg[onload]').length`),0)
    assert.equal(await evaluate('window.__hostileAlerts'),0)
  }
  await click('Records');await enabled('Apply dates / refresh');await fill('Workflow','PICKUP');await fill('From','2005-12-12');await fill('To','2005-12-12');await enabled('Apply dates / refresh');await click('Apply dates / refresh')
  for(const record of hostileRecords){
    await enabled('#'+record.id)
    assert.ok(await evaluate(`document.querySelector('[aria-labelledby="records-heading"] table').textContent.includes(${JSON.stringify(record.pickupLocation)})`))
    await inertHostileDom('[aria-labelledby="records-heading"] table');await click('#'+record.id);await enabled('Close details')
    assert.ok(await evaluate(`document.querySelector('.detail-panel').textContent.includes(${JSON.stringify(record.pickupLocation)})`))
    await inertHostileDom('.detail-panel');await click('Close details')
  }
  await click('Reports');await hasText('Applied period:');await fill('Report workflow','PICKUP');await fill('From date','2005-12-12');await fill('To date','2005-12-12');await enabled('Apply report filters');await click('Apply report filters');await fill('Report view','details')
  for(const text of hostileTexts)await until(()=>evaluate(`document.querySelector('.report-section').textContent.includes(${JSON.stringify(text)})`),'SEC-03 literal report text '+text)
  await inertHostileDom('.report-section')
  const hostileCsv=await evaluate(`fetch('/api/admin/reports/export?from=2005-12-12&to=2005-12-12&workflow=PICKUP&report=details&format=csv').then(async r=>({status:r.status,text:await r.text()}))`)
  assert.equal(hostileCsv.status,200)
  for(const text of ['=2+2','+SUM(1,1)','@test'])assert.ok(hostileCsv.text.includes("'"+text),'SEC-03 CSV text protection '+text)
  assert.equal(await evaluate('window.__hostileAlerts'),0)
  await evaluate('window.alert=window.__originalAlert')
  console.log('PASS SEC-03: hostile stored Pickup text remains literal in Records/details/reports, creates no executable DOM, and formula-looking CSV text is protected.')

  console.log('PASS: Shared branding/logo/favicon, exact workflow order, Electrical Testing Open for both roles, Workflows headings, login/footer text and three-column cards without overflow at 1280/1024/768.')
  console.log('PASS: Admin navigation/API boundaries, Overview, report periods and applied filters, filtered CSV/XLSX downloads, detail links, monthly figures/tonnes/exclusions, soft-delete refresh and tablet layout.')
  console.log('PASS: Electrical Testing selected-month counters/date contributions, Staff historical read-only, date/month synchronization, date-scoped Admin reductions, zero floor, empty months, unsaved guards, addition/reduction lost-response retries, separate sales references, Records/reporting and 1280/1024/768 layouts.')
  console.log('PASS: Whitegoods zero/same-day entries, fixed factor, review/save, retry/navigation, independent monthly summary and error recovery, Admin month/count corrections, stale versions and deletion-driven totals.')
  console.log('PASS: Collectables operational/physical-label reminders, fixed tare, positive-net validation, category isolation, review/edit/save, retry/navigation, Staff permissions, date filters, Admin correction, stale versions and delete reconciliation.')
  console.log('PASS: Electrical Crate/Pallet four-container/two-category isolation, positive-net validation, review, retry/navigation, Staff access, date filters, Admin container/tare correction, stale versions, delete reconciliation and tablet layout.')
  console.log('PASS: Pickup positive-weight and required-location validation, exact percentages, category restrictions, review/edit, retained retry payload/navigation, Staff permissions, date filters, Admin recalculation, stale correction and lost-delete reconciliation.')
  console.log('PASS: Salvage direction-specific categories, single crew, independent review, all four zero/positive combinations, mandatory times at zero, positive/zero correction transitions, stale Admin correction, retry/navigation across workflows, soft deletion and lost-delete reconciliation.')
  console.log('PASS: login/CSRF, validation/review, active-submission navigation/logout guards, retry UUID retained across Records navigation, definitive conflict handling, Admin correction, lost-delete/404 reconciliation, selected-detail refresh, and tablet layout.')
  console.log('PASS: Today activity nine cards, backend values, accurate testing labels, month independence, zero values, placement and responsive dashboard layouts.')
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
