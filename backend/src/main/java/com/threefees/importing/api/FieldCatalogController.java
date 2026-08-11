package com.threefees.importing.api;

import com.threefees.importing.application.FieldCatalogService;
import com.threefees.importing.domain.DatasetType;
import com.threefees.importing.domain.FieldDefinition;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/field-catalogs")
public class FieldCatalogController {

  private final FieldCatalogService fieldCatalogService;

  public FieldCatalogController(FieldCatalogService fieldCatalogService) {
    this.fieldCatalogService = fieldCatalogService;
  }

  @GetMapping("/{datasetType}")
  public FieldCatalogResponse find(@PathVariable DatasetType datasetType) {
    return new FieldCatalogResponse(
        datasetType,
        fieldCatalogService.sha256(datasetType),
        fieldCatalogService.fields(datasetType));
  }

  public record FieldCatalogResponse(
      DatasetType datasetType, String sha256, List<FieldDefinition> fields) {}
}
