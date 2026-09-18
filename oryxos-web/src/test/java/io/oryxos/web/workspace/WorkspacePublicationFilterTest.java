package io.oryxos.web.workspace;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.workspace.LocalWorkspaceStorageProvider;
import io.oryxos.core.workspace.WorkspacePublication;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class WorkspacePublicationFilterTest {
  @TempDir Path root;

  @Test
  void potentiallyDirtyClientErrorInvalidatesStaleEditorsAndArchivesEvidence() throws Exception {
    var storage = new LocalWorkspaceStorageProvider().open(root, "");
    var publication = new WorkspacePublication(storage.root());
    String before = publication.revision();
    var filter = new WorkspacePublicationFilter(storage, new ObjectMapper());
    var response = new MockHttpServletResponse();
    filter.doFilter(
        request("PUT", "/api/v1/agents/demo"),
        response,
        (req, res) -> {
          Files.writeString(root.resolve("already-changed.txt"), "changed before validation error");
          ((jakarta.servlet.http.HttpServletResponse) res).setStatus(400);
        });
    assertEquals(400, response.getStatus());
    assertNotEquals(before, publication.revision());
    assertEquals("rejected-unverified", response.getHeader("X-Workspace-Outcome"));
    assertFalse(Files.exists(root.resolve(WorkspacePublication.RESERVATION)));
    try (var files = Files.list(root)) {
      Path archive =
          files
              .filter(p -> p.getFileName().toString().startsWith(".workspace-rejected-"))
              .findFirst()
              .orElseThrow();
      assertEquals("rejected-unverified", Files.readString(archive.resolve("failed")).strip());
    }
    var stale = request("PUT", "/api/v1/agents/demo");
    stale.addHeader("If-Match", before);
    var rejected = new MockHttpServletResponse();
    filter.doFilter(stale, rejected, (req, res) -> fail("must not run stale write"));
    assertEquals(409, rejected.getStatus());
  }

  @Test
  void staleEditorGetsConflictWithoutExecutingHandler() throws Exception {
    var storage = new LocalWorkspaceStorageProvider().open(root, "");
    var filter = new WorkspacePublicationFilter(storage, new ObjectMapper());
    var read = new MockHttpServletResponse();
    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/v1/workspace/file"), read, (a, b) -> {});
    String initial = read.getHeader("X-Workspace-Revision");
    var write = new MockHttpServletRequest("POST", "/api/v1/workspace/file");
    write.addHeader("If-Match", initial);
    var success = new MockHttpServletResponse();
    filter.doFilter(write, success, (a, b) -> b.getWriter().write("ok"));
    assertEquals(200, success.getStatus());
    assertNotEquals(initial, success.getHeader("X-Workspace-Revision"));
    var stale = new MockHttpServletRequest("POST", "/api/v1/workspace/file");
    stale.addHeader("If-Match", initial);
    var rejected = new MockHttpServletResponse();
    filter.doFilter(stale, rejected, (a, b) -> fail("stale handler must not execute"));
    assertEquals(409, rejected.getStatus());
    assertFalse(Files.exists(root.resolve(WorkspacePublication.RESERVATION)));
  }

  @Test
  void serverFailureRetainsRecoveryRecordAndBlocksNextWriter() throws Exception {
    var storage = new LocalWorkspaceStorageProvider().open(root, "");
    var filter = new WorkspacePublicationFilter(storage, new ObjectMapper());
    var failure = new MockHttpServletResponse();
    filter.doFilter(
        new MockHttpServletRequest("PUT", "/api/v1/agents/demo"),
        failure,
        (a, b) -> ((jakarta.servlet.http.HttpServletResponse) b).setStatus(500));
    assertTrue(Files.exists(root.resolve(WorkspacePublication.RESERVATION).resolve("failed")));
    var rejected = new MockHttpServletResponse();
    filter.doFilter(
        new MockHttpServletRequest("PUT", "/api/v1/agents/demo"),
        rejected,
        (a, b) -> fail("pending recovery cannot be silently reclaimed"));
    assertEquals(409, rejected.getStatus());
  }

  @Test
  void exclusionsMatchExactMethodAndRouteRatherThanLegalAssetNames() throws Exception {
    var storage = new LocalWorkspaceStorageProvider().open(root, "");
    var filter = new WorkspacePublicationFilter(storage, new ObjectMapper());

    assertTrue(filter.shouldNotFilter(request("POST", "/api/v1/agents/demo/invoke")));
    assertTrue(filter.shouldNotFilter(request("POST", "/api/v1/agents/demo/session/messages")));
    assertTrue(filter.shouldNotFilter(request("POST", "/api/v1/knowledge/ops/reindex")));

    assertFalse(filter.shouldNotFilter(request("PUT", "/api/v1/agents/demo/invoke")));
    assertFalse(filter.shouldNotFilter(request("DELETE", "/api/v1/skills/invoke")));
    assertFalse(filter.shouldNotFilter(request("PUT", "/api/v1/personas/reindex")));
    assertFalse(filter.shouldNotFilter(request("PUT", "/api/v1/agents/session/basic")));
    assertFalse(filter.shouldNotFilter(request("PUT", "/api/v1/agents/a/skills/invoke")));
  }

  @Test
  void canonicalMvcPathCannotBypassManagedRouteDetection() throws Exception {
    var storage = new LocalWorkspaceStorageProvider().open(root, "");
    var filter = new WorkspacePublicationFilter(storage, new ObjectMapper());

    assertFalse(filter.shouldNotFilter(request("PUT", "/api/v1/%61gents/demo")));
    assertFalse(filter.shouldNotFilter(request("PUT", "/api/v1/agents;v=1/demo")));
    assertTrue(filter.shouldNotFilter(request("POST", "/api/v1/%61gents/demo/invoke")));
  }

  @Test
  void malformedQuotedIfMatchReturnsBadRequestBeforeReservation() throws Exception {
    var storage = new LocalWorkspaceStorageProvider().open(root, "");
    var filter = new WorkspacePublicationFilter(storage, new ObjectMapper());
    var request = request("PUT", "/api/v1/agents/demo");
    request.addHeader("If-Match", "\"");
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, (a, b) -> fail("malformed revision must not execute"));

    assertEquals(400, response.getStatus());
    assertFalse(Files.exists(root.resolve(WorkspacePublication.RESERVATION)));
  }

  @Test
  void storageErrorClearsStaleContentLengthBeforeWritingJson() throws Exception {
    var storage = new LocalWorkspaceStorageProvider().open(root, "");
    var filter = new WorkspacePublicationFilter(storage, new ObjectMapper());
    var response = new MockHttpServletResponse();

    filter.doFilter(
        request("PUT", "/api/v1/agents/demo"),
        response,
        (a, b) -> {
          var servletResponse = (jakarta.servlet.http.HttpServletResponse) b;
          servletResponse.setContentLength(1);
          throw new IOException("injected");
        });

    assertEquals(503, response.getStatus());
    assertNotEquals("1", response.getHeader("Content-Length"));
    assertTrue(response.getContentAsString().contains("工作区存储不可用"));
  }

  private static MockHttpServletRequest request(String method, String path) {
    return new MockHttpServletRequest(method, path);
  }
}
