package io.oryxos.cli.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 账号命令的用户输入校验：一致且够长才接受；失败一律 {@link IllegalArgumentException}（CLI 落成一行）。 */
class UserCommandTest {

  private static final String PASSWORD = "password-123";

  @Test
  @DisplayName("两次一致且够长_返回密码")
  void confirmedPasswordIsAccepted() {
    assertThat(UserCommand.requireConfirmedPassword(PASSWORD, PASSWORD)).isEqualTo(PASSWORD);
  }

  @Test
  @DisplayName("两次不一致_抛IllegalArgumentException")
  void mismatchIsRejected() {
    assertThatThrownBy(() -> UserCommand.requireConfirmedPassword(PASSWORD, "different-123"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("passwords do not match");
  }

  @Test
  @DisplayName("过短_抛IllegalArgumentException")
  void tooShortIsRejected() {
    assertThatThrownBy(() -> UserCommand.requireConfirmedPassword("short", "short"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("password must be at least 8 characters");
  }
}
