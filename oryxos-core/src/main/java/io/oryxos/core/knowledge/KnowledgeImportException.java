package io.oryxos.core.knowledge;

/** 文档导入的同步校验失败（不支持类型 / 扫描件 PDF / 超限等）：入口即拒绝，不进状态机、 不产生半完成记录（Clarify-Q3）。message 面向管理员可读。 */
public class KnowledgeImportException extends RuntimeException {

  public KnowledgeImportException(String message) {
    super(message);
  }

  public KnowledgeImportException(String message, Throwable cause) {
    super(message, cause);
  }
}
