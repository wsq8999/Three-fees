package com.threefees.organization.infrastructure.persistence;

import com.threefees.organization.domain.City;
import java.util.List;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CityMapper {

  @Select("SELECT code, name FROM city ORDER BY display_order, code")
  @ConstructorArgs({
    @Arg(column = "code", javaType = String.class),
    @Arg(column = "name", javaType = String.class)
  })
  List<City> findAll();

  @Select("SELECT COUNT(*) FROM city")
  int count();
}
