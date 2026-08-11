package com.threefees.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.threefees.identity.domain.Role;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class InitialAccountCatalogTest {

  @Test
  void catalogContainsOneAdministratorAndThirteenCityUsersWithoutCredentials() {
    var accounts = BootstrapAccountCatalog.accounts();

    assertThat(accounts).hasSize(14);
    assertThat(accounts)
        .filteredOn(account -> account.roles().contains(Role.SUPER_ADMIN))
        .hasSize(1);
    assertThat(accounts)
        .filteredOn(account -> account.roles().contains(Role.CITY_USER))
        .hasSize(13);
    assertThat(accounts)
        .filteredOn(account -> account.roles().contains(Role.CITY_USER))
        .extracting(BootstrapAccount::cityCode)
        .doesNotContainNull()
        .doesNotHaveDuplicates();
    assertThat(new HashSet<>(accounts.stream().map(BootstrapAccount::username).toList()))
        .hasSize(accounts.size());
  }
}
