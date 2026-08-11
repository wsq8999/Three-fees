package com.threefees.identity.application;

public class InvalidCredentialsException extends RuntimeException {

  public InvalidCredentialsException() {
    super("用户名或口令不正确");
  }
}
