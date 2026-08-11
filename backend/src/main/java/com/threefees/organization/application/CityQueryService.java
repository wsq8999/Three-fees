package com.threefees.organization.application;

import com.threefees.organization.domain.City;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CityQueryService {

  private final CityRepository cityRepository;

  public CityQueryService(CityRepository cityRepository) {
    this.cityRepository = cityRepository;
  }

  @Transactional(readOnly = true)
  public List<City> findAll() {
    return cityRepository.findAll();
  }

  @Transactional(readOnly = true)
  public int count() {
    return cityRepository.count();
  }
}
