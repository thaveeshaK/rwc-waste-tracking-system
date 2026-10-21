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

async function fill(label, value) {
  await evaluate(`(() => {
    const label = [...document.querySelectorAll('label')].find(l => l.textContent.trim().startsWith(${JSON.stringify(label)}));
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
    window.fetch = async (...args) => {
      const [url, init] = args;
      const submission = url === '/api/crate-pallet-records' && init?.method === 'POST';
      if (submission) window.__submissionIds.push(JSON.parse(init.body).submissionId);
      const response = await window.__realFetch(...args);
      if (submission && response.ok && !window.__lostResponse) {
        window.__lostResponse = true; throw new TypeError('Simulated lost response');
      }
      return response;
    };`)
  await click('Confirm and submit')
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
  await click('Delete record')
  await click('Confirm deletion')
  await hasText('Record #' + savedId + ' deleted.')
  assert.equal(await evaluate(`[...document.querySelectorAll('tbody button')].some(b => b.textContent === '#${savedId}')`), false)
  console.log('PASS: browser login, percentage validation, review/correction, lost-response retry with the same UUID, Records, Staff permissions, Admin correction, soft deletion, and tablet layout.')
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
