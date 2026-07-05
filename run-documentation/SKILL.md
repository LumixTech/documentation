---
name: run-documentation
description: Build, run, and drive the Lumix documentation site (Docusaurus). Use when asked to start, run, build, serve, screenshot, or verify the docs site / documentation portal, or to confirm a docs change renders.
---

The Lumix documentation portal is a **Docusaurus 3.9** static site (in
`documentation/`). An agent can't open a browser window, so "run it" means:
start the dev server and drive headless Chrome against it via
**`.claude/skills/run-documentation/driver.mjs`** — a dependency-free Node
script that boots `docusaurus start`, waits for compile, screenshots each
page, and asserts it rendered.

All paths below are relative to `documentation/`. Commands were verified on
Windows 11 (PowerShell), Node v24, npm v11, with Chrome installed.

## Prerequisites

- **Node ≥ 20** and **npm** (`node -v` → v24.16.0, `npm -v` → 11.13.0 here).
- **Chrome or Edge** installed. The driver auto-detects
  `C:\Program Files\Google\Chrome\Application\chrome.exe` (or Edge); override
  with `CHROME_PATH`. No Playwright/chromium-cli needed.

## Setup

The repo's `documentation/.npmrc` pins an **internal Verdaccio registry**
(`http://10.9.9.50:4873`) that is only reachable on the company network.
Off-network it times out (`ETIMEDOUT`) and the install corrupts. Install with
the public registry override (do **not** edit `.npmrc` — it carries a secret
auth token):

```powershell
npm install --registry https://registry.npmjs.org/ --no-audit --no-fund
```

(On the corporate network the plain `npm install` works against Verdaccio.)

## Run (agent path) — driver.mjs

From `documentation/`:

```powershell
node .claude/skills/run-documentation/driver.mjs
```

It launches `docusaurus start` on `http://127.0.0.1:3000/documentation/`, waits
for the first compile, then for each page: polls the route until it returns
2xx, screenshots it with headless Chrome, and checks the HTML for the site
title. Prints a `PASS`/`FAIL` summary and exits non-zero if any page failed.
The server is always stopped on exit.

Default pages: home (`/`) + `docs/intro`. Pass your own (site-relative, **no
leading slash**):

```powershell
node .claude/skills/run-documentation/driver.mjs docs/intro docs/overview blog
```

Screenshots land in **`%TEMP%\lumix-docs-shots\`** (the driver prints the
absolute path); `server.log` there has the dev-server output. After it
finishes, open `home.png` / `docs-intro.png` to confirm the render.

| thing | value |
|---|---|
| `PORT` env | dev-server port (default `3000`) |
| `CHROME_PATH` env | path to a Chrome/Edge binary |
| `SHOTS_DIR` env | screenshot output dir (default `%TEMP%\lumix-docs-shots`) |
| `BASE_URL` env | site baseUrl, default `/documentation/` (matches `docusaurus.config.ts`) |
| `--serve` flag | build then serve the prod bundle instead of dev — **see Gotchas: build currently fails** |

## Run (human path)

```powershell
npm start          # = docusaurus start; opens a browser at /documentation/. Ctrl-C to stop.
```

Useless to an agent (waits forever, needs a real browser) — use the driver.

## Test

There is no unit-test suite (`package.json` has no `test` script). The quality
gates are:

```powershell
npm run typecheck                       # tsc --noEmit — passes
node node_modules/@docusaurus/core/bin/docusaurus.mjs build   # currently FAILS — see Gotchas
```

## Gotchas

- **Private registry in `.npmrc`.** `documentation/.npmrc` points npm at
  `http://10.9.9.50:4873`. Off the company network it hangs then dies with
  `npm error Exit handler never called!` (a misleading message — the real
  cause is `ETIMEDOUT` to that host). A failed retry also **prunes**
  `node_modules`. Always install with `--registry https://registry.npmjs.org/`
  off-network.
- **`build` / `--serve` currently fails.** `docusaurus.config.ts` sets
  `onBrokenLinks: 'throw'`, and several docs link to files that don't resolve
  (e.g. `./05-error-handling-rfc7807` from the backend pages). `build` exits 1
  with `Docusaurus found broken links!`. The **dev server (`start`) only
  warns**, which is why the driver uses it. Fix the links (or set
  `onBrokenLinks: 'warn'`) before relying on `--serve`/`build`.
- **baseUrl is `/documentation/`, not `/`.** The home page is
  `http://127.0.0.1:3000/documentation/`; a hit to `/` shows only a redirect
  stub. The driver already prepends `BASE_URL`.
- **Route paths ≠ filenames.** Docusaurus strips numeric folder prefixes, so
  `docs/03-backend/05-error-handling-rfc7807.md` serves at
  `/docs/backend/error-handling-rfc7807`. When picking pages to screenshot,
  use the stripped path (`docs/intro`, `docs/overview`), not the on-disk name.
- **Dev server compiles routes lazily.** The *first* cold request to a route
  can return 404 before webpack registers it. The driver polls each route
  until 2xx (don't "fix" a one-off 404 by editing content — retry).
- **`Accept` header decides 404 vs 200.** The dev server only rewrites SPA
  routes to `index.html` when the request `Accept` contains `text/html` or
  `*/*` (connect-history-api-fallback). A bare `node http.get` / fetch with no
  `Accept` header gets 404 on valid routes; `curl` and Chrome work because
  they send `*/*` / `text/html`. The driver sets the header — keep it if you
  edit `get()`.
- **git-bash mangles a leading `/` argument.** Running the driver under
  git-bash with `/` or `/docs/intro` as an arg turns it into
  `C:/Program Files/Git/...` (MSYS path conversion) → `Request path contains
  unescaped characters`. Use PowerShell, or pass paths **without** a leading
  slash (`docs/intro`).

## Troubleshooting

- **`Exit handler never called!` on install** → registry unreachable. Use
  `--registry https://registry.npmjs.org/`. If `node_modules` got pruned,
  re-run the full install.
- **`EADDRINUSE` / pages 404 against a server you didn't start** → a stale dev
  server holds port 3000. Find and kill it:
  `Get-NetTCPConnection -LocalPort 3000 -State Listen | %% { Stop-Process -Id $_.OwningProcess -Force }`,
  or run the driver with `PORT=3001`.
- **Driver reports `No Chrome/Edge found`** → set `CHROME_PATH` to your browser
  binary.
- **`docusaurus found broken links`** → expected on `build` today (see
  Gotchas); use the dev-server driver, or fix the links.
