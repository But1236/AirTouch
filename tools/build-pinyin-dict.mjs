#!/usr/bin/env node
/*
 * build-pinyin-dict.mjs —— 生成九宫格（T9）拼音词典 android/app/src/main/assets/pinyin_dict.txt。
 *
 * 【为什么需要它】
 * 九宫格拼音在字母层面是有歧义的（5 键 = j/k/l），手机端不能调用系统输入法，
 * 只能自己带一份「数字串 -> 候选汉字/词」的紧凑词典。词典是构建期产物，
 * 运行时零网络，所以它必须进仓库；而多兆字节的原始语料不进仓库（见 .gitignore）。
 *
 * 【输出格式】纯文本、UTF-8、LF、制表符分隔，一行一个数字键：
 *   #chars
 *   2<TAB>啊阿锕吖嗄          <- 键 = 该字「去声调主读音」的 T9 数字串；值按常用度降序
 *   #words
 *   64426<TAB>你好            <- 键 = 词中每个字主读音数字串的顺序拼接；值按词频降序，空格分隔
 * 以 # 开头的其它行、空行都是注释，解析器应跳过。
 *
 * 【数据来源与许可】（两个源都是 MIT，和本仓库的 MIT 许可兼容；均只下载到
 *  tools/pinyin-source/，该目录被 .gitignore 忽略）
 *   1) 读音：mozillazg/pinyin-data 的 pinyin.txt
 *      https://raw.githubusercontent.com/mozillazg/pinyin-data/master/pinyin.txt
 *      MIT License, Copyright (c) 2016 mozillazg
 *      （数据本身含 Unicode Unihan kMandarin 等公开资料）
 *   2) 字频 + 词表 + 词频：fxsjy/jieba 的 jieba/dict.txt
 *      https://raw.githubusercontent.com/fxsjy/jieba/master/jieba/dict.txt
 *      MIT License, Copyright (c) 2012 fxsjy
 *      格式：词 频次 词性
 *   （备选 rime-essay 的 essay.txt 是 LGPL-3.0，且它的单字频次多挂在繁体字形上
 *     ——「为/这/说」等简体字的频次是 0，故未采用。）
 *
 * 【用法】
 *   node tools/build-pinyin-dict.mjs                 正常生成（已缓存语料时不需要联网）
 *   node tools/build-pinyin-dict.mjs --dry-run       只统计不写文件（用来调参）
 *   node tools/build-pinyin-dict.mjs --force-fallback 跳过下载，强制走内置小词表（演练降级路径）
 *
 * 【失败策略】
 * 源文件下不下来、或下载结果明显被截断时，本脚本会直接报错退出（不写半成品词典）；
 * 只有在显式指定、或确实无网且本地也没有缓存时，才退回内置的极简词表：那种产物会在
 * 文件顶部写警告注释、并以退出码 3 收场，而且一定过不了 tools/verify-pinyin-dict.mjs
 * （覆盖率只有一千多字）—— 这是刻意的，降级产物必须显眼，不能被误当成正式词典。
 *
 * 【收录口径】
 *   汉字：只收在 jieba 语料里留有痕迹的字（有单字频次或出现在词条里），共约 12000 个，
 *         覆盖语料 100% 的单字出现次数。
 *   词条：二字词按词频取前 7500、三字词取前 300。上限不是随手定的：质量闸门点名的
 *         「你好」在二字词里排第 6162 名，所以上限必须明显大于它。
 *   生成结果应落在 40–150 KB；改了 WORD_LIMITS 或换了语料请重跑 verify 脚本。
 */
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SRC_DIR = path.join(ROOT, 'tools', 'pinyin-source');
const OUT_FILE = path.join(ROOT, 'android', 'app', 'src', 'main', 'assets', 'pinyin_dict.txt');

const DRY_RUN = process.argv.includes('--dry-run');
const FORCE_FALLBACK = process.argv.includes('--force-fallback');

// ---------------------------------------------------------------- 数据源定义

const SOURCES = [
  {
    key: 'readings',
    file: 'pinyin.txt',
    url: 'https://raw.githubusercontent.com/mozillazg/pinyin-data/master/pinyin.txt',
    license: 'MIT (c) 2016 mozillazg',
    // 上游约 0.94 MB / 44000+ 行；门限留足余量，低于它基本可以断定是截断或错误页面。
    minBytes: 700_000,
    minLines: 35_000,
  },
  {
    key: 'lexicon',
    file: 'jieba-dict.txt',
    url: 'https://raw.githubusercontent.com/fxsjy/jieba/master/jieba/dict.txt',
    license: 'MIT (c) 2012 fxsjy',
    // 上游约 4.84 MB / 349000+ 行。
    minBytes: 3_500_000,
    minLines: 250_000,
  },
];

