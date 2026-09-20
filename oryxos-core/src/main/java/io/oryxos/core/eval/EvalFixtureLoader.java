package io.oryxos.core.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Loads JSON eval suites and baselines (classpath or filesystem). */
public final class EvalFixtureLoader {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private EvalFixtureLoader() {}

  public static EvalSuiteResult loadSuiteFromClasspath(String resourcePath) throws IOException {
    try (InputStream in = EvalFixtureLoader.class.getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IOException("classpath resource not found: " + resourcePath);
      }
      return parseSuite(MAPPER.readTree(in));
    }
  }

  public static EvalSuiteResult loadSuite(Path path) throws IOException {
    Objects.requireNonNull(path, "path");
    try (InputStream in = Files.newInputStream(path)) {
      return parseSuite(MAPPER.readTree(in));
    }
  }

  public static EvalBaseline loadBaselineFromClasspath(String resourcePath) throws IOException {
    try (InputStream in = EvalFixtureLoader.class.getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IOException("classpath resource not found: " + resourcePath);
      }
      return parseBaseline(MAPPER.readTree(in));
    }
  }

  public static EvalBaseline loadBaseline(Path path) throws IOException {
    Objects.requireNonNull(path, "path");
    try (InputStream in = Files.newInputStream(path)) {
      return parseBaseline(MAPPER.readTree(in));
    }
  }

  static EvalSuiteResult parseSuite(JsonNode root) {
    String name = text(root, "name", "unnamed");
    List<EvalCase> cases = new ArrayList<>();
    JsonNode arr = root.get("cases");
    if (arr != null && arr.isArray()) {
      for (JsonNode n : arr) {
        cases.add(parseCase(n));
      }
    }
    Optional<EvalBaseline> baseline = Optional.empty();
    if (root.has("baseline") && root.get("baseline").isObject()) {
      baseline = Optional.of(parseBaseline(root.get("baseline")));
    }
    return EvalHarness.evaluate(name, cases, baseline);
  }

  static EvalBaseline parseBaseline(JsonNode root) {
    String suiteName = text(root, "suiteName", text(root, "name", "unnamed"));
    String capturedAt = text(root, "capturedAt", "");
    JsonNode m = root.get("metrics");
    if (m == null || !m.isObject()) {
      throw new IllegalArgumentException("baseline.metrics required");
    }
    EvalMetrics metrics =
        new EvalMetrics(
            m.path("successRate").asDouble(1.0),
            m.path("toolAccuracy").asDouble(1.0),
            m.path("citationQuality").asDouble(1.0),
            m.path("latencyMsAvg").asDouble(0.0),
            m.path("costMicrosTotal").asLong(0L),
            m.path("caseCount").asInt(0));
    return new EvalBaseline(suiteName, capturedAt, metrics);
  }

  private static EvalCase parseCase(JsonNode n) {
    return new EvalCase(
        text(n, "id", ""),
        EvalTargetKind.valueOf(text(n, "kind", "AGENT").toUpperCase()),
        text(n, "name", ""),
        n.path("success").asBoolean(false),
        stringList(n.get("expectedTools")),
        stringList(n.get("actualTools")),
        stringList(n.get("expectedCitations")),
        stringList(n.get("actualCitations")),
        n.path("latencyMs").asLong(0L),
        n.path("costMicros").asLong(0L));
  }

  private static List<String> stringList(JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    Iterator<JsonNode> it = node.elements();
    while (it.hasNext()) {
      out.add(it.next().asText());
    }
    return out;
  }

  private static String text(JsonNode n, String field, String defaultValue) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? defaultValue : v.asText(defaultValue);
  }
}
