package io.oryxos.tool.sandbox;

import java.io.IOException;
import java.util.List;

/**
 * 进程启动契约（024 容器级执行隔离，自 ShellTools 包级接缝升格，形状不变）。
 *
 * <p><b>destroy 语义契约（FR-006 的根基）</b>：实现返回的 {@link Process}，其 {@code destroy()/destroyForcibly()}
 * MUST 终止<b>真实执行本体</b>而非仅终止本地句柄——
 *
 * <ul>
 *   <li>local 档：终止进程树（主进程 + 派生子孙）；
 *   <li>docker 档：终止容器本体（如 {@code --cidfile} + destroy 联动 {@code docker kill}）—— docker CLI
 *       与容器进程无父子关系（容器由 daemon 派生），杀 CLI 及其进程树够不到容器。
 * </ul>
 *
 * <p>调用方（ShellTools 超时终止）依赖此条实现「命令挂死不拖死 ReAct 循环」。实现另需保证 {@code start} 抛出的 {@link IOException}
 * 携带可区分失败原因的信息（CLI 缺失 / daemon 不可达 / 镜像缺失，FR-011 fail-loud 口径）。
 */
@FunctionalInterface
public interface ProcessStarter {

  /**
   * 启动命令并返回进程句柄。
   *
   * @param command argv 形式的完整命令（argv[0] 为可执行文件），不经 shell 解释
   * @return 进程句柄，destroy 语义见接口契约
   * @throws IOException 启动失败（本地进程创建失败 / docker CLI 或 daemon 故障等）
   */
  Process start(List<String> command) throws IOException;
}
