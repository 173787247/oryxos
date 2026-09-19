package io.oryxos.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/** 审批审计（042 / #464）。 */
public interface ApprovalEventRepository extends JpaRepository<ApprovalEvent, Long> {}
