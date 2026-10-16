package io.oryxos.web.version;

import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Component;

/**
 * 版本信息只读提供者（052）。
 *
 * <p>启动期一次性解析 {@code version}/{@code build}
 * 并缓存为不可变值，实例生命周期内稳定一致（FR-003），与健康状态解耦。来源缺失只降级、绝不抛异常（FR-004）：
 *
 * <ul>
 *   <li>{@code version}：{@link BuildProperties#getVersion()}（= Maven {@code <version>}）→ 否则 {@code
 *       "unknown"}。
 *   <li>{@code build}：{@link GitProperties} 的 short commit id → 否则 {@link BuildProperties} 构建时间 →
 *       否则 {@code "dev"}。
 * </ul>
 */
@Component
public class VersionInfoProvider {

  static final String UNKNOWN_VERSION = "unknown";
  static final String DEV_BUILD = "dev";

  private final String version;
  private final String build;

  public VersionInfoProvider(
      ObjectProvider<BuildProperties> buildProperties,
      ObjectProvider<GitProperties> gitProperties) {
    BuildProperties build = buildProperties.getIfAvailable();
    GitProperties git = gitProperties.getIfAvailable();
    this.version = resolveVersion(build);
    this.build = resolveBuild(git, build);
  }

  public String version() {
    return version;
  }

  public String build() {
    return build;
  }

  private static String resolveVersion(BuildProperties build) {
    if (build == null) {
      return UNKNOWN_VERSION;
    }
    String v = build.getVersion();
    return (v != null && !v.isBlank()) ? v : UNKNOWN_VERSION;
  }

  private static String resolveBuild(GitProperties git, BuildProperties build) {
    if (git != null) {
      String sha = git.getShortCommitId();
      if (sha != null && !sha.isBlank()) {
        return sha;
      }
    }
    if (build != null && build.getTime() != null) {
      return DateTimeFormatter.ISO_INSTANT.format(build.getTime());
    }
    return DEV_BUILD;
  }
}
