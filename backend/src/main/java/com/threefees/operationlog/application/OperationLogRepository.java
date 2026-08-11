package com.threefees.operationlog.application;

import com.threefees.operationlog.domain.OperationAction;
import com.threefees.operationlog.domain.OperationResult;

public interface OperationLogRepository {

  void insert(
      String traceId,
      Long appUserId,
      String usernameSnapshot,
      OperationAction action,
      OperationResult result);
}
