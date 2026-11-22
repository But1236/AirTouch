#!/usr/bin/env node
/*
 * verify-pinyin-dict.mjs —— 离线校验 android/app/src/main/assets/pinyin_dict.txt。
 *
 * 为什么需要它：九宫格词典是「生成物 + 必须提交」的组合，最容易出的问题有两个 ——
 * 生成时悄悄缺字少词（比如源站被截断），以及提交后被手工改坏格式。构建脚本只在
 * 生成那一刻负责，之后没人再看；所以需要这个**不联网、不依赖任何下载源**的脚本，
 * 只读仓库里提交的那一份产物，在任何一台干净机器和 CI 上都能跑，任何一条不满足
 * 就以非 0 退出码结束。
 *
 * 校验项：
 *   1) 格式/解析：段头齐全、非注释行恰好一个 TAB、键值非空、行内字符不重复；
 *   2) 自洽性：数字键只含 2–9、键长合理，且 #words 里每个词都由 #chars 里
 *      对应汉字的键拼出来（这条把两个段落绑死，任何一侧被改坏都会立刻暴露）；
 *   3) 质量闸门：64426 -> 你好 必须排第一，64/426/33 以及常用功能字必须靠前；
 *   4) 覆盖率：不重复汉字数 >= 3500（打印确切数字）；
 *   5) 体积：40–150 KB 目标窗口（略越界只警告，不算失败）。
 *
 * 用法: node tools/verify-pinyin-dict.mjs [词典路径]
 *   （不传路径就校验仓库里那一份；传路径是为了在改动规则时拿副本做回归。）
 *
 * 注意：build 脚本 --force-fallback 生成的降级产物**应当**被本脚本判为失败
 * （它顶部有警告注释、覆盖率也只有一千多字），这正是本脚本要挡住的情况。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const DICT = process.argv[2]
  ? path.resolve(process.argv[2])
  : path.join(ROOT, 'android', 'app', 'src', 'main', 'assets', 'pinyin_dict.txt');

/** 汉字键长 1–6：单字母音节（啊=a→2）键长只能是 1，格式文档的示例行 `2 -> 啊阿吖嗄锕`
 *  就是这个情况，所以下界取 1 而不是 2。词键 = 各字键拼接，2–18。 */
const CHAR_KEY_LEN = [1, 6];
const WORD_KEY_LEN = [2, 18];

/** 「靠前」的判定：排在前 3 位即通过，同时打印真实名次，便于发现排序质量的退化。 */
const FRONT_N = 3;

const SIZE_MIN = 40 * 1024;
const SIZE_MAX = 150 * 1024;
const MIN_CHARS = 3500;

const errors = [];
const warnings = [];
const fail = (msg) => errors.push(msg);
const warn = (msg) => warnings.push(msg);

// ------------------------------------------------------------------ 读取

let text;
try {
  text = fs.readFileSync(DICT, 'utf8');
} catch (err) {
  console.error(`读取失败: ${DICT}\n  ${err.message}`);
  process.exit(1);
}
const bytes = Buffer.byteLength(text, 'utf8');
if (bytes === 0) fail('文件是空的');
if (text.charCodeAt(0) === 0xfeff) fail('文件带 UTF-8 BOM，Android 端的 BufferedReader 会把第一行读成 "\\uFEFF#chars"');
if (text.includes('\r')) fail('文件含 CR（应为 LF 换行）');

const lines = text.split('\n');
if (lines.length && lines[lines.length - 1] === '') lines.pop(); // 末尾换行

// ------------------------------------------------------------------ 解析

const charOrder = []; // 按出现顺序的字符键
const charMap = new Map(); // 字符键 -> 候选字数组
const wordOrder = [];
const wordMap = new Map(); // 数字键 -> 词数组

let section = null;
let seenCharsHeader = 0;
let seenWordsHeader = 0;

