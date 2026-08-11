package com.threefees.operationlog.infrastructure.persistence;

import com.threefees.operationlog.application.OperationLogRepository;
import com.threefees.operationlog.domain.OperationAction;
import com.threefees.operationlog.domain.OperationResult;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisOperationLogRepository implements OperationLogRepository {

  private final OperationLogMapper operationLogMapper;

  public MyBatisOperationLogRepository(OperationLogMapper operationLogMapper) {
    this.operationLogMapper = operationLogMapper;
  }

  @Override
  public void insert(
      String traceId,
      Long appUserId,
      String usernameSnapshot,
      OperationAction action,
      OperationResult result) {
    if (operationLogMapper.insert(
            traceId, appUserId, usernameSnapshot, action.name(), result.name())
        != 1) {
      throw new IllegalStateException("Operation audit row was not persisted");
    }
  }
}
