package dev.rareradar.core.lab;

import dev.rareradar.core.report.TextWrap;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextWrapTest {
    private static int w(String s) { return s.replaceAll("§.", "").length(); }

    @Test
    void shortLinesAreUntouched() {
        assertEquals(List.of("hello world"), TextWrap.wrap("hello world", 40, TextWrapTest::w));
        assertEquals(List.of(""), TextWrap.wrap("", 10, TextWrapTest::w));
    }

    @Test
    void longLinesWrapAtSpacesAndNeverExceedTheWidth() {
        String t = "the quick brown fox jumps over the lazy dog and keeps running through the forest";
        List<String> out = TextWrap.wrap(t, 24, TextWrapTest::w);
        assertTrue(out.size() > 2);
        for (String l : out) assertTrue(w(l) <= 24, "too wide: '" + l + "'");
        assertEquals(t.replace(" ", ""), String.join("", out).replace(" ", ""), "no text lost");
        assertTrue(out.get(1).startsWith("  "), "continuations are indented");
    }

    @Test
    void colourCodeCarriesAcrossBreaks() {
        List<String> out = TextWrap.wrap("§6aaaa bbbb cccc dddd eeee ffff gggg hhhh", 14, TextWrapTest::w);
        for (int i = 1; i < out.size(); i++) assertTrue(out.get(i).contains("§6"), out.get(i));
    }

    @Test
    void oneHugeWordIsBrokenInsteadOfLoopingForever() {
        List<String> out = TextWrap.wrap("x".repeat(200), 20, TextWrapTest::w);
        assertTrue(out.size() >= 10);
        for (String l : out) assertTrue(w(l) <= 22, l);
    }
}
