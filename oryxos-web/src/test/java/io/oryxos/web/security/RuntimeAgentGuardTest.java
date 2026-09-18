package io.oryxos.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.auth.Principal;
import io.oryxos.core.auth.PrincipalContext;
import io.oryxos.core.auth.Role;
import io.oryxos.core.policy.Action;
import io.oryxos.core.policy.AuthorizationService;
import io.oryxos.core.policy.ResourceRef;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** #503：开跑门禁只走同一 decide，并把主体放入 PrincipalContext。 */
class RuntimeAgentGuardTest {

  @AfterEach
  void tearDown() {
    PrincipalContext.clear();
  }

  @Test
  void bindForRunSetsPrincipalContextAfterDecide() {
    AuthorizationService authorization = mock(AuthorizationService.class);
    when(authorization.decide(any(), eq(Action.RUN_AGENT), eq(ResourceRef.agent("ops"))))
        .thenReturn(AuthorizationService.Decision.ALLOWED);
    Principal alice = Principal.user("alice", "alice", Set.of(Role.EDITOR));
    HttpServletRequest request = requestWith(alice);
    RuntimeAgentGuard guard = new RuntimeAgentGuard(new AssetBindGuard(authorization));

    guard.bindForRun(request, "ops");

    assertThat(PrincipalContext.current()).isSameAs(alice);
    verify(authorization).decide(any(), eq(Action.RUN_AGENT), eq(ResourceRef.agent("ops")));
  }

  @Test
  void bindForRunDoesNotInstallWhenDecideDenies() {
    AuthorizationService authorization = mock(AuthorizationService.class);
    when(authorization.decide(any(), eq(Action.RUN_AGENT), eq(ResourceRef.agent("ops"))))
        .thenReturn(AuthorizationService.Decision.denied("OFFLINE"));
    RuntimeAgentGuard guard = new RuntimeAgentGuard(new AssetBindGuard(authorization));
    HttpServletRequest request = requestWith(Principal.user("bob", "bob", Set.of(Role.VIEWER)));

    assertThatThrownBy(() -> guard.bindForRun(request, "ops"))
        .isInstanceOf(AssetGovernanceAccessException.class);
    assertThat(PrincipalContext.current()).isNull();
  }

  @Test
  void authorizeAndCaptureReturnsPrincipalWithoutInstalling() {
    AuthorizationService authorization = mock(AuthorizationService.class);
    when(authorization.decide(any(), any(), any()))
        .thenReturn(AuthorizationService.Decision.ALLOWED);
    Principal alice = Principal.user("alice", "alice", Set.of(Role.ADMIN));
    RuntimeAgentGuard guard = new RuntimeAgentGuard(new AssetBindGuard(authorization));

    Principal captured = guard.authorizeAndCapture(requestWith(alice), "ops");

    assertThat(captured).isSameAs(alice);
    assertThat(PrincipalContext.current()).isNull();
  }

  @Test
  void installAndClearRoundTrip() {
    Principal alice = Principal.user("alice", "alice", Set.of(Role.EDITOR));
    RuntimeAgentGuard.install(alice);
    assertThat(PrincipalContext.current()).isSameAs(alice);
    RuntimeAgentGuard.clear();
    assertThat(PrincipalContext.current()).isNull();
  }

  private static HttpServletRequest requestWith(Principal principal) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getAttribute("io.oryxos.web.principal")).thenReturn(principal);
    return request;
  }
}
