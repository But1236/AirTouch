// 把 PNG 解码成 ASCII 亮度图，用于在没有图像查看能力时检查窗口布局。
// 用法: node tools/png-dump.mjs <png> [cols] [rows]
import { readFileSync } from 'node:fs';
import { inflateSync } from 'node:zlib';

const [, , file, colsArg, rowsArg] = process.argv;
const cols = Number(colsArg || 96);
const rows = Number(rowsArg || 40);

const buf = readFileSync(file);
if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error('not a png');

let pos = 8;
let width = 0, height = 0, bitDepth = 0, colorType = 0, interlace = 0;
const idat = [];
while (pos < buf.length) {
  const len = buf.readUInt32BE(pos);
  const type = buf.toString('ascii', pos + 4, pos + 8);
  const data = buf.subarray(pos + 8, pos + 8 + len);
  if (type === 'IHDR') {
    width = data.readUInt32BE(0);
    height = data.readUInt32BE(4);
    bitDepth = data[8];
    colorType = data[9];
    interlace = data[12];
  } else if (type === 'IDAT') {
    idat.push(data);
  } else if (type === 'IEND') {
    break;
  }
  pos += 12 + len;
}

if (bitDepth !== 8) throw new Error(`unsupported bit depth ${bitDepth}`);
if (interlace !== 0) throw new Error('interlaced png unsupported');
const channels = { 0: 1, 2: 3, 4: 2, 6: 4 }[colorType];
if (!channels) throw new Error(`unsupported color type ${colorType}`);

const raw = inflateSync(Buffer.concat(idat));
const stride = width * channels;
const out = Buffer.alloc(height * stride);

for (let y = 0; y < height; y++) {
  const filter = raw[y * (stride + 1)];
  const line = raw.subarray(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride);
  const cur = out.subarray(y * stride, (y + 1) * stride);
  const prev = y > 0 ? out.subarray((y - 1) * stride, y * stride) : Buffer.alloc(stride);
  for (let x = 0; x < stride; x++) {
    const a = x >= channels ? cur[x - channels] : 0;
    const b = prev[x];
    const c = x >= channels ? prev[x - channels] : 0;
    let v = line[x];
    if (filter === 1) v += a;
    else if (filter === 2) v += b;
    else if (filter === 3) v += (a + b) >> 1;
    else if (filter === 4) {
      const p = a + b - c;
      const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
      v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
    }
    cur[x] = v & 0xff;
  }
}

const lum = (x, y) => {
  const i = y * stride + x * channels;
  if (channels >= 3) return (out[i] * 299 + out[i + 1] * 587 + out[i + 2] * 114) / 1000;
  return out[i];
};

const ramp = ' .:-=+*#%@';
console.log(`${file}  ${width}x${height}  colorType=${colorType}`);
console.log(`+${'-'.repeat(cols)}+`);
for (let r = 0; r < rows; r++) {
  let line = '';
  for (let c = 0; c < cols; c++) {
    const x0 = Math.floor((c * width) / cols);
    const x1 = Math.max(x0 + 1, Math.floor(((c + 1) * width) / cols));
    const y0 = Math.floor((r * height) / rows);
    const y1 = Math.max(y0 + 1, Math.floor(((r + 1) * height) / rows));
    let sum = 0, n = 0;
    for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) { sum += lum(x, y); n++; }
    const v = sum / n;
    line += ramp[Math.min(ramp.length - 1, Math.round((255 - v) / 25.6))];
  }
  console.log(`|${line}|`);
}
console.log(`+${'-'.repeat(cols)}+`);

// 白色区域（二维码底板）的包围盒 + 上/下半区的深色像素统计
let minX = width, minY = height, maxX = -1, maxY = -1;
for (let y = 0; y < height; y++) {
  for (let x = 0; x < width; x++) {
    if (lum(x, y) > 245) {
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
  }
}
console.log(`white bbox: x ${minX}..${maxX}  y ${minY}..${maxY}`);

const band = (y0, y1) => {
  let dark = 0, total = 0;
  for (let y = y0; y < y1; y++) {
    for (let x = minX; x <= maxX; x++) {
      total++;
      if (lum(x, y) < 128) dark++;
    }
  }
  return total ? (dark / total) * 100 : 0;
};
const mid = Math.round((minY + maxY) / 2);
console.log(`dark %% top half    : ${band(minY, mid).toFixed(1)}`);
console.log(`dark %% bottom half : ${band(mid, maxY + 1).toFixed(1)}`);
for (let i = 0; i < 10; i++) {
  const y0 = minY + Math.floor(((maxY - minY + 1) * i) / 10);
  const y1 = minY + Math.floor(((maxY - minY + 1) * (i + 1)) / 10);
  console.log(`  band ${i}: y ${y0}..${y1}  dark ${band(y0, y1).toFixed(1)}%`);
}
