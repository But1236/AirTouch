#!/usr/bin/env node
/*
 * wss-probe.mjs - independent end-to-end probe of the AirTouch WSS endpoint.
 *
 * Why this exists: the Windows sandbox this project was built in cannot acquire Schannel client
 * credentials (SEC_E_NO_CREDENTIALS), so a .NET client cannot open a TLS connection there. Node uses
 * its own OpenSSL stack - the same TLS family Android's OkHttp/Conscrypt uses - so this probe
 * verifies the real, production-configuration wss:// endpoint:
 *
 *   1. TLS handshake against the self-signed certificate, with an explicit SHA-256 fingerprint check
 *   2. RFC 6455 upgrade handshake (verifying Sec-WebSocket-Accept)
 *   3. wrong-token rejection (error frame + close code 1008)
 *   4. pairing with the one-time token, welcome frame
 *   5. control frames (move / click / scroll / reset)
 *   6. client -> server file upload with a hostile file name, verified on disk
 *   7. server-initiated ping being answered (client replies with a pong frame)
 *
 * Usage (the server URL/token/fingerprint come from the server's stdout lines):
 *   node tools/wss-probe.mjs --url "wss://127.0.0.1:8443/ws?token=..." \
 *        --fingerprint "AA:BB:..." --download "C:\...\downloads"
 */
import tls from 'node:tls';
import { randomBytes, createHash } from 'node:crypto';
import { readFileSync, existsSync, statSync, writeFileSync, readdirSync } from 'node:fs';
import path from 'node:path';

// --------------------------------------------------------------------------- args

const argv = process.argv.slice(2);
const arg = (name, fallback = null) => {
  const index = argv.indexOf(`--${name}`);
  return index >= 0 && index + 1 < argv.length ? argv[index + 1] : fallback;
};

const initialUrl = arg('url');
const expectedFingerprint = (arg('fingerprint') ?? '').toUpperCase();
const downloadDir = arg('download');
const connectHost = arg('host', '127.0.0.1');

if (!initialUrl || !downloadDir) {
  console.error('用法: node tools/wss-probe.mjs --url <wss url> --fingerprint <sha256> --download <dir>');
  process.exit(2);
}

const results = [];
let failures = 0;

