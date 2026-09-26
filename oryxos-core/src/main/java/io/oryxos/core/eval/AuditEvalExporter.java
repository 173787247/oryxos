package io.oryxos.core.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Maps audit trace observations to offline eval fixtures (048 follow-on / flywheel first cut).
 *
 * <p>Golden capture: {@code expectedTools} copies {@code actualTools}. Citations stay empty until a
 * later cut parses retrieve_knowledge payloads.
 */
public final class AuditEvalExporter {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private AuditEvalExporter() {}

  /** One observation → one AGENT {@link EvalCase} (expected tools = actual tools). */
  public static EvalCase toCase(AuditTraceObservation obs) {
    Objects.requireNonNull(obs, "obs");
    String id = "trace-" + sanitizeId(obs.traceId());
    return new EvalCase(
        id,
        EvalTargetKind.AGENT,
        obs.agentName(),
        obs.success(),
        obs.actualTools(),
        obs.actualTools(),
        List.of(),
        List.of(),
        obs.latencyMs(),
        obs.costMicros());
  }

  public static List<EvalCase> toCases(List<AuditTraceObservation> observations) {
    if (observations == null || observations.isEmpty()) {
      return List.of();
    }
    List<EvalCase> out = new ArrayList<>(observations.size());
    for (AuditTraceObservation o : observations) {
      out.add(toCase(o));
    }
    return List.copyOf(out);
  }

  /** Suite JSON compatible with {@link EvalFixtureLoader#parseSuite}. */
  public static String toSuiteJson(String suiteName, List<AuditTraceObservation> observations) {
    String name = suiteName == null || suiteName.isBlank() ? "audit-export" : suiteName.strip();
    ObjectNode root = MAPPER.createObjectNode();
    root.put("name", name);
    ArrayNode cases = root.putArray("cases");
    for (EvalCase c : toCases(observations)) {
      ObjectNode n = cases.addObject();
      n.put("id", c.id());
      n.put("kind", c.kind().name());
      n.put("name", c.name());
      n.put("success", c.success());
      putStringArray(n, "expectedTools", c.expectedTools());
      putStringArray(n, "actualTools", c.actualTools());
      putStringArray(n, "expectedCitations", c.expectedCitations());
      putStringArray(n, "actualCitations", c.actualCitations());
      n.put("latencyMs", c.latencyMs());
      n.put("costMicros", c.costMicros());
    }
    try {
      return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    } catch (Exception e) {
      throw new IllegalStateException("failed to serialize eval suite JSON: " + e.getMessage(), e);
    }
  }

  private static void putStringArray(ObjectNode n, String field, List<String> values) {
    ArrayNode arr = n.putArray(field);
    for (String v : values) {
      arr.add(v);
    }
  }

  public static String sanitizeId(String raw) {
    String t = raw == null ? "" : raw.strip();
    if (t.isEmpty()) {
      return "unknown";
    }
    StringBuilder sb = new StringBuilder(t.length());
    for (int i = 0; i < t.length(); i++) {
      char c = t.charAt(i);
      if ((c >= 'a' && c <= 'z')
          || (c >= 'A' && c <= 'Z')
          || (c >= '0' && c <= '9')
          || c == '-'
          || c == '_') {
        sb.append(c);
      } else {
        sb.append('_');
      }
    }
    return sb.toString();
  }
}