for (let i = 0; i < lines.length; i++) {
  const line = lines[i];
  const at = `第 ${i + 1} 行`;
  if (line === '') continue;
  if (line.startsWith('#')) {
    // 段头之外以 # 开头的行一律当注释跳过（格式约定允许）。
    if (line === '#chars') { seenCharsHeader++; section = 'chars'; }
    else if (line === '#words') { seenWordsHeader++; section = 'words'; }
    continue;
  }
  if (!section) { fail(`${at} 出现在任何段头之前: ${JSON.stringify(line.slice(0, 40))}`); continue; }

  const parts = line.split('\t');
  if (parts.length !== 2) {
    fail(`${at} 不是「键 TAB 值」（有 ${parts.length - 1} 个 TAB）: ${JSON.stringify(line.slice(0, 40))}`);
    continue;
  }
  const [key, value] = parts;
  if (!key) fail(`${at} 的键为空`);
  if (!value) fail(`${at} 的值为空`);
  if (!/^[2-9]+$/.test(key)) fail(`${at} 的键含 2–9 以外的字符: ${JSON.stringify(key)}`);

  if (section === 'chars') {
    const [lo, hi] = CHAR_KEY_LEN;
    if (key.length < lo || key.length > hi) fail(`${at} 汉字键长 ${key.length} 不在 ${lo}–${hi}: ${key}`);
    if (charMap.has(key)) { fail(`${at} 汉字键重复出现: ${key}`); continue; }
    const chars = [...value];
    if (chars.length !== value.length) fail(`${at} 值里有代理对残留`);
    const seen = new Set();
    for (const ch of chars) {
      if (seen.has(ch)) fail(`${at} 行内重复字符「${ch}」`);
      seen.add(ch);
      if (!isHan(ch.codePointAt(0))) fail(`${at} 出现非汉字字符「${ch}」(U+${ch.codePointAt(0).toString(16).toUpperCase()})`);
    }
    charMap.set(key, chars);
    charOrder.push(key);
  } else {
    const [lo, hi] = WORD_KEY_LEN;
    if (key.length < lo || key.length > hi) fail(`${at} 词键长 ${key.length} 不在 ${lo}–${hi}: ${key}`);
    if (wordMap.has(key)) { fail(`${at} 词键重复出现: ${key}`); continue; }
    const words = value.split(' ');
    if (words.some((w) => w === '')) fail(`${at} 词表里有空项（多余空格？）`);
    const seen = new Set();
    for (const w of words) {
      if (seen.has(w)) fail(`${at} 行内重复词「${w}」`);
      seen.add(w);
      const n = [...w].length;
      if (n < 2 || n > 3) fail(`${at} 词「${w}」长度 ${n} 不是 2–3`);
      if (![...w].every((c) => isHan(c.codePointAt(0)))) fail(`${at} 词「${w}」含非汉字字符`);
    }
    wordMap.set(key, words);
    wordOrder.push(key);
  }
}

function isHan(cp) {
  return (
    cp === 0x3007 || // 〇
    (cp >= 0x3400 && cp <= 0x4dbf) ||
    (cp >= 0x4e00 && cp <= 0x9fff) ||
    (cp >= 0xf900 && cp <= 0xfaff) ||
    (cp >= 0x20000 && cp <= 0x3ffff)
  );
}

// ------------------------------------------------------------------ 结构检查

if (lines[0] !== '#chars') fail(`第一行必须是 "#chars"（Android 端按段头顺序解析），实际是 ${JSON.stringify(lines[0]?.slice(0, 40))}`);
if (seenCharsHeader !== 1) fail(`#chars 段头出现 ${seenCharsHeader} 次，应为 1 次`);
if (seenWordsHeader !== 1) fail(`#words 段头出现 ${seenWordsHeader} 次，应为 1 次`);
if (charOrder.length === 0) fail('#chars 段没有任何条目');
if (wordOrder.length === 0) fail('#words 段没有任何条目');

// 同一个汉字只能挂在唯一一个键上（否则是按多音字重复收录，候选会重复出现）
const charToKey = new Map();
for (const key of charOrder) {
  for (const ch of charMap.get(key)) {
    if (charToKey.has(ch)) fail(`汉字「${ch}」同时出现在键 ${charToKey.get(ch)} 和 ${key}`);
    else charToKey.set(ch, key);
  }
}

// 自洽性：词 = 成员字键的顺序拼接。这一条同时验证了「词里的字都收在 #chars 里」。
let wordEntries = 0;
const allWords = new Set();
let mismatch = 0;
for (const key of wordOrder) {
  for (const w of wordMap.get(key)) {
    wordEntries++;
    if (allWords.has(w)) fail(`词「${w}」在文件中出现多次`);
    allWords.add(w);
    const parts = [...w].map((c) => charToKey.get(c));
    if (parts.some((p) => !p)) {
      const missing = [...w].filter((c) => !charToKey.has(c)).join('');
      mismatch++;
      if (mismatch <= 5) fail(`词「${w}」含未收录的字「${missing}」，无法从 #chars 推出键`);
      continue;
    }
    const expect = parts.join('');
    if (expect !== key) {
      mismatch++;
      if (mismatch <= 5) fail(`词「${w}」在键 ${key} 下，但按成员字拼音应为 ${expect}`);
    }
  }
}

// ------------------------------------------------------------------ 质量闸门

