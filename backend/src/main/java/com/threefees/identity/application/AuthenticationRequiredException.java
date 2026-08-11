package com.threefees.identity.application;

public class AuthenticationRequiredException extends RuntimeException {

  public AuthenticationRequiredException() {
    super("需要登录后访问");
  }
}
