package com.threefees.importing.application;

import com.threefees.importing.domain.ImportError;
import java.util.List;

public class ImportValidationException extends RuntimeException {

  private final List<ImportError> errors;

  public ImportValidationException(List<ImportError> errors) {
    super("Import contains validation errors");
    this.errors = List.copyOf(errors);
  }

  public List<ImportError> errors() {
    return errors;
  }
}
