package com.zetarun.app.domain

import java.math.BigDecimal
import java.util.Locale

/** Strict number-only parsing: unrelated conversation and malformed number phrases never submit. */
object SpokenNumberParser {
    private val ones = mapOf(
        "zero" to 0L, "oh" to 0L, "one" to 1L, "two" to 2L, "three" to 3L,
        "four" to 4L, "five" to 5L, "six" to 6L, "seven" to 7L, "eight" to 8L, "nine" to 9L,
        "ten" to 10L, "eleven" to 11L, "twelve" to 12L, "thirteen" to 13L,
        "fourteen" to 14L, "fifteen" to 15L, "sixteen" to 16L, "seventeen" to 17L,
        "eighteen" to 18L, "nineteen" to 19L,
    )
    private val tens = mapOf(
        "twenty" to 20L, "thirty" to 30L, "forty" to 40L, "fifty" to 50L,
        "sixty" to 60L, "seventy" to 70L, "eighty" to 80L, "ninety" to 90L,
    )
    private val aliases = mapOf("won" to 1L, "to" to 2L, "too" to 2L, "for" to 4L, "fore" to 4L, "ate" to 8L)
    private val scales = mapOf("billion" to 1_000_000_000L, "million" to 1_000_000L, "thousand" to 1_000L)
    private val numeric = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")
    private val groupedNumeric = Regex("[+-]?[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]+)?")

    fun parseInt(text: String): Int? = try {
        parse(text)?.intValueExact()
    } catch (_: ArithmeticException) {
        null
    }

    fun parse(text: String): BigDecimal? {
        var cleaned = text.lowercase(Locale.US).trim().replace('−', '-')
            .trimEnd('!', '?')
        if (cleaned.isBlank() || cleaned.length > 200) return null
        if (numeric.matches(cleaned)) return cleaned.toBigDecimalOrNull()
        if (groupedNumeric.matches(cleaned)) return cleaned.replace(",", "").toBigDecimalOrNull()
        // A transcript's terminal full stop is punctuation; embedded full stops remain invalid.
        cleaned = cleaned.removeSuffix(".").replace(Regex("(?<=[a-z])-(?=[a-z])"), " ")
        val tokens = cleaned.split(Regex("\\s+")).toMutableList()
        var sign = 1
        if (tokens.firstOrNull() in setOf("minus", "negative")) {
            sign = -1
            tokens.removeAt(0)
        } else if (tokens.firstOrNull() == "plus") {
            tokens.removeAt(0)
        }
        if (tokens.isEmpty()) return null
        val points = tokens.indices.filter { tokens[it] == "point" || tokens[it] == "dot" }
        if (points.size > 1) return null
        if (points.isNotEmpty()) {
            val point = points.single()
            val integer = if (point == 0) 0L else parseInteger(tokens.take(point)) ?: return null
            val fraction = tokens.drop(point + 1)
            if (fraction.isEmpty()) return null
            val digits = fraction.map { token ->
                (ones[token] ?: aliases[token])?.takeIf { it in 0..9 } ?: return null
            }.joinToString("")
            return "$integer.$digits".toBigDecimal().multiply(BigDecimal(sign))
        }
        return parseInteger(tokens)?.toBigDecimal()?.multiply(BigDecimal(sign))
    }

    private fun parseInteger(tokens: List<String>): Long? {
        if (tokens.isEmpty()) return null
        if (tokens.size == 1) {
            aliases[tokens[0]]?.let { return it }
            tokens[0].toLongOrNull()?.takeIf { it in 0..999_999_999_999L }?.let { return it }
        }
        // Digit-by-digit speech is allowed only with canonical digits: "one to" is ambiguous.
        if (tokens.size > 1 && tokens.all { (ones[it] ?: 10L) in 0..9 }) {
            return tokens.joinToString("") { ones.getValue(it).toString() }.toLongOrNull()
        }
        var total = 0L
        var previousScale = Long.MAX_VALUE
        var start = 0
        tokens.forEachIndexed { index, token ->
            val scale = scales[token] ?: return@forEachIndexed
            if (scale >= previousScale || index == start) return null
            val group = parseGroup(tokens.subList(start, index)) ?: return null
            if (group <= 0 || group >= 1000) return null
            total += group * scale
            previousScale = scale
            start = index + 1
        }
        if (start == tokens.size) return total
        var remaining = tokens.drop(start)
        if (start > 0 && remaining.firstOrNull() == "and") remaining = remaining.drop(1)
        val last = parseGroup(remaining) ?: return null
        if (start > 0 && last >= previousScale) return null
        return total + last
    }

    private fun parseGroup(tokens: List<String>): Long? {
        if (tokens.isEmpty()) return null
        val hundreds = tokens.withIndex().filter { it.value == "hundred" }
        if (hundreds.isEmpty()) return parseUnderHundred(tokens)
        if (hundreds.size != 1) return null
        val index = hundreds.single().index
        val leading = if (index == 0) 1L else parseUnderHundred(tokens.take(index)) ?: return null
        if (leading !in 1..99) return null
        var rest = tokens.drop(index + 1)
        if (rest.isEmpty()) return leading * 100
        if (rest.first() == "and") rest = rest.drop(1)
        val trailing = parseUnderHundred(rest) ?: return null
        return leading * 100 + trailing
    }

    private fun parseUnderHundred(tokens: List<String>): Long? {
        if (tokens.size == 1) return ones[tokens[0]] ?: tens[tokens[0]] ?: aliases[tokens[0]]
        if (tokens.size != 2) return null
        val ten = tens[tokens[0]] ?: return null
        val one = (ones[tokens[1]] ?: aliases[tokens[1]])?.takeIf { it in 1..9 } ?: return null
        return ten + one
    }
}
