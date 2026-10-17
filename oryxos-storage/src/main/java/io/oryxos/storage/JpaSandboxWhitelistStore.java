package io.oryxos.storage;

import io.oryxos.core.sandbox.SandboxWhitelist.Category;
import io.oryxos.core.sandbox.SandboxWhitelistStore;
import java.util.List;

/** {@link SandboxWhitelistStore} 的 SQLite/JPA 实现：sandbox_whitelist 表 ↔ {@link Entry} 互转。 */
public class JpaSandboxWhitelistStore implements SandboxWhitelistStore {

  private final SandboxWhitelistRepository repository;

  public JpaSandboxWhitelistStore(SandboxWhitelistRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<Entry> loadAll() {
    return repository.findAll().stream()
        .map(r -> new Entry(Category.valueOf(r.getCategory()), r.getEntryValue()))
        .toList();
  }

  @Override
  public boolean add(Category category, String value) {
    if (repository.existsByCategoryAndEntryValue(category.name(), value)) {
      return false;
    }
    SandboxWhitelistRow row = new SandboxWhitelistRow();
    row.setCategory(category.name());
    row.setEntryValue(value);
    try {
      repository.saveAndFlush(row);
      return true;
    } catch (RuntimeException conflict) {
      // 契约要求 add 幂等（已存在返回 false），而"已存在"在一个集群里不只由本进程决定：两个副本同时启动时
      // 会双双通过上面的存在性检查，由唯一键 (category, entry_value) 兜底。冲突在这里是正常结果，不是错误；
      // 非约束违规原样上抛。这正是 ConcurrentMigrationIT 抓到的形态（两副本并发启动播种同一批白名单）。
      if (ConstraintViolations.isConstraintViolation(conflict)) {
        return false;
      }
      throw conflict;
    }
  }

  @Override
  public boolean remove(Category category, String value) {
    List<SandboxWhitelistRow> rows = repository.findByCategoryAndEntryValue(category.name(), value);
    if (rows.isEmpty()) {
      return false;
    }
    repository.deleteAll(rows);
    return true;
  }
}
