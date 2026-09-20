package io.oryxos.storage;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceAssetActiveRepository
    extends JpaRepository<WorkspaceAssetActiveEntity, WorkspaceAssetActiveEntity.Pk> {}
