package io.oryxos.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.workspace.WorkspaceAvailability;
import io.oryxos.core.workspace.WorkspaceStorage;
import io.oryxos.web.workspace.WorkspacePublicationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
public class WorkspacePublicationConfig {
  @Bean
  FilterRegistrationBean<WorkspacePublicationFilter> workspacePublicationFilter(
      WorkspaceStorage storage, ObjectMapper mapper, WorkspaceAvailability availability) {
    FilterRegistrationBean<WorkspacePublicationFilter> registration =
        new FilterRegistrationBean<>();
    registration.setFilter(new WorkspacePublicationFilter(storage, mapper, availability));
    registration.addUrlPatterns("/api/v1/*");
    // Authentication and RBAC run first; unauthorized requests cannot reserve the workspace.
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
    return registration;
  }
}