// ---------------------------------------------------------------- 词典规模参数
//
// 目标窗口 40–150 KB。花字节的地方有两处：汉字本体（每字 3 字节 UTF-8）和词条
// （每个二字词 6 字节 + 一个几乎独享的 5 位数字键，摊下来约 13 字节/词）。
// 值取在窗口中部，留出余量，避免上游语料变动就把产物顶出窗口。

// ---------------------------------------------------------------- 收录口径
//
// 汉字：只收在 jieba 词库里留有痕迹的字（有单字频次，或出现在某个词条里）。
//   为什么不做「基本区全收」：U+4E00–U+9FFF 全收是 20924 字、约 63 KB，其中 8915 字
//   在语料里一次都没出现过（异体字/死字/字形变体），白占约 26 KB；这些字节换成词条
//   更划算。实测这 12000 字覆盖了语料 100.00% 的单字出现次数，并且经人工核对，
//   《通用规范汉字表》一级字表 3500 字一个不缺（verify 脚本再用 200 字固定样本做离线
//   回归）。代价是二/三级字表里约 700 个罕用字没有收录。

/** 词条收录上限（按词频降序取前 N 条）。注意 你好 在 jieba 二字词里排第 6162 名，
 *  所以 WORD_LIMITS[2] 必须明显大于它，否则质量闸门 64426 -> 你好 会失守。 */
const WORD_LIMITS = {
  2: 7500, // 二字词：T9 拼音输入的主力
  3: 300, // 三字词：挑最常用的少量
};

/** 在词库里完全找不到旁证的字（多为语料乱码字），单字频次按此系数折算。 */
const UNCORROBORATED_CHAR_PENALTY = 0.02;

/** 接口文档里点名出现过的示例字（格式示例行 `2<TAB>啊阿吖嗄锕`）里，读音顺序和上游
 *  不一致的那个：pinyin.txt 记作「yā,ā」，而 Unihan kMandarin 与文档示例都按 ā 处理。
 *  这里强制指定它的键为 2，保证文档示例逐字可复现；其余示例字（啊/阿/嗄/锕）本来就对。 */
const FORCED_CHARS = new Map([['吖', '2']]);

// ---------------------------------------------------------------- T9 工具

const T9 = {
  a: 2, b: 2, c: 2, d: 3, e: 3, f: 3, g: 4, h: 4, i: 4,
  j: 5, k: 5, l: 5, m: 6, n: 6, o: 6, p: 7, q: 7, r: 7, s: 7,
  t: 8, u: 8, v: 8, w: 9, x: 9, y: 9, z: 9,
};

/**
 * 拼音 -> 去声调、只留 a-z（ü/ǖǘǚǜ 一律折成 v，ê 折成 e）。
 * 注意 ü 必须在 NFD 分解之前替换：分解后它是 u + 组合分音符，正则就匹配不到了。
 * 好在 u 与 v 都落在 8 键上，即便漏掉，数字串也不会错。
 */
function toneless(pinyin) {
  return String(pinyin)
    .replace(/[üÜ]/g, 'v')
    .replace(/[êÊ]/g, 'e')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z]/g, '');
}

/** 音节 -> 数字键；含非 T9 字符或空音节时返回 null。 */
function syllableKey(pinyin) {
  const s = toneless(pinyin);
  if (!s) return null;
  let k = '';
  for (const ch of s) {
    const d = T9[ch];
    if (!d) return null;
    k += d;
  }
  return k;
}

const isCjk = (cp) =>
  cp === 0x3007 || // 〇
  (cp >= 0x3400 && cp <= 0x4dbf) ||
  (cp >= 0x4e00 && cp <= 0x9fff) ||
  (cp >= 0xf900 && cp <= 0xfaff) ||
  (cp >= 0x20000 && cp <= 0x3ffff);

// ---------------------------------------------------------------- 下载与校验

async function fileInfo(file) {
  try {
    const st = await fsp.stat(file);
    return st.isFile() ? st : null;
  } catch {
    return null;
  }
}

async function countLines(file) {
  const text = await fsp.readFile(file, 'utf8');
  let n = 0;
  for (let i = 0; i < text.length; i++) if (text.charCodeAt(i) === 10) n++;
  return { lines: n, text };
}

