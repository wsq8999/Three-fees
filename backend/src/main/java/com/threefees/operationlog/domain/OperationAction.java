package com.threefees.operationlog.domain;

public enum OperationAction {
  SESSION_LOGIN,
  SESSION_LOGOUT,
  USER_CREATE,
  USER_UPDATE,
  USER_ENABLE,
  USER_DISABLE,
  USER_PASSWORD_RESET,
  USER_PASSWORD_CHANGE
}
