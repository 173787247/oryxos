package io.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/** ConstraintViolations 单元：两库的约束违规识别（类型 / SQLState 23xxx / SQLite 标识）与"只吞约束违规"。 */
class ConstraintViolationsTest {

  @Test
  @DisplayName("Spring 的两类完整性异常直接命中")
  void recognizesSpringIntegrityExceptions() {
    assertTrue(
        ConstraintViolations.isConstraintViolation(new DataIntegrityViolationException("dup")));
    assertTrue(ConstraintViolations.isConstraintViolation(new DuplicateKeyException("dup")));
  }

  @Test
  @DisplayName("Hibernate 的约束违规异常命中")
  void recognizesHibernateConstraintViolation() {
    org.hibernate.exception.ConstraintViolationException error =
        new org.hibernate.exception.ConstraintViolationException(
            "dup", new SQLException("duplicate key", "23505"), "uk_name");

    assertTrue(ConstraintViolations.isConstraintViolation(error));
  }

  @Test
  @DisplayName("沿 cause 链识别嵌套的完整性异常")
  void walksTheCauseChain() {
    RuntimeException wrapped =
        new RuntimeException(
            "outer", new RuntimeException("inner", new DataIntegrityViolationException("dup")));

    assertTrue(ConstraintViolations.isConstraintViolation(wrapped));
  }

  @Test
  @DisplayName("SQLState 23xxx 视为完整性违规")
  void treatsSqlState23AsIntegrityViolation() {
    assertTrue(ConstraintViolations.isConstraintViolation(new SQLException("x", "23505")));
    assertTrue(ConstraintViolations.isConstraintViolation(new SQLException("x", "23000")));
  }

  @Test
  @DisplayName("SQLite 的 SQLITE_CONSTRAINT 消息标识视为完整性违规")
  void treatsSqliteConstraintMarkerAsIntegrityViolation() {
    assertTrue(
        ConstraintViolations.isConstraintViolation(
            new RuntimeException(new SQLException("SQLITE_CONSTRAINT_UNIQUE: unique failed"))));
  }

  @Test
  @DisplayName("非完整性错误与空异常一律不算")
  void rejectsNonIntegrityFailures() {
    assertFalse(ConstraintViolations.isConstraintViolation(new SQLException("boom", "42000")));
    assertFalse(ConstraintViolations.isConstraintViolation(new RuntimeException("boom")));
    assertFalse(ConstraintViolations.isConstraintViolation(null));
  }

  @Test
  @DisplayName("只吞约束违规，其它失败原样上抛")
  void rethrowsOnlyNonConstraintFailures() {
    assertDoesNotThrow(
        () ->
            ConstraintViolations.rethrowUnlessConstraintViolation(
                new DataIntegrityViolationException("dup")));

    DataAccessResourceFailureException failure = new DataAccessResourceFailureException("db down");
    assertSame(
        failure,
        assertThrows(
            DataAccessResourceFailureException.class,
            () -> ConstraintViolations.rethrowUnlessConstraintViolation(failure)));
  }
}
