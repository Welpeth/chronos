package com.chronos.tracker.activity;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActivityClassifierTest {

    private final ActivityClassifier classifier = new ActivityClassifier(Duration.ofMinutes(2), Duration.ofMinutes(5));

    @Test
    void classifiesBySpecThresholds() {
        assertEquals(ActivityState.ACTIVE, classifier.classify(Duration.ZERO));
        assertEquals(ActivityState.ACTIVE, classifier.classify(Duration.ofSeconds(119)));
        assertEquals(ActivityState.POSSIBLY_IDLE, classifier.classify(Duration.ofMinutes(2)));
        assertEquals(ActivityState.POSSIBLY_IDLE, classifier.classify(Duration.ofSeconds(299)));
        assertEquals(ActivityState.INACTIVE, classifier.classify(Duration.ofMinutes(5)));
    }

    @Test
    void rejectsInvertedThresholds() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActivityClassifier(Duration.ofMinutes(5), Duration.ofMinutes(2)));
    }
}
