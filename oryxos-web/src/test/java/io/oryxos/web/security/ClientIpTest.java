package io.oryxos.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpTest {

  @Test
  @DisplayName("无 wrapper_返回 remoteAddr")
  void unwrapped_returnsRemoteAddr() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("10.0.0.8");
    assertThat(ClientIp.peerAddress(request)).isEqualTo("10.0.0.8");
  }

  @Test
  @DisplayName("ForwardedHeaderFilter 式 wrapper 改写 getRemoteAddr 后仍取对端")
  void wrapperSpoofedRemoteAddr_stillReturnsPeer() {
    MockHttpServletRequest peer = new MockHttpServletRequest();
    peer.setRemoteAddr("127.0.0.1");
    HttpServletRequest spoofed =
        new HttpServletRequestWrapper(peer) {
          @Override
          public String getRemoteAddr() {
            return "8.8.8.8";
          }
        };
    assertThat(ClientIp.peerAddress(spoofed)).isEqualTo("127.0.0.1");
  }

  @Test
  @DisplayName("remoteAddr 空_unknown")
  void blankRemoteAddr_unknown() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("  ");
    assertThat(ClientIp.peerAddress(request)).isEqualTo("unknown");
  }
}
