# Data model

- WorkspaceStorageProvider: 唯一 id，按配置创建 WorkspaceStorage；生命周期由应用管理。
- WorkspaceStorage: 所选 root Path、不可变能力集合、健康状态和显式 native execution view。
- WorkspaceIdentity: 共享根下 `.workspace-id` 的固定部署标识；由部署方初始化，应用不创建。
- WorkspaceRevision: 不透明的工作区发布代次（UUID；首次为 initial）。编辑快照保留读取代次，修改时比较调用方预期代次；不同返回冲突。版本必须与所返回的规范文件内容对应，不可用新代次标记旧缓存。
- PublicationReservation: 工作区内持久排他预约及提交恢复记录；正常结束清理，崩溃留下可诊断记录；维护恢复要求所有写者停机。
- RunOutput: output/<agent>/<run-id>/，不同执行互不覆盖；shell 工作目录与发布状态显式区分。
- ReconciliationStatus: 域的 lastSeenVersion、最后成功对账与最近失败；沿用 workspace_versions 表，无双写绑定索引。
