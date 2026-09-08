package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.embedding.TextEmbedder;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MockEmbeddingModelTest {

  @Test
  void sameTextAlwaysYieldsSameUnitVector() {
    TextEmbedder first = new MockEmbeddingModel();
    TextEmbedder second = new MockEmbeddingModel();

    float[] a = first.embed("磁盘告警怎么处理");
    float[] b = second.embed("磁盘告警怎么处理");

    // 跨实例确定性（SC-004：CI 可稳定断言）
    assertArrayEquals(a, b);
    assertEquals(MockEmbeddingModel.DIMENSIONS, a.length);
    double norm = 0;
    for (float value : a) {
      norm += (double) value * value;
    }
    assertEquals(1.0, Math.sqrt(norm), 1e-5, "mock 向量必须单位化");
  }

  @Test
  void differentTextsYieldDistinguishableVectors() {
    TextEmbedder embedder = new MockEmbeddingModel();
    assertFalse(Arrays.equals(embedder.embed("磁盘告警"), embedder.embed("网络故障")), "不同文本的向量必须可区分");
  }

  @Test
  void factoryRoutesMockAndRejectsMissingConfigReadably() {
    ProviderEmbeddingModelFactory factory = new ProviderEmbeddingModelFactory();

    assertTrue(factory.buildOne("mock", null, null, null) instanceof MockEmbeddingModel);

    // 未配置 provider：可读报错点名配置键，不静默回退（plan 停点 3）
    IllegalArgumentException noProvider =
        assertThrows(IllegalArgumentException.class, () -> factory.buildOne(null, "k", "u", "m"));
    assertTrue(noProvider.getMessage().contains("knowledge.embedding.provider"));

    IllegalArgumentException noModel =
        assertThrows(
            IllegalArgumentException.class,
            () -> factory.buildOne("qwen", "key", "https://dashscope.aliyuncs.com", null));
    assertTrue(noModel.getMessage().contains("knowledge.embedding.model"));

    assertThrows(
        IllegalArgumentException.class,
        () -> factory.buildOne("qwen", "", "https://dashscope.aliyuncs.com", "text-embedding-v4"));
  }
}
