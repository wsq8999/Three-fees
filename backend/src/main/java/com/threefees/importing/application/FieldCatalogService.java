package com.threefees.importing.application;

import com.threefees.importing.domain.DatasetType;
import com.threefees.importing.domain.FieldDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class FieldCatalogService {

  private static final Map<DatasetType, String> EXPECTED_HASHES =
      Map.of(
          DatasetType.BILLING_POINT,
          "210c99e93f689e96e1e97841a0d4298d190c21b6a4edc3743dcc1500ac2bd75f",
          DatasetType.PAYMENT,
          "f753cac6e442eb1147941ccc9b34c1996e2722ba42b5f0de64c9f1ff9586600c",
          DatasetType.METER_READING,
          "21c86079ceb9834416ca26c9f86d6e4a10871d321e8a08410ccfb54e5b510aad",
          DatasetType.BENCHMARK,
          "d7aa67a73f0dba9602e76f88b9f61dc4cfbc8e8313485faa3fcb522044225cbf");

  private final Map<DatasetType, Catalog> catalogs;

  public FieldCatalogService() {
    var loaded = new EnumMap<DatasetType, Catalog>(DatasetType.class);
    for (DatasetType datasetType : DatasetType.values()) {
      loaded.put(datasetType, load(datasetType));
    }
    catalogs = Map.copyOf(loaded);
  }

  public List<FieldDefinition> fields(DatasetType datasetType) {
    return catalogs.get(datasetType).fields();
  }

  public String sha256(DatasetType datasetType) {
    return catalogs.get(datasetType).sha256();
  }

  private Catalog load(DatasetType datasetType) {
    byte[] bytes = readResource(datasetType.resourceName());
    String hash = sha256(bytes);
    if (!EXPECTED_HASHES.get(datasetType).equals(hash)) {
      throw new IllegalStateException("Field catalog checksum mismatch: " + datasetType);
    }
    String content = new String(bytes, StandardCharsets.UTF_8);
    List<FieldDefinition> fields =
        content.lines().filter(line -> !line.isBlank()).map(this::parseLine).toList();
    if (fields.size() != datasetType.fieldCount()) {
      throw new IllegalStateException("Field catalog row count mismatch: " + datasetType);
    }
    for (int index = 0; index < fields.size(); index++) {
      if (fields.get(index).order() != index + 1) {
        throw new IllegalStateException("Field catalog order mismatch: " + datasetType);
      }
    }
    var technicalNames = new HashSet<String>();
    for (FieldDefinition field : fields) {
      if (!technicalNames.add(field.technicalName())) {
        throw new IllegalStateException(
            "Field catalog technical name must be unique: "
                + datasetType
                + " / "
                + field.technicalName());
      }
    }
    return new Catalog(fields, hash);
  }

  private FieldDefinition parseLine(String line) {
    String[] cells = line.split("\\t", -1);
    if (cells.length != 6) {
      throw new IllegalStateException("Each field catalog row must contain six columns");
    }
    return new FieldDefinition(
        Integer.parseInt(cells[0]), cells[1], cells[2], cells[3], cells[4], cells[5]);
  }

  private byte[] readResource(String name) {
    try (InputStream input = new ClassPathResource(name).getInputStream()) {
      return input.readAllBytes();
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot read field catalog: " + name, exception);
    }
  }

  private String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private record Catalog(List<FieldDefinition> fields, String sha256) {
    private Catalog {
      fields = List.copyOf(fields);
    }
  }
}
