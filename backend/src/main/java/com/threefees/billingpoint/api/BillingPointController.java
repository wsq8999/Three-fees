package com.threefees.billingpoint.api;

import com.threefees.billingpoint.application.BillingPointQueryService;
import com.threefees.billingpoint.application.BillingPointQueryService.BillingPointDetail;
import com.threefees.billingpoint.application.BillingPointQueryService.BillingPointFilter;
import com.threefees.billingpoint.application.BillingPointQueryService.FilterOptions;
import com.threefees.billingpoint.application.BillingPointQueryService.PageResult;
import com.threefees.identity.application.CurrentUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/billing-point-periods")
public class BillingPointController {

  private final BillingPointQueryService queryService;

  public BillingPointController(BillingPointQueryService queryService) {
    this.queryService = queryService;
  }

  @GetMapping
  public PageResult findPage(
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String name,
      @RequestParam(required = false) String cityCode,
      @RequestParam(required = false) String district,
      @RequestParam(required = false) @Pattern(regexp = "[0-9]{4}-(0[1-9]|1[0-2])") String period,
      @RequestParam(required = false) String siteKeyword,
      @RequestParam(required = false) String paymentKeyword,
      @RequestParam(required = false) Boolean paymentEligible,
      @RequestParam(required = false) String billingPointStatus,
      @RequestParam(required = false) String auditStatus,
      @RequestParam(required = false) String reportStatus,
      @RequestParam(required = false) @Pattern(regexp = "[0-9]{4}-(0[1-9]|1[0-2])") String focusPeriod,
      @RequestParam(required = false) String focusCityCode,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @AuthenticationPrincipal CurrentUser actor) {
    return queryService.findPage(
        new BillingPointFilter(
            code,
            name,
            cityCode,
            district,
            period,
            siteKeyword,
            paymentKeyword,
            paymentEligible,
            billingPointStatus,
            auditStatus,
            reportStatus,
            focusPeriod,
            focusCityCode),
        page,
        size,
        actor);
  }

  @GetMapping("/filter-options")
  public FilterOptions filterOptions(@AuthenticationPrincipal CurrentUser actor) {
    return queryService.filterOptions(actor);
  }

  @GetMapping("/{publicId}")
  public BillingPointDetail find(
      @PathVariable String publicId, @AuthenticationPrincipal CurrentUser actor) {
    return queryService.findDetail(publicId, actor);
  }
}
