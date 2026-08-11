package com.threefees.importing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.threefees.importing.domain.DatasetType;
import org.junit.jupiter.api.Test;

class FieldCatalogServiceTest {

  private final FieldCatalogService service = new FieldCatalogService();

  @Test
  void catalogsMatchRequirementCountsOrderUniquenessAndCanonicalChecksums() {
    assertCatalog(
        DatasetType.BILLING_POINT,
        73,
        "审核状态",
        "电表倍率",
        "210c99e93f689e96e1e97841a0d4298d190c21b6a4edc3743dcc1500ac2bd75f");
    assertCatalog(
        DatasetType.PAYMENT,
        198,
        "审核状态",
        "是否为首单",
        "f753cac6e442eb1147941ccc9b34c1996e2722ba42b5f0de64c9f1ff9586600c");
    assertCatalog(
        DatasetType.METER_READING,
        42,
        "报账点名称",
        "电损税金",
        "21c86079ceb9834416ca26c9f86d6e4a10871d321e8a08410ccfb54e5b510aad");
    assertCatalog(
        DatasetType.BENCHMARK,
        39,
        "报账点编码",
        "31",
        "d7aa67a73f0dba9602e76f88b9f61dc4cfbc8e8313485faa3fcb522044225cbf");
  }

  private void assertCatalog(
      DatasetType type, int count, String first, String last, String expectedHash) {
    var fields = service.fields(type);
    assertThat(fields).hasSize(count);
    assertThat(fields.getFirst().technicalName()).isEqualTo(first);
    assertThat(fields.getLast().technicalName()).isEqualTo(last);
    assertThat(fields).extracting("technicalName").doesNotHaveDuplicates();
    assertThat(service.sha256(type)).isEqualTo(expectedHash);
  }
}
