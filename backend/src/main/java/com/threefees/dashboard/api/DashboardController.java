package com.threefees.dashboard.api;

import com.threefees.dashboard.application.DashboardQueryService;
import com.threefees.dashboard.application.DashboardSummary;
import com.threefees.identity.application.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

  private final DashboardQueryService dashboardQueryService;

  public DashboardController(DashboardQueryService dashboardQueryService) {
    this.dashboardQueryService = dashboardQueryService;
  }

  @GetMapping("/summary")
  public DashboardSummary summary(
      @RequestParam(required = false) String period,
      @AuthenticationPrincipal CurrentUser principal) {
    return dashboardQueryService.summarize(principal, period);
  }
}
