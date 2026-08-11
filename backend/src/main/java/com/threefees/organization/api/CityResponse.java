package com.threefees.organization.api;

import com.threefees.organization.domain.City;

public record CityResponse(String code, String name) {

  static CityResponse from(City city) {
    return new CityResponse(city.code(), city.name());
  }
}
