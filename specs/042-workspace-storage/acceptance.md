# 042 实现与验收记录

分支：`042-workspace-storage`，基线：`main@ccd952c`。用户已批准首期及可插拔文件接口。代码、测试与 Speckit 产物均在当前工作区，未提交、推送或部署。

## 交付范围

复用 027 的目录、相对绑定链接、PG workspace_versions 与轮询。新增 WorkspaceStorageProvider 显式注册、Java NIO 标准读写分派、local/shared-posix 插件与执行视图边界；补齐对账、身份校验、readiness、原子写、管理版本冲突、恢复日志、知识单次快照、运行输出隔离、已有 RWX PVC 接入和迁移恢复文档。

存储插件由 Spring Bean（服务端）或 ServiceLoader（轻 CLI）扩展。未知、重复或能力不足的插件拒绝启动；原生 OSS/S3 插件不在本期实现范围。POSIX 语义及本机执行视图仍是首期能力要求。

## 验证记录

T001–T012 已完成，T013 等待真实外部共享环境。下列结果来自实际命令；未把默认跳过的扫描或同机测试算作真实跨节点验收。

| 检查 | 命令 / 证据 | 结果 |
|---|---|---|
| 后端全量回归及 Checkstyle | `mvn -B -pl oryxos-boot -am test -Dfrontend.skip=true -Dspotless.skip=true` | 通过：335 个测试类、2035 项测试，失败/错误/跳过均为 0；日志 `/tmp/oryxos-042-regression-final.log` |
| 027 集成回归 | FilePlaneVisibilityIT / KnowledgeExactlyOnceIT / KnowledgeFlowIT | 通过：6 + 4 + 1 项；日志 `/tmp/oryxos-042-integration-final.log`；不计为真实双节点验收 |
| Java 格式、P3C/PMD、SpotBugs/FindSecBugs | `mvn -B -fae -pl oryxos-boot -am spotless:check pmd:check spotbugs:check` | 全部通过；`/tmp/oryxos-042-quality-final.log` |
| 前端 | 前端目录 `npm test`、`npm run build` | 中央复跑通过，7 个测试文件及 Vite production build |
| 离线恢复 | `python3 -m unittest bin/test_workspace_recover.py` | 3 个测试通过 |
| Helm | `bash scripts/helm-verify.sh` | 通过；`/tmp/oryxos-042-helm-check.log` |
| OWASP | `mvn -B -Dowasp.skip=false org.owasp:dependency-check-maven:aggregate` | 通过现有 CVSS ≥8 阻断阈值；有阈值以下/无数值评分告警，详见下文；`/tmp/oryxos-042-owasp.log` |
| Speckit 一致性 | [analysis.md](analysis.md) | 覆盖全部 FR/SC；真实环境及门禁状态单列 |
| 独立代码审查 | 分模块审查、全分支审查及六项修复的定向复核 | 六项修复及后续存储故障保留修复均通过限定复核；执行结果以下列中央日志为准 |

默认回归统计来自最终成功日志中的测试类结果，适用于 Surefire 默认选择范围；不是全部 opt-in IT 的数量。额外集成命令：

```sh
mvn -B -pl oryxos-boot -am test \
  -Dtest=FilePlaneVisibilityIT,KnowledgeExactlyOnceIT,KnowledgeFlowIT \
  -DexcludedGroups= -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfrontend.skip=true -Dspotless.skip=true
```

最后静态修整将 Agent 名称/定义文件改为命名常量，将知识解析临时文件后缀改为固定支持格式；不改对外契约。前者已由完整回归中的管理一致性测试验证，后者单独复跑知识快照/插件 5 项测试通过（`/tmp/oryxos-042-knowledge-final-2.log`）。Unicode 告警仅在固定后缀选择小函数上说明误报理由，未降低扫描门槛。

Shell 发布父目录增加显式空值守卫后，`ShellToolsTest` 13 项复跑通过（`/tmp/oryxos-042-shell-final.log`）。工作区目录树条目名称补齐空值守卫后，工作区 API 25 项及健康 1 项复跑通过（`/tmp/oryxos-042-web-health-final.log`）。

