package io.oryxos.core.knowledge;

import io.oryxos.core.knowledge.model.KnowledgeCapabilities;
import java.util.Optional;

/**
 * 知识后端插件 = 检索（必选）+ 能力声明 + 可选管理访问器（FR-006）。
 *
 * <p>契约测试（contracts/knowledge-spi.md §2 第 7 条）要求 {@code admin()} 的有无与能力声明一致： 声明了任一管理能力却返回
 * empty（或反之）视为契约违约。
 */
public interface KnowledgeBackend extends KnowledgeRetriever {

  /** 注册名（local / 远程插件名），清单 {@code backend:} 字段按此解析。 */
  String name();

  KnowledgeCapabilities capabilities();

  /** 声明了管理能力时必须返回实现；纯检索后端返回 empty。 */
  Optional<KnowledgeAdmin> admin();
}
