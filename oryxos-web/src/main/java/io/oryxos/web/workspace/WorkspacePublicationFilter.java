package io.oryxos.web.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.workspace.WorkspaceAvailability;
import io.oryxos.core.workspace.WorkspacePublication;
import io.oryxos.core.workspace.WorkspaceStorage;
import io.oryxos.web.common.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.UrlPathHelper;

/** Serializes managed HTTP edits across replicas and publishes an opaque workspace revision. */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Storage and mapper are injected shared services.")
public final class WorkspacePublicationFilter extends OncePerRequestFilter {
  private final WorkspaceStorage storage;
  private final WorkspacePublication publication;
  private final ObjectMapper mapper;
  private final WorkspaceAvailability availability;

  /** Compatibility constructor for standalone callers without a managed probe. */
  public WorkspacePublicationFilter(WorkspaceStorage storage, ObjectMapper mapper) {
    this(storage, mapper, () -> true);
  }

  public WorkspacePublicationFilter(
      WorkspaceStorage storage, ObjectMapper mapper, WorkspaceAvailability availability) {
    this.storage = storage;
    this.publication = new WorkspacePublication(storage.root());
    this.mapper = mapper;
    this.availability = availability;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    String method = request.getMethod();
    boolean managed =
        path.matches("/api/v1/(agents|skills|knowledge|personas)(/.*)?")
            || "/api/v1/workspace/file".equals(path);
    boolean runtimeOperation =
        "POST".equals(method)
            && (path.matches("/api/v1/agents/[^/]+/(invoke|trigger|generate-files)")
                || path.matches("/api/v1/agents/[^/]+/session/messages")
                || "/api/v1/agents/import-preview".equals(path)
                || path.matches("/api/v1/knowledge/[^/]+/reindex"));
    return !managed || runtimeOperation || "OPTIONS".equals(method);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
    WorkspacePublication.Reservation reservation = null;
    ContentCachingResponseWrapper buffered =
        read ? null : new ContentCachingResponseWrapper(response);
    try {
      // Reject before any NFS syscall: hard mounts can block even the identity read.
      if (!availability.available()) {
        error(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "工作区健康检查未就绪，请稍后重试");
        return;
      }
      storage.checkHealth();
      if (read) {
        // Read before the handler: a concurrent writer makes this conservative (stale), never
        // newer.
        response.setHeader("X-Workspace-Revision", publication.revision());
        chain.doFilter(request, response);
        return;
      }
      String expected;
      try {
        expected = unquoteRevision(request.getHeader("If-Match"));
      } catch (IllegalArgumentException invalid) {
        error(response, HttpServletResponse.SC_BAD_REQUEST, "If-Match 格式非法");
        return;
      }
      reservation =
          publication.begin(expected, request.getMethod() + " " + request.getRequestURI());
      chain.doFilter(request, buffered);
      if (buffered.getStatus() >= HttpServletResponse.SC_INTERNAL_SERVER_ERROR) {
        reservation.failed();
      } else if (buffered.getStatus() >= HttpServletResponse.SC_BAD_REQUEST) {
        buffered.setHeader("X-Workspace-Revision", reservation.rejectUnverified());
        buffered.setHeader("X-Workspace-Outcome", "rejected-unverified");
      } else {
        buffered.setHeader("X-Workspace-Revision", reservation.commit());
      }
      buffered.copyBodyToResponse();
    } catch (WorkspacePublication.ConflictException conflict) {
      error(response, HttpServletResponse.SC_CONFLICT, conflict.getMessage());
    } catch (IOException | RuntimeException failure) {
      if (reservation != null) {
        try {
          reservation.failed();
        } catch (IOException | RuntimeException ignored) {
          // Existing reservation itself is the recovery signal, even when the volume is
          // unavailable.
        }
      }
      error(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "工作区存储不可用或提交中断，请检查工作区健康及恢复记录");
    }
  }

  private static final String QUOTE = "\"";
  private static final int MIN_QUOTED_LENGTH = 2;

  private static String unquoteRevision(String revision) {
    if (revision == null) {
      return null;
    }
    boolean starts = revision.startsWith(QUOTE);
    boolean ends = revision.endsWith(QUOTE);
    if (!starts && !ends) {
      return revision;
    }
    if (revision.length() < MIN_QUOTED_LENGTH || starts != ends) {
      throw new IllegalArgumentException("Invalid quoted revision");
    }
    return revision.substring(1, revision.length() - 1);
  }

  private void error(HttpServletResponse response, int status, String message) throws IOException {
    response.reset();
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    mapper.writeValue(response.getOutputStream(), ApiResponse.error(status, message));
  }
}
