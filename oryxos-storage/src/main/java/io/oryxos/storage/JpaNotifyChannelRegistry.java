package io.oryxos.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.notify.NotifyChannelDef;
import io.oryxos.core.notify.NotifyChannelRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link NotifyChannelRegistry} 的 SQLite/JPA 实现：notify_channels 表 ↔ {@link NotifyChannelDef} 互转。
 * {@code config} 列是 JSON 文本，序列化沿用 {@link JpaSessionManager} 的手工 ObjectMapper 模式。
 */
public class JpaNotifyChannelRegistry implements NotifyChannelRegistry {

  private final NotifyChannelRepository repository;

  private final ObjectMapper mapper = new ObjectMapper();

  public JpaNotifyChannelRegistry(NotifyChannelRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<NotifyChannelDef> list() {
    return repository.findAll().stream().map(this::toDef).toList();
  }

  @Override
  public Optional<NotifyChannelDef> find(String name) {
    return repository.findById(name).map(this::toDef);
  }

  @Override
  public boolean exists(String name) {
    return repository.existsById(name);
  }

  @Override
  public NotifyChannelDef save(NotifyChannelDef channel) {
    NotifyChannel entity = repository.findById(channel.name()).orElseGet(NotifyChannel::new);
    entity.setName(channel.name());
    entity.setType(channel.type());
    entity.setUrl(channel.url());
    entity.setDescription(channel.description());
    entity.setConfig(writeConfig(channel.config()));
    return toDef(repository.save(entity));
  }

  @Override
  public void delete(String name) {
    repository.deleteById(name);
  }

  private NotifyChannelDef toDef(NotifyChannel e) {
    return new NotifyChannelDef(
        e.getName(), e.getType(), e.getUrl(), e.getDescription(), readConfig(e.getConfig()));
  }

  private String writeConfig(Map<String, String> config) {
    if (config == null || config.isEmpty()) {
      return null;
    }
    try {
      return mapper.writeValueAsString(config);
    } catch (JsonProcessingException ex) {
      // config 写不进去等于渠道配置丢失，显式失败而非静默存空
      throw new IllegalStateException("通知渠道 config 序列化失败: " + ex.getOriginalMessage(), ex);
    }
  }

  private Map<String, String> readConfig(String configJson) {
    if (configJson == null || configJson.isBlank()) {
      return Map.of();
    }
    try {
      return mapper.readValue(configJson, new TypeReference<Map<String, String>>() {});
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("通知渠道 config 反序列化失败: " + ex.getOriginalMessage(), ex);
    }
  }
}
