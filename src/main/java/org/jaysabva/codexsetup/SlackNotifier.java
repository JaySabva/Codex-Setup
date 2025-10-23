package org.jaysabva.codexsetup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Slack Workflow notifier for codex based on event
 *
 * Date: 23/10/25
 *
 * @author Jay Sabva
 */
public class SlackNotifier {

    private final static String ULTRON_JIRA_WEBHOOK_URL = System.getenv("ULTRON_JIRA_WEBHOOK_URL");
    private final static String JUGGERNAUT_REVIEW_WEBHOOK_URL = System.getenv("JUGGERNAUT_REVIEW_WEBHOOK_URL");
    private final static String GALACTUS_GITLAB_MR_WEBHOOK_URL = System.getenv("GALACTUS_GITLAB_MR_WEBHOOK_URL");
    private final static ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) throws JsonProcessingException {
        if (args.length == 0 || args[0] == null || args[0].isBlank()) {
            System.err.println("Usage: java SlackNotifier '<JSON_DATA>'");
            return;
        }

        String inputJson = args[0];
        JsonNode inputJsonNode = mapper.readTree(inputJson);

        String type = inputJsonNode.path("type").asText();
        if (!type.equals("agent-turn-complete")) {
            return;
        }

        String codexResponse = inputJsonNode.path("last-assistant-message").asText();
        if (codexResponse == null) {
            return;
        }

        JsonNode maybeJson = tryParseJson(codexResponse);
        if (maybeJson != null) {
            if (maybeJson.has("findings")) {
                handleCodeReviewFindings(maybeJson);
                return;
            } else if ("Gitlab-MR".equalsIgnoreCase(maybeJson.path("type").asText())) {
                handleGitlabMR(maybeJson);
                return;
            }
        }

        if (codexResponse.startsWith("[JIRA-EXPLAIN")) {
            handleJiraSummary(inputJsonNode, codexResponse);
        }
    }

    private static void handleGitlabMR(JsonNode mrJson) {
        String ticket = nullToEmpty(mrJson.path("ticket").asText(""));
        String pr = nullToEmpty(mrJson.path("pr").asText(""));
        String source = nullToEmpty(mrJson.path("source").asText(""));
        String target = nullToEmpty(mrJson.path("target").asText(""));

        // Build Slack text in the exact required format
        // Extract readable ticket key if it's a Jira link
        String ticketLabel = ticket;
        if (ticket.contains("/browse/")) {
            ticketLabel = ticket.substring(ticket.lastIndexOf("/") + 1);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(":jira: ");
        if (!ticket.isEmpty()) {
            sb.append("*Ticket:* <").append(ticket).append("|").append(ticketLabel).append(">\n");
        } else {
            sb.append("*Ticket:* -\n");
        }

        sb.append(":gitlab_rocks: ");
        if (!target.isEmpty()) {
            sb.append("*").append(target).append(" PR:* ").append(pr.isEmpty() ? "-" : pr).append("\n");
        } else {
            sb.append("*PR:* ").append(pr.isEmpty() ? "-" : pr).append("\n");
        }

        sb.append(":merged: ");
        sb.append("*[ ").append(source).append(" → ").append(target).append(" ]*");

        String payload = "{\"text\":\"" + escapeJson(sb.toString()) + "\"}";

        // Send to the GitLab MR webhook
        sendSlackAlert(payload, GALACTUS_GITLAB_MR_WEBHOOK_URL);
    }

    private static void handleCodeReviewFindings(JsonNode findingsPayload) {
        try {
            List<Finding> findings = Arrays.asList(
                    mapper.treeToValue(findingsPayload.path("findings"), Finding[].class)
            );

            findings = findings.stream()
                    .sorted(Comparator.comparingInt((Finding f) -> f.priority).thenComparing((f -> f.confidence_score)))
                    .collect(Collectors.toList());

            Collections.reverse(findings);

            String slackText = buildFindingsSlackMessage(findingsPayload, findings);

            slackText = toSlackMrkdwn(slackText);

            String payload = "{\"text\":\"" + escapeJson(slackText) + "\"}";

            sendSlackAlert(payload, JUGGERNAUT_REVIEW_WEBHOOK_URL);
        } catch (Exception e) {
//            System.err.println("Failed to parse findings: " + e.getMessage());
        }
    }

    private static void handleJiraSummary(JsonNode inputJsonNode, String codexResponse) {
        String unescaped = jsonUnescape(codexResponse);
        String ticketNumber = codexResponse.substring(14, codexResponse.indexOf(']')).trim();
        unescaped = unescaped.replaceFirst("\\[JIRA-EXPLAIN-[^\\]]+\\]\\n", "");

        String slackText = toSlackMrkdwn(unescaped);

        String payload = "{\"text\":\"" + escapeJson(slackText) + "\", \"ticket\": \"" + escapeJson(ticketNumber) + "\"}";

        sendSlackAlert(payload, ULTRON_JIRA_WEBHOOK_URL);
    }

    private static String buildFindingsSlackMessage(JsonNode findingsPayload, List<Finding> findings) {
        String correctness = findingsPayload.path("overall_correctness").asText("");
        String explanation = findingsPayload.path("overall_explanation").asText("");
        double confOverall = findingsPayload.path("overall_confidence_score").asDouble(0.0);

        StringBuilder sb = new StringBuilder();

        // Header
        sb.append(":rotating_light: *Code Review Findings*").append("\n\n");

        int idx = 1;
        for (Finding f : findings) {
            String titleRaw = nullToEmpty(f.title);
            String title = escSlack(titleRaw);

            long fConfPct = Math.round(f.confidence_score * 100);
            String prTag = "[P" + f.priority + "]";
            String confTag = "[" + fConfPct + "%]";

            // If title already starts with [P?], strip it
            String cleanTitle = title.replaceAll("^\\[P\\d+\\]\\s*", "");

            // Desired format: [P0] Restore aggregation TZ propagation [40%]
            sb.append("*")
                    .append(idx)
                    .append(") ")
                    .append(prTag)
                    .append(" ")
                    .append(cleanTitle)
                    .append(" ")
                    .append(confTag)
                    .append("*\n");

            // Code reference
            String file = (f.code_location != null) ? nullToEmpty(f.code_location.absolute_file_path) : "";
            String lines = "";
            if (f.code_location != null && f.code_location.line_range != null) {
                lines = f.code_location.line_range.start + "–" + f.code_location.line_range.end;
            }
            if (!file.isEmpty()) {
                sb.append("• _Code Reference:_ `")
                        .append(escSlack(file))
                        .append(lines.isEmpty() ? "" : (":" + lines))
                        .append("`\n");
            }

            // Body as quoted block
            String body = escSlack(nullToEmpty(f.body));
            if (!body.isEmpty()) {
                sb.append("> ").append(body.replace("\n", "\n> ")).append("\n");
            }

            sb.append("\n");
            idx++;
        }

        // Overall summary
        if (!explanation.isEmpty() || confOverall > 0.0 || !correctness.isEmpty()) {
            sb.append("*Overall Summary*\n");
            if (!correctness.isEmpty()) {
                sb.append("*Correctness:* ").append(escSlack(correctness)).append("\n");
            }
            if (!explanation.isEmpty()) {
                sb.append("*Explanation:* ").append(escSlack(explanation)).append("\n");
            }
        }

        return sb.toString();
    }

    private static void sendSlackAlert(String payload, String webhookUrl) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(webhookUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);

            byte[] postData = payload.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(postData);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                try (InputStream err = conn.getErrorStream()) {
                    if (err != null) {
                        String error = new String(err.readAllBytes(), StandardCharsets.UTF_8);
                        System.err.println("[Slack] Failed: " + responseCode + " - " + error);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[Slack] Error sending alert: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static JsonNode tryParseJson(String s) {
        try {
            if (s == null || s.isEmpty()) return null;
            char first = s.charAt(0);
            if (first != '{' && first != '[') return null;
            return mapper.readTree(s);
        } catch (Exception ignore) {
            return null;
        }
    }

    // --- Turn JSON escape sequences into real characters ---
    private static String jsonUnescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                switch (n) {
                    case 'n':
                        out.append('\n');
                        i++;
                        continue;
                    case 'r':
                        out.append('\r');
                        i++;
                        continue;
                    case 't':
                        out.append('\t');
                        i++;
                        continue;
                    case '"':
                        out.append('\"');
                        i++;
                        continue;
                    case '\\':
                        out.append('\\');
                        i++;
                        continue;
                    case 'u':
                        if (i + 5 < s.length()) {
                            String hex = s.substring(i + 2, i + 6);
                            try {
                                out.append((char) Integer.parseInt(hex, 16));
                                i += 5;
                                continue;
                            } catch (Exception ignore) {
                            }
                        }
                        // fallthrough if broken
                    default:
                        out.append(n);
                        i++;
                        continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    //**********************************************************************************************************************************//
    //*                                                      Private Methods                                                           *//
    //**********************************************************************************************************************************//

    // --- Convert GitHub-style markdown to Slack mrkdwn ---
    private static String toSlackMrkdwn(String s) {
        // **bold** -> *bold*  (Slack understands *bold*)
        s = s.replace("**", "*");

        // [label](url) -> <url|label>
        // Use regex to transform markdown links
        Pattern p = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
        Matcher m = p.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String label = m.group(1);
            String url = m.group(2);
            m.appendReplacement(sb, "<" + Matcher.quoteReplacement(url) + "|" + Matcher.quoteReplacement(label) + ">");
        }
        m.appendTail(sb);
        s = sb.toString();

        // Optional: convert leading "- " bullets to "• " (purely cosmetic)
        s = s.replaceAll("(?m)^-\\s", "• ");

        return s;
    }

    // --- Escape for JSON embedding ---
    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace("\t", "\\t");
    }

    private static String escSlack(String s) {
        // Minimal escaping for Slack mrkdwn: escape special chars in link contexts
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String nullToEmpty(String s) {
        return (s == null) ? "" : s;
    }

    public static class Finding {
        public String title;
        public String body;
        public double confidence_score;
        public int priority;
        public CodeLocation code_location;
    }

    public static class CodeLocation {
        public String absolute_file_path;
        public LineRange line_range;
    }

    public static class LineRange {
        public int start;
        public int end;
    }

}
