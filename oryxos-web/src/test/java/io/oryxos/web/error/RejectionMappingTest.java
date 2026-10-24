package io.oryxos.web.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.oryxos.core.cost.BudgetExceededException;
import io.oryxos.core.routing.ResidencyConstraintException;
import io.oryxos.provider.ProviderNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RejectionMapping 单元：确定性拒绝的 (状态码, message) 映射，HTTP 与 SSE 共用同一份。 */
class RejectionMappingTest {

  @Test
  @DisplayName("预算耗尽映射 402（付费语义，不占用 429 限流）")
  void mapsBudgetExceededToPaymentRequired() {
    BudgetExceededException error = new BudgetExceededException("预算已耗尽");

    RejectionMapping.Mapped mapped = RejectionMapping.map(error);

    assertNotNull(mapped);
    assertEquals(402, mapped.status());
    assertEquals(error.getMessage(), mapped.message());
  }

  @Test
  @DisplayName("provider 未注册映射 400（确定性配置错误，不是 404/500）")
  void mapsProviderNotFoundToBadRequest() {
    ProviderNotFoundException error = new ProviderNotFoundException("deepseek");

    RejectionMapping.Mapped mapped = RejectionMapping.map(error);

    assertNotNull(mapped);
    assertEquals(400, mapped.status());
    assertEquals(error.getMessage(), mapped.message());
  }

  @Test
  @DisplayName("驻留白名单拒绝映射 403")
  void mapsResidencyConstraintToForbidden() {
    ResidencyConstraintException error = new ResidencyConstraintException("驻留白名单拒绝");

    RejectionMapping.Mapped mapped = RejectionMapping.map(error);

    assertNotNull(mapped);
    assertEquals(403, mapped.status());
    assertEquals(error.getMessage(), mapped.message());
  }

  @Test
  @DisplayName("未知运行时异常不映射，调用方按 500 处理")
  void returnsNullForUnknownFailure() {
    assertNull(RejectionMapping.map(new IllegalStateException("boom")));
    assertNull(RejectionMapping.map(new RuntimeException()));
  }
}
