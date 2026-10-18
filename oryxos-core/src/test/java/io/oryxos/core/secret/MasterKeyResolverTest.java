package io.oryxos.core.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
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
  void 解析与落盘分离_守卫通过前磁盘上没有新密钥() throws Exception {
    MasterKeyResolver resolver = new MasterKeyResolver(root, () -> null);

    MasterKeyResolver.ResolvedMasterKey resolved = resolver.resolveForStartup();

    assertThat(resolved.origin()).isEqualTo(MasterKeyResolver.Origin.GENERATED);
    assertThat(resolved.key()).hasSize(32);
    // 真推迟：守卫通过之前，自动生成的钥匙只在内存里，磁盘上没有可被误当成"原密钥"的文件
    assertThat(root.resolve(MasterKeyResolver.KEY_FILE)).doesNotExist();

    resolver.persistIfGenerated();

    Path keyFile = root.resolve(MasterKeyResolver.KEY_FILE);
    assertThat(keyFile).exists();
    assertThat(Base64.getDecoder().decode(Files.readString(keyFile).trim()))
        .isEqualTo(resolved.key());
    assertThat(Files.getPosixFilePermissions(keyFile))
        .containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE); // 0600
  }

  @Test
  void 老版本留下的0644密钥文件_启动时归一到0600() throws Exception {
    byte[] key = new byte[32];
    key[0] = 42;
    Path keyFile = root.resolve(MasterKeyResolver.KEY_FILE);
    Files.writeString(keyFile, Base64.getEncoder().encodeToString(key));
    Files.setPosixFilePermissions(
        keyFile, PosixFilePermissions.fromString("rw-r--r--")); // 老版本形态 0644
    assertThat(Files.getPosixFilePermissions(keyFile))
        .containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ);

    MasterKeyResolver.ResolvedMasterKey resolved =
        new MasterKeyResolver(root, () -> null).resolveForStartup();

    assertThat(resolved.origin()).isEqualTo(MasterKeyResolver.Origin.FILE); // 读已有文件，不重建
    assertThat(resolved.key()).isEqualTo(key);
    assertThat(Files.getPosixFilePermissions(keyFile))
        .containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE); // 升级即归一
  }

  @Test
  void 环境变量档_不会顺手写出密钥文件() {
    byte[] envKey = new byte[32];
    envKey[1] = 3;
    String envValue = Base64.getEncoder().encodeToString(envKey);
    MasterKeyResolver resolver = new MasterKeyResolver(root, () -> envValue);

    assertThat(resolver.resolveForStartup().origin()).isEqualTo(MasterKeyResolver.Origin.ENV);
    resolver.persistIfGenerated();

    assertThat(root.resolve(MasterKeyResolver.KEY_FILE)).doesNotExist();
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
