package io.oryxos.storage;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * 约束违规识别（跨两库）：PG 经 Spring 翻译成 {@link DataIntegrityViolationException}；SQLite 的 community 方言把 {@code
 * SQLITE_CONSTRAINT} 包成 {@code JpaSystemException}（翻译缺口，契约测试实证）。
 *
 * <p>为什么要有这个类：**幂等写入**（"已存在就算成功"）必须把唯一键冲突当成正常路径，而"这是不是约束违规"在两库上
 * 不是同一个异常类型。判定只放这一处，别让每个幂等写入各抄一遍——抄多处的那种错法本仓已经踩过。
 *
 * <p>判定只认 cause 链里的类型与 SQLState：{@code 23xxx}（PG 的完整性违规）或 {@code SQLITE_CONSTRAINT} 标识。
 * 不是约束违规的异常**原样上抛**，绝不吞真错误。
 */
final class ConstraintViolations {

  private static final String INTEGRITY_VIOLATION_STATE_PREFIX = "23";

  private static final String SQLITE_CONSTRAINT_MARKER = "SQLITE_CONSTRAINT";

  private ConstraintViolations() {}

  /** 这条异常（含 cause 链）是不是唯一键/完整性约束违规；其它一律返回 {@code false}。 */
  static boolean isConstraintViolation(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof DataIntegrityViolationException
          || cause instanceof DuplicateKeyException
          || cause instanceof org.hibernate.exception.ConstraintViolationException) {
        return true;
      }
      if (cause instanceof java.sql.SQLException sql) {
        String state = sql.getSQLState();
        if (state != null && state.startsWith(INTEGRITY_VIOLATION_STATE_PREFIX)) {
          return true;
        }
        if (String.valueOf(sql.getMessage()).contains(SQLITE_CONSTRAINT_MARKER)) {
          return true;
        }
      }
    }
    return false;
  }

  /** 约束违规 → 静默返回（调用方按"已被占用/已存在"处理）；其它失败原样上抛。 */
  static void rethrowUnlessConstraintViolation(DataAccessException error) {
    if (!isConstraintViolation(error)) {
      throw error;
    }
  }
}
