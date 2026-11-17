// tools/dl.mjs —— 用 Node 自带的 OpenSSL 下载文件。
// 受限环境里 Schannel 可能拿不到客户端凭据（SEC_E_NO_CREDENTIALS），curl / PowerShell 会失败，
// 这个脚本用 Node 的 OpenSSL 绕开；普通桌面会话直接用 curl 即可。
// 用法: node tools\dl.mjs <url> <输出文件>
import fs from 'node:fs';
import path from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';

const [url, out] = process.argv.slice(2);
if (!url || !out) {
  console.error('用法: node tools\\dl.mjs <url> <输出文件>');
  process.exit(1);
}

const started = Date.now();
const res = await fetch(url);
if (!res.ok) {
  console.error(`下载失败: HTTP ${res.status} ${res.statusText}`);
  process.exit(1);
}

const total = Number(res.headers.get('content-length') || 0);
let done = 0;
let lastTick = 0;

const body = Readable.fromWeb(res.body);
body.on('data', (chunk) => {
  done += chunk.length;
  const now = Date.now();
  if (now - lastTick > 1000) {
    lastTick = now;
    const pct = total ? ((done / total) * 100).toFixed(1) + '%' : '';
    process.stdout.write(`\r  ${(done / 1048576).toFixed(1)} MB  ${pct}   `);
  }
});

fs.mkdirSync(path.dirname(path.resolve(out)), { recursive: true });
const tmp = out + '.part';
await pipeline(body, fs.createWriteStream(tmp));
fs.renameSync(tmp, out);

const secs = ((Date.now() - started) / 1000).toFixed(0);
process.stdout.write(`\r  完成: ${out}  (${(done / 1048576).toFixed(1)} MB, ${secs}s)\n`);