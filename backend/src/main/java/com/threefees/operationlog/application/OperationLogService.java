package com.threefees.operationlog.application;

import com.threefees.identity.application.CurrentUser;
import com.threefees.operationlog.domain.OperationAction;
import com.threefees.operationlog.domain.OperationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OperationLogService {

  private static final Logger LOGGER = LoggerFactory.getLogger(OperationLogService.class);

  private final OperationLogRepository operationLogRepository;

  public OperationLogService(OperationLogRepository operationLogRepository) {
    this.operationLogRepository = operationLogRepository;
  }

  public void loginSucceeded(String traceId, CurrentUser user) {
    record(
        traceId,
        user.id(),
        user.username(),
        OperationAction.SESSION_LOGIN,
        OperationResult.SUCCEEDED);
  }

  public void loginFailed(String traceId, String username) {
    record(traceId, null, username, OperationAction.SESSION_LOGIN, OperationResult.FAILED);
  }

  public void logoutSucceeded(String traceId, CurrentUser user) {
    record(
        traceId,
        user.id(),
        user.username(),
        OperationAction.SESSION_LOGOUT,
        OperationResult.SUCCEEDED);
  }

  private void record(
      String traceId,
      Long userId,
      String username,
      OperationAction action,
      OperationResult result) {
    try {
      operationLogRepository.insert(traceId, userId, username, action, result);
    } catch (RuntimeException exception) {
      LOGGER.warn(
          "Operation audit write failed traceId={} action={} exceptionType={}",
          traceId,
          action,
          exception.getClass().getName());
    }
  }
}
