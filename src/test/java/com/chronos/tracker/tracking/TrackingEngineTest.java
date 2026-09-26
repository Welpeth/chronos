package com.chronos.tracker.tracking;

import com.chronos.tracker.activity.ActivityClassifier;
import com.chronos.tracker.activity.ActivityState;
import com.chronos.tracker.jira.JiraException;
import com.chronos.tracker.jira.JiraService;
import com.chronos.tracker.jira.JiraSyncStatus;
import com.chronos.tracker.jira.UnconfiguredJiraService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrackingEngineTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-26T10:00:00Z"));
    private final ActivityClassifier classifier = new ActivityClassifier(Duration.ofMinutes(2), Duration.ofMinutes(5));
    private Duration idle = Duration.ZERO;

    private TrackingEngine engine(JiraService jira) {
        return new TrackingEngine(new TimeTracker(clock), () -> idle, classifier, jira);
    }

    @Test
    void manualIssueIsTrackedWhenJiraIsNotConfigured() {
        TrackingEngine engine = engine(new UnconfiguredJiraService());
        engine.pollJira();
        engine.setManualIssue(" proj-9 ");

        engine.tick();
        clock.advance(Duration.ofSeconds(30));
        TrackingEngine.Snapshot snapshot = engine.tick();

        assertEquals(Optional.of("PROJ-9"), snapshot.issueKey());
        assertEquals(Duration.ofSeconds(30), snapshot.elapsed());
        assertEquals(JiraSyncStatus.NOT_CONFIGURED, snapshot.jiraStatus());
    }

    @Test
    void jiraIssueIsTrackedAndInactivityPauses() {
        FakeJira jira = new FakeJira();
        jira.issue = Optional.of("PROJ-1");
        TrackingEngine engine = engine(jira);

        engine.pollJira();
        engine.tick();
        clock.advance(Duration.ofMinutes(1));
        idle = Duration.ofMinutes(6);
        TrackingEngine.Snapshot paused = engine.tick();
        clock.advance(Duration.ofMinutes(10));

        TrackingEngine.Snapshot later = engine.tick();

        assertEquals(ActivityState.INACTIVE, paused.activity());
        assertEquals(TrackerState.PAUSED, later.trackerState());
        assertEquals(Duration.ofMinutes(1), later.elapsed());
        assertEquals(JiraSyncStatus.SYNCED, later.jiraStatus());
    }

    @Test
    void jiraFailureKeepsTheLastKnownIssue() {
        FakeJira jira = new FakeJira();
        jira.issue = Optional.of("PROJ-1");
        TrackingEngine engine = engine(jira);
        engine.pollJira();
        engine.tick();

        jira.fail = true;
        engine.pollJira();
        clock.advance(Duration.ofMinutes(2));
        TrackingEngine.Snapshot snapshot = engine.tick();

        assertEquals(Optional.of("PROJ-1"), snapshot.issueKey());
        assertEquals(Duration.ofMinutes(2), snapshot.elapsed());
        assertEquals(JiraSyncStatus.ERROR, snapshot.jiraStatus());
    }

    @Test
    void manualIssueOverridesJira() {
        FakeJira jira = new FakeJira();
        jira.issue = Optional.of("PROJ-1");
        TrackingEngine engine = engine(jira);
        engine.pollJira();
        engine.setManualIssue("PROJ-2");

        assertEquals(Optional.of("PROJ-2"), engine.tick().issueKey());

        engine.clearManualIssue();
        assertEquals(Optional.of("PROJ-1"), engine.tick().issueKey());
    }

    private static final class FakeJira implements JiraService {
        Optional<String> issue = Optional.empty();
        boolean fail;

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public Optional<String> fetchCurrentIssueKey() throws JiraException {
            if (fail) {
                throw new JiraException("offline");
            }
            return issue;
        }
    }
}
