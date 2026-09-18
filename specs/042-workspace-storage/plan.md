# Implementation Plan: 高可用共享工作区及可靠性补齐

**Branch**: `042-workspace-storage` | **Date**: 2026-09-17 | **Spec**: [spec.md](spec.md)

## Summary
复用 027 的目录布局、相对链接、原子改名和 PostgreSQL workspace_versions。文件标准接口复用 Java NIO FileSystemProvider，以显式 WorkspaceStorageProvider 注册表选择插件，并把所选插件的 Path 传播到业务读写；禁止通过字符串重建默认文件系统路径绕过插件。首期 local 与 shared-posix 使用同一 POSIX 操作实现，后者增加预置身份校验。未来对象存储需要实现相同能力或执行视图适配，能力不足启动即拒绝。

## Technical Context
Java 21 / Spring Boot 3 / Maven 多模块；不增加运行时第三方依赖或模块（boot 仅复用已有版本管理的 provided SpotBugs 注解）。契约在 oryxos-core，装配在 oryxos-cli，健康探针在 oryxos-boot。PG 继续存协调信息；NFS/NAS/CephFS 由部署方提供。JUnit 5 使用真实临时目录和可观测替代插件，集群共享卷验收另外执行。正常通知沿用 027 的轮询时延；漏通知在可配置 reconcile-interval 加一次重载耗时内收敛。

## Global Constraints
- 保持 agents/skills/knowledge/personas 布局和相对软链接唯一绑定真相源；真实路径越界检查必须保留。
- 业务 Files 操作通过所选插件 Path 的 FileSystemProvider 分发；标准接口包括流、属性、列目录、创建、复制、移动、删除和链接。原生执行路径是显式能力，不能靠 toString/Path.of 偷渡。
- 未知/重复插件、缺少能力、共享身份缺失或不匹配均拒绝；共享模式禁止自动初始化错误挂载。
- 单文件发布以同卷原子改名实现；多文件操作不能宣称目录级原子可见。并发修改需冲突检测及可恢复记录；不能把 JVM synchronized 或会过期的租约当分布式写锁。
- 所有测试隔离于现有 Docker PG/mem0/工作区；不部署当前分支到旧迁移历史的运行数据库。
- 不声称同机目录测试证明真实 NFS、跨主机或多可用区高可用。

## Constitution Check
通过：标准契约位于 core；无循环依赖，无新模块；保留目录和链接语义；同步执行；无 SecurityManager；复用版本总线，无已有迁移修改。设计后复核同样通过。

## Design
1. WorkspaceStorageProvider 显式 id + open(root, expectedIdentity)，WorkspaceStorage 提供 root、capabilities、checkHealth 和 nativePath。注册表拒绝重复与未知 id。NIO 包装保持 Path 的 provider 身份，常规读写和属性调用均经守卫；禁止向默认文件系统静默降级。
2. local 默认兼容，shared-posix 要求预先存在 `.workspace-id`，每次文件操作检查，运行期丢失后拒绝访问；健康暴露脱敏状态。身份校验是误挂载防护，不能证明底层副本数量。
3. 版本轮询增加周期全量对账及重载失败状态；单域失败不推进成功时间，立即重试。总线失败不丢弃已加载注册表。
4. 管理提交采用持久排他预约和发布代次校验；编辑界面保存读取快照的代次，服务端规范读避免缓存滞后与新代次错配。预约不因超时被其他写者抢占，以避免暂停写者恢复后覆盖新版本。多文件提交记录恢复信息，崩溃残留须在停止写者的维护窗口恢复，禁止凭超时自动接管。部署文档明确这一安全边界。
5. 管理/工具/知识清单/HTTP 下载补齐原子文件发布；运行输出按 agent/run 隔离，原生 shell 执行视图与输出发布有显式边界。
6. Helm 支持 existingClaim，共享部署校验；迁移保留相对软链接并核对内容摘要，恢复演练先停止所有写者。

## Project Structure
- oryxos-core/src/main/java/io/oryxos/core/workspace/: 插件契约、NIO 桥接、身份与发布
- oryxos-core/.../cluster/: 027 对账可靠性
- oryxos-{boot,web,tool,knowledge,persona}/: 装配和业务入口适配
- charts/oryxos/: existingClaim 与共享配置
- docs/SharedVolumeGuide.md: 运维、迁移及真实共享验收
- specs/042-workspace-storage/: spec、research、data-model、contracts、quickstart、tasks、分析及验收记录

## Validation
先接口/错挂载/通知丢失/并发与中断测试，再模块回归和 Maven 质量门禁。提供可复现命令及实际结果。外部 NFS/K8s 未配置时真实跨主机验收明确待部署环境执行。
