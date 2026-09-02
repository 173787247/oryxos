package io.oryxos.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.persona.PersonaPresetCatalog;
import io.oryxos.persona.PersonaService;
import io.oryxos.persona.PersonaStore;
import io.oryxos.web.GlobalExceptionHandler;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * specs/025-persona-library 验收 harness：PersonaApiController——人格库浏览（12 内置 + 自定义合并）+ 自定义 CRUD（内置只读
 * 400、未知 404）。
 */
class PersonaApiControllerTest {

  @TempDir Path oryxosRoot;

  private MockMvc mvc;
  private PersonaService personas;

  private static final String CUSTOM_MD =
      "---\nname: 团队审查员\ndescription: 团队定制人格\nemoji: 👀\n---\n正文";

  @BeforeEach
  void setUp() {
    personas = new PersonaService(new PersonaPresetCatalog(), new PersonaStore(oryxosRoot));
    mvc =
        MockMvcBuilders.standaloneSetup(new PersonaApiController(personas))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  /** 把多行 md 嵌进 JSON 字符串字面量（转义引号/换行）。 */
  private static String jsonBody(String content) {
    return "{\"sourceContent\":\""
        + content
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        + "\"}";
  }

  @Test
  @DisplayName("025：GET /personas 返回 12 内置 + 自定义，带 builtin 标记")
  void list_mergesBuiltinsAndCustoms() throws Exception {
    personas.create("team-reviewer", CUSTOM_MD);

    mvc.perform(get("/api/v1/personas"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.length()").value(13))
        .andExpect(jsonPath("$.data[0].builtin").value(true))
        .andExpect(jsonPath("$.data[12].builtin").value(false))
        .andExpect(jsonPath("$.data[12].key").value("team-reviewer"))
        .andExpect(jsonPath("$.data[12].label").value("团队审查员"));
  }

  @Test
  @DisplayName("025：GET /personas/{key} 内置返回元数据 + 源文件全文")
  void get_builtin_returnsSourceContent() throws Exception {
    mvc.perform(get("/api/v1/personas/product-manager"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.key").value("product-manager"))
        .andExpect(jsonPath("$.data.label").value("产品经理"))
        .andExpect(jsonPath("$.data.builtin").value(true))
        .andExpect(jsonPath("$.data.sourceContent").isNotEmpty());
  }

  @Test
  @DisplayName("025：GET /personas/{unknown} → 404")
  void get_unknown_returns404() throws Exception {
    mvc.perform(get("/api/v1/personas/no-such"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  @DisplayName("025：POST /personas 新建自定义，列表可见")
  void create_custom_thenList() throws Exception {
    mvc.perform(
            post("/api/v1/personas")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"key\":\"team-reviewer\",\"sourceContent\":"
                        + "\"---\\nname: 团队审查员\\n---\\n正文\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.key").value("team-reviewer"))
        .andExpect(jsonPath("$.data.builtin").value(false));

    mvc.perform(get("/api/v1/personas"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[12].key").value("team-reviewer"));
  }

  @Test
  @DisplayName("025：POST 与内置同名 → 400")
  void create_builtinKeyConflict_returns400() throws Exception {
    mvc.perform(
            post("/api/v1/personas")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"key\":\"product-manager\",\"sourceContent\":"
                        + "\"---\\nname: 自定义产品\\n---\\n正文\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("025：POST key 为空 → 400")
  void create_blankKey_returns400() throws Exception {
    mvc.perform(
            post("/api/v1/personas")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"  \",\"sourceContent\":\"x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("025：PUT 改自定义成功并重新投影 meta")
  void update_custom_ok() throws Exception {
    personas.create("team-reviewer", CUSTOM_MD);

    mvc.perform(
            put("/api/v1/personas/team-reviewer")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody("---\nname: 新名字\n---\n新正文")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.key").value("team-reviewer"))
        .andExpect(jsonPath("$.data.label").value("新名字"));
  }

  @Test
  @DisplayName("025：PUT 内置 → 400（只读）")
  void update_builtin_returns400() throws Exception {
    mvc.perform(
            put("/api/v1/personas/product-manager")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody("---\nname: 篡改\n---\n正文")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("025：PUT 未知 → 404")
  void update_unknown_returns404() throws Exception {
    mvc.perform(
            put("/api/v1/personas/no-such")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody("x")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  @DisplayName("025：DELETE 自定义物理删，删后 404")
  void delete_custom_thenGone() throws Exception {
    personas.create("team-reviewer", CUSTOM_MD);

    mvc.perform(delete("/api/v1/personas/team-reviewer"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    mvc.perform(get("/api/v1/personas/team-reviewer")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("025：DELETE 内置 → 400（只读）")
  void delete_builtin_returns400() throws Exception {
    mvc.perform(delete("/api/v1/personas/product-manager"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("025：DELETE 未知 → 404")
  void delete_unknown_returns404() throws Exception {
    mvc.perform(delete("/api/v1/personas/no-such"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }
}
