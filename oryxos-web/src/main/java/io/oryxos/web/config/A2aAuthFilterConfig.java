package io.oryxos.web.config;

import io.oryxos.core.a2a.A2aProperties;
import io.oryxos.web.security.A2aAuthFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Registers {@link A2aAuthFilter} ahead of API Key filter when A2A is enabled. */
@Configuration
@ConditionalOnProperty(prefix = "oryxos.a2a", name = "enabled", havingValue = "true")
public class A2aAuthFilterConfig {

  @Bean
  FilterRegistrationBean<A2aAuthFilter> a2aAuthFilter(A2aProperties a2aProperties) {
    FilterRegistrationBean<A2aAuthFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(new A2aAuthFilter(a2aProperties));
    registration.addUrlPatterns("/api/v1/a2a");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }
}
