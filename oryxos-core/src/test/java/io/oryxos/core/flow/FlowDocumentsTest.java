package io.oryxos.core.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FlowDocumentsTest {

  @Test
  void helloNotify_example_validates() throws IOException {
    assertExampleOk("flows/hello-notify.flow.md", "hello-notify");
  }

  @Test
  void branchApprove_example_validates() throws IOException {
    assertExampleOk("flows/branch-approve.flow.md", "branch-approve");
  }

  @Test
  void knowledgeHandoff_example_validates() throws IOException {
    assertExampleOk("flows/knowledge-handoff.flow.md", "knowledge-handoff");
  }

  @Test
  void ticketApproveApply_example_validates() throws IOException {
    assertExampleOk("flows/ticket-approve-apply.flow.md", "ticket-approve-apply");
  }

  private static void assertExampleOk(String resource, String expectedId) throws IOException {
    String md;
    try (InputStream in = FlowDocumentsTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertNotNull(in, "missing classpath resource: " + resource);
      md = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    FlowDocuments.Result result = FlowDocuments.parseAndValidate(md);
    assertTrue(result.ok(), result.diagnostics()::toString);
    assertEquals(expectedId, result.definition().id());
  }
}
