package com.chronos.tracker.jira;

import com.chronos.tracker.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestJiraServiceTest {

    private static final Map<String, String> CREDENTIALS = Map.of(
            "JIRA_BASE_URL", "https://empresa.atlassian.net",
            "JIRA_EMAIL", "eu@empresa.com",
            "JIRA_API_TOKEN", "token");

    @Test
    void defaultJqlLooksForMyOpenIssuesAndTodaysDone() {
        assertEquals(
                "project in (\"PROJ\", \"ABC\") AND assignee = currentUser()"
                        + " AND (statusCategory != Done OR updated >= startOfDay())",
                RestJiraService.defaultJql(List.of("PROJ", "ABC")));
    }

    @Test
    void addsOrderingOnlyWhenMissing() {
        assertEquals("project = X ORDER BY updated DESC", RestJiraService.withOrdering("project = X"));
        assertEquals("project = X order by rank", RestJiraService.withOrdering("project = X order by rank"));
    }

    @Test
    void customJqlWinsOverProjectKeys() {
        Map<String, String> values = new java.util.HashMap<>(CREDENTIALS);
        values.put("JIRA_PROJECT_KEY", "PROJ");
        values.put("JIRA_JQL", "assignee = currentUser()");

        RestJiraService service = (RestJiraService) RestJiraService.from(AppConfig.fromMap(values));

        assertEquals("assignee = currentUser() ORDER BY updated DESC", service.jql());
    }

    @Test
    void wholeColumnsAlsoSearchOtherPeoplesTasksInTheCountedColumns() {
        Map<String, String> values = new java.util.HashMap<>(CREDENTIALS);
        values.put("JIRA_PROJECT_KEY", "PROJ");
        values.put("JIRA_IN_PROGRESS_STATUSES", "Test");
        values.put("JIRA_USE_DEFAULT_STATUSES", "false");

        RestJiraService off = (RestJiraService) RestJiraService.from(AppConfig.fromMap(values));
        assertTrue(off.columnJql().isEmpty());

        values.put("JIRA_WATCH_WHOLE_COLUMNS", "true");
        RestJiraService on = (RestJiraService) RestJiraService.from(AppConfig.fromMap(values));
        assertEquals(Optional.of("project in (\"PROJ\") AND status in (\"Test\")"
                + " AND (assignee is EMPTY OR assignee != currentUser()) ORDER BY updated DESC"), on.columnJql());
    }

    @Test
    void otherPeoplesTasksComeAfterMineWithoutRepeating() {
        JiraIssue mine = new JiraIssue("PROJ-1", "Minha", "Test", StatusCategory.IN_PROGRESS);
        JiraIssue repeated = new JiraIssue("PROJ-1", "Minha", "Test", StatusCategory.IN_PROGRESS);
        JiraIssue others = new JiraIssue("PROJ-2", "Da Ana", "Test", StatusCategory.IN_PROGRESS, "", "Ana", true);

        List<JiraIssue> all = RestJiraService.withOthers(List.of(mine), List.of(repeated, others));

        assertEquals(List.of("PROJ-1", "PROJ-2"), all.stream().map(JiraIssue::key).toList());
        assertTrue(all.get(0).mine());
        assertFalse(all.get(1).mine());
        assertEquals("Ana", all.get(1).assignee());
    }

    @Test
    void missingSettingsGiveAnUnconfiguredService() {
        JiraService service = RestJiraService.from(AppConfig.fromMap(CREDENTIALS));

        assertInstanceOf(UnconfiguredJiraService.class, service);
        assertFalse(service.isConfigured());
    }

    @Test
    void alertJqlLooksForRecentIssuesOfTheChosenTypesFromAnyone() {
        assertEquals(
                "project in (\"SCRUM\") AND issuetype in (\"Bug Cliente\", \"Incidente\")"
                        + " AND created >= -3d ORDER BY created DESC",
                RestJiraService.alertJql(List.of("SCRUM"), List.of("Bug Cliente", "Incidente")));
    }
}
