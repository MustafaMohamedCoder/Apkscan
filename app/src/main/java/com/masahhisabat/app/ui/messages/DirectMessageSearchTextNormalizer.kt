package com.masahhisabat.app.ui.messages

import java.util.Locale

/** تطبيع عربي مشترك للبحث والعرض؛ لا يعدل النص الأصلي المخزن. */
internal object DirectMessageSearchTextNormalizer {
    data class MappedText(
        val value: String,
        val originalIndexes: List<Int>
    )

    fun normalize(value: String): String = normalizeWithIndexes(value).value

    fun normalizeWithIndexes(value: String): MappedText {
        val normalized = StringBuilder()
        val originalIndexes = mutableListOf<Int>()
        var pendingWhitespaceIndex: Int? = null

        value.forEachIndexed { index, character ->
            when {
                character.isSearchWhitespace() -> {
                    if (normalized.isNotEmpty() && pendingWhitespaceIndex == null) {
                        pendingWhitespaceIndex = index
                    }
                }

                isSearchIgnorable(character) -> Unit
                else -> {
                    pendingWhitespaceIndex?.let { whitespaceIndex ->
                        normalized.append(' ')
                        originalIndexes += whitespaceIndex
                        pendingWhitespaceIndex = null
                    }
                    character.normalizedSearchCharacters().forEach { normalizedCharacter ->
                        normalized.append(normalizedCharacter)
                        originalIndexes += index
                    }
                }
            }
        }
        return MappedText(normalized.toString(), originalIndexes)
    }

    fun isSearchIgnorable(character: Char): Boolean =
        character == '\u0640' ||
            character in '\u064B'..'\u0652' ||
            Character.getType(character) == Character.FORMAT.toInt()

    private fun Char.isSearchWhitespace(): Boolean = isWhitespace() || this == '\u00A0'

    private fun Char.normalizedSearchCharacters(): String = when (this) {
        'أ', 'إ', 'آ' -> "ا"
        'ى' -> "ي"
        'ة' -> "ه"
        else -> toString().lowercase(Locale("ar"))
    }
}
