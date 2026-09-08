package io.oryxos.core.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.retrieval.RetrievalPipeline.Candidate;
import io.oryxos.core.retrieval.RetrievalPipeline.Fused;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetrievalPipelineTest {

  @Test
  void rrfRewardsPresenceInBothRoutes() {
    // 片段 1 在两路都靠前；片段 2 只在向量路第一
    List<Candidate> vector = List.of(new Candidate(2, 0.99), new Candidate(1, 0.80));
    List<Candidate> keyword = List.of(new Candidate(1, 3.0), new Candidate(3, 1.0));

    List<Fused> fused = RetrievalPipeline.fuseByRank(3, vector, keyword);

    assertEquals(1, fused.get(0).id(), "双路都命中的片段应赢过单路第一（名次融合，不比原始分）");
    assertEquals(3, fused.size());
  }

  @Test
  void topKBoundsResultAndEmptyRouteIsTolerated() {
    List<Candidate> vector =
        List.of(new Candidate(1, 0.9), new Candidate(2, 0.8), new Candidate(3, 0.7));

    List<Fused> fused = RetrievalPipeline.fuseByRank(2, vector, List.of());

    assertEquals(2, fused.size());
    assertEquals(1, fused.get(0).id(), "单路融合保持该路名次序");
    assertEquals(2, fused.get(1).id());
  }

  @Test
  void equalWeightsMatchUnweightedExactly() {
    List<Candidate> vector = List.of(new Candidate(2, 0.99), new Candidate(1, 0.80));
    List<Candidate> keyword = List.of(new Candidate(1, 3.0), new Candidate(3, 1.0));

    List<Fused> unweighted = RetrievalPipeline.fuseByRank(3, vector, keyword);
    List<Fused> weighted =
        RetrievalPipeline.fuseByRank(3, new double[] {1.0, 1.0}, vector, keyword);

    assertEquals(unweighted, weighted, "等权系数必须与无权重版逐项一致（SC-002 兼容前提）");
  }

  @Test
  void higherWeightLiftsThatRoutesCandidates() {
    // 两路候选不相交：等权下并列名次靠插入序，路 B 权重翻倍后其头名必须反超
    List<Candidate> routeA = List.of(new Candidate(1, 0.9));
    List<Candidate> routeB = List.of(new Candidate(2, 5.0), new Candidate(3, 1.0));

    List<Fused> equal = RetrievalPipeline.fuseByRank(3, new double[] {1.0, 1.0}, routeA, routeB);
    List<Fused> boosted = RetrievalPipeline.fuseByRank(3, new double[] {1.0, 2.0}, routeA, routeB);

    assertEquals(1, equal.get(0).id(), "等权并列时保持插入序基线");
    assertEquals(2, boosted.get(0).id(), "高权路头名应因权重提升反超");
  }

  @Test
  void zeroWeightRouteContributesNothingButIsTolerated() {
    List<Candidate> routeA = List.of(new Candidate(1, 0.9));
    List<Candidate> routeB = List.of(new Candidate(2, 5.0));

    List<Fused> fused = RetrievalPipeline.fuseByRank(3, new double[] {1.0, 0.0}, routeA, routeB);

    assertEquals(1, fused.get(0).id(), "零权重路不得影响排序");
    assertEquals(0.0, fused.get(1).score(), 1e-12, "零权重路候选贡献为零、垫底呈现");
  }

  @Test
  void weightCountMismatchIsRejected() {
    List<Candidate> route = List.of(new Candidate(1, 0.9));

    assertThrows(
        IllegalArgumentException.class,
        () -> RetrievalPipeline.fuseByRank(3, new double[] {1.0, 1.0}, route));
  }

  @Test
  void cosineComputesSimilarityAndRejectsDimensionMismatch() {
    float[] a = {1, 0, 0};
    float[] b = {1, 0, 0};
    float[] c = {0, 1, 0};

    assertEquals(1.0, RetrievalPipeline.cosine(a, b), 1e-9);
    assertEquals(0.0, RetrievalPipeline.cosine(a, c), 1e-9);
    assertTrue(RetrievalPipeline.cosine(a, new float[] {-1, 0, 0}) < 0);

    // 维度不一致拒绝比较（FR-014 的底层防线）
    assertThrows(
        IllegalArgumentException.class, () -> RetrievalPipeline.cosine(a, new float[] {1, 0}));
  }
}
