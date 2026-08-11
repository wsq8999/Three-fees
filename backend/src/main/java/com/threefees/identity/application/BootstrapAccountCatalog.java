package com.threefees.identity.application;

import com.threefees.identity.domain.Role;
import java.util.List;
import java.util.Set;

public final class BootstrapAccountCatalog {

  private static final List<BootstrapAccount> ACCOUNTS =
      List.of(
          administrator(),
          cityUser("nanjing_user", "南京市用户", "320100"),
          cityUser("wuxi_user", "无锡市用户", "320200"),
          cityUser("xuzhou_user", "徐州市用户", "320300"),
          cityUser("changzhou_user", "常州市用户", "320400"),
          cityUser("suzhou_user", "苏州市用户", "320500"),
          cityUser("nantong_user", "南通市用户", "320600"),
          cityUser("lianyungang_user", "连云港市用户", "320700"),
          cityUser("huaian_user", "淮安市用户", "320800"),
          cityUser("yancheng_user", "盐城市用户", "320900"),
          cityUser("yangzhou_user", "扬州市用户", "321000"),
          cityUser("zhenjiang_user", "镇江市用户", "321100"),
          cityUser("taizhou_user", "泰州市用户", "321200"),
          cityUser("suqian_user", "宿迁市用户", "321300"));

  private BootstrapAccountCatalog() {}

  public static List<BootstrapAccount> accounts() {
    return ACCOUNTS;
  }

  private static BootstrapAccount administrator() {
    return new BootstrapAccount("admin", "超级管理员", null, Set.of(Role.SUPER_ADMIN));
  }

  private static BootstrapAccount cityUser(String username, String displayName, String cityCode) {
    return new BootstrapAccount(username, displayName, cityCode, Set.of(Role.CITY_USER));
  }
}
