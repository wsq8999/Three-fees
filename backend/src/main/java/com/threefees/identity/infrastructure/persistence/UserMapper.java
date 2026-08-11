package com.threefees.identity.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper {

  @Select(
      """
      SELECT u.id,
             u.username,
             u.display_name,
             u.password_hash,
             c.code AS city_code,
             c.name AS city_name,
             u.is_enabled AS enabled,
             u.must_change_password,
             u.updated_at,
             u.version
        FROM app_user u
        LEFT JOIN city c ON c.id = u.city_id
       WHERE u.username = #{username}
      """)
  @ConstructorArgs({
    @Arg(column = "id", javaType = long.class, id = true),
    @Arg(column = "username", javaType = String.class),
    @Arg(column = "display_name", javaType = String.class),
    @Arg(column = "password_hash", javaType = String.class),
    @Arg(column = "city_code", javaType = String.class),
    @Arg(column = "city_name", javaType = String.class),
    @Arg(column = "enabled", javaType = boolean.class),
    @Arg(column = "must_change_password", javaType = boolean.class),
    @Arg(column = "updated_at", javaType = LocalDateTime.class),
    @Arg(column = "version", javaType = long.class)
  })
  UserRow findByUsername(String username);

  @Select(
      """
      SELECT u.id, u.username, u.display_name, u.password_hash,
             c.code AS city_code, c.name AS city_name,
             u.is_enabled AS enabled, u.must_change_password,
             u.updated_at, u.version
        FROM app_user u
        LEFT JOIN city c ON c.id = u.city_id
       WHERE u.id = #{id}
      """)
  @ConstructorArgs({
    @Arg(column = "id", javaType = long.class, id = true),
    @Arg(column = "username", javaType = String.class),
    @Arg(column = "display_name", javaType = String.class),
    @Arg(column = "password_hash", javaType = String.class),
    @Arg(column = "city_code", javaType = String.class),
    @Arg(column = "city_name", javaType = String.class),
    @Arg(column = "enabled", javaType = boolean.class),
    @Arg(column = "must_change_password", javaType = boolean.class),
    @Arg(column = "updated_at", javaType = LocalDateTime.class),
    @Arg(column = "version", javaType = long.class)
  })
  UserRow findById(long id);

  @Select(
      """
      SELECT u.id,
             u.username,
             u.display_name,
             u.password_hash,
             c.code AS city_code,
             c.name AS city_name,
             u.is_enabled AS enabled,
             u.must_change_password,
             u.updated_at,
             u.version
        FROM app_user u
        LEFT JOIN city c ON c.id = u.city_id
       ORDER BY u.id
       LIMIT #{limit} OFFSET #{offset}
      """)
  @ConstructorArgs({
    @Arg(column = "id", javaType = long.class, id = true),
    @Arg(column = "username", javaType = String.class),
    @Arg(column = "display_name", javaType = String.class),
    @Arg(column = "password_hash", javaType = String.class),
    @Arg(column = "city_code", javaType = String.class),
    @Arg(column = "city_name", javaType = String.class),
    @Arg(column = "enabled", javaType = boolean.class),
    @Arg(column = "must_change_password", javaType = boolean.class),
    @Arg(column = "updated_at", javaType = LocalDateTime.class),
    @Arg(column = "version", javaType = long.class)
  })
  List<UserRow> findPage(@Param("offset") int offset, @Param("limit") int limit);

  @Select(
      """
      <script>
      SELECT u.id, u.username, u.display_name, u.password_hash,
             c.code AS city_code, c.name AS city_name,
             u.is_enabled AS enabled, u.must_change_password, u.updated_at, u.version
        FROM app_user u
        LEFT JOIN city c ON c.id = u.city_id
       WHERE 1 = 1
       <if test='keyword != null and keyword != ""'>
         AND (LOWER(u.username) LIKE LOWER(#{keywordPattern})
              OR u.display_name LIKE #{keywordPattern})
       </if>
       <if test='cityCode != null and cityCode != ""'>AND c.code = #{cityCode}</if>
       <if test='enabled != null'>AND u.is_enabled = #{enabled}</if>
       ORDER BY
       <choose>
         <when test='sort == "UPDATED_AT_ASC"'>u.updated_at ASC, u.id ASC</when>
         <when test='sort == "UPDATED_AT_DESC"'>u.updated_at DESC, u.id DESC</when>
         <when test='sort == "USERNAME_DESC"'>u.username DESC, u.id DESC</when>
         <otherwise>u.username ASC, u.id ASC</otherwise>
       </choose>
       LIMIT #{limit} OFFSET #{offset}
      </script>
      """)
  @ConstructorArgs({
    @Arg(column = "id", javaType = long.class, id = true),
    @Arg(column = "username", javaType = String.class),
    @Arg(column = "display_name", javaType = String.class),
    @Arg(column = "password_hash", javaType = String.class),
    @Arg(column = "city_code", javaType = String.class),
    @Arg(column = "city_name", javaType = String.class),
    @Arg(column = "enabled", javaType = boolean.class),
    @Arg(column = "must_change_password", javaType = boolean.class),
    @Arg(column = "updated_at", javaType = LocalDateTime.class),
    @Arg(column = "version", javaType = long.class)
  })
  List<UserRow> findPageFiltered(
      @Param("keyword") String keyword,
      @Param("keywordPattern") String keywordPattern,
      @Param("cityCode") String cityCode,
      @Param("enabled") Boolean enabled,
      @Param("sort") String sort,
      @Param("offset") int offset,
      @Param("limit") int limit);

  @Select("SELECT COUNT(*) FROM app_user")
  long count();

  @Select(
      """
      <script>
      SELECT COUNT(*)
        FROM app_user u
        LEFT JOIN city c ON c.id = u.city_id
       WHERE 1 = 1
       <if test='keyword != null and keyword != ""'>
         AND (LOWER(u.username) LIKE LOWER(#{keywordPattern})
              OR u.display_name LIKE #{keywordPattern})
       </if>
       <if test='cityCode != null and cityCode != ""'>AND c.code = #{cityCode}</if>
       <if test='enabled != null'>AND u.is_enabled = #{enabled}</if>
      </script>
      """)
  long countFiltered(
      @Param("keyword") String keyword,
      @Param("keywordPattern") String keywordPattern,
      @Param("cityCode") String cityCode,
      @Param("enabled") Boolean enabled);

  @Select("SELECT app_user_id, role_code FROM app_user_role WHERE app_user_id = #{userId}")
  @ConstructorArgs({
    @Arg(column = "app_user_id", javaType = long.class),
    @Arg(column = "role_code", javaType = String.class)
  })
  List<RoleRow> findRoles(long userId);

  @Insert(
      """
      INSERT INTO app_user
        (username, display_name, password_hash, city_id, is_enabled, must_change_password,
         created_by, updated_by, version)
      VALUES
        (#{username}, #{displayName}, #{passwordHash}, NULL, TRUE, TRUE, 'SYSTEM', 'SYSTEM', 0)
      """)
  int insertAdministrator(
      @Param("username") String username,
      @Param("displayName") String displayName,
      @Param("passwordHash") String passwordHash);

  @Insert(
      """
      INSERT INTO app_user
        (username, display_name, password_hash, city_id, is_enabled, must_change_password,
         created_by, updated_by, version)
      SELECT #{username}, #{displayName}, #{passwordHash}, c.id, TRUE, TRUE,
             'SYSTEM', 'SYSTEM', 0
        FROM city c
       WHERE c.code = #{cityCode}
      """)
  int insertCityUser(
      @Param("username") String username,
      @Param("displayName") String displayName,
      @Param("passwordHash") String passwordHash,
      @Param("cityCode") String cityCode);

  @Insert(
      """
      INSERT INTO app_user_role (app_user_id, role_code)
      SELECT id, #{roleCode}
        FROM app_user
       WHERE username = #{username}
      """)
  int insertRole(@Param("username") String username, @Param("roleCode") String roleCode);

  @Insert(
      """
      INSERT INTO app_user
        (username, display_name, password_hash, city_id, is_enabled, must_change_password,
         created_by, updated_by, version)
      SELECT #{username}, #{displayName}, #{passwordHash}, c.id, TRUE, TRUE,
             #{actor}, #{actor}, 0
        FROM city c
       WHERE c.code = #{cityCode}
      """)
  @Options(useGeneratedKeys = true, keyProperty = "holder.id", keyColumn = "id")
  int insertManagedCityUser(
      @Param("username") String username,
      @Param("displayName") String displayName,
      @Param("passwordHash") String passwordHash,
      @Param("cityCode") String cityCode,
      @Param("actor") String actor,
      @Param("holder") GeneratedId holder);

  @Update(
      """
      UPDATE app_user
         SET display_name = #{displayName},
             city_id = (SELECT id FROM city WHERE code = #{cityCode}),
             is_enabled = #{enabled},
             updated_at = CURRENT_TIMESTAMP(3),
             updated_by = #{actor},
             version = version + 1
       WHERE id = #{id} AND version = #{version}
      """)
  int updateManagedUser(
      @Param("id") long id,
      @Param("displayName") String displayName,
      @Param("cityCode") String cityCode,
      @Param("enabled") boolean enabled,
      @Param("version") long version,
      @Param("actor") String actor);

  @Update(
      """
      UPDATE app_user
         SET password_hash = #{passwordHash},
             must_change_password = #{mustChangePassword},
             updated_at = CURRENT_TIMESTAMP(3),
             updated_by = #{actor},
             version = version + 1
       WHERE id = #{id}
      """)
  int updatePassword(
      @Param("id") long id,
      @Param("passwordHash") String passwordHash,
      @Param("mustChangePassword") boolean mustChangePassword,
      @Param("actor") String actor);
}
