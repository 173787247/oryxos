package io.oryxos.storage;

import io.oryxos.core.durable.ApprovalCallbackReceiptStore;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/** JPA 回调收据（044 / #466）：唯一主键保证跨重启去重。 */
public class JpaApprovalCallbackReceiptStore implements ApprovalCallbackReceiptStore {

  private final ApprovalCallbackReceiptRepository repository;

  public JpaApprovalCallbackReceiptStore(ApprovalCallbackReceiptRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public boolean tryClaim(String channel, String callbackId, String checkpointId) {
    ApprovalCallbackReceiptEntity.Pk pk = new ApprovalCallbackReceiptEntity.Pk(channel, callbackId);
    if (repository.existsById(pk)) {
      return false;
    }
    ApprovalCallbackReceiptEntity row = new ApprovalCallbackReceiptEntity();
    row.setChannel(channel);
    row.setCallbackId(callbackId);
    row.setCheckpointId(checkpointId);
    row.setCreatedAt(Instant.now());
    try {
      repository.saveAndFlush(row);
      return true;
    } catch (DataIntegrityViolationException ex) {
      return false;
    }
  }

  @Override
  public boolean alreadySeen(String channel, String callbackId) {
    return repository.existsById(new ApprovalCallbackReceiptEntity.Pk(channel, callbackId));
  }
}
