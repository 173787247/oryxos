package io.oryxos.core.knowledge.model;

/**
 * 出处——检索命中的一等公民字段（规避 AgentScope 把出处埋弱类型 payload 的教训，research D9）。
 *
 * @param kbName 知识库名
 * @param relPath 库内相对路径；远程后端映射不到时为空串并以 {@code readable=false} 显式标注
 * @param position 片段位置：markdown/纯文本用片段序号（如 "3"），PDF 用页码（如 "page:2"）
 * @param readable 本地可跟读（可按路径 read_file 读取原文）；远程无本地文件时为 false
 */
public record Citation(String kbName, String relPath, String position, boolean readable) {

  /** 出处不可用（远程后端缺字段）时的占位显示文本。 */
  public static final String UNAVAILABLE = "出处不可用";

  /** 统一渲染格式 {@code [库名] 文件路径 #片段位置}；出处不可用时显式标注而不是给出假路径。 */
  public String display() {
    if (relPath == null || relPath.isBlank()) {
      return "[" + kbName + "] " + UNAVAILABLE;
    }
    return "[" + kbName + "] " + relPath + " #" + position;
  }
}
