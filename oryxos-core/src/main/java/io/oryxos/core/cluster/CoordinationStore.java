package io.oryxos.core.cluster;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 多副本协调存储契约（026，跨模块契约放 core——依赖倒置）：一条 CAS 认领原语（插入捕唯一约束冲突 + 条件更新抢过期 + owner 条件续租/释放）套四类载体——turn
 * 租约、调度到点、事件回执、渠道属主，外加 实例心跳。默认实现基于共享数据库（JpaCoordinationStore，零新增运维组件）；Redis 等第二实现留缝不做。
 *
 * <p>时间判定一律以数据库时间为准（SELECT CURRENT_TIMESTAMP，两库通用），不是各副本本地时钟—— NTP 秒级漂移不影响正确性。全部方法的布尔返回值 = CAS
 * rowcount 语义（true 恰表示本调用赢得/保有）。
 */
public interface CoordinationStore {

  /** turn 租约认领：新插入或抢过期成功返回 true；被他人持有且未过期返回 false（调用方轮询等待）。 */
  boolean tryAcquireTurn(String sessionId, String owner, Duration ttl);

  /** turn 续租（fencing）：owner 仍持有则延长并返回 true；返回 false = 已被回收，执行方必须立即中止。 */
  boolean renewTurn(String sessionId, String owner, Duration ttl);

  /** turn 释放：只删自己的（owner 条件）。 */
  void releaseTurn(String sessionId, String owner);

  /** 悬空轮留痕辅助：把当前轮关联的 agent_executions id 写入租约行（无 execution 的路径不调用）。 */
  void attachExecution(String sessionId, String owner, long agentExecutionId);

  /**
   * 抢过期 turn 时返回前任租约的未完结 execution id（供补失败留痕）；由 tryAcquireTurn 的抢过期 路径内部记录，调用方经此取回。无前任或前任无
   * execution 返回 null。
   */
  Long lastReclaimedExecutionId();

  /**
   * 调度到点认领（恰好一次）：fireTime MUST 是 CronTrigger 计算的理论触发时刻（各副本对同一 cron 必然同值）——绝非墙钟。条件更新 rowcount==1 返回
   * true = 本副本执行本次到点。
   */
  boolean claimFireTime(String scheduleId, Instant fireTime, String owner);

  /** 入站事件判重：首见插入回执返回 true；唯一约束冲突返回 false = 重复，丢弃。 */
  boolean markReceipt(String receiptKey);

  /** 渠道属主认领（独连型渠道，如企微）：语义同 tryAcquireTurn。 */
  boolean tryAcquireChannel(String channelName, String owner, Duration ttl);

  boolean renewChannel(String channelName, String owner, Duration ttl);

  void releaseChannel(String channelName, String owner);

  /** 实例心跳 upsert（instance_id 主键；epoch=进程启动毫秒）。 */
  void heartbeat(String instanceId, long epoch);

  /** 全部实例（含可能已死的——存活判定由调用方按 last_heartbeat_at 距今与 TTL 比较）。 */
  List<InstanceInfo> listInstances();

  /** 现役 turn 持有（谁在处理哪个会话，运维可见性）。 */
  List<TurnLeaseInfo> activeTurnLeases();

  /** 惰性清理：批删超龄回执与死实例行；心跳循环顺手调用。 */
  void purgeExpired(Duration receiptTtl, Duration instanceDeadAfter);

  /** 实例信息（运维查询载体）。 */
  record InstanceInfo(String instanceId, long epoch, Instant startedAt, Instant lastHeartbeatAt) {}

  /** 现役轮次持有信息。 */
  record TurnLeaseInfo(String sessionId, String owner, Instant leaseUntil, Instant acquiredAt) {}
}