function check(name, ok, detail = '') {
  results.push({ name, ok, detail });
  if (!ok) failures++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' :: ' + detail : ''}`);
}

function info(message) {
  console.log(`      · ${message}`);
}

// ------------------------------------------------------------------- web socket

const OPCODE = { TEXT: 0x1, BINARY: 0x2, CLOSE: 0x8, PING: 0x9, PONG: 0xa };

class WebSocketClient {
  constructor(socket, leftover = Buffer.alloc(0)) {
    this.socket = socket;
    this.buffer = leftover;
    this.frames = [];
    this.waiters = [];
    this.closed = null;
    this.pings = 0;

    socket.on('data', (chunk) => {
      this.buffer = Buffer.concat([this.buffer, chunk]);
      this.drain();
    });
    socket.on('close', () => {
      this.closed = this.closed ?? { code: null, reason: 'socket closed' };
      this.wake();
    });
    socket.on('error', (error) => {
      this.closed = this.closed ?? { code: null, reason: error.message };
      this.wake();
    });

    // Frames that arrived in the same TCP segment as the handshake response (the welcome frame
    // usually does) are already in the buffer and must be parsed now.
    if (this.buffer.length > 0) {
      this.drain();
    }
  }

  drain() {
    for (;;) {
      if (this.buffer.length < 2) return;
      const first = this.buffer[0];
      const second = this.buffer[1];
      const fin = (first & 0x80) !== 0;
      const opcode = first & 0x0f;
      const masked = (second & 0x80) !== 0;
      let length = second & 0x7f;
      let offset = 2;

      if (length === 126) {
        if (this.buffer.length < offset + 2) return;
        length = this.buffer.readUInt16BE(offset);
        offset += 2;
      } else if (length === 127) {
        if (this.buffer.length < offset + 8) return;
        const big = this.buffer.readBigUInt64BE(offset);
        length = Number(big);
        offset += 8;
      }

      let maskKey = null;
      if (masked) {
        if (this.buffer.length < offset + 4) return;
        maskKey = this.buffer.subarray(offset, offset + 4);
        offset += 4;
      }

      if (this.buffer.length < offset + length) return;
      let payload = this.buffer.subarray(offset, offset + length);
      if (masked) {
        const unmasked = Buffer.alloc(length);
        for (let i = 0; i < length; i++) {
          unmasked[i] = payload[i] ^ maskKey[i % 4];
        }
        payload = unmasked;
      }

      this.buffer = this.buffer.subarray(offset + length);

      if (opcode === OPCODE.PING) {
        this.pings++;
        this.sendFrame(OPCODE.PONG, payload);
        continue;
      }

      if (opcode === OPCODE.CLOSE) {
        const code = payload.length >= 2 ? payload.readUInt16BE(0) : null;
        const reason = payload.length > 2 ? payload.subarray(2).toString('utf8') : '';
        this.closed = { code, reason };
        this.wake();
        continue;
      }

      if (!fin) {
        // Fragmentation is not used by this server; treat as unexpected but harmless.
        info(`收到分片帧（opcode ${opcode}），已忽略`);
        continue;
      }

      this.frames.push({ opcode, payload });
      this.wake();
    }
  }

  wake() {
    for (const waiter of this.waiters.splice(0)) {
      waiter();
    }
  }

  /** Waits until a frame matching the predicate arrives, or the socket closes. */
  async waitFor(predicate, timeoutMs = 5000) {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const index = this.frames.findIndex(predicate);
      if (index >= 0) {
        return this.frames.splice(index, 1)[0];
      }
      if (this.closed) return null;
      const remaining = deadline - Date.now();
      if (remaining <= 0) return null;

      await new Promise((resolve) => {
        const timer = setTimeout(resolve, remaining);
        this.waiters.push(() => {
          clearTimeout(timer);
          resolve();
        });
      });
    }
  }

  async waitText(type, timeoutMs = 5000) {
    const frame = await this.waitFor(
      (f) => f.opcode === OPCODE.TEXT && safeJson(f.payload)?.type === type,
      timeoutMs,
    );
    return frame === null ? null : safeJson(frame.payload);
  }

  async waitBinaryTotal(expectedLength, timeoutMs = 15000) {
    const collected = [];
    let total = 0;
    const deadline = Date.now() + timeoutMs;

    while (total < expectedLength && Date.now() < deadline) {
      const frame = await this.waitFor((f) => f.opcode === OPCODE.BINARY, deadline - Date.now());
      if (frame === null) break;
      collected.push(frame.payload);
      total += frame.payload.length;
    }

    return Buffer.concat(collected);
  }

  async waitClose(timeoutMs = 5000) {
    const deadline = Date.now() + timeoutMs;
    while (!this.closed && Date.now() < deadline) {
      await new Promise((resolve) => {
        const timer = setTimeout(resolve, deadline - Date.now());
        this.waiters.push(() => {
          clearTimeout(timer);
          resolve();
        });
      });
    }
    return this.closed;
  }

  sendFrame(opcode, payload) {
    const maskKey = randomBytes(4);
    const length = payload.length;
    let header;

    if (length < 126) {
      header = Buffer.alloc(2);
      header[1] = 0x80 | length;
    } else if (length < 65536) {
      header = Buffer.alloc(4);
      header[1] = 0x80 | 126;
      header.writeUInt16BE(length, 2);
    } else {
      header = Buffer.alloc(10);
      header[1] = 0x80 | 127;
      header.writeBigUInt64BE(BigInt(length), 2);
    }

    header[0] = 0x80 | opcode;

    const masked = Buffer.alloc(length);
    for (let i = 0; i < length; i++) {
      masked[i] = payload[i] ^ maskKey[i % 4];
    }

    this.socket.write(Buffer.concat([header, maskKey, masked]));
  }

  sendText(value) {
    this.sendFrame(OPCODE.TEXT, Buffer.from(value, 'utf8'));
  }

  sendBinary(buffer) {
    this.sendFrame(OPCODE.BINARY, buffer);
  }

  close(code = 1000, reason = 'bye') {
    const payload = Buffer.alloc(2 + Buffer.byteLength(reason));
    payload.writeUInt16BE(code, 0);
    payload.write(reason, 2);
    this.sendFrame(OPCODE.CLOSE, payload);
  }
}

function safeJson(buffer) {
  try {
    return JSON.parse(buffer.toString('utf8'));
  } catch {
    return null;
  }
}

// ------------------------------------------------------------------------ connect

function parseUrl(url) {
  const parsed = new URL(url);
  return {
    host: parsed.hostname,
    port: Number(parsed.port || (parsed.protocol === 'wss:' ? 443 : 80)),
    path: parsed.pathname + parsed.search,
    secure: parsed.protocol === 'wss:',
    token: parsed.searchParams.get('token'),
  };
}

/** Opens the TLS socket (verifying the fingerprint) and performs the RFC 6455 upgrade. */
async function connect(target, overrideToken = null) {
  const socket = await new Promise((resolve, reject) => {
    const raw = tls.connect(
      {
        host: connectHost,
        port: target.port,
        // The certificate is self-signed and pinned by fingerprint below instead.
        rejectUnauthorized: false,
        minVersion: 'TLSv1.2',
      },
      () => resolve(raw),
    );
    raw.once('error', reject);
    raw.setTimeout(8000, () => reject(new Error('TLS 握手超时')));
  });

  const peer = socket.getPeerCertificate();
  const fingerprint = (peer.fingerprint256 ?? '').toUpperCase();
  const protocol = socket.getProtocol();

  const key = randomBytes(16).toString('base64');
  const expect = createHash('sha1')
    .update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11')
    .digest('base64');

  const requestPath = overrideToken === null
    ? target.path
    : `/ws?token=${encodeURIComponent(overrideToken)}`;

  const request =
    `GET ${requestPath} HTTP/1.1\r\n` +
    `Host: ${target.host}:${target.port}\r\n` +
    'Upgrade: websocket\r\n' +
    'Connection: Upgrade\r\n' +
    `Sec-WebSocket-Key: ${key}\r\n` +
    'Sec-WebSocket-Version: 13\r\n' +
    '\r\n';

  socket.write(request);

  const response = await new Promise((resolve, reject) => {
    let data = Buffer.alloc(0);
    const onData = (chunk) => {
      data = Buffer.concat([data, chunk]);
      const text = data.toString('latin1');
      const headerEnd = text.indexOf('\r\n\r\n');
      if (headerEnd >= 0) {
        socket.off('data', onData);
        // Everything after the header block is already WebSocket framing; hand it to the client
        // instead of unshifting it, so no frame can be lost between the two listeners.
        resolve({ header: text.slice(0, headerEnd), leftover: data.subarray(headerEnd + 4) });
      }
    };
    socket.on('data', onData);
    socket.once('error', reject);
    setTimeout(() => reject(new Error('WebSocket 升级超时')), 8000);
  });

  const status = response.header.split('\r\n')[0];
  const accept = /sec-websocket-accept:\s*(\S+)/i.exec(response.header)?.[1] ?? '';

  return { socket, status, accept, expect, fingerprint, protocol, leftover: response.leftover };
}

async function withConnection(target, overrideToken, body) {
  const session = await connect(target, overrideToken);
  const client = new WebSocketClient(session.socket, session.leftover);
  try {
    return await body({ ...session, client });
  } finally {
    try {
      client.close();
      session.socket.end();
    } catch {
      /* ignore */
    }
  }
}

// --------------------------------------------------------------------------- main

const target = parseUrl(initialUrl);
console.log('AirTouch wss:// 独立探测（Node + OpenSSL，手工实现 RFC 6455）');
console.log(`目标：${target.secure ? 'wss' : 'ws'}://${target.host}:${target.port}${target.path.replace(/token=.*/, 'token=<hidden>')}`);
console.log('');

