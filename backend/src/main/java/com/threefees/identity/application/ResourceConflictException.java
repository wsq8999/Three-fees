package com.threefees.identity.application;

public class ResourceConflictException extends BusinessRuleException {

  public ResourceConflictException(String code, String message) {
    super(code, message);
  }
}
