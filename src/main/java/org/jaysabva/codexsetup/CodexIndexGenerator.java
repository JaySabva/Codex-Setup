package org.jaysabva.codexsetup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generate Codex Sessions List Per Branch
 *
 * Date: 23/10/25
 *
 * @author Jay Sabva
 */
public class CodexIndexGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final Path DEFAULT_SESSIONS_DIR =
            Paths.get(System.getProperty("user.home"), ".codex", "sessions");
    private static final Path OUTPUT_FILE =
            Paths.get(System.getProperty("user.home"), ".codex", "codex_sessions_index.json");

    static class SessionInfo {
        public String firstUserMessage;
        public String branch;
        public String time;
        public String sessionId;
        public String file;
        public String jsonlFilePath;
    }

    public static void main(String[] args) {
        Path sessionsRoot = args.length > 0 ? Paths.get(args[0]) : DEFAULT_SESSIONS_DIR;
        Path output = args.length > 1 ? Paths.get(args[1]) : OUTPUT_FILE;

        if (!Files.isDirectory(sessionsRoot)) {
            System.err.println("Sessions directory not found: " + sessionsRoot);
            System.exit(1);
        }

        List<SessionInfo> allSessions = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(sessionsRoot)) {
            List<Path> files = paths.filter(p -> p.toString().endsWith(".jsonl")).collect(Collectors.toList());
            for (Path f : files) {
                SessionInfo info = parseSessionFile(f);
                if (info != null) allSessions.add(info);
            }
        } catch (IOException e) {
            System.err.println("Error scanning sessions: " + e.getMessage());
        }

        // Group: cwd → branch → all sessions (sorted desc by time)
        Map<String, Map<String, List<SessionInfo>>> grouped = new TreeMap<>();

        for (SessionInfo s : allSessions) {
            String cwd = s.file != null ? extractCwdFromPath(s.file) : "(no-cwd)";
            String branch = (s.branch != null && !s.branch.isBlank()) ? s.branch : "(no-branch)";

            grouped
                    .computeIfAbsent(cwd, k -> new HashMap<>())
                    .computeIfAbsent(branch, k -> new ArrayList<>())
                    .add(s);
        }

        // Sort lists by time (newest first)
        for (Map<String, List<SessionInfo>> byBranch : grouped.values()) {
            for (List<SessionInfo> sessions : byBranch.values()) {
                sessions.sort(Comparator.comparing(
                        (SessionInfo si) -> parseInstant(si.time)
                ).reversed());
            }
        }

        // Write one JSON file
        try {
            MAPPER.writeValue(output.toFile(), grouped);
            System.out.println("✅ JSON index written to: " + output.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Failed writing JSON: " + e.getMessage());
        }
    }

    private static SessionInfo parseSessionFile(Path file) {
        String firstUserMessage = null, branch = null, time = null, sessionId = null, cwd = null;

        try (BufferedReader br = Files.newBufferedReader(file)) {
            String line;
            boolean gotMeta = false;

            while ((line = br.readLine()) != null) {
                JsonNode root;
                try {
                    root = MAPPER.readTree(line);
                } catch (Exception ignore) { continue; }

                String type = asText(root, "type");

                if (!gotMeta && "session_meta".equals(type)) {
                    gotMeta = true;
                    JsonNode payload = root.path("payload");
                    sessionId = asText(payload, "id");
                    time = asText(payload, "timestamp");
                    cwd = asText(payload, "cwd");
                    JsonNode git = payload.path("git");
                    if (!git.isMissingNode()) branch = asText(git, "branch");
                    continue;
                }

                if (firstUserMessage == null
                        && "response_item".equals(type)
                        && "message".equals(asText(root.path("payload"), "type"))
                        && "user".equals(asText(root.path("payload"), "role"))) {

                    JsonNode content = root.path("payload").path("content");
                    if (content.isArray()) {
                        for (JsonNode c : content) {
                            if ("input_text".equals(asText(c, "type"))) {
                                String text = asText(c, "text");
                                if (isUsefulMessage(text)) {
                                    firstUserMessage = shorten(cleanMessage(text));
                                    break;
                                }
                            }
                        }
                    }
                }

                if (gotMeta && firstUserMessage != null) break;
            }
        } catch (IOException e) {
            System.err.println("Failed to parse " + file + ": " + e.getMessage());
            return null;
        }

        SessionInfo info = new SessionInfo();
        info.firstUserMessage = (firstUserMessage == null) ? "(no user message)" : firstUserMessage;
        info.branch = branch == null ? "(no-branch)" : branch;
        info.time = time;
        info.sessionId = sessionId;
        info.file = cwd != null ? cwd : "(no-cwd)";
        info.jsonlFilePath = file.toString();
        return info;
    }

    private static Instant parseInstant(String s) {
        try { return Instant.parse(s); } catch (Exception e) { return Instant.EPOCH; }
    }

    private static String asText(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private static boolean isUsefulMessage(String text) {
        if (text == null) return false;
        text = text.trim();
        return !text.isEmpty()
                && !text.startsWith("<environment_context>")
                && !text.startsWith("{")
                && !text.startsWith("[");
    }

    private static String cleanMessage(String text) {
        return Pattern.compile("<[^>]+>").matcher(text).replaceAll("").trim();
    }

    private static String shorten(String text) {
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() > 120 ? text.substring(0, 117) + "..." : text;
    }

    private static String extractCwdFromPath(String filePath) {
        // fallback: use full cwd path (since stored in metadata)
        return filePath;
    }
}
