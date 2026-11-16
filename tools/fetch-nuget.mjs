#!/usr/bin/env node
/*
 * fetch-nuget.mjs - offline NuGet package fetcher for the AirTouch build environment.
 *
 * Sandboxed agent sessions confine the process token, so Schannel cannot acquire credentials there
 * and `dotnet restore` / `curl` / PowerShell cannot reach https://api.nuget.org. A normal desktop
 * session reaches it fine - this script is a sandbox workaround, not a desktop requirement.
 * Node.js ships its own OpenSSL stack and *can* reach it, so this script downloads the .nupkg files
 * we need into a plain folder feed that `dotnet restore` consumes through ./NuGet.config.
 *
 * Usage:
 *   node tools/fetch-nuget.mjs [--out <dir>] [id@1.2.3 | id@8.0. ...]
 *
 * The output directory must be given with --out. (It used to be the first positional argument,
 * which silently swallowed a leading "id@version" spec and created a junk directory named after it.)
 */
import { mkdir, writeFile, stat } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import https from 'node:https';
import path from 'node:path';

const FLAT = 'https://api.nuget.org/v3-flatcontainer';

const argv = process.argv.slice(2);
const outFlag = argv.findIndex((a) => a === '--out' || a === '-o');
let OUT = path.resolve('.nuget-local');
let specs = argv.filter((a) => a !== '--out' && a !== '-o');

if (outFlag >= 0) {
  const dir = argv[outFlag + 1];
  if (!dir) {
    console.error('--out 需要一个目录参数');
    process.exit(2);
  }
  OUT = path.resolve(dir);
  // Drop the flag and its value from the spec list (keeping any other specs).
  specs = argv.filter((_, i) => i !== outFlag && i !== outFlag + 1);
} else if (specs[0] && !specs[0].includes('@')) {
  // Backwards compatible form: a single leading path argument. Refuse to guess when the argument
  // looks like a package spec, so a mistyped command can never create a directory named "id@1.2.3".
  OUT = path.resolve(specs[0]);
  specs = specs.slice(1);
}

const REQUESTS = [
  { id: 'fleck', version: '1.2.0' },
  { id: 'qrcoder', version: '1.6.0' },
  // QRCoder's net6.0 dependency group pulls these in; the app itself only uses the
  // System.Drawing-free PngByteQRCode renderer.
  { id: 'system.drawing.common', version: '6.0.0' },
  { id: 'microsoft.win32.systemevents', version: '6.0.0' },
  { id: 'microsoft.netcore.app.ref', band: '8.0.' },
  { id: 'microsoft.aspnetcore.app.ref', band: '8.0.', track: 'microsoft.netcore.app.ref' },
  { id: 'microsoft.windowsdesktop.app.ref', band: '8.0.', track: 'microsoft.netcore.app.ref' },
  { id: 'microsoft.netcore.app.host.win-x64', band: '8.0.', track: 'microsoft.netcore.app.ref' },
  // Extra ad-hoc specs: "id@1.2.3" pins a version, "id@8.0." picks the newest in that band.
  ...specs.map((spec) => {
    const [id, v] = spec.split('@');
    return v?.endsWith('.') ? { id, band: v } : { id, version: v };
  }),
];

function get(url, redirects = 6) {
  return new Promise((resolve, reject) => {
    https
      .get(url, { headers: { 'user-agent': 'airtouch-offline-fetch' } }, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          res.resume();
          if (redirects <= 0) return reject(new Error(`too many redirects for ${url}`));
          return get(new URL(res.headers.location, url).href, redirects - 1).then(resolve, reject);
        }
        if (res.statusCode === 404) {
          res.resume();
          return resolve({ status: 404, body: Buffer.alloc(0) });
        }
        if (res.statusCode !== 200) {
          res.resume();
          return reject(new Error(`HTTP ${res.statusCode} for ${url}`));
        }
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => resolve({ status: 200, body: Buffer.concat(chunks) }));
      })
      .on('error', reject);
  });
}

const cmp = (a, b) => {
  const pa = a.split('.').map(Number);
  const pb = b.split('.').map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (d) return d;
  }
  return 0;
};

const stable = (v) => /^\d+(\.\d+)*$/.test(v);

async function listVersions(id) {
  const r = await get(`${FLAT}/${id}/index.json`);
  if (r.status !== 200) throw new Error(`cannot list versions of ${id}`);
  return JSON.parse(r.body.toString('utf8')).versions.filter(stable);
}

async function resolveVersion(req, pinned) {
  const versions = await listVersions(req.id);
  if (req.version) {
    if (!versions.includes(req.version)) throw new Error(`${req.id} ${req.version} not published`);
    return req.version;
  }
  if (pinned) {
    if (versions.includes(pinned)) return pinned;
    // walk down until a lockstep version exists
    for (const v of versions.filter((v) => v.startsWith(req.band)).sort(cmp).reverse()) {
      if (cmp(v, pinned) < 0) return v;
    }
    throw new Error(`no ${req.id} in band ${req.band} at or below ${pinned}`);
  }
  const inBand = versions.filter((v) => v.startsWith(req.band)).sort(cmp);
  if (!inBand.length) throw new Error(`no stable ${req.id} in band ${req.band}`);
  return inBand[inBand.length - 1];
}

async function download(id, version) {
  const file = path.join(OUT, `${id}.${version}.nupkg`);
  try {
    const s = await stat(file);
    if (s.size > 0) return { file, cached: true };
  } catch {
    /* not cached */
  }
  const url = `${FLAT}/${id}/${version}/${id}.${version}.nupkg`;
  const r = await get(url);
  if (r.status !== 200) throw new Error(`${id} ${version}: nupkg not found`);
  await writeFile(file, r.body);
  return { file, cached: false, sha256: createHash('sha256').update(r.body).digest('hex').slice(0, 16) };
}

await mkdir(OUT, { recursive: true });
const manifest = [];
let pinnedVersion = null;

for (const req of REQUESTS) {
  const version = await resolveVersion(req, req.track ? pinnedVersion : null);
  if (!req.track) pinnedVersion = version;
  const res = await download(req.id, version);
  manifest.push({ id: req.id, version });
  console.log(`${res.cached ? 'cached ' : 'fetched'} ${req.id} ${version}`);
}

await writeFile(path.join(OUT, 'manifest.json'), JSON.stringify(manifest, null, 2));
console.log(`\nLocal feed ready: ${OUT} (${manifest.length} packages)`);
