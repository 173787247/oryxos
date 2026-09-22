package io.oryxos.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Boot 4 defaults to Jackson 3 ({@code tools.jackson}); existing code still injects
 * {@code com.fasterxml.jackson.databind.ObjectMapper}. Ensure a Jackson 2 mapper bean
 * exists even when Jackson2 auto-config does not bind one in this reactor.
 */
@Configuration
public class Boot4Jackson2CompatConfig {

  @Bean
  @ConditionalOnMissingBean(ObjectMapper.class)
  ObjectMapper jackson2ObjectMapper() {
    return new ObjectMapper().findAndRegisterModules();
  }
}
