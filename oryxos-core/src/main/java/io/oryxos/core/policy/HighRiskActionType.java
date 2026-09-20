package io.oryxos.core.policy;

/**
 * 高风险 Agent 动作分类（042 / #464）：策略可按动作类型配置审批，与具体工具名正交（同一类可覆盖多工具 / MCP）。
 *
 * <p>与沙箱 {@code io.oryxos.tool.sandbox.ActionType} 正交——沙箱管资源边界，本枚举管 HITL 分级。
 */
public enum HighRiskActionType {

  /** 本机命令执行（如 {@code shell}）。 */
  SHELL,

  /** 对外发送（HTTP 写、notify、SMTP 等）。 */
  EXTERNAL_SEND,

  /** 工作区写/删/改文件。 */
  FILE_MUTATION,

  /** 破坏性操作（删库级意图等；本刀预留，规则可显式挂）。 */
  DESTRUCTIVE,

  /** MCP 工具调用（按注册归属判定）。 */
  MCP,

  /** 规则显式声明、分类器未覆盖的自定义动作。 */
  CUSTOM
}
