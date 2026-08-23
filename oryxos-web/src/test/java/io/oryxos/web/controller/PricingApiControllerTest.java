package io.oryxos.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.storage.LlmPricing;
import io.oryxos.storage.LlmPricingRepository;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.CreatePricingRequest;
import io.oryxos.web.controller.dto.PricingView;
import io.oryxos.web.controller.dto.UpdatePricingRequest;
import io.oryxos.web.error.ResourceNotFoundException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 016 模型定价 CRUD：新增/更新/删除/重复冲突/列表过滤。 */
class PricingApiControllerTest {

  private LlmPricingRepository repository;
  private PricingApiController controller;

  @BeforeEach
  void setUp() {
    repository = mock(LlmPricingRepository.class);
    controller = new PricingApiController(repository);
  }

  @Test
  @DisplayName("新增定价：落库并回显 provider/model/单价")
  void createSavesAndReturnsView() {
    when(repository.findByProviderAndModel("deepseek", "deepseek-chat"))
        .thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    ApiResponse<PricingView> resp =
        controller.create(new CreatePricingRequest("deepseek", "deepseek-chat", 1.0, 2.0));

    assertThat(resp.getCode()).isZero();
    assertThat(resp.getData().provider()).isEqualTo("deepseek");
    assertThat(resp.getData().model()).isEqualTo("deepseek-chat");
    assertThat(resp.getData().promptPrice()).isEqualTo(1.0);
    assertThat(resp.getData().completionPrice()).isEqualTo(2.0);
  }

  @Test
  @DisplayName("重复 (provider, model) 新增被拒绝")
  void createDuplicateThrows() {
    when(repository.findByProviderAndModel("deepseek", "deepseek-chat"))
        .thenReturn(Optional.of(pricing("deepseek", "deepseek-chat")));

    assertThatThrownBy(
            () ->
                controller.create(new CreatePricingRequest("deepseek", "deepseek-chat", 1.0, 2.0)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("更新单价：只改价格，provider/model 不变")
  void updateChangesPricesOnly() {
    LlmPricing existing = pricing("deepseek", "deepseek-chat");
    when(repository.findById(1L)).thenReturn(Optional.of(existing));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    ApiResponse<PricingView> resp = controller.update(1L, new UpdatePricingRequest(3.0, 4.0));

    assertThat(resp.getData().provider()).isEqualTo("deepseek");
    assertThat(resp.getData().model()).isEqualTo("deepseek-chat");
    assertThat(resp.getData().promptPrice()).isEqualTo(3.0);
    assertThat(resp.getData().completionPrice()).isEqualTo(4.0);
  }

  @Test
  @DisplayName("删除不存在的定价返回 404")
  void deleteMissingThrows() {
    when(repository.existsById(99L)).thenReturn(false);

    assertThatThrownBy(() -> controller.delete(99L)).isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  @DisplayName("列表按 provider 过滤")
  void listFiltersByProvider() {
    when(repository.findAll())
        .thenReturn(List.of(pricing("deepseek", "deepseek-chat"), pricing("openai", "gpt-5.5")));

    ApiResponse<List<PricingView>> resp = controller.list("deepseek");

    assertThat(resp.getData()).hasSize(1);
    assertThat(resp.getData().get(0).provider()).isEqualTo("deepseek");
  }

  private static LlmPricing pricing(String provider, String model) {
    LlmPricing p = new LlmPricing();
    p.setProvider(provider);
    p.setModel(model);
    p.setPromptPrice(1.0);
    p.setCompletionPrice(2.0);
    return p;
  }
}
