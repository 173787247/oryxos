package io.oryxos.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/** IM 审批回调收据（044 / #466）。 */
public interface ApprovalCallbackReceiptRepository
    extends JpaRepository<ApprovalCallbackReceiptEntity, ApprovalCallbackReceiptEntity.Pk> {}
