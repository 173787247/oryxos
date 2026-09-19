package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** IdentityMappingService list（#577）。 */
class IdentityMappingServiceListTest {

  private IdentityMappingRepository repository;
  private WebUserRepository userRepository;
  private AuthEventRecorder authEventRecorder;
  private IdentityMappingService service;

  @BeforeEach
  void setUp() {
    repository = mock(IdentityMappingRepository.class);
    userRepository = mock(WebUserRepository.class);
    authEventRecorder = mock(AuthEventRecorder.class);
    service = new IdentityMappingService(repository, userRepository, authEventRecorder);
  }

  @Test
  @DisplayName("list_返回仓库有序结果的防御拷贝")
  void list_returnsCopy() {
    IdentityMapping a = new IdentityMapping();
    a.setIssuer("a");
    a.setSubject("1");
    a.setUsername("alice");
    when(repository.findAllByOrderByIssuerAscSubjectAsc()).thenReturn(List.of(a));
    List<IdentityMapping> result = service.list();
    assertThat(result).hasSize(1);
    assertThat(result.get(0).getUsername()).isEqualTo("alice");
    verify(repository).findAllByOrderByIssuerAscSubjectAsc();
  }
}