// --- 1/2: TLS + upgrade with a deliberately wrong token (must be rejected cleanly)
await withConnection(target, '00000000-0000-4000-8000-000000000000', async (session) => {
  check('TLS 握手完成', session.status.includes('101'), `${session.protocol} / ${session.status}`);
  check('服务端证书指纹与服务器自报一致', session.fingerprint === expectedFingerprint,
    session.fingerprint || '<无证书>');
  check('Sec-WebSocket-Accept 计算正确', session.accept === session.expect, session.accept);
  check('HTTP 状态行为 101 Switching Protocols', session.status.startsWith('HTTP/1.1 101'), session.status);

  const error = await session.client.waitText('error', 5000);
  check('伪造 token 收到 error 帧', error !== null, JSON.stringify(error));
  if (error) {
    check('错误码为 token_invalid', error.code === 'token_invalid', error.code);
  }

  const closed = await session.client.waitClose(5000);
  check('服务端以 1008 关闭连接', closed?.code === 1008, `close code ${closed?.code ?? '<none>'}`);
});

// --- 3/4/5/6: pair with the real one-time token
await withConnection(target, null, async (session) => {
  const welcome = await session.client.waitText('welcome', 5000);
  check('使用正确 token 收到 welcome', welcome !== null, JSON.stringify(welcome));
  if (welcome) {
    check('welcome 声明 chunkSize 为 65536', welcome.chunkSize === 65536, String(welcome.chunkSize));
    check('welcome 声明 maxFileSize', typeof welcome.maxFileSize === 'number' && welcome.maxFileSize > 0,
      String(welcome.maxFileSize));
  }

  session.client.sendText(JSON.stringify({ type: 'hello', deviceName: 'node-probe', appVersion: '1.0.0' }));
  session.client.sendText(JSON.stringify({ type: 'move', dx: 12, dy: -8 }));
  session.client.sendText(JSON.stringify({ type: 'click', button: 'left' }));
  session.client.sendText(JSON.stringify({ type: 'scroll', amount: 2 }));
  session.client.sendText(JSON.stringify({ type: 'reset' }));

  session.client.sendText(JSON.stringify({ type: 'ping' }));
  const pong = await session.client.waitText('pong', 5000);
  check('文本 ping 得到 pong', pong !== null);

  // --- file upload with a directory traversal attempt in the name
  const payload = randomBytes(180 * 1024);
  session.client.sendText(JSON.stringify({
    type: 'file_header',
    fileName: '../../../evil/../probe-payload.bin',
    fileSize: payload.length,
  }));
  for (let offset = 0; offset < payload.length; offset += 65536) {
    session.client.sendBinary(payload.subarray(offset, Math.min(offset + 65536, payload.length)));
  }

  const ack = await session.client.waitText('file_ack', 20000);
  check('上传后收到 file_ack', ack !== null, JSON.stringify(ack));

  if (ack) {
    check('上传状态为 ok', ack.status === 'ok', ack.status);

    // "probe-payload.bin", or "probe-payload (1).bin" when a previous run left a file behind —
    // either way the directory components of "../../../evil/../probe-payload.bin" must be gone.
    const sanitised = /^probe-payload( \(\d+\))?\.bin$/.test(ack.fileName ?? '');
    check('文件名被清洗（仅保留文件名，必要时加去重后缀）', sanitised, ack.fileName);

    const saved = ack.savedPath ?? '';
    const insideDownloadDir = path.resolve(saved).toLowerCase().startsWith(path.resolve(downloadDir).toLowerCase() + path.sep);
    check('文件保存到下载目录内（未穿越）', insideDownloadDir, saved);

    if (existsSync(saved)) {
      const onDisk = readFileSync(saved);
      check('落盘内容与上传内容一致', onDisk.length === payload.length && onDisk.equals(payload),
        `${onDisk.length} / ${payload.length} 字节`);
    } else {
      check('落盘文件存在', false, saved);
    }

    const leftovers = readdirSync(downloadDir).filter((name) => name.endsWith('.part'));
    check('没有残留 .part 文件', leftovers.length === 0, leftovers.join(', '));
  }

  // --- second upload replicating the Android client's exact send sequence, including the optional
  //     file_end frame it emits after the last chunk, and a non-ASCII file name
  const second = randomBytes(70000);
  session.client.sendText(JSON.stringify({ type: 'hello', deviceName: 'android-shape', appVersion: '1.0.0' }));
  session.client.sendText(JSON.stringify({ type: 'file_header', fileName: '报告 2026.pdf', fileSize: second.length }));
  for (let offset = 0; offset < second.length; offset += 65536) {
    session.client.sendBinary(second.subarray(offset, Math.min(offset + 65536, second.length)));
  }
  session.client.sendText(JSON.stringify({ type: 'file_end' }));

  const ack2 = await session.client.waitText('file_ack', 20000);
  check('Android 形态上传（header+chunks+file_end）收到 file_ack', ack2 !== null, JSON.stringify(ack2));
  if (ack2) {
    check('中文文件名端到端未被破坏', ack2.fileName === '报告 2026.pdf', ack2.fileName);
    check('中文文件名上传状态 ok', ack2.status === 'ok', ack2.status);

    const saved2 = ack2.savedPath ?? '';
    const inside2 = path.resolve(saved2).toLowerCase().startsWith(path.resolve(downloadDir).toLowerCase() + path.sep);
    check('中文文件名仍在下载目录内', inside2, saved2);
    check('中文文件名落盘内容一致',
      existsSync(saved2) && readFileSync(saved2).equals(second),
      existsSync(saved2) ? `${statSync(saved2).size} / ${second.length} 字节` : '文件不存在');
  }

  // file_end 是「对端声明结束」：字节数已满足时不应再产生第二个 file_ack
  session.client.sendText(JSON.stringify({ type: 'ping' }));
  check('file_end 之后连接仍可用', (await session.client.waitText('pong', 5000)) !== null);

  const extraAcks = session.client.frames
    .filter((f) => f.opcode === OPCODE.TEXT && safeJson(f.payload)?.type === 'file_ack').length;
  check('file_end 没有产生重复 file_ack', extraAcks === 0, `剩余未读 ack：${extraAcks}`);

  // 未知类型必须被忽略且不影响连接（协议 §2.3）
  session.client.sendText(JSON.stringify({ type: 'something_new', value: 1 }));
  session.client.sendText(JSON.stringify({ type: 'ping' }));
  check('未知类型被忽略且连接存活', (await session.client.waitText('pong', 5000)) !== null);

  // The serve mode uses a 2 s ping interval with a 5 s watchdog tick, so a ping lands within ~7 s;
  // wait up to 15 s so a slower tick can never turn this into a false failure.
  info('等待最多 15 秒以观测服务端 WebSocket Ping 帧…');
  const pingDeadline = Date.now() + 15000;
  while (session.client.pings < 1 && Date.now() < pingDeadline && session.client.closed === null) {
    await new Promise((resolve) => setTimeout(resolve, 250));
  }

  check('服务端发送过 WebSocket Ping 帧', session.client.pings >= 1, `收到 ${session.client.pings} 个 ping`);
  check('客户端已自动回 Pong（连接未被服务端判定为失活）', session.client.closed === null,
    session.client.closed ? `已关闭：${session.client.closed.reason}` : '连接保持');

  session.client.sendText(JSON.stringify({ type: 'reset' }));
  const stillAlive = await session.client.waitFor(() => false, 300);
  check('心跳之后连接仍然可用', session.client.closed === null && stillAlive === null);
});

