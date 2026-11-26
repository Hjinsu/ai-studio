package com.aistudio.production.stage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aistudio.production.contract.ProductionPlanning.Segment;

class SpeechTimingTest {
    @Test
    void wordsAndPausesAreCounted() {
        assertThat(SpeechTiming.estimateMs("")).isZero();
        assertThat(SpeechTiming.estimateMs((String) null)).isZero();
        assertThat(SpeechTiming.estimateMs("Light bends")).isEqualTo(2 * SpeechTiming.WORD_MS);
        assertThat(SpeechTiming.estimateMs("Light bends.")).isEqualTo(2 * SpeechTiming.WORD_MS + SpeechTiming.SENTENCE_MS);
        assertThat(SpeechTiming.estimateMs("Light, bends?"))
                .isEqualTo(2 * SpeechTiming.WORD_MS + SpeechTiming.COMMA_MS + SpeechTiming.SENTENCE_MS);
    }

    @Test
    void numbersAndAcronymsAreExpandedToSpokenWords() {
        // "243" reads as "two hundred forty three"; "1,400" as "one thousand four hundred"
        assertThat(SpeechTiming.spokenWords("243")).isEqualTo(3);
        assertThat(SpeechTiming.spokenWords("1,400")).isEqualTo(4);
        assertThat(SpeechTiming.spokenWords("1.4")).isEqualTo(3);
        assertThat(SpeechTiming.spokenWords("NASA")).isEqualTo(2);
        assertThat(SpeechTiming.spokenWords("sky")).isEqualTo(1);
        // the decimal point and thousands separator are not pauses
        assertThat(SpeechTiming.estimateMs("About 1.4 suns")).isEqualTo(5 * SpeechTiming.WORD_MS);
        assertThat(SpeechTiming.estimateMs("About 1,400 suns")).isEqualTo(6 * SpeechTiming.WORD_MS);
    }

    @Test
    void segmentsAreSummedInOrder() {
        var segments = List.of(new Segment("a", "One two.", List.of()), new Segment("b", "Three four.", List.of()));
        assertThat(SpeechTiming.estimateMs(segments))
                .isEqualTo(SpeechTiming.estimateMs("One two.") + SpeechTiming.estimateMs("Three four."));
    }
}
