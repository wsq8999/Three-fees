package com.threefees.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.threefees.ThreeFeesApplication;
import com.threefees.identity.application.UserRepository;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = ThreeFeesApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiContractIntegrationTest {

  private static final String INITIAL_TEST_PASSWORD = UUID.randomUUID().toString();

  @DynamicPropertySource
  static void configureBootstrap(DynamicPropertyRegistry registry) {
    registry.add("app.bootstrap.enabled", () -> true);
    registry.add("app.bootstrap.initial-password", () -> INITIAL_TEST_PASSWORD);
  }

  @Value("${local.server.port}")
  private int port;

  @Autowired private UserRepository userRepository;

  @Autowired private PasswordEncoder passwordEncoder;

  @Autowired private AuthenticationManager authenticationManager;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void bootstrapHashesAuthenticateThroughConfiguredProvider() {
    var administrator = userRepository.findByUsername("admin").orElseThrow();
    assertThat(administrator.passwordHash()).doesNotContain(INITIAL_TEST_PASSWORD);
    assertThat(passwordEncoder.matches(INITIAL_TEST_PASSWORD, administrator.passwordHash()))
        .isTrue();
    assertThatCode(
            () ->
                authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                        "admin", INITIAL_TEST_PASSWORD)))
        .doesNotThrowAnyException();
  }

  @Test
  void unauthenticatedRequestUsesProblemDetailsAndIssuesCsrfCookie() throws Exception {
    var client = newClient();

    var response = get(client.httpClient(), "/api/v1/sessions/current");

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .startsWith("application/problem+json");
    assertThat(response.body()).contains("\"code\":\"AUTHENTICATION_REQUIRED\"");
    assertThat(cookieValue(client.cookieManager(), "XSRF-TOKEN")).isNotBlank();
  }

  @Test
  void loginRequiresCsrfAndRejectsInvalidCredentialsWithoutAccountDisclosure() throws Exception {
    var withoutCsrf = newClient();
    var missingCsrf =
        postJson(
            withoutCsrf.httpClient(),
            "/api/v1/sessions",
            loginBody("admin", INITIAL_TEST_PASSWORD),
            null);
    assertThat(missingCsrf.statusCode()).isEqualTo(403);
    assertThat(missingCsrf.body()).contains("\"code\":\"CSRF_VALIDATION_FAILED\"");

    var invalid = newClient();
    get(invalid.httpClient(), "/api/v1/sessions/current");
    var invalidResponse =
        postJson(
            invalid.httpClient(),
            "/api/v1/sessions",
            loginBody("admin", UUID.randomUUID().toString()),
            cookieValue(invalid.cookieManager(), "XSRF-TOKEN"));
    assertThat(invalidResponse.statusCode()).isEqualTo(401);
    assertThat(invalidResponse.body()).contains("\"code\":\"INVALID_CREDENTIALS\"");
    assertThat(invalidResponse.body()).doesNotContain("admin");
  }

  @Test
  void administratorCanUseSessionUserCityAndDashboardContracts() throws Exception {
    var client = authenticatedClient("admin");

    var current = get(client.httpClient(), "/api/v1/sessions/current");
    assertThat(current.statusCode()).isEqualTo(200);
    assertThat(current.body())
        .contains("\"username\":\"admin\"")
        .contains("\"roles\":[\"SUPER_ADMIN\"]")
        .contains("\"city\":null");

    var cities = get(client.httpClient(), "/api/v1/cities");
    assertThat(cities.statusCode()).isEqualTo(200);
    assertThat(cities.body()).contains("\"code\":\"320100\"").contains("\"name\":\"南京市\"");

    var users = get(client.httpClient(), "/api/v1/users?page=0&size=20");
    assertThat(users.statusCode()).isEqualTo(200);
    assertThat(users.body())
        .contains("\"totalElements\":14")
        .contains("\"page\":0")
        .contains("\"size\":20");

    var dashboard = get(client.httpClient(), "/api/v1/dashboard/summary");
    assertThat(dashboard.statusCode()).isEqualTo(200);
    assertThat(dashboard.body())
        .contains("\"currentDataPeriod\":null")
        .contains("\"cityCount\":13")
        .contains("\"billingPointCount\":0")
        .contains("\"overLimitBillingPointCount\":0")
        .contains("\"draftReportCount\":0");

    var openApi = get(client.httpClient(), "/v3/api-docs");
    assertThat(openApi.statusCode()).isEqualTo(200);
    assertThat(openApi.body()).contains("\"/api/v1/sessions\"").contains("\"/api/v1/users\"");
  }

  @Test
  void cityUserCannotListUsersAndLogoutInvalidatesServerSession() throws Exception {
    var client = authenticatedClient("nanjing_user");

    var forbidden = get(client.httpClient(), "/api/v1/users?page=0&size=20");
    assertThat(forbidden.statusCode()).isEqualTo(403);
    assertThat(forbidden.body()).contains("\"code\":\"ACCESS_DENIED\"");

    var openApi = get(client.httpClient(), "/v3/api-docs");
    assertThat(openApi.statusCode()).isEqualTo(403);
    assertThat(openApi.body()).contains("\"code\":\"ACCESS_DENIED\"");

    var dashboard = get(client.httpClient(), "/api/v1/dashboard/summary");
    assertThat(dashboard.body()).contains("\"cityCount\":1");

    var logout =
        delete(
            client.httpClient(),
            "/api/v1/sessions/current",
            cookieValue(client.cookieManager(), "XSRF-TOKEN"));
    assertThat(logout.statusCode()).isEqualTo(204);

    var current = get(client.httpClient(), "/api/v1/sessions/current");
    assertThat(current.statusCode()).isEqualTo(401);
  }

  @Test
  void loginAndLogoutWriteAuditEventsWithoutCredentialColumns() throws Exception {
    var client = authenticatedClient("admin");
    var logout =
        delete(
            client.httpClient(),
            "/api/v1/sessions/current",
            cookieValue(client.cookieManager(), "XSRF-TOKEN"));
    assertThat(logout.statusCode()).isEqualTo(204);

    var actions =
        jdbcTemplate.queryForList(
            "SELECT action_code FROM operation_log WHERE username_snapshot = ? ORDER BY id",
            String.class,
            "admin");
    assertThat(actions).contains("SESSION_LOGIN", "SESSION_LOGOUT");
    Integer credentialColumns =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
              FROM information_schema.columns
             WHERE LOWER(table_name) = 'operation_log'
               AND LOWER(column_name) LIKE '%password%'
            """,
            Integer.class);
    assertThat(credentialColumns).isZero();
  }

  private TestClient authenticatedClient(String username) throws Exception {
    var client = newClient();
    get(client.httpClient(), "/api/v1/sessions/current");
    var response =
        postJson(
            client.httpClient(),
            "/api/v1/sessions",
            loginBody(username, INITIAL_TEST_PASSWORD),
            cookieValue(client.cookieManager(), "XSRF-TOKEN"));
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
    assertThat(response.headers().firstValue("Location")).contains("/api/v1/sessions/current");
    return client;
  }

  private TestClient newClient() {
    var cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    var httpClient =
        HttpClient.newBuilder()
            .cookieHandler(cookieManager)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    return new TestClient(httpClient, cookieManager);
  }

  private HttpResponse<String> get(HttpClient client, String path) throws Exception {
    var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5)).GET().build();
    return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> postJson(
      HttpClient client, String path, String body, String csrfToken) throws Exception {
    var builder =
        HttpRequest.newBuilder(uri(path))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json");
    if (csrfToken != null) {
      builder.header("X-XSRF-TOKEN", csrfToken);
    }
    return client.send(
        builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> delete(HttpClient client, String path, String csrfToken)
      throws Exception {
    var request =
        HttpRequest.newBuilder(uri(path))
            .timeout(Duration.ofSeconds(5))
            .header("X-XSRF-TOKEN", csrfToken)
            .DELETE()
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private String cookieValue(CookieManager cookieManager, String name) {
    return cookieManager.getCookieStore().getCookies().stream()
        .filter(cookie -> cookie.getName().equals(name))
        .map(HttpCookie::getValue)
        .findFirst()
        .orElse("");
  }

  private String loginBody(String username, String password) {
    return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
  }

  private record TestClient(HttpClient httpClient, CookieManager cookieManager) {}
}
