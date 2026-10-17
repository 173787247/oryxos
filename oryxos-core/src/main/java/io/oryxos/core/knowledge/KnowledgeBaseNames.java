package io.oryxos.core.knowledge;

import io.oryxos.core.fs.RealPathBoundary;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 知识库名的唯一判定：字符集 + 真实路径边界 + 真实目录名一致，三者缺一不可。
 *
 * <p>它是「建库 / 改描述 / 删库 / 导入文档 / 重建索引 / REST 入口」共用的同一条规则，作用是把过去靠 「目标目录里恰好有可读的 {@code
 * KNOWLEDGE.md}」这种偶然条件拦住的越界，变成确定的 400。
 */
public final class KnowledgeBaseNames {

  /** 库名字符集：与建库输入框、Agent 绑定同一口径（否则会出现「能建不能绑」或反之）。 */
  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]+");

  private static final String ILLEGAL_NAME = "非法知识库名（只允许字母/数字/下划线/连字符）";

  private static final String ESCAPED_DIR = "知识库目录越界（真实路径不在知识库根内）";

  private static final String ALIAS_DIR = "知识库名与外层目录不一致（软链别名）";

  private KnowledgeBaseNames() {}

  /** 名字形状：非 null 且只含字母/数字/下划线/连字符。 */
  public static boolean isSafe(String name) {
    return name != null && SAFE_NAME.matcher(name).matches();
  }

  /**
   * 校验知识库名并解析成库目录的真实路径。
   *
   * <p>三臂各挡一类越界，缺一不可：
   *
   * <ul>
   *   <li><b>字符集</b>：挡 {@code ..} / {@code .} / 多段名。{@code kbName="."} 解析到知识库根自身，真实路径就在根内，
   *       边界臂会放行——而 {@code deleteBase(".")} 会 walk 整个根、把所有库一起删掉。
   *   <li><b>真实路径边界</b>：挡「库目录本身是指向根外的软链」。名字合法也挡不住它：{@code Files.walk} 从软链起点会
   *       下钻到根外目录，库外内容照样被切分入库。绑定侧早已判这种形态为 ESCAPED，这里让操作侧与之一致。
   *   <li><b>真实目录名一致</b>：挡「库目录是根内的软链别名」（{@code knowledge/alias -> knowledge/ops}）。真实路径边界会
   *       放行它（目标在根内），但解析出来的库目录名是 {@code ops}，不是请求的 {@code alias}——放行的话请求的名字与 它实际操作的目录不是同一个：{@code
   *       deleteBase("alias")} 会删掉 {@code ops}、{@code importDocument("alias", …)} 会往 {@code ops}
   *       里写。只比较最后一段，所以整棵 {@code knowledge/} 或 {@code oryxos.root} 在软链上 （真实部署形态）不受影响。
   * </ul>
   *
   * @param knowledgeRoot 知识库根目录（不存在时按最近已存在祖先投影）
   * @param name 知识库名，即库目录名
   * @return 库目录的真实路径（已确认位于知识库根内且真实目录名等于请求名；目录本身是否存在由调用方判定）
   * @throws IllegalArgumentException 名字非法、真实路径越界或真实目录名与请求名不一致——对外统一 400
   */
  public static Path requireBaseDir(Path knowledgeRoot, String name) {
    Objects.requireNonNull(knowledgeRoot, "knowledgeRoot");
    if (!isSafe(name)) {
      throw new IllegalArgumentException(ILLEGAL_NAME + ": " + name);
    }
    Path root = knowledgeRoot.toAbsolutePath().normalize();
    Path real;
    try {
      real = RealPathBoundary.requireWithin(root, root.resolve(name));
    } catch (IllegalArgumentException | UncheckedIOException e) {
      // 根外软链先在此拒绝，口径保持「越界」，不因下面的别名臂而改写。
      throw new IllegalArgumentException(ESCAPED_DIR + ": " + name, e);
    }
    Path realName = real.getFileName();
    if (realName == null || !realName.toString().equals(name)) {
      throw new IllegalArgumentException(ALIAS_DIR + ": " + name);
    }
    return real;
  }
}
