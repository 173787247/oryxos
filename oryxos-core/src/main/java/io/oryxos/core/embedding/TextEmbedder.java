package io.oryxos.core.embedding;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本向量化端口——检索基建的通用组件，不带 knowledge 语义（FR-016：记忆语义化升级复用同一端口）。 跨模块契约放 core（依赖倒置）：oryxos-provider
 * 出实现（复用 Provider 注册表凭证）， oryxos-knowledge 消费。同步签名（宪法 VII）。
 */
public interface TextEmbedder {

  /** 把一段文本转成单位化 float 向量；服务不可用时抛出可读 RuntimeException（上层降级，FR-013）。 */
  float[] embed(String text);

  /** 向量化模型标识（provider/model），随片段落库用于一致性校验（FR-014）。 */
  String modelId();

  /** 向量维度。 */
  int dimensions();

  /** 批量向量化；默认逐条调用，支持批量端点的实现可覆盖。 */
  default List<float[]> embedAll(List<String> texts) {
    List<float[]> vectors = new ArrayList<>(texts.size());
    for (String text : texts) {
      vectors.add(embed(text));
    }
    return vectors;
  }
}
