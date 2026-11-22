package com.airtouch.app.input.keyboard.t9

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.IOException
import java.io.Reader
import java.util.concurrent.Executors

/**
 * 内置拼音词库（九键中文输入的基础）。
 *
 * ## 为什么非要一份词库
 * 九键的每一次按键都对应 3~4 个字母（`5` = jkl），所以「用户到底打的是哪个拼音」在按键层面是
 * **不可知**的 —— 只有词库能把 `5426` 还原成 `jian`/`lian`/`jiao` 再落到汉字。电脑端的输入法
 * 帮不上忙：九键这种键位本来就不是它认得的输入方式，而中文也没法用虚拟键码表达（见协议 §2.7）。
 *
 * ## 资源格式
 * `assets/pinyin_dict.txt`，按行、制表符分隔，由 `tools/build-pinyin-dict.mjs` 生成：
 * ```
 * #chars
 * 64<TAB>你尼泥呢…
 * #words
 * 64426<TAB>你好
 * ```
 * - `#chars`：键是**某个音节的九键数字**，值是这个数字下所有音节的汉字，按字频降序。
 *   注意不同音节可能落在同一个数字上（`9426` 既可能是 `xian` 也可能是 `zhao`），
 *   它们**共用一行** —— 这正是九键想要的：打 `9426` 就该同时看到「先」和「找」。
 * - `#words`：整词候选，键是各字音节数字的拼接（`你好` = `64` + `426` = `64426`），按词频降序。
 *   生成脚本按每个字的**主拼音**拼键，所以多音字词会与真实读音不一致（`银行` 落在 `9469464`，
 *   因为「行」取的是 zhǎng 的音）—— 这类词查不到，会退化成逐音节候选。这是刻意的妥协：要「修正」
 *   它就得同时改生成脚本和这里的查询规则。
 * - `#` 开头的行与空行都是注释。
 *
 * ## 线程
 * 解析在后台线程做（资源约几十 KB、上万个汉字，主线程上解析会卡住第一帧），完成后回主线程回调。
 * 没加载完时 [cached] 返回 null，界面据此显示「词库载入中」，而不是假装没有候选。
 */
class PinyinDictionary(
    private val charsByDigits: Map<String, String>,
    private val wordsByDigits: Map<String, List<String>>,
) {

    /** 某个数字序列上的候选汉字（按字频降序）。没有对应汉字返回空串 */
    fun charsFor(digits: String): String = charsByDigits[digits].orEmpty()

    /** 某个数字序列对应的整词候选（按词频降序） */
    fun wordsFor(digits: String): List<String> = wordsByDigits[digits].orEmpty()

    /** 这个数字序列能不能自成一个音节（拼音切分时用） */
    fun isSyllable(digits: String): Boolean = charsByDigits.containsKey(digits)

    /** 词库是否成功读到了内容。空词库只可能是「资源损坏/没打进包」，界面要如实提示 */
    val isEmpty: Boolean get() = charsByDigits.isEmpty()

    companion object {
        const val ASSET_NAME = "pinyin_dict.txt"

        private const val SECTION_CHARS = "#chars"
        private const val SECTION_WORDS = "#words"

        private const val SECTION_NONE = 0
        private const val SECTION_CHARS_ID = 1
        private const val SECTION_WORDS_ID = 2

        @Volatile
        private var instance: PinyinDictionary? = null

        private val loader = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "pinyin-dict-load").apply { isDaemon = true }
        }
        private val mainHandler = Handler(Looper.getMainLooper())

        /** 已经加载好的词库；还在加载中返回 null */
        fun cached(): PinyinDictionary? = instance

        /**
         * 后台加载一次并缓存在进程里，完成后在主线程回调 [onLoaded]。
         *
         * 重复调用只会解析一次：词库是不可变数据，进程内共享即可。
         */
        fun loadAsync(context: Context, onLoaded: (PinyinDictionary) -> Unit) {
            instance?.let {
                onLoaded(it)
                return
            }
            val appContext = context.applicationContext
            loader.execute {
                val dictionary = try {
                    appContext.assets.open(ASSET_NAME).bufferedReader().use { parse(it) }
                } catch (e: IOException) {
                    // 读不到就退化成空词库：九键仍然能打英文/标点，只是没有中文候选，
                    // 比让整个键盘崩掉要好。
                    PinyinDictionary(emptyMap(), emptyMap())
                }
                instance = dictionary
                mainHandler.post { onLoaded(dictionary) }
            }
        }

        /** 解析词库文本。纯函数、不碰 Android，便于单独验证 */
        fun parse(reader: Reader): PinyinDictionary {
            val chars = HashMap<String, String>()
            val words = HashMap<String, MutableList<String>>()
            var section = SECTION_NONE
            val lines = if (reader is BufferedReader) reader else reader.buffered()

            while (true) {
                val raw = lines.readLine() ?: break
                val text = raw.trim()
                if (text.isEmpty()) continue

                if (text.startsWith("#")) {
                    section = when {
                        text.startsWith(SECTION_CHARS) -> SECTION_CHARS_ID
                        text.startsWith(SECTION_WORDS) -> SECTION_WORDS_ID
                        else -> section
                    }
                    continue
                }

                val tab = text.indexOf('\t')
                if (tab <= 0) continue
                val key = text.substring(0, tab).trim()
                val value = text.substring(tab + 1).trim()
                if (key.isEmpty() || value.isEmpty()) continue

                when (section) {
                    SECTION_CHARS_ID -> chars[key] = value
                    SECTION_WORDS_ID -> words.getOrPut(key) { ArrayList() }
                        .addAll(value.split(' ').filter { it.isNotEmpty() })
                }
            }
            return PinyinDictionary(chars, words)
        }
    }
}
