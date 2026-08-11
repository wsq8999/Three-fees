package com.threefees.organization.application;

import com.threefees.organization.domain.City;
import java.util.List;

public interface CityRepository {

  List<City> findAll();

  int count();
}
