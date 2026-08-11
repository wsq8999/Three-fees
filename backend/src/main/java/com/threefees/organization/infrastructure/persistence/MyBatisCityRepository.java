package com.threefees.organization.infrastructure.persistence;

import com.threefees.organization.application.CityRepository;
import com.threefees.organization.domain.City;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisCityRepository implements CityRepository {

  private final CityMapper cityMapper;

  public MyBatisCityRepository(CityMapper cityMapper) {
    this.cityMapper = cityMapper;
  }

  @Override
  public List<City> findAll() {
    return cityMapper.findAll();
  }

  @Override
  public int count() {
    return cityMapper.count();
  }
}