const rank = (list, item) => list.indexOf(item) + 1; // 0 表示不存在
const gateResults = [];
function gateChar(key, ch) {
  const list = charMap.get(key);
  if (!list) { gateResults.push([`${key} 含 ${ch}`, false, '键不存在']); return; }
  const r = rank(list, ch);
  gateResults.push([`${key} 含 ${ch}`, r > 0 && r <= FRONT_N, r > 0 ? `第 ${r} 位` : '缺失']);
}
function gateWordFirst(key, word) {
  const list = wordMap.get(key);
  if (!list) { gateResults.push([`${key} 首选 ${word}`, false, '键不存在']); return; }
  const r = rank(list, word);
  gateResults.push([`${key} 首选 ${word}`, r === 1, r > 0 ? `第 ${r} 位` : '缺失']);
}

gateWordFirst('64426', '你好'); // 题面点名：ni + hao
gateChar('64', '你');
gateChar('426', '好');
gateChar('33', '的');
for (const ch of '的了是在我不有人这') {
  const key = charToKey.get(ch);
  if (!key) { gateResults.push([`常用字 ${ch}`, false, '未收录']); continue; }
  const r = rank(charMap.get(key), ch);
  gateResults.push([`常用字 ${ch} 靠前`, r <= FRONT_N, `键 ${key} 第 ${r} 位`]);
}
for (const [name, ok, detail] of gateResults) if (!ok) fail(`质量闸门未通过: ${name}（${detail}）`);

/** 离线抽查用的「常用字」固定样本：语料单字频次前 200 名、且属于《通用规范汉字表》的字。
 *  样本已经固化在本文件里，所以校验过程不需要联网、也不需要任何外部词表。
 *  只要求「收录」，不要求名次 —— 名次由上面的质量闸门负责。 */
const COMMON_CHARS =
  '了是在和有他不我的人也为就这上年中你说一到都等着对来与地还要又大而之道以得她个后去将那但从月下把被于时只多过可并能好会出或日由里用所向已其给很看使前新想却它最见起小高更如再才便没走做让内及听成各事号至叫两当三本无此们家长市门正同吃天比米外即分问话打老自副跟笑则像较国死省住万因曾元呢手达头作该女开路约军称名二每属谁受带进先应吧县心占处区太点水南张今间总回山请者党段法连站第共另生全行倒快派啊相吗经按找';
const missingCommon = [...COMMON_CHARS].filter((ch) => !charToKey.has(ch));
const commonOk = missingCommon.length === 0;
if (!commonOk) fail(`常用字样本缺 ${missingCommon.length} 个: ${missingCommon.join('')}`);

// ------------------------------------------------------------------ 覆盖 & 体积

const distinctChars = charToKey.size;
if (distinctChars < MIN_CHARS) fail(`汉字覆盖 ${distinctChars} < ${MIN_CHARS}`);
if (bytes < SIZE_MIN || bytes > SIZE_MAX) {
  warn(`体积 ${bytes} 字节 (${(bytes / 1024).toFixed(1)} KB) 略出目标窗口 40–150 KB`);
}

// ------------------------------------------------------------------ 报告

console.log('pinyin_dict.txt 校验（离线，不联网）');
console.log(`  路径      ${path.relative(ROOT, DICT)}`);
console.log(`  体积      ${bytes} 字节 (${(bytes / 1024).toFixed(1)} KB)，目标 40–150 KB`);
console.log(`  汉字      ${distinctChars} 个，${charOrder.length} 个数字键`);
console.log(`  词条      ${wordEntries} 条，${wordOrder.length} 个数字键`);
console.log('  质量闸门');
for (const [name, ok, detail] of gateResults) console.log(`    ${ok ? '✓' : '✗'} ${name} —— ${detail}`);
console.log(`  覆盖率    ${distinctChars} 个不重复汉字 ${distinctChars >= MIN_CHARS ? '>=' : '<'} ${MIN_CHARS} ${distinctChars >= MIN_CHARS ? '✓' : '✗'}`);
console.log(`  常用字    ${COMMON_CHARS.length - missingCommon.length}/${COMMON_CHARS.length} 个固定样本已收录 ${commonOk ? '✓' : '✗'}`);
console.log(`  自洽性    #words 的键全部可由 #chars 的汉字键拼出 ${mismatch === 0 ? '✓' : `✗（${mismatch} 条不符）`}`);

for (const w of warnings) console.log(`  ! 警告    ${w}`);
if (errors.length) {
  console.log(`\n结果: 失败（${errors.length} 项）`);
  for (const e of errors.slice(0, 40)) console.log(`  ✗ ${e}`);
  if (errors.length > 40) console.log(`  ... 另有 ${errors.length - 40} 项`);
  process.exit(1);
}
console.log('\n结果: 通过');
