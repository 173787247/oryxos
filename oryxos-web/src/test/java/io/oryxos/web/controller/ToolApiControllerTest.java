package io.oryxos.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import io.oryxos.web.GlobalExceptionHandler;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ToolApiControllerTest {

  @Test
  @DisplayName("GET /tools 看到构造后才放进 Map 的工具")
  void listReflectsToolsAddedAfterConstruction() throws Exception {
    Map<String, OryxTool> live = new HashMap<>();
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(new ToolApiController(live))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    mvc.perform(get("/api/v1/tools"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isEmpty());

    live.put("github_search", stub("github_search"));

    mvc.perform(get("/api/v1/tools"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].name").value("github_search"));
  }

  private static OryxTool stub(String name) {
    return new OryxTool() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getDescription() {
        return name;
      }

      @Override
      public String getInputSchema() {
        return "{}";
      }

      @Override
      public ToolResult execute(com.fasterxml.jackson.databind.JsonNode input) {
        return ToolResult.ok("ok");
      }
    };
  }
}
