package com.threefees.identity.application;

public class CsrfValidationException extends RuntimeException {

  public CsrfValidationException() {
    super("请求安全校验失败");
  }
}
