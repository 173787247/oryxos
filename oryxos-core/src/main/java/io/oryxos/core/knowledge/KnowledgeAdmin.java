package io.oryxos.core.knowledge;

import io.oryxos.core.knowledge.model.DocumentStatus;
import java.util.List;

/**
 * 可选管理契约：仅声明了对应能力的后端实现（FR-006）。未声明能力的调用由上层入口按 {@link
 * io.oryxos.core.knowledge.model.KnowledgeCapabilities} 可读拒绝，不落到本接口。
 */
public interface KnowledgeAdmin {

  /** 创建知识库（目录 + 清单）；重名拒绝。 */
  void createBase(String name, String description);

  /** 删除知识库及其索引数据；引用保护（FR-011）由上层先行校验。 */
  void deleteBase(String name);

  /** 导入（或重新导入）库内一份已落盘文档：同步解析校验，后台切分向量化（Clarify-Q3 两段式）。 */
  DocumentStatus importDocument(String kbName, String relPath);

  /** 删除库内一份文档及其索引片段（FR-008 单文档操作）。 */
  void deleteDocument(String kbName, String relPath);

  /** 双缓冲重建全库索引（FR-024）：旧索引持续服务，新索引就绪后原子切换。 */
  void rebuild(String kbName);

  /** 库内全部文档的索引状态。 */
  List<DocumentStatus> status(String kbName);
}
