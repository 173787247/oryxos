export const DEFAULT_MCP_REQUEST_TIMEOUT_SECONDS = 30

export function normalizeMcpRequestTimeout(value) {
  const seconds = Number(value)
  if (!Number.isInteger(seconds) || seconds < 1 || seconds > 3600) {
    throw new Error('请求超时必须是 1–3600 之间的整数秒数')
  }
  return seconds
}
