package com.zetarun.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class SpokenNumberParserTest {
    @Test fun recognizesOrdinaryEnglishNumbers() {
        assertNumber("zero", "0")
        assertNumber("nineteen", "19")
        assertNumber("twenty four", "24")
        assertNumber("forty-two", "42")
        assertNumber("one hundred", "100")
        assertNumber("one hundred and twenty three", "123")
        assertNumber("hundred and nine", "109")
        assertNumber("twelve hundred", "1200")
        assertNumber("nine hundred ninety nine thousand nine hundred ninety nine", "999999")
        assertNumber("one million two hundred thirty four thousand five hundred sixty seven", "1234567")
        assertNumber("one thousand and six", "1006")
    }

    @Test fun recognizesNumbersSignsDecimalsAndDigitSequences() {
        assertNumber("  24  ", "24")
        assertNumber("+24", "24")
        assertNumber("−24", "-24")
        assertNumber("1,024.50", "1024.50")
        assertNumber(".5", "0.5")
        assertNumber("negative forty two", "-42")
        assertNumber("minus two point zero five", "-2.05")
        assertNumber("point five", "0.5")
        assertNumber("one two three", "123")
        assertNumber("one zero one", "101")
        assertNumber("Twenty ONE!", "21")
        assertNumber("twenty one.", "21")
    }

    @Test fun homophonesAreAcceptedInNumberGrammar() {
        assertNumber("for", "4")
        assertNumber("to", "2")
        assertNumber("too", "2")
        assertNumber("ate", "8")
        assertNumber("won", "1")
        assertNumber("twenty for", "24")
        assertNumber("two point for", "2.4")
    }

    @Test fun unrelatedSpeechAndMalformedNumbersAreRejected() {
        val rejected = listOf(
            "", " ", "the answer is four", "I think twenty four", "four seconds", "forty cats",
            "twenty twenty", "one and two", "one to", "one two hundred", "two three four five cats",
            "minus minus two", "plus minus two", "one point twenty five", "one point", "point",
            "one point two point three", "one hundred and", "one thousand million", "one million million",
            "one thousand two thousand", "zero hundred", "one hundred hundred", "and four",
            "NaN", "Infinity", "0x20", "2e3", "2+2", "2,4", "12,34", "one, two",
        )
        for (text in rejected) assertNull("Should reject '$text'", SpokenNumberParser.parse(text))
    }

    @Test fun integerParsingNeverRoundsOrOverflows() {
        assertEquals(24, SpokenNumberParser.parseInt("twenty four"))
        assertEquals(2, SpokenNumberParser.parseInt("2.0"))
        assertNull(SpokenNumberParser.parseInt("2.5"))
        assertNull(SpokenNumberParser.parseInt("2147483648"))
        assertNull(SpokenNumberParser.parseInt("999999999999999999999999999999999999"))
    }

    @Test fun partialNumbersRemainDifferentFromTheirLongerCompletion() {
        // The parser must never guess that a prefix already means the expected answer.
        assertEquals(1, SpokenNumberParser.parseInt("one"))
        assertEquals(100, SpokenNumberParser.parseInt("one hundred"))
        assertEquals(124, SpokenNumberParser.parseInt("one hundred twenty four"))
        assertEquals(20, SpokenNumberParser.parseInt("twenty"))
        assertEquals(24, SpokenNumberParser.parseInt("twenty four"))
    }

    @Test fun excessiveInputIsBounded() {
        assertNull(SpokenNumberParser.parse("one ".repeat(2000)))
    }

    private fun assertNumber(text: String, expected: String) {
        val actual = SpokenNumberParser.parse(text)
        assertNotNull("Should parse '$text'", actual)
        assertEquals("Wrong number for '$text'", 0, BigDecimal(expected).compareTo(actual))
    }
}
