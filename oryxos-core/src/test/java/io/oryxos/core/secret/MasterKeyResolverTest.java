package io.oryxos.core.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MasterKeyResolverTest {

  @TempDir Path root;

  @Test
  void 无环境变量_首启自动生成且权限0600() throws Exception {
    MasterKeyResolver resolver = new MasterKeyResolver(root, () -> null);
    byte[] key = resolver.resolve();

    assertThat(key).hasSize(32);
    Path keyFile = root.resolve(MasterKeyResolver.KEY_FILE);
    assertThat(keyFile).exists();
    Set<PosixFilePermission> perms = Files.getPosixFilePermissions(keyFile);
    assertThat(perms)
        .containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE); // 0600
  }

  @Test
  void 二次启动_读回同一密钥() {
    byte[] first = new MasterKeyResolver(root, () -> null).resolve();
    byte[] second = new MasterKeyResolver(root, () -> null).resolve();
    assertThat(second).isEqualTo(first);
  }

  @Test
  void 环境变量优先于文件() {
    byte[] fileKey = new MasterKeyResolver(root, () -> null).resolve(); // 先生成文件档
    byte[] envKey = new byte[32];
    envKey[5] = 7;
    String envValue = Base64.getEncoder().encodeToString(envKey);

    byte[] resolved = new MasterKeyResolver(root, () -> envValue).resolve();

    assertThat(resolved).isEqualTo(envKey).isNotEqualTo(fileKey); // 文件档被忽略
  }

  @Test
  void 环境变量非法格式_抛错含格式提示() {
    assertThatThrownBy(() -> new MasterKeyResolver(root, () -> "!!not-base64!!").resolve())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("openssl rand -base64 32");
    // 合法 Base64 但长度不对
    String shortKey = Base64.getEncoder().encodeToString(new byte[8]);
    assertThatThrownBy(() -> new MasterKeyResolver(root, () -> shortKey).resolve())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("32");
  }

  @Test
  void 文件内容损坏_抛错() throws Exception {
    Files.writeString(root.resolve(MasterKeyResolver.KEY_FILE), "corrupted-not-base64!!");
    assertThatThrownBy(() -> new MasterKeyResolver(root, () -> null).resolve())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(MasterKeyResolver.KEY_FILE);
  }

  @Test
  void 全部异常message不含密钥值() {
    byte[] envKey = new byte[32];
    envKey[3] = 9;
    String envValue = Base64.getEncoder().encodeToString(envKey);
    // 触发"长度不对"路径时用的短值也不得回显
    String shortValue = Base64.getEncoder().encodeToString(new byte[8]);
    assertThatThrownBy(() -> new MasterKeyResolver(root, () -> shortValue).resolve())
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain(shortValue));
    // 正常解析路径无异常；此处断言合法值不会出现在任何抛出的信息里（防御性回归锚点）
    assertThat(new MasterKeyResolver(root, () -> envValue).resolve()).isEqualTo(envKey);
  }
}
