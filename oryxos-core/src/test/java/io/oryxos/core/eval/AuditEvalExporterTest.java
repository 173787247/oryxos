package io.oryxos.core.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuditEvalExporterTest {

  @Test
  @DisplayName("toCase copies tools as expected=actual golden")
  void goldenTools() {
    AuditTraceObservation obs =
        new AuditTraceObservation(
            "abc-123", "ops", true, List.of("shell", "read_file"), 900L, 12000L);
    EvalCase c = AuditEvalExporter.toCase(obs);
    assertEquals("trace-abc-123", c.id());
    assertEquals(EvalTargetKind.AGENT, c.kind());
    assertEquals("ops", c.name());
    assertEquals(List.of("shell", "read_file"), c.expectedTools());
    assertEquals(c.expectedTools(), c.actualTools());
    assertEquals(900L, c.latencyMs());
    assertEquals(12000L, c.costMicros());
  }

  @Test
  @DisplayName("suite JSON round-trips through EvalFixtureLoader")
  void roundTrip() throws Exception {
    String json =
        AuditEvalExporter.toSuiteJson(
            "from-audit",
            List.of(
                new AuditTraceObservation(
                    "t1", "helper", true, List.of("delegate_agent"), 100L, 0L)));
    EvalSuiteResult suite = EvalFixtureLoader.parseSuite(new ObjectMapper().readTree(json));
    assertEquals("from-audit", suite.suiteName());
    assertEquals(1, suite.cases().size());
    assertTrue(suite.cases().get(0).id().startsWith("trace-"));
  }
}
