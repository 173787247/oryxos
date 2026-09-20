# Data model additions（044 / #466）

## durable_task_checkpoints (V20 columns)

| Column | Meaning |
|--------|---------|
| `ttl_seconds` | approval TTL from policy decision |
| `expires_at` | `created_at + ttl` (null if no ttl) |

## approval_callback_receipts

| Column | Meaning |
|--------|---------|
| `channel` | feishu / wecom / … |
| `callback_id` | channel-side idempotency key |
| `checkpoint_id` | optional link |
| `created_at` | claim time |

PK `(channel, callback_id)`.
