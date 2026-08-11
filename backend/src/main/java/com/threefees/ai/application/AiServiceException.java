package com.threefees.ai.application;

public class AiServiceException extends RuntimeException {

  private final String code;
  private final boolean retryable;

  public AiServiceException(String code, String message, boolean retryable) {
    super(message);
    this.code = code;
    this.retryable = retryable;
  }

  public String code() {
    return code;
  }

  public boolean retryable() {
    return retryable;
  }
}
