package com.threefees.operationlog.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OperationLogMapper {

  @Insert(
      """
      INSERT INTO operation_log
        (trace_id, app_user_id, username_snapshot, action_code, result_code)
      VALUES
        (#{traceId}, #{appUserId}, #{usernameSnapshot}, #{actionCode}, #{resultCode})
      """)
  int insert(
      @Param("traceId") String traceId,
      @Param("appUserId") Long appUserId,
      @Param("usernameSnapshot") String usernameSnapshot,
      @Param("actionCode") String actionCode,
      @Param("resultCode") String resultCode);
}
