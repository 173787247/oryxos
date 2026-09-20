package io.oryxos.web.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.storage.IdentityMapping;
import io.oryxos.storage.IdentityMappingService;
import io.oryxos.web.GlobalExceptionHandler;
import io.oryxos.web.config.WebOidcProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** identity_mappings HTTP API（#577）：flag 关 404；开时 list/upsert/delete。 */
class IdentityMappingsApiControllerTest {

  private MockMvc mvc;
  private WebOidcProperties properties;
  private IdentityMappingService mappings;

  @BeforeEach
  void setUp() {
    properties = new WebOidcProperties();
    mappings = mock(IdentityMappingService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new IdentityMappingsApiController(properties, mappings))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("flag关_list_返回404")
  void flagOff_list_404() throws Exception {
    properties.setMappingsApiEnabled(false);
    mvc.perform(get("/api/v1/identity-mappings")).andExpect(status().isNotFound());
    verify(mappings, never()).list();
  }

  @Test
  @DisplayName("flag开_list")
  void flagOn_list() throws Exception {
    properties.setMappingsApiEnabled(true);
    IdentityMapping row = new IdentityMapping();
    row.setIssuer("https://idp.example");
    row.setSubject("sub-1");
    row.setUsername("alice");
    row.setEmail("alice@example.com");
    when(mappings.list()).thenReturn(List.of(row));
    mvc.perform(get("/api/v1/identity-mappings"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].issuer").value("https://idp.example"))
        .andExpect(jsonPath("$.data[0].subject").value("sub-1"))
        .andExpect(jsonPath("$.data[0].username").value("alice"))
        .andExpect(jsonPath("$.data[0].email").value("alice@example.com"));
  }

  @Test
  @DisplayName("flag开_upsert")
  void flagOn_upsert() throws Exception {
    properties.setMappingsApiEnabled(true);
    IdentityMapping saved = new IdentityMapping();
    saved.setIssuer("https://idp.example");
    saved.setSubject("sub-1");
    saved.setUsername("alice");
    when(mappings.upsert(eq("https://idp.example"), eq("sub-1"), eq("alice"), isNull()))
        .thenReturn(saved);
    mvc.perform(
            post("/api/v1/identity-mappings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"issuer\":\"https://idp.example\",\"subject\":\"sub-1\",\"username\":\"alice\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("alice"));
    verify(mappings).upsert("https://idp.example", "sub-1", "alice", null);
  }

  @Test
  @DisplayName("flag关_upsert_404")
  void flagOff_upsert_404() throws Exception {
    properties.setMappingsApiEnabled(false);
    mvc.perform(
            post("/api/v1/identity-mappings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"issuer\":\"https://idp.example\",\"subject\":\"sub-1\",\"username\":\"alice\"}"))
        .andExpect(status().isNotFound());
    verify(mappings, never()).upsert(eq("https://idp.example"), eq("sub-1"), eq("alice"), isNull());
  }

  @Test
  @DisplayName("flag开_delete")
  void flagOn_delete() throws Exception {
    properties.setMappingsApiEnabled(true);
    mvc.perform(
            delete("/api/v1/identity-mappings")
                .param("issuer", "https://idp.example")
                .param("subject", "sub-1"))
        .andExpect(status().isOk());
    verify(mappings).delete("https://idp.example", "sub-1");
  }
}
