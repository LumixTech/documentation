#!/usr/bin/env node
// Driver for the Lumix Docusaurus documentation site.
// Starts the dev server, waits for it to compile, then drives headless
// Chrome to screenshot one or more pages and assert their content.
//
// Usage (run from the documentation/ directory):
//   node .claude/skills/run-documentation/driver.mjs                 # home + docs/intro
//   node .claude/skills/run-documentation/driver.mjs /docs/intro /blog
//   node .claude/skills/run-documentation/driver.mjs --serve         # build + serve (prod) instead of dev
//
// Env overrides:
//   PORT=3000            dev/serve port
//   BASE_URL=/documentation/   site baseUrl (must match docusaurus.config.ts)
//   CHROME_PATH=...      full path to a Chrome/Edge binary
//   SHOTS_DIR=...        where screenshots land (default: <tmp>/lumix-docs-shots)
//   NPM_REGISTRY=...     only used by the install step in SKILL.md, not here

import { spawn, spawnSync } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
// documentation/ is three levels up from .claude/skills/run-documentation/
const UNIT_DIR = path.resolve(__dirname, '..', '..', '..');

const PORT = parseInt(process.env.PORT || '3000', 10);
const HOST = '127.0.0.1';
const BASE_URL = process.env.BASE_URL || '/documentation/'; // from docusaurus.config.ts
const SHOTS_DIR = process.env.SHOTS_DIR || path.join(os.tmpdir(), 'lumix-docs-shots');
const SERVE_MODE = process.argv.includes('--serve');

// Site-relative paths to capture (default set, or whatever was passed on argv).
const argPaths = process.argv.slice(2).filter((a) => !a.startsWith('--'));
const PAGES = argPaths.length ? argPaths : ['/', '/docs/intro'];

const DOCUSAURUS = path.join(UNIT_DIR, 'node_modules', '@docusaurus', 'core', 'bin', 'docusaurus.mjs');
const LOG = path.join(SHOTS_DIR, 'server.log');

function log(...a) { console.log('[driver]', ...a); }

function findChrome() {
  if (process.env.CHROME_PATH && fs.existsSync(process.env.CHROME_PATH)) return process.env.CHROME_PATH;
  const candidates = [
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
    '/usr/bin/google-chrome',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
  ];
  for (const c of candidates) if (fs.existsSync(c)) return c;
  throw new Error('No Chrome/Edge found. Set CHROME_PATH to a Chromium-family binary.');
}

function get(urlPath) {
  return new Promise((resolve) => {
    // The Accept header MATTERS: Docusaurus' dev server only rewrites SPA
    // routes to index.html when Accept contains text/html or */* (via
    // connect-history-api-fallback). Without it, valid routes return 404.
    const opts = { host: HOST, port: PORT, path: urlPath, timeout: 60000, headers: { Accept: 'text/html,*/*' } };
    const req = http.get(opts, (res) => {
      let body = '';
      res.on('data', (d) => (body += d));
      res.on('end', () => resolve({ status: res.statusCode, body }));
    });
    req.on('error', () => resolve({ status: 0, body: '' }));
    req.on('timeout', () => { req.destroy(); resolve({ status: 0, body: '' }); });
  });
}

async function waitForServer(urlPath, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const { status } = await get(urlPath);
    if (status >= 200 && status < 400) return true;
    await new Promise((r) => setTimeout(r, 1000));
  }
  return false;
}

// Docusaurus' dev server compiles routes lazily: the FIRST cold hit to a
// not-yet-built route can 404 before webpack registers it. Poll until 2xx.
async function warm(urlPath, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  let last = { status: 0, body: '' };
  while (Date.now() < deadline) {
    last = await get(urlPath);
    if (last.status >= 200 && last.status < 400) return last;
    await new Promise((r) => setTimeout(r, 800));
  }
  return last;
}

function killTree(pid) {
  if (process.platform === 'win32') {
    spawnSync('taskkill', ['/pid', String(pid), '/T', '/F'], { stdio: 'ignore' });
  } else {
    try { process.kill(-pid, 'SIGTERM'); } catch { /* noop */ }
  }
}

