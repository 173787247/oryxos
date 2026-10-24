package io.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.oryxos.core.auth.Principal;
import io.oryxos.core.policy.Action;
import io.oryxos.core.policy.ResourceRef;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

/** AuthzEventRecorder 单元：默认值、按列宽截断、trace 携带与 fail-open。 */
class AuthzEventRecorderTest {

  private static final int MAX_METHOD = 16;
  private static final int MAX_RESOURCE_TYPE = 32;

  private final AuthzEventRepository repository = mock(AuthzEventRepository.class);
  private final AuthzEventRecorder recorder = new AuthzEventRecorder(repository);

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  @DisplayName("空主体/空动作/空理由落库时用占位值")
  void fillsDefaultsForMissingInputs() {
    recorder.record(null, null, null, null, null, null);

    AuthzEvent saved = captured();
    assertEquals("ANONYMOUS", saved.getPrincipalKind());
    assertEquals("anonymous", saved.getPrincipalId());
    assertEquals("UNKNOWN", saved.getAction());
    assertEquals("未说明理由", saved.getReason());
    assertNull(saved.getResourceType());
    assertNull(saved.getResourceId());
    assertNull(saved.getRequestMethod());
    assertNull(saved.getRequestPath());
    assertNull(saved.getTraceId());
  }

  @Test
  @DisplayName("自由文本字段按列宽截断（含 request_method 与 resource_type）")
  void truncatesEveryFreeTextFieldToItsColumnWidth() {
    String longText = "x".repeat(600);
    Principal principal =
        new Principal(Principal.Kind.API_KEY, "p".repeat(200), "d", Set.of(), Set.of(), Set.of());
    ResourceRef resource = new ResourceRef("t".repeat(40), "r".repeat(300));

    recorder.record(principal, Action.MANAGE_AGENTS, resource, longText, "m".repeat(20), longText);

    AuthzEvent saved = captured();
    assertEquals(128, saved.getPrincipalId().length());
    assertEquals(512, saved.getReason().length());
    assertEquals(512, saved.getRequestPath().length());
    assertEquals(255, saved.getResourceId().length());
    assertEquals(MAX_METHOD, saved.getRequestMethod().length());
    assertEquals(MAX_RESOURCE_TYPE, saved.getResourceType().length());
  }

  @Test
  @DisplayName("MDC 里的 traceId 随事件落库并按列宽截断")
  void storesTraceIdFromMdc() {
    MDC.put("traceId", "t".repeat(80));

    recorder.record(null, Action.READ_WORKSPACE, null, "r", "GET", "/x");

    assertEquals(64, captured().getTraceId().length());
  }

  @Test
  @DisplayName("空白 traceId 不落库")
  void skipsBlankTraceId() {
    MDC.put("traceId", "   ");

    recorder.record(null, Action.READ_WORKSPACE, null, "r", "GET", "/x");

    assertNull(captured().getTraceId());
  }

  @Test
  @DisplayName("写库失败被吞掉，拒绝裁决不回滚")
  void swallowsRepositoryFailure() {
    doThrow(new IllegalStateException("db down")).when(repository).save(any());

    assertDoesNotThrow(() -> recorder.record(null, Action.READ_WORKSPACE, null, "r", "GET", "/x"));
  }

  private AuthzEvent captured() {
    ArgumentCaptor<AuthzEvent> captor = ArgumentCaptor.forClass(AuthzEvent.class);
    verify(repository).save(captor.capture());
    return captor.getValue();
  }
}
