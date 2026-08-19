package io.oryxos.knowledge.retrieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.knowledge.retrieve.RetrievalPipeline.Candidate;
import io.oryxos.knowledge.retrieve.RetrievalPipeline.Fused;
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
