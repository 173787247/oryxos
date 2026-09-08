package io.oryxos.core.embedding;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * float32[] ↔ 小端序 BLOB 编解码——014 知识库与 015 记忆索引共用的存储格式（跨模块基建，随检索管线放 core）。 只处理非空向量；「null =
 * 无向量」之类的档位语义由调用方自行包装。
 */
public final class VectorCodec {

  private VectorCodec() {}

  public static byte[] encode(float[] vector) {
    ByteBuffer buffer =
        ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    for (float value : vector) {
      buffer.putFloat(value);
    }
    return buffer.array();
  }

  public static float[] decode(byte[] bytes) {
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    float[] vector = new float[bytes.length / Float.BYTES];
    for (int i = 0; i < vector.length; i++) {
      vector[i] = buffer.getFloat();
    }
    return vector;
  }
}
