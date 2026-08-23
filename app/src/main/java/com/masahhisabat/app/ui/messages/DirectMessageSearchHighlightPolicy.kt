package com.masahhisabat.app.ui.messages

/**
 * يحدد نطاقات تمييز المطابقات داخل النص المعروض من دون تغيير النص أو ترتيبه.
 * النطاقات غير متداخلة لتبقى القراءة مرئية وواضحة في بطاقة الرسالة.
 */
object DirectMessageSearchHighlightPolicy {
    fun matchRanges(displayedText: String, query: String): List<IntRange> {
        val normalizedQuery = DirectMessageSearchTextNormalizer.normalize(query)
        if (normalizedQuery.isBlank()) return emptyList()

        val mappedText = DirectMessageSearchTextNormalizer.normalizeWithIndexes(displayedText)
        if (mappedText.value.isBlank()) return emptyList()

        val ranges = mutableListOf<IntRange>()
        var nextSearchStart = 0
        while (nextSearchStart <= mappedText.value.length - normalizedQuery.length) {
            val matchStart = mappedText.value.indexOf(normalizedQuery, nextSearchStart)
            if (matchStart < 0) break
            val matchEnd = matchStart + normalizedQuery.length - 1
            val originalStart = expandStart(displayedText, mappedText.originalIndexes[matchStart])
            val originalEnd = expandEnd(displayedText, mappedText.originalIndexes[matchEnd])
            ranges += originalStart..originalEnd
            nextSearchStart = matchEnd + 1
        }
        return ranges
    }

    private fun expandStart(value: String, index: Int): Int {
        var expanded = index
        while (expanded > 0 && DirectMessageSearchTextNormalizer.isSearchIgnorable(value[expanded - 1])) {
            expanded--
        }
        return expanded
    }

    private fun expandEnd(value: String, index: Int): Int {
        var expanded = index
        while (expanded < value.lastIndex && DirectMessageSearchTextNormalizer.isSearchIgnorable(value[expanded + 1])) {
            expanded++
        }
        return expanded
    }
}
