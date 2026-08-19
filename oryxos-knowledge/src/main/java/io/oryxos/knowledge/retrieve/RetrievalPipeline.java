package io.oryxos.knowledge.retrieve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 检索流水线的融合段（FR-004）：双路召回结果按名次做 RRF 融合——跨路分数量纲不可比，名次可比 （research D10）。纯函数、不带业务语义（FR-016
 * 通用基建）。精排为可选槽位：v1 无内置精排， 声明 rerank 能力的后端结果直通。
 */
public final class RetrievalPipeline {

  /** RRF 平滑常数（业界惯用 60）：压低头名断层，保留名次序。 */
  static final int RRF_K = 60;

  private RetrievalPipeline() {}

  /** 一路召回的候选（id + 该路自己的分数，列表须已按分数降序）。 */
  public record Candidate(long id, double score) {}

  /** 融合结果（id + RRF 分数，降序）。 */
  public record Fused(long id, double score) {}

  /**
   * 名次融合：score(id) = Σ_route 1/(K + rank)。任一路为空照常融合（单路即原名次序）。
   *
   * @param routes 各路召回结果（每路内部已降序）
   * @param topK 保留条数
   */
  @SafeVarargs
  public static List<Fused> fuseByRank(int topK, List<Candidate>... routes) {
    Map<Long, Double> scores = new LinkedHashMap<>();
    for (List<Candidate> route : routes) {
      if (route == null) {
        continue;
      }
      for (int rank = 0; rank < route.size(); rank++) {
        double contribution = 1.0 / (RRF_K + rank + 1);
        scores.merge(route.get(rank).id(), contribution, Double::sum);
      }
    }
    List<Fused> fused = new ArrayList<>(scores.size());
    scores.forEach((id, score) -> fused.add(new Fused(id, score)));
    fused.sort(Comparator.comparingDouble(Fused::score).reversed());
    return fused.size() > topK ? List.copyOf(fused.subList(0, topK)) : List.copyOf(fused);
  }

  /** 余弦相似度；调用方保证维度一致（不一致由上层按 FR-014 拒绝混比）。 */
  public static double cosine(float[] a, float[] b) {
    if (Objects.isNull(a) || Objects.isNull(b)) {
      throw new IllegalArgumentException("向量为空，拒绝比较");
    }
    // p3c 会把 float[].length 误判为浮点比较，先取成 int 再比
    int dimA = a.length;
    int dimB = b.length;
    if (dimA != dimB) {
      throw new IllegalArgumentException("向量维度不一致，拒绝比较");
    }
    double dot = 0;
    double normA = 0;
    double normB = 0;
    for (int i = 0; i < a.length; i++) {
      dot += (double) a[i] * b[i];
      normA += (double) a[i] * a[i];
      normB += (double) b[i] * b[i];
    }
    if (normA <= 0 || normB <= 0) {
      return 0;
    }
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
  }
}
