package io.oryxos.web.controller;

import io.oryxos.persona.PersonaService;
import io.oryxos.persona.PersonaService.PersonaEntry;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.CreatePersonaRequest;
import io.oryxos.web.controller.dto.PersonaDetailView;
import io.oryxos.web.controller.dto.PersonaLibraryView;
import io.oryxos.web.controller.dto.UpdatePersonaLibraryRequest;
import io.oryxos.web.error.ResourceNotFoundException;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 人格库端点（025 人格库）：内置（classpath 只读）+ 自定义（{@code .oryxos/personas/}）合并列表的浏览与自定义 CRUD。
 *
 * <p>错误码复用既有：key 冲突 / 内容为空 / 内置只读 → 400（`IllegalArgumentException`）；不存在 → 404
 * （`ResourceNotFoundException`）；统一 `ApiResponse` 信封。内置人格一律拒绝增删改（只读、随 jar 升级）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification =
        "core-stage web API is unauthenticated by design (internal network + gateway); auth is extension-phase. 协作者是 Spring 注入的共享单例，构造注入共享同一引用正是意图。")
@RestController
@RequestMapping("/api/v1/personas")
public class PersonaApiController {

  private final PersonaService personas;

  public PersonaApiController(PersonaService personas) {
    this.personas = personas;
  }

  /** 合并列表：12 内置 + 全部自定义，带 builtin 标记（内置只读、自定义可 CRUD）。 */
  @GetMapping
  public ApiResponse<List<PersonaLibraryView>> list() {
    return ApiResponse.ok(personas.list().stream().map(PersonaLibraryView::from).toList());
  }

  /** 单个人格详情：卡片元数据 + 源文件全文（sourceContent，喂 import-preview）。未知 key → 404。 */
  @GetMapping("/{key}")
  public ApiResponse<PersonaDetailView> get(@PathVariable String key) {
    PersonaEntry entry =
        personas.get(key).orElseThrow(() -> new ResourceNotFoundException("人格不存在: " + key));
    // get 命中（内置或自定义）则 source 必可取
    return ApiResponse.ok(PersonaDetailView.from(entry, personas.source(key).orElse("")));
  }

  /** 新建自定义人格：key + 源文件全文。与内置/已有自定义同名 → 400。 */
  @PostMapping
  public ApiResponse<PersonaLibraryView> create(@RequestBody CreatePersonaRequest req) {
    if (req == null || req.key() == null || req.key().isBlank()) {
      throw new IllegalArgumentException("人格 key 为空");
    }
    return ApiResponse.ok(PersonaLibraryView.from(personas.create(req.key(), req.sourceContent())));
  }

  /** 改自定义人格：仅自定义；内置 key → 400（只读）。 */
  @PutMapping("/{key}")
  public ApiResponse<PersonaLibraryView> update(
      @PathVariable String key, @RequestBody UpdatePersonaLibraryRequest req) {
    if (personas.get(key).isEmpty()) {
      throw new ResourceNotFoundException("人格不存在: " + key); // → 404
    }
    return ApiResponse.ok(
        PersonaLibraryView.from(personas.update(key, req == null ? null : req.sourceContent())));
  }

  /** 删自定义人格：仅自定义；内置 key → 400（只读）。物理删除。 */
  @DeleteMapping("/{key}")
  public ApiResponse<Void> delete(@PathVariable String key) {
    if (personas.get(key).isEmpty()) {
      throw new ResourceNotFoundException("人格不存在: " + key); // → 404
    }
    personas.delete(key);
    return ApiResponse.ok(null);
  }
}