### 依赖扫描边界

本次显式启用更新和 aggregate 扫描，非默认跳过。报告 `target/dependency-check-report.{html,json}` 包含 175 个依赖/扫描对象，13 个对象有告警、32 条记录、28 个不同告警标识；有数值评分者最大 7.5。构建按项目现有 CVSS ≥8 阈值通过，不等同无漏洞，也不是对全部扫描匹配的人工确认。报告含旧构建 JAR 与当前前端产物的重复匹配；为保护正在运行的挂载 JAR，本轮没有重新 package。未降低门槛、增加漏洞豁免或升级本次范围之外的既有运行依赖。

### 收敛故障补充验证

门禁收尾时发现 NIO 布尔存在性检查可能吞 IO，从而将断挂载误认为目录删除。新增确定性测试覆盖：identity 不匹配、marker 缺失、派生中缺失、派生失败后在 catch 前恢复，以及存储恢复后的真实业务删除。旧实现确认为 RED，修复后定向 Agent/Skill 测试通过，独立限定复核确认原始 IO 保留且不被健康重探掩盖。另验证根内相对 SKILL.md 文件链接保持跟随，目录枚举的 NOFOLLOW 语义不变。最终完整回归 2035 项与 027 集成 11 项均通过，结果见上表。

## 关键回归证据

- 插件：可观察 provider 操作分派、不同物化根下工具白名单、Agent/Skill 业务、包装 Path 的技能/知识绑定与引用保护；知识业务按选定根读写。
- 收敛：通知遗漏、启动时总线不可用、单域失败重试和成功状态不误推进。
- 文件：中断保留旧内容、共享不安全追加拒绝、内部暂存文件读/下载和链接别名防护。
- 管理并发：相同旧代次冲突、滞后注册表的规范读取、未知知识库预校验、处理器 4xx 推进代次及诊断归档、5xx 保留预约。
- 恢复：中断/已提交 Agent 文件事务、缺失身份拒绝、ownerless 预约显式确认、档案保留。
- 输出：Agent/run 与 Shell invocation 分离、成功发布、失败清理、清理错误不覆盖原始执行错误。
- 部署：existingClaim 不创建 PVC、多副本 RWX 声明约束、shared-posix identity 必填、readiness 缓存探针。

## 尚待真实环境验收（T013）

本轮没有提供两个独立节点的 NFS/NAS/CephFS 挂载或 K8s RWX PVC。真实跨主机故障转移、SC-002 的 ≤3s 运行注册表收敛测量、实际共享备份恢复仍未执行。按 [quickstart.md](quickstart.md) 及 [SharedVolumeGuide](../../docs/SharedVolumeGuide.md) 执行并补充节点、挂载参数、请求结果、时间戳、摘要和链接清单后才能关闭 T013。

本机临时目录、嵌入式测试数据库、多个对象/JVM 的协议测试不证明外部存储高可用。

## 运维边界

- 管理写入进程异常退出后预约不会自动过期；需停止所有写者后执行离线检查/恢复。服务读取可由其他健康副本承担，不等于元数据写入具备无人工故障接管。
- 单文件原子替换不等于多文件目录快照，亦不等于文件、绑定和数据库联合事务。Shell 完成边界以所等待的命令退出为准，调用方须等待其后台写者；首期不提供后台进程写入的快照隔离。
- 工作区版本为不透明 UUID，内容/备份指纹另用 SHA-256；失败响应后的代次不能用于未经重读的自动重试。
- 第一方编辑器带 If-Match；旧外部客户端省略时只有写互斥。在线写管理统一经 API，直接改盘及轻 CLI 须在维护停写窗口。
- 用户现有 Docker PG、mem0、工作区数据和挂载中的旧 boot JAR 均未用于故障测试或替换。新分支需独立部署验证，不能据此报告当前在线 UI 已升级。
