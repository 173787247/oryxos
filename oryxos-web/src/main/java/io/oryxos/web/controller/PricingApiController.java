package io.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.storage.LlmPricing;
import io.oryxos.storage.LlmPricingRepository;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.CreatePricingRequest;
import io.oryxos.web.controller.dto.PricingView;
import io.oryxos.web.controller.dto.UpdatePricingRequest;
import io.oryxos.web.error.ResourceNotFoundException;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型定价 CRUD（016 审计看板）：(provider, model) → 输入/输出 token 单价（元/百万 token）。
 *
 * <p>价格是运营数据、随模型降价频繁变动，管理台自助调价、无需重启；成本计算按 (provider, model) 查价（写时定格）。
 */
@SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification = "core-stage web API is unauthenticated by design (internal network + gateway).")
@RestController
@RequestMapping("/api/v1/pricing")
public class PricingApiController {

  private final LlmPricingRepository repository;

  public PricingApiController(LlmPricingRepository repository) {
    this.repository = repository;
  }

  @GetMapping
  public ApiResponse<List<PricingView>> list(
      @RequestParam(name = "provider", required = false) String provider) {
    List<PricingView> views =
        repository.findAll().stream()
            .filter(e -> provider == null || provider.isBlank() || provider.equals(e.getProvider()))
            .map(PricingView::from)
            .toList();
    return ApiResponse.ok(views);
  }

  @PostMapping
  public ApiResponse<PricingView> create(@RequestBody CreatePricingRequest req) {
    if (req == null
        || req.provider() == null
        || req.provider().isBlank()
        || req.model() == null
        || req.model().isBlank()) {
      throw new IllegalArgumentException("provider 和 model 不能为空");
    }
    if (repository.findByProviderAndModel(req.provider(), req.model()).isPresent()) {
      throw new IllegalArgumentException("模型定价已存在: " + req.provider() + "/" + req.model());
    }
    LlmPricing entity = new LlmPricing();
    entity.setProvider(req.provider());
    entity.setModel(req.model());
    entity.setPromptPrice(req.promptPrice());
    entity.setCompletionPrice(req.completionPrice());
    return ApiResponse.ok(PricingView.from(repository.save(entity)));
  }

  @PutMapping("/{id}")
  public ApiResponse<PricingView> update(
      @PathVariable Long id, @RequestBody UpdatePricingRequest req) {
    LlmPricing existing =
        repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("模型定价不存在: " + id));
    if (req != null) {
      existing.setPromptPrice(req.promptPrice());
      existing.setCompletionPrice(req.completionPrice());
    }
    return ApiResponse.ok(PricingView.from(repository.save(existing)));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    if (!repository.existsById(id)) {
      throw new ResourceNotFoundException("模型定价不存在: " + id);
    }
    repository.deleteById(id);
    return ApiResponse.ok(null);
  }
}
