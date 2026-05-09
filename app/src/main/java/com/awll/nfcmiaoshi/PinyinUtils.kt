package com.awll.nfcmiaoshi

import net.sourceforge.pinyin4j.PinyinHelper

/**
 * 拼音工具类
 */
object PinyinUtils {

    /**
     * 获取联系人姓名的首字母拼音
     * 使用 pinyin4j 将中文字符转为拼音，再取首字母
     *
     * @param name 姓名
     * @return 首字母（A-Z），无法判断时返回空字符串
     */
    fun getPinyinInitial(name: String): String {
        if (name.isEmpty()) return ""
        val firstChar = name.firstOrNull { c ->
            c in 'A'..'Z' || c in 'a'..'z' || c in '\u4E00'..'\u9FFF' || c in '0'..'9'
        } ?: return ""

        if (firstChar in '0'..'9') return "#"
        if (firstChar in 'A'..'Z') return firstChar.toString()
        if (firstChar in 'a'..'z') return firstChar.uppercaseChar().toString()

        return try {
            val pinyins = PinyinHelper.toHanyuPinyinStringArray(firstChar)
            pinyins?.firstOrNull()?.firstOrNull { it in 'A'..'Z' || it in 'a'..'z' }?.uppercaseChar()?.toString() ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