/** 下载到 tools/pinyin-source/；已有且通过体积门限的缓存直接复用。 */
async function ensureSource(src) {
  await fsp.mkdir(SRC_DIR, { recursive: true });
  const dest = path.join(SRC_DIR, src.file);

  const cached = await fileInfo(dest);
  if (cached && cached.size >= src.minBytes) {
    console.log(`  复用缓存 ${src.file} (${(cached.size / 1048576).toFixed(2)} MB)`);
    return { file: dest, fromCache: true };
  }

  let lastErr = null;
  for (let attempt = 1; attempt <= 4; attempt++) {
    const tmp = dest + '.part';
    try {
      const res = await fetch(src.url, {
        headers: { 'user-agent': 'airtouch-pinyin-dict-builder' },
        redirect: 'follow',
      });
      if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText}`);
      // 流式落盘：源文件有好几兆，整包 arrayBuffer 在弱网下更容易被中途掐断，
      // 而且截断的响应会「成功」返回一段短内容——所以先写 .part，再用体积门限卡住。
      await pipeline(Readable.fromWeb(res.body), fs.createWriteStream(tmp));
      const st = await fsp.stat(tmp);
      if (st.size < src.minBytes) {
        // 最常见的坏情况：拿到的是错误页或半截响应。宁可直接失败，也不写坏词典。
        throw new Error(`下载体积只有 ${st.size} 字节，低于门限 ${src.minBytes}，疑似被截断`);
      }
      await fsp.rename(tmp, dest);
      console.log(`  下载 ${src.file} (${(st.size / 1048576).toFixed(2)} MB) <- ${src.url}`);
      return { file: dest, fromCache: false };
    } catch (err) {
      lastErr = err;
      await fsp.rm(tmp, { force: true });
      console.warn(`  第 ${attempt} 次下载 ${src.file} 失败: ${err.message}`);
      if (attempt < 4) await new Promise((r) => setTimeout(r, 2000 * attempt));
    }
  }

  const stale = await fileInfo(dest);
  if (stale && stale.size > 0) {
    console.warn(`  ${src.url} 不可达，退回本地残存缓存 ${src.file}（${stale.size} 字节）`);
    return { file: dest, fromCache: true, degraded: true };
  }
  throw new Error(`数据源不可达且本地无缓存: ${src.url}\n  最后一次错误: ${lastErr?.message}`);
}

/** 读源文件并做完整性抽查；任何一项不过就抛错，绝不生成半成品词典。 */
async function loadSources() {
  console.log('读取数据源（tools/pinyin-source/，已在 .gitignore 中忽略）...');
  for (const src of SOURCES) console.log(`  · ${src.file}  ${src.license}  ${src.url}`);
  const got = {};
  for (const src of SOURCES) got[src.key] = await ensureSource(src);

  // ---- 读音表 ----
  const readings = new Map(); // codepoint -> [读音...]
  {
    const { text, lines } = await countLines(got.readings.file);
    if (lines < SOURCES[0].minLines) {
      throw new Error(`pinyin.txt 只有 ${lines} 行，低于门限 ${SOURCES[0].minLines}，疑似被截断`);
    }
    for (const raw of text.split('\n')) {
      const m = /^U\+([0-9A-Fa-f]+):\s*([^#]*)/.exec(raw.trim());
      if (!m) continue;
      const list = m[2].split(',').map((s) => s.trim()).filter(Boolean);
      if (list.length) readings.set(parseInt(m[1], 16), list);
    }
    if (readings.size < 35_000) throw new Error(`pinyin.txt 只解析出 ${readings.size} 条读音，疑似被截断`);
    // 抽查：常用字的主读音必须对得上。
    const expect = [['你', 'ni'], ['好', 'hao'], ['中', 'zhong'], ['的', 'de'], ['啊', 'a']];
    for (const [ch, want] of expect) {
      const got0 = toneless(readings.get(ch.codePointAt(0))?.[0] ?? '');
      if (got0 !== want) {
        throw new Error(`pinyin.txt 抽查失败：${ch} 的主读音应为 ${want}，实际为 ${got0 || '(缺失)'}`);
      }
    }
    console.log(`  读音表 ${readings.size} 个字，抽查 你/好/中/的 通过`);
  }

  // ---- 词库 ----
  const words = new Map(); // 词 -> 频次
  {
    const { text, lines } = await countLines(got.lexicon.file);
    if (lines < SOURCES[1].minLines) {
      throw new Error(`jieba dict.txt 只有 ${lines} 行，低于门限 ${SOURCES[1].minLines}，疑似被截断`);
    }
    for (const raw of text.split('\n')) {
      if (!raw) continue;
      const a = raw.lastIndexOf(' ');
      if (a < 0) continue;
      const b = raw.lastIndexOf(' ', a - 1);
      if (b < 0) continue;
      const w = raw.slice(0, b);
      const f = Number(raw.slice(b + 1, a));
      if (!w || !Number.isFinite(f) || f <= 0) continue;
      const prev = words.get(w);
      if (prev === undefined || f > prev) words.set(w, f);
    }
    if (words.size < 250_000) throw new Error(`jieba 词库只解析出 ${words.size} 条，疑似被截断`);
    for (const w of ['你好', '中国', '我们', '的']) {
      if (!(words.get(w) > 0)) throw new Error(`jieba 词库抽查失败：缺少常用词条「${w}」`);
    }
    console.log(`  词库 ${words.size} 条，抽查 你好/中国/我们 通过`);
  }

  return { readings, words, degraded: !!(got.readings.degraded || got.lexicon.degraded) };
}

// ---------------------------------------------------------------- 降级词表（最后手段）
//
// 只有在「下载失败且本地无缓存」时才会被用到：这份表是从正式产物里抽出来的极小样本
// （每个数字键保留最常用的 6 个字，外加一百多个最高频词），够键盘出字、能过质量闸门
// 里的逐条抽查，但覆盖率远低于 3500 字的门槛。所以 verify-pinyin-dict.mjs 一定会判它
// 不合格——这是刻意的：降级产物必须显眼，不能被误当成正式词典。生成时还会在文件顶部
// 写警告注释、并以退出码 3 收场。
const FALLBACK_CHARS = [
  ['2', '啊阿锕吖嗄'],
  ['3', '鄂俄恶额饿娥'],
  ['6', '哦嗯噢喔呣'],
  ['22', '把吧罢八巴擦'],
  ['23', '侧测策册厕恻'],
  ['24', '此比次爱必刺'],
  ['26', '按安波暗案氨'],
  ['28', '不部布步补粗'],
  ['32', '大打达法发答'],
  ['33', '的得德徳锝嘚'],
  ['34', '地第低底敌帝'],
  ['36', '恩摁蒽峎煾'],
  ['37', '而二儿耳尔饵'],
  ['38', '副府复读度赴'],
  ['42', '哈伽嘎噶呷蛤'],
  ['43', '和个各河喝合'],
  ['48', '湖故股古忽虎'],
  ['52', '拉啦卡喀辣剌'],
  ['53', '了可科克客乐'],
  ['54', '里及即既极急'],
  ['58', '路据居率哭举'],
  ['62', '那吗拿马哪骂'],
  ['63', '呢么讷嚜麼嚒'],
  ['64', '你米泥密尼拟'],
  ['66', '末摸魔莫抹磨'],
  ['68', '女墓木目怒藕'],
  ['72', '怕爬撒洒萨啪'],
  ['73', '热色惹涩瑟穑'],
  ['74', '日其起死四气'],
  ['76', '破颇坡泊迫泼'],
  ['78', '去如区入取铺'],
  ['82', '他她它塔踏塌'],
  ['83', '特忒忑慝蟘鋱'],
  ['84', '提体题替踢梯'],
  ['88', '图土吐徒突涂'],
  ['92', '呀亚压瓦挖牙'],
  ['93', '也则页叶业夜'],
  ['94', '一以已自字子'],
  ['96', '我握窝哟卧斡'],
  ['98', '与于无鱼五足'],
  ['224', '才白菜拜摆百'],
  ['226', '报办半抱草包'],
  ['234', '被北背倍杯备'],
  ['236', '本锛奔涔苯笨'],
  ['242', '茶差插查察叉'],
  ['243', '别车扯撤憋鳖'],
  ['244', '吃持赤迟翅池'],
  ['246', '滨彬斌宾濒鬓'],
  ['248', '出处除初楚触'],
  ['264', '昂盎肮卬骯醠'],
  ['268', '凑辏腠輳'],
  ['284', '催翠脆崔摧淬'],
  ['286', '村错剉存撮寸'],
  ['324', '带待代戴呆岱'],
  ['326', '到道但倒反刀'],
  ['334', '非飞费废妃肥'],
  ['336', '分份粉坟粪焚'],
  ['343', '爹跌叠碟迭牒'],
  ['348', '丢丟铥'],
  ['368', '都斗豆抖兜否'],
  ['384', '对队堆兑怼碓'],
  ['386', '多吨躲夺顿墩'],
  ['424', '还该海改盖害'],
  ['426', '好高号干汉搞'],
  ['434', '给黑嘿黒潶給'],
  ['436', '很跟根恨狠痕'],
  ['468', '后厚够狗沟勾'],
  ['482', '话花画化挂华'],
  ['484', '会回归鬼贵挥'],
  ['486', '过或国火活获'],
  ['524', '来开赖莱揩凯'],
  ['526', '看老靠考烤砍'],
  ['534', '类累泪雷勒垒'],
  ['536', '肯啃恳垦龈裉'],
  ['542', '家加架假甲价'],
  ['543', '接街皆届界解'],
  ['546', '进今近金仅尽'],
  ['548', '就酒流六九久'],
  ['568', '口楼扣搂叩漏'],
  ['582', '跨垮夸挎胯骻'],
  ['583', '觉绝略决掘掠'],
  ['584', '亏夔盔溃窥魁'],
  ['586', '军均郡落论轮'],
  ['624', '买卖乃脉埋麦'],
  ['626', '南难满毛男冒'],
  ['634', '没内每美枚煤'],
  ['636', '们门嫩闷焖恁'],
  ['643', '灭捏镍涅孽聂'],
  ['646', '您民敏闽抿闵'],
  ['648', '牛扭纽妞谬钮'],
  ['668', '某谋牟鍪缪蛑'],
  ['683', '虐疟硸瘧'],
  ['686', '诺挪喏糯懦搦'],
  ['724', '派拍排牌塞赛'],
  ['726', '三跑散盘然炮'],
  ['734', '陪配赔佩裴胚'],
  ['736', '人任认忍喷仁'],
  ['742', '杀啥沙傻恰砂'],
  ['743', '设且射社切蛇'],
  ['744', '是时使事市式'],
  ['746', '亲秦品琴擒钦'],
  ['748', '属书数求球树'],
  ['768', '肉艘搜柔揉嗽'],
  ['782', '挼'],
  ['783', '却确缺阙雀鹊'],
  ['784', '虽岁随遂碎睿'],
  ['786', '所若群孙弱缩'],
  ['824', '太台抬态泰钛'],
  ['826', '谈坛逃套叹探'],
  ['843', '铁贴帖萜餮呫'],
  ['868', '头投透偷骰緰'],
  ['884', '退推腿褪蜕颓'],
  ['886', '脱拖托陀吞驼'],
  ['924', '在再外载歪灾'],
  ['926', '要万早完言眼'],
  ['934', '为未位贼微味'],
  ['936', '问文怎闻温吻'],
  ['942', '下辖夏吓扎瞎'],
  ['943', '这着者些写折'],
  ['944', '之只至直知指'],
  ['946', '新因心信银引'],
  ['948', '住主朱驻诸著'],
  ['968', '有又由走右游'],
  ['983', '月约学越曰血'],
  ['984', '最嘴罪醉蕞朘'],
  ['986', '做作坐左座云'],
  ['2264', '藏浜帮邦镑棒'],
  ['2364', '曾层甭崩蹦绷'],
  ['2424', '拆柴钗豺虿侪'],
  ['2426', '便边朝变产表'],
  ['2436', '臣陈沉趁宸尘'],
  ['2464', '并兵病柄冰饼'],
  ['2468', '抽臭仇瞅丑愁'],
  ['2482', '欻歘'],
  ['2484', '吹垂锤棰捶椎'],
  ['2486', '春纯戳醇唇蠢'],
  ['2664', '从璁丛葱聪淙'],
  ['2826', '窜蹿篡撺爨熶'],
  ['3264', '当党放方房防'],
  ['3364', '等风封奉灯登'],
  ['3426', '点电掉调殿店'],
  ['3464', '定顶丁盯钉订'],
  ['3664', '东动洞懂冬董'],
  ['3826', '段断短端缎锻'],
  ['4264', '刚岗港钢纲杭'],
  ['4364', '更横哼羹耕恒'],
  ['4664', '共红供宫功公'],
  ['4824', '怪坏怀拐淮乖'],
  ['4826', '官管关换观环'],
  ['5264', '抗廊狼郎朗炕'],
  ['5364', '冷愣坑棱楞铿'],
  ['5426', '见叫较间连剑'],
  ['5464', '另经竟令惊领'],
  ['5664', '龙空孔恐隆控'],
  ['5824', '快块筷脍哙侩'],
  ['5826', '涓宽乱卷款卵'],
  ['6264', '忙囊芒盲蟒茫'],
  ['6364', '能梦猛蒙锰孟'],
  ['6426', '年面庙秒念鸟'],
  ['6464', '名明命宁鸣凝'],
  ['6664', '弄浓农脓哝侬'],
  ['6826', '暖煖'],
  ['7264', '让旁胖嚷丧桑'],
  ['7364', '仍僧捧扔碰砰'],
  ['7424', '晒筛酾簛'],
  ['7426', '前山少钱瞧桥'],
  ['7436', '身甚神深伸审'],
  ['7464', '请清平情轻凭'],
  ['7468', '手受首收守授'],
  ['7482', '刷耍唰誜'],
  ['7484', '谁水睡税氵涗'],
  ['7486', '说顺硕朔舜瞬'],
  ['7664', '送宋松容荣溶'],
  ['7826', '全算权酸软劝'],
  ['8264', '唐汤堂糖躺烫'],
  ['8364', '疼腾藤滕誊痋'],
  ['8426', '天条跳田挑甜'],
  ['8464', '听停厅挺亭廷'],
  ['8664', '同通铜痛统童'],
  ['8826', '团湍疃鏄抟湪'],
  ['9264', '王往杨望网忘'],
  ['9364', '增翁赠瓮嗡憎'],
  ['9424', '寨斋窄摘宅债'],
  ['9426', '小笑先县占站'],
  ['9436', '真镇阵震针朕'],
  ['9464', '应行性型形英'],
  ['9468', '州周洲轴皱粥'],
  ['9482', '抓髽簻'],
  ['9484', '追锥坠缀赘惴'],
  ['9486', '准捉桌灼啄卓'],
  ['9664', '用总纵宗拥涌'],
  ['9826', '元原远选院愿'],
  ['24264', '常场厂唱尝肠'],
  ['24364', '成称城呈承乘'],
  ['24664', '冲虫充崇宠舂'],
  ['24824', '揣踹啜膪嘬搋'],
  ['24826', '穿传船川串喘'],
  ['48264', '黄光广皇慌晃'],
  ['54264', '将两讲量江降'],
  ['54664', '炯迥窘熲炅囧'],
  ['58264', '矿狂旷劻况匡'],
  ['64264', '娘酿孃嬢'],
  ['74264', '上强伤抢墙尚'],
  ['74364', '省生声胜升圣'],
  ['74664', '穷琼跫穹邛芎'],
  ['74824', '摔甩衰帅蟀'],
  ['74826', '拴闩栓涮'],
  ['94264', '向想长像张相'],
  ['94364', '正争证政怔睁'],
  ['94664', '中种重众钟兄'],
  ['94824', '拽跩'],
  ['94826', '转专砖赚撰馔'],
  ['248264', '床闯创窗幢疮'],
  ['748264', '双霜爽孀塽樉'],
  ['948264', '装状撞庄壮桩'],
];

const FALLBACK_WORDS = [
  ['2222', '爸爸'],
  ['2243', '八个'],
  ['2253', '罢了'],
  ['2254', '巴黎'],
  ['2274', '八旗'],
  ['2294', '巴西'],
  ['2296', '把握'],
  ['2422', '糍粑'],
  ['2424', '此次'],
  ['243', '阿哥'],
  ['2434', '此地'],
  ['2438', '必读'],
  ['2453', '此刻'],
  ['2454', '比例'],
  ['2458', '此举'],
  ['2474', '次日'],
  ['2478', '比如'],
  ['2493', '毕业'],
  ['2494', '慈禧'],
  ['2498', '必须'],
  ['254', '阿里'],
  ['2626', '暗暗'],
  ['2638', '安抚'],
  ['2653', '博客'],
  ['2654', '玻璃'],
  ['2658', '安陆'],
  ['2662', '安娜'],
  ['2666', '按摩'],
  ['2674', '波斯'],
  ['2694', '脖子'],
  ['2824', '不必'],
  ['2826', '不安'],
  ['2832', '步伐'],
  ['2833', '不得'],
  ['2838', '不服'],
  ['2848', '不顾'],
  ['2853', '不可'],
  ['2854', '不利'],
  ['2858', '布局'],
  ['2872', '不怕'],
  ['2878', '不如'],
  ['2894', '不易'],
  ['2898', '不足'],
  ['3226', '答案'],
  ['3228', '发布'],
  ['3232', '发达'],
  ['3234', '大地'],
  ['3238', '大夫'],
  ['3243', '大哥'],
  ['3248', '大湖'],
  ['3254', '打击'],
  ['3258', '法律'],
  ['3262', '大妈'],
  ['3264', '大米'],
  ['3274', '大批'],
  ['3276', '打破'],
  ['3278', '打入'],
  ['3284', '大体'],
  ['3293', '大业'],
  ['3294', '法子'],
  ['3298', '发育'],
  ['3363', '份额'],
  ['3394', '得以'],
  ['3428', '地步'],
  ['3432', '抵达'],
  ['3434', '弟弟'],
  ['3437', '第二'],
  ['3453', '地壳'],
  ['3454', '地理'],
  ['3474', '第四'],
  ['3478', '地区'],
  ['3482', '恶化'],
  ['3486', '俄国'],
  ['3488', '地图'],
  ['3494', '第一'],
  ['3498', '低于'],
  ['3543', '恶劣'],
  ['3626', '鄂南'],
  ['3724', '二次'],
  ['3736', '恶人'],
  ['374', '饿死'],
  ['3743', '二哥'],
  ['3754', '二级'],
  ['3758', '二路'],
  ['3768', '儿女'],
  ['3794', '儿子'],
  ['3826', '赌博'],
  ['3828', '腹部'],
  ['3834', '腹地'],
  ['3838', '夫妇'],
  ['3843', '符合'],
  ['3848', '富户'],
  ['3854', '独立'],
  ['3858', '俘虏'],
  ['3862', '驸马'],
  ['3866', '抚摸'],
  ['3868', '父母'],
  ['3874', '夫妻'],
  ['3883', '独特'],
  ['3892', '复杂'],
  ['3893', '负责'],
  ['3894', '复习'],
  ['3898', '服务'],
  ['3924', '额外'],
  ['394', '鄂西'],
  ['3944', '遏制'],
  ['3946', '恶心'],
  ['4242', '哈哈'],
  ['4324', '何必'],
  ['4326', '胳膊'],
  ['4328', '各部'],
  ['4332', '合法'],
  ['4334', '各地'],
  ['4343', '各个'],
  ['4348', '合乎'],
  ['4354', '各级'],
  ['4358', '格局'],
  ['4378', '歌曲'],
  ['5394', '可以'],
  ['543', '饥饿'],
  ['583', '巨额'],
  ['64426', '你好'],
  ['743663', '什么'],
  ['94664486', '中国'],
  ['96636', '我们'],
];

/** 降级路径直接用现成的「键 -> 内容」行，不再反推拼音，避免再造一套易错的转换。 */
function fallbackDict() {
  const wordMap = new Map();
  for (const [key, word] of FALLBACK_WORDS) {
    if (!wordMap.has(key)) wordMap.set(key, []);
    wordMap.get(key).push(word);
  }
  return {
    charOut: FALLBACK_CHARS.map(([key, chars]) => [key, chars]),
    wordOut: [...wordMap.entries()].sort((a, b) => (a[0] < b[0] ? -1 : 1)),
    wordCount: FALLBACK_WORDS.length,
  };
}

// ---------------------------------------------------------------- 组装词典

function buildDictionary(readings, words) {
  const singleFreq = new Map(); // 字 -> 单字频次
  const wordSupport = new Map(); // 字 -> 含有该字的词条频次之和（乱码字/死字没有旁证）
  for (const [w, f] of words) {
    const chars = [...w];
    if (chars.length === 1) {
      const prev = singleFreq.get(w);
      if (prev === undefined || f > prev) singleFreq.set(w, f);
    } else if (chars.length <= 6 && chars.every((c) => isCjk(c.codePointAt(0)))) {
      for (const c of new Set(chars)) wordSupport.set(c, (wordSupport.get(c) ?? 0) + f);
    }
  }

  // ---- 收集汉字 ----
  const charKey = new Map(); // 字 -> 数字键
  const charScore = new Map(); // 字 -> 排序分
  for (const [cp, list] of readings) {
    if (!isCjk(cp)) continue;
    const ch = String.fromCodePoint(cp);
    const forced = FORCED_CHARS.get(ch);
    const key = forced ?? syllableKey(list[0]);
    if (!key) continue;
    const sf = singleFreq.get(ch) ?? 0;
    const sup = wordSupport.get(ch) ?? 0;
    if (sf <= 0 && sup <= 0 && !forced) continue; // 语料里查无此字：不收（见上面的收录口径）
    charKey.set(ch, key);
    // 排序分：有单字频次就用单字频次（语料乱码字没有词条旁证，先打个折），
    // 没有单字频次就用词条支持度，两者都没有的排到最后（按码位）。
    charScore.set(ch, sf > 0 ? sf * (sup > 0 ? 1 : UNCORROBORATED_CHAR_PENALTY) : sup);
  }

  // ---- 收集词条：只收纯汉字、且每个字都有键的二字/三字词 ----
  const wordList = [];
  for (const [w, f] of words) {
    const chars = [...w];
    const n = chars.length;
    const limit = WORD_LIMITS[n];
    if (!limit) continue;
    if (!chars.every((c) => isCjk(c.codePointAt(0)))) continue;
    const keys = chars.map((c) => charKey.get(c));
    if (keys.some((k) => !k)) continue;
    wordList.push({ word: w, freq: f, key: keys.join('') });
  }
  wordList.sort((a, b) => b.freq - a.freq || (a.word < b.word ? -1 : 1));

  const byLength = { 2: [], 3: [] };
  for (const x of wordList) byLength[[...x.word].length].push(x);
  const picked = new Map(); // 数字键 -> [{word, freq}]
  let wordCount = 0;
  for (const n of [2, 3]) {
    for (const x of byLength[n].slice(0, WORD_LIMITS[n])) {
      if (!picked.has(x.key)) picked.set(x.key, []);
      picked.get(x.key).push(x);
      wordCount++;
    }
  }

  // ---- 按数字键聚合 ----
  const charLines = new Map();
  for (const [ch, key] of charKey) {
    if (!charLines.has(key)) charLines.set(key, []);
    charLines.get(key).push(ch);
  }

  const cmpKey = (a, b) => (a.length - b.length) || (a < b ? -1 : 1);
  const charOut = [...charLines.entries()]
    .map(([key, chars]) => {
      chars.sort(
        (a, b) =>
          (charScore.get(b) - charScore.get(a)) ||
          (a.codePointAt(0) - b.codePointAt(0)),
      );
      return [key, chars.join('')];
    })
    .sort((a, b) => cmpKey(a[0], b[0]));

  const wordOut = [...picked.entries()]
    .map(([key, list]) => {
      list.sort((a, b) => b.freq - a.freq || (a.word < b.word ? -1 : 1));
      return [key, list.map((x) => x.word).join(' ')];
    })
    .sort((a, b) => cmpKey(a[0], b[0]));

  return { charOut, wordOut, wordCount };
}

// ---------------------------------------------------------------- 输出

function serialize({ charOut, wordOut }, degraded) {
  const lines = [];
  if (degraded) {
    lines.push('# !! 降级产物：上游语料不可达，本文件来自内置极简词表，覆盖率远低于正式词典 !!');
    lines.push('# !! 请联网后重新执行 node tools/build-pinyin-dict.mjs 生成正式词典           !!');
  }
  lines.push('#chars');
  for (const [k, v] of charOut) lines.push(`${k}\t${v}`);
  lines.push('#words');
  for (const [k, v] of wordOut) lines.push(`${k}\t${v}`);
  return lines.join('\n') + '\n';
}

async function main() {
  let dict;
  let degraded = false;
  if (FORCE_FALLBACK) {
    console.warn('!! --force-fallback：跳过下载，使用内置极简词表（仅用于演练降级路径）');
    dict = fallbackDict();
    degraded = true;
  } else {
    let readings, words, stale;
    try {
      ({ readings, words, degraded: stale } = await loadSources());
    } catch (err) {
      console.error('\n数据源准备失败：');
      console.error('  ' + String(err.message).split('\n').join('\n  '));
      console.error('\n为避免写出「看起来正常、其实缺字缺词」的静默降级词典，这里直接终止，未写任何文件。');
      console.error('如需演练降级路径，可显式执行： node tools/build-pinyin-dict.mjs --force-fallback');
      process.exit(1);
    }
    if (stale) console.warn('!! 注意：有数据源用的是残存缓存，产物质量不可保证');
    dict = buildDictionary(readings, words);
    degraded = stale;
  }

  const text = serialize(dict, degraded);
  const bytes = Buffer.byteLength(text, 'utf8');

  const distinctChars = dict.charOut.reduce((s, [, v]) => s + [...v].length, 0);
  const kb = bytes / 1024;
  console.log('\n生成结果：');
  console.log(`  汉字      ${distinctChars} 个（${dict.charOut.length} 个数字键）`);
  console.log(`  词条      ${dict.wordCount} 条（${dict.wordOut.length} 个数字键）`);
  console.log(`  输出体积  ${bytes} 字节 (${kb.toFixed(1)} KB)`);
  if (bytes < 40 * 1024 || bytes > 150 * 1024) {
    console.warn(`  !! 体积落在目标窗口 40–150 KB 之外，需要调整 WORD_LIMITS`);
  }

  if (DRY_RUN) {
    console.log('  --dry-run：未写文件');
    return;
  }
  await fsp.mkdir(path.dirname(OUT_FILE), { recursive: true });
  await fsp.writeFile(OUT_FILE, text, 'utf8');
  console.log(`  已写入    ${path.relative(ROOT, OUT_FILE)}`);
  if (degraded) process.exit(3); // 降级产物：以非 0 退出码提醒调用方
}

await main();
