package com.threefees.organization.api;

import com.threefees.organization.application.CityQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cities")
public class CityController {

  private final CityQueryService cityQueryService;

  public CityController(CityQueryService cityQueryService) {
    this.cityQueryService = cityQueryService;
  }

  @GetMapping
  public List<CityResponse> findAll() {
    return cityQueryService.findAll().stream().map(CityResponse::from).toList();
  }
}
