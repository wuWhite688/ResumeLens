package com.arthur.jdragresume.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HoldoutRunnerSupport {
    static final String SEEN_DOMAIN = "seen_domain";
    static final String NEW_DOMAIN = "new_domain";
    private static final Pattern VERSION = Pattern.compile("v[1-9][0-9]*");

    private HoldoutRunnerSupport() {
    }

    static ProductionConfig loadProductionConfig() {
        Properties source = new Properties();
        try (InputStream stream = HoldoutRunnerSupport.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (stream == null) {
                throw new IllegalStateException("application.properties is missing from the test classpath");
            }
            source.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read production application.properties", ex);
        }

        int chunkSize = parseIntDefault(source, "app.rag.chunk-size");
        int chunkOverlap = parseIntDefault(source, "app.rag.chunk-overlap");
        int topK = parseIntDefault(source, "app.rag.top-k");
        double minSimilarity = parseDoubleDefault(source, "app.rag.min-similarity");

        if (chunkSize <= 0) {
            throw new IllegalStateException("app.rag.chunk-size must be > 0");
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalStateException("app.rag.chunk-overlap must be >= 0 and < chunk-size");
        }
        if (topK <= 0) {
            throw new IllegalStateException("app.rag.top-k must be > 0");
        }
        if (minSimilarity < 0.0 || minSimilarity > 1.0) {
            throw new IllegalStateException("app.rag.min-similarity must be in [0, 1]");
        }
        return new ProductionConfig(chunkSize, chunkOverlap, topK, minSimilarity);
    }

    static String normalizeDomainRelation(String raw) {
        if (raw == null || raw.isBlank()) {
            return SEEN_DOMAIN;
        }
        if (SEEN_DOMAIN.equals(raw) || NEW_DOMAIN.equals(raw)) {
            return raw;
        }
        throw new IllegalStateException("unsupported domainRelation: " + raw);
    }

    static RunPlan planRun(Path datasetDir, Path holdoutRoot, Path runLog, ObjectMapper mapper) throws IOException {
        Path pairs = datasetDir.resolve("pairs.json");
        if (!Files.isRegularFile(pairs)) {
            throw new IllegalStateException("missing dataset: " + pairs.toAbsolutePath());
        }
        JsonNode root = mapper.readTree(pairs.toFile());
        if (!root.has("holdoutVersion")) {
            return new RunPlan(false, null, holdoutRoot.resolve(".smoke"));
        }

        JsonNode versionNode = root.get("holdoutVersion");
        if (!versionNode.isTextual() || versionNode.asText().isBlank()) {
            throw new IllegalStateException("holdoutVersion must be a non-blank string when present");
        }
        String version = versionNode.asText().trim();
        if (!VERSION.matcher(version).matches()) {
            throw new IllegalStateException("holdoutVersion must match v1, v2, ...; got " + version);
        }
        requireVersionSection(runLog, version);
        return new RunPlan(true, version, holdoutRoot);
    }

    static void requireVersionSection(Path runLog, String version) throws IOException {
        if (!Files.isRegularFile(runLog)) {
            throw new IllegalStateException("RUN-LOG.md is missing: " + runLog.toAbsolutePath());
        }
        String expected = "## " + version;
        long matches = Files.readAllLines(runLog, StandardCharsets.UTF_8).stream()
                .filter(expected::equals)
                .count();
        if (matches == 0) {
            throw new IllegalStateException(
                    "RUN-LOG.md has no exact section '" + expected + "'; refusing to create one automatically"
            );
        }
        if (matches != 1) {
            throw new IllegalStateException(
                    "RUN-LOG.md must contain exactly one section '" + expected + "'; found " + matches
            );
        }
    }

    static int appendPendingRun(
            Path runLog,
            String version,
            ProductionConfig config,
            String commitSha,
            String resultPath,
            String note
    ) throws IOException {
        requireVersionSection(runLog, version);
        List<String> lines = new ArrayList<>(Files.readAllLines(runLog, StandardCharsets.UTF_8));
        int heading = lines.indexOf("## " + version);
        int sectionEnd = lines.size();
        for (int i = heading + 1; i < lines.size(); i++) {
            if (lines.get(i).startsWith("## ")) {
                sectionEnd = i;
                break;
            }
        }

        int maxOrdinal = 0;
        int placeholder = -1;
        Pattern rowNumber = Pattern.compile("^\\|\\s*(\\d+)\\s*\\|");
        for (int i = heading + 1; i < sectionEnd; i++) {
            String line = lines.get(i).trim();
            if (line.startsWith("| — |")) {
                placeholder = i;
                continue;
            }
            Matcher matcher = rowNumber.matcher(line);
            if (matcher.find()) {
                maxOrdinal = Math.max(maxOrdinal, Integer.parseInt(matcher.group(1)));
            }
        }
        if (placeholder >= 0) {
            lines.remove(placeholder);
            sectionEnd -= 1;
        }

        int ordinal = maxOrdinal + 1;
        String date = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        String parameters = "%d/%d, Top-%d, %s".formatted(
                config.chunkSize(),
                config.chunkOverlap(),
                config.topK(),
                formatThreshold(config.minSimilarity())
        );
        String row = "| %d | %s | `%s` | %s | `%s` | 待判定 | %s |".formatted(
                ordinal,
                date,
                escapeCell(commitSha),
                escapeCell(parameters),
                escapeCell(resultPath),
                escapeCell(note)
        );
        lines.add(sectionEnd, row);
        writeAtomically(runLog, String.join(System.lineSeparator(), lines) + System.lineSeparator());
        return ordinal;
    }

    static String formatThreshold(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    static String runStartedNote() {
        return "runner 已触发 " + Instant.now() + "；完成状态见 `logs/holdout-console.log`";
    }

    private static int parseIntDefault(Properties source, String key) {
        String value = productionDefault(source, key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException(key + " production default is not an integer: " + value, ex);
        }
    }

    private static double parseDoubleDefault(Properties source, String key) {
        String value = productionDefault(source, key);
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException(key + " production default is not a number: " + value, ex);
        }
    }

    private static String productionDefault(Properties source, String key) {
        String raw = source.getProperty(key);
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("missing production property " + key);
        }
        raw = raw.trim();
        if (!raw.startsWith("${")) {
            return raw;
        }

        Matcher matcher = Pattern.compile("^\\$\\{[^:}]+:([^}]+)}$").matcher(raw);
        if (!matcher.matches()) {
            throw new IllegalStateException(
                    key + " must be a literal or ${ENV:default}; refusing to guess from " + raw
            );
        }
        String fallback = matcher.group(1).trim();
        if (fallback.isEmpty()) {
            throw new IllegalStateException(key + " has no production default");
        }
        return fallback;
    }

    private static void writeAtomically(Path path, String content) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IllegalStateException("RUN-LOG path has no parent: " + path);
        }
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "run-log-", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String escapeCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    record ProductionConfig(int chunkSize, int chunkOverlap, int topK, double minSimilarity) {
    }

    record RunPlan(boolean formal, String holdoutVersion, Path outputRoot) {
    }
}
