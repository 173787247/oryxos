# Research — 042

## 027 复用点
WorkspaceVersionPoller、WorkspaceVersionNotifier、AtomicFiles 和 SharedVolumeGuide 已有版本总线及共享卷约束。当前漏通知没有最终对账，AgentStore.writeAll 逐文件备份并非目录事务，HTTP 下载及部分工具直接写最终路径，输出提示目录为公共 output。

## 决策
- 采用 Java NIO 标准操作接口加显式插件注册，不另造一套不完整 Files API；测试用可观测 provider 验证业务真实经过插件。
- 首期插件 local/shared-posix；对象存储原生适配另期。OSS ossfs2 缺少本项目依赖的软链接/原子 rename 语义，不能当透明共享盘。官方依据：https://www.alibabacloud.com/help/en/oss/developer-reference/ossfs-2-0/ 。
- 周期对账修复“内容已写、版本未 bump”的崩溃窗口，而不引入第二套通知表。
- 身份文件必须管理员预置；健康失败不回落空本地目录。真实挂载 HA 取决于部署，软件不能自行保证。
- 持久预约不自动过期，避免租约 fencing 与 POSIX 文件写入无法原子绑定的漏洞；崩溃后维护恢复换取不会静默覆盖。不能把 PG advisory lock 单独当文件系统 fencing。
- 不改原始运行 PG/SQLite 的 Flyway 历史，不重建用户的 mem0。

## 备选
单纯配置 root 虽兼容 NFS，但没有插件分派/身份防护；全量替换业务为自造 key API 改动大且容易漏 native parser/shell；对象存储 FUSE 缺少所需能力，均不作为首期主路径。


## 集成审查补充
工作区代次采用不透明 UUID，避免每次编辑哈希整个共享卷。它是管理发布的条件令牌，不是文件内容摘要或备份校验值；备份继续用 SHA-256。返回令牌时必须读取当前规范文件，不能使用滞后 ProfileRegistry 表示。第一方编辑器保存读取快照的令牌。拒绝写请求可能已触碰文件，保守推进代次并归档诊断；这会让无关编辑更早得到冲突，换取不会静默接受陈旧覆盖。
