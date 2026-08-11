package com.threefees.task.domain;

public enum TaskStatus {
  QUEUED,
  RUNNING,
  RETRY_WAIT,
  SUCCEEDED,
  FAILED
}