function shotFile(p) {
  const name = p === '/' ? 'home' : p.replace(/^\/+|\/+$/g, '').replace(/[\\/]/g, '-') || 'home';
  return path.join(SHOTS_DIR, `${name}.png`);
}

function screenshot(chrome, url, out) {
  const r = spawnSync(chrome, [
    '--headless=new', '--disable-gpu', '--no-sandbox', '--hide-scrollbars',
    '--force-device-scale-factor=1', '--window-size=1280,1600',
    '--virtual-time-budget=8000',
    `--screenshot=${out}`, url,
  ], { stdio: 'ignore', timeout: 60000 });
  return r.status === 0 && fs.existsSync(out) && fs.statSync(out).size > 0;
}

async function main() {
  fs.mkdirSync(SHOTS_DIR, { recursive: true });
  const chrome = findChrome();
  log('chrome:', chrome);
  log('unit  :', UNIT_DIR);
  log('shots :', SHOTS_DIR);

  const subcmd = SERVE_MODE ? 'serve' : 'start';
  const args = SERVE_MODE
    ? [DOCUSAURUS, 'serve', '--port', String(PORT), '--host', HOST, '--no-open']
    : [DOCUSAURUS, 'start', '--port', String(PORT), '--host', HOST, '--no-open'];

  if (SERVE_MODE) {
    log('building (docusaurus build)…');
    const b = spawnSync(process.execPath, [DOCUSAURUS, 'build'], { cwd: UNIT_DIR, stdio: 'inherit' });
    if (b.status !== 0) { console.error('[driver] build failed'); process.exit(1); }
  }

  log(`launching: docusaurus ${subcmd} on http://${HOST}:${PORT}${BASE_URL}`);
  const logStream = fs.createWriteStream(LOG);
  const server = spawn(process.execPath, args, {
    cwd: UNIT_DIR,
    detached: process.platform !== 'win32',
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  server.stdout.pipe(logStream);
  server.stderr.pipe(logStream);

  let exitCode = 0;
  try {
    log('waiting for first compile (can take 30-90s)…');
    const ready = await waitForServer(BASE_URL, 150000);
    if (!ready) {
      console.error(`[driver] server never became ready. See ${LOG}`);
      console.error(fs.readFileSync(LOG, 'utf8').slice(-2000));
      throw new Error('server not ready');
    }
    log('server is up.');

    const results = [];
    for (const p of PAGES) {
      const urlPath = (BASE_URL + p.replace(/^\//, '')).replace(/\/{2,}/g, '/');
      const url = `http://${HOST}:${PORT}${urlPath}`;
      // Warm the route (Docusaurus compiles lazily on first request).
      const { status, body } = await warm(urlPath, 60000);
      const titleOk = /Lumix Documentation/.test(body);
      const out = shotFile(p);
      const ok = screenshot(chrome, url, out);
      const size = ok ? fs.statSync(out).size : 0;
      results.push({ url, status, titleOk, out, ok, size });
      log(`${status} ${titleOk ? 'title✓' : 'title✗'} shot=${ok ? `${size}B` : 'FAILED'}  ${url}`);
    }

    console.log('\n=== SUMMARY ===');
    let allGood = true;
    for (const r of results) {
      const good = r.status >= 200 && r.status < 400 && r.titleOk && r.ok;
      allGood = allGood && good;
      console.log(`${good ? 'PASS' : 'FAIL'}  ${r.url}\n      -> ${r.out} (${r.size} bytes, http ${r.status})`);
    }
    console.log(`\nScreenshots in: ${SHOTS_DIR}`);
    exitCode = allGood ? 0 : 1;
  } catch (e) {
    console.error('[driver] error:', e.message);
    exitCode = 1;
  } finally {
    log('stopping server…');
    killTree(server.pid);
    logStream.end();
  }
  process.exit(exitCode);
}

main();
