package com.threefees.identity.application;

public class ResourceNotFoundException extends BusinessRuleException {

  public ResourceNotFoundException(String resourceName) {
    super("RESOURCE_NOT_FOUND", resourceName + "不存在或不在当前数据范围内");
  }
}