// --- 3: the phone dropped off the Wi-Fi and reconnects with the token it already used.
//        That is the *normal* recovery path (the server rotates the token on disconnect), so it must
//        be rejected without consuming the three-strike brute-force budget — otherwise a brief
//        network blip would permanently ban the legitimate phone.
async function attempt(overrideToken) {
  let outcome = null;
  await withConnection(target, overrideToken, async (session) => {
    const error = await session.client.waitText('error', 5000);
    const closeCode = (await session.client.waitClose(5000))?.code ?? null;
    outcome = { code: error?.code ?? null, closeCode };
  });
  return outcome;
}

await new Promise((resolve) => setTimeout(resolve, 800)); // let the server observe the disconnect first

const replay = await attempt(target.token);
check('重放已用过的 token 被拒绝',
  replay.code === 'token_expired' || replay.code === 'busy',
  JSON.stringify(replay));
check('重放不会因 token 校验失败而误判为封禁', replay.code !== 'banned', JSON.stringify(replay));

// Three genuinely wrong tokens must still be counted from scratch: the replay above used up none of
// the budget, so the ban has to arrive on the third bogus attempt, not earlier.
const bogus = [];
for (let i = 0; i < 3; i++) {
  bogus.push((await attempt('11111111-2222-4333-8444-55555555555' + i)).code);
}
info(`伪造 token 依次返回：${bogus.join(' → ')}`);
check('前两次错误 token 只是 token_invalid（重放未占用封禁额度）',
  bogus[0] === 'token_invalid' && bogus[1] === 'token_invalid', bogus.join(','));
check('第三次错误 token 才触发封禁', bogus[2] === 'banned', String(bogus[2]));

// --- summary
console.log('');
console.log('-'.repeat(64));
console.log(`结果：通过 ${results.length - failures}，失败 ${failures}`);
if (failures > 0) {
  console.log('失败项：');
  for (const result of results.filter((r) => !r.ok)) {
    console.log(`  - ${result.name}${result.detail ? ' :: ' + result.detail : ''}`);
  }
}

// Machine readable summary for the caller.
if (process.env.AIRTOUCH_PROBE_REPORT) {
  writeFileSync(process.env.AIRTOUCH_PROBE_REPORT, JSON.stringify({ results, failures }, null, 2));
}

process.exit(failures === 0 ? 0 : 1);
