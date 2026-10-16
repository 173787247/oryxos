package io.oryxos.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.HealthView;
import io.oryxos.web.version.VersionInfoProvider;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;

/** 052 健康端点版本字段：真实版本回显、取值稳定、向后兼容、来源缺失降级。 */
class SystemApiControllerHealthTest {

  private static SystemApiController controller(VersionInfoProvider versionInfo) {
    return new SystemApiController(mock(ProfileRegistry.class), versionInfo);
  }

  @SuppressWarnings("unchecked")
  private static VersionInfoProvider versionInfo(BuildProperties build, GitProperties git) {
    ObjectProvider<BuildProperties> buildProvider = mock(ObjectProvider.class);
    ObjectProvider<GitProperties> gitProvider = mock(ObjectProvider.class);
    when(buildProvider.getIfAvailable()).thenReturn(build);
    when(gitProvider.getIfAvailable()).thenReturn(git);
    return new VersionInfoProvider(buildProvider, gitProvider);
  }

  private static BuildProperties buildProps(String version) {
    Properties p = new Properties();
    p.setProperty("version", version);
    return new BuildProperties(p);
  }

  private static GitProperties gitProps(String shortSha) {
    Properties p = new Properties();
    p.setProperty("commit.id.abbrev", shortSha);
    return new GitProperties(p);
  }

  @Test
  @DisplayName("US1：health 返回体含非空 version 与 build（C-2/C-3），status 仍为 ok（C-1）")
  void healthExposesVersionAndBuild() {
    ApiResponse<HealthView> resp =
        controller(versionInfo(buildProps("4.1.1"), gitProps("abc1234"))).health();

    assertThat(resp.getCode()).isZero();
    assertThat(resp.getData().status()).isEqualTo("ok");
    assertThat(resp.getData().version()).isEqualTo("4.1.1");
    assertThat(resp.getData().build()).isEqualTo("abc1234");
  }

  @Test
  @DisplayName("FR-003/C-4：同一实例多次请求，version/build 取值一致")
  void healthStableAcrossCalls() {
    SystemApiController controller =
        controller(versionInfo(buildProps("4.1.1"), gitProps("abc1234")));

    HealthView first = controller.health().getData();
    HealthView second = controller.health().getData();

    assertThat(second.version()).isEqualTo(first.version());
    assertThat(second.build()).isEqualTo(first.build());
  }

  @Test
  @DisplayName("US2/C-1：向后兼容——status 字段仍存在且为 ok")
  void healthKeepsStatusForLegacyCallers() {
    HealthView data =
        controller(versionInfo(buildProps("4.1.1"), gitProps("abc1234"))).health().getData();

    assertThat(data.status()).isEqualTo("ok");
  }

  @Test
  @DisplayName("FR-004/C-5：version/build 来源缺失时降级为占位值，非空、不抛异常")
  void healthDegradesWhenSourcesMissing() {
    assertThatCode(
            () -> {
              HealthView data = controller(versionInfo(null, null)).health().getData();
              assertThat(data.status()).isEqualTo("ok");
              assertThat(data.version()).isEqualTo("unknown");
              assertThat(data.build()).isEqualTo("dev");
            })
        .doesNotThrowAnyException();
  }
}
