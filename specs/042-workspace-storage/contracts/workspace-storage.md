# Workspace storage contract

插件注册以 `id -> WorkspaceStorageProvider` 显式映射，支持 Spring 注入的扩展 provider。未知/重复 id 是配置错误。

标准文件 API 为 java.nio.file.Files / Path / FileSystemProvider；WorkspaceStorage.root() 返回绑定当前 provider 的 Path，resolve/normalize/toRealPath/list 返回值必须保持 provider。文件属性读取遵守 NOFOLLOW_LINKS，dangling link 不能等同不存在。输入字符串只能由 WorkspaceStorage.resolve 转为工作区 Path。跨 provider 移动不得静默降级原子性。

首期必须声明：STREAM_IO、ATOMIC_MOVE、SYMBOLIC_LINKS、NATIVE_EXECUTION_VIEW；shared-posix 还声明 SHARED。未来插件能力不足应在装配时拒绝，而非运行时破坏现有业务。

shared-posix 每次操作前核对预置 `.workspace-id`（普通文件，不接受软链接）；不匹配抛存储不可用异常，健康 DOWN。身份验证失败不能调用 mkdir 初始化根。nativePath 是显式插件方法，仅在声明执行视图时支持，调用前核验健康。

文件覆盖先同目录临时流写入，再原子改名；失败保留原文件并尽力清理本次临时文件。多文件事务必须对外说明逐文件可见，并记录恢复流程。

错误分类：未知插件/能力不足=配置错误；身份或 IO 故障=不可用；预期发布代次不匹配或预约占用=冲突。异常和健康响应不得包含凭据。


## 管理编辑协议
服务端管理过滤器必须注入 `WorkspaceAvailability` 非阻塞存储快照。快照不可用或过期时，在文件 I/O 和预约前返回 503；不得在请求线程同步等待探针。此准入不取消此前已进入内核的 I/O，探针尚未失效的故障发现窗口仍存在。

GET 返回 `X-Workspace-Revision`，只可与该读取快照一起保存。受保护修改用 `If-Match` 回传；不匹配 HTTP 409，处理器不执行。读取编辑内容须来自规范文件，不能把共享最新代次附到过期注册表数据。第一方编辑器必须带读取时的代次；旧 API 客户端省略时仅保证写互斥。

拒绝请求不能被默认当作完整回滚。一般 4xx 保守推进代次并保留 `rejected-unverified` 诊断记录，避免可能已修改的内容仍沿用旧代次；客户端失败后应重新读取内容再重试。服务端异常保留阻塞预约，需离线核实恢复。多文件状态与绑定/DB 不宣称联合事务。


### 发布结果状态表

| 请求阶段 / 结果 | 业务处理器 | 代次与预约 |
|---|---|---|
| 存储快照不可用或过期 | 不执行，503 | 不访问文件系统，不取得预约 |
| 预约前格式错误、旧 If-Match 或已有预约 | 不执行 | 不推进代次、不改变既有预约 |
| 已取得预约，处理器成功 | 执行 | 推进代次后释放预约 |
| 已取得预约，处理器返回 4xx（包括业务 409） | 可能已有副作用 | 保守推进代次，归档 rejected-unverified 后释放预约；不宣称回滚成功 |
| 处理器异常、5xx 或提交/归档 I/O 失败 | 结果可能不完整 | 保留阻塞预约与诊断，停所有写者后维护恢复 |

代次为工作区范围的不透明 UUID；尚无发布时为 `initial`，不是内容哈希。失败响应中的新代次不能作为未重读内容的自动重试依据。SHA-256 仅用于内容指纹及备份完整性。
