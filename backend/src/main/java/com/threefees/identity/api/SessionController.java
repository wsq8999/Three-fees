package com.threefees.identity.api;

import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.application.InvalidCredentialsException;
import com.threefees.identity.infrastructure.security.AppUserPrincipal;
import com.threefees.operationlog.application.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SessionController {

  private static final URI CURRENT_SESSION_URI = URI.create("/api/v1/sessions/current");

  private final AuthenticationManager authenticationManager;
  private final SecurityContextRepository securityContextRepository;
  private final OperationLogService operationLogService;

  public SessionController(
      AuthenticationManager authenticationManager,
      SecurityContextRepository securityContextRepository,
      OperationLogService operationLogService) {
    this.authenticationManager = authenticationManager;
    this.securityContextRepository = securityContextRepository;
    this.operationLogService = operationLogService;
  }

  @PostMapping
  public ResponseEntity<SessionResponse> create(
      @Valid @RequestBody LoginRequest loginRequest,
      HttpServletRequest request,
      HttpServletResponse response) {
    Authentication authentication;
    try {
      authentication =
          authenticationManager.authenticate(
              UsernamePasswordAuthenticationToken.unauthenticated(
                  loginRequest.username(), loginRequest.password()));
    } catch (AuthenticationException exception) {
      operationLogService.loginFailed(traceId(request), loginRequest.username());
      throw new InvalidCredentialsException();
    }

    if (request.getSession(false) == null) {
      request.getSession(true);
    } else {
      request.changeSessionId();
    }

    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    securityContextRepository.saveContext(context, request, response);

    var principal = (AppUserPrincipal) authentication.getPrincipal();
    operationLogService.loginSucceeded(traceId(request), principal);
    return ResponseEntity.created(CURRENT_SESSION_URI)
        .body(new SessionResponse(UserResponse.from(principal)));
  }

  @GetMapping("/current")
  public SessionResponse current(@AuthenticationPrincipal CurrentUser currentUser) {
    return new SessionResponse(UserResponse.from(currentUser));
  }

  @DeleteMapping("/current")
  public ResponseEntity<Void> delete(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
    if (authentication.getPrincipal() instanceof CurrentUser currentUser) {
      operationLogService.logoutSucceeded(traceId(request), currentUser);
    }
    new SecurityContextLogoutHandler().logout(request, response, authentication);
    return ResponseEntity.noContent().build();
  }

  private String traceId(HttpServletRequest request) {
    Object traceId = request.getAttribute("traceId");
    return traceId == null ? "unavailable" : traceId.toString();
  }
}
