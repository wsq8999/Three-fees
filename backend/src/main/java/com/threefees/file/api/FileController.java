package com.threefees.file.api;

import com.threefees.file.application.StoredFileService;
import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.domain.Role;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {

  private final StoredFileService storedFileService;

  public FileController(StoredFileService storedFileService) {
    this.storedFileService = storedFileService;
  }

  @GetMapping("/{publicId}")
  public ResponseEntity<InputStreamResource> download(
      @PathVariable String publicId,
      @RequestParam(defaultValue = "false") boolean inline,
      @AuthenticationPrincipal CurrentUser currentUser) {
    var storedFile = storedFileService.find(publicId);
    if (!currentUser.roles().contains(Role.SUPER_ADMIN)
        && !currentUser.username().equals(storedFile.createdBy())) {
      throw new AccessDeniedException("File is outside the current user's scope");
    }
    ContentDisposition disposition =
        inline
            ? ContentDisposition.inline()
                .filename(storedFile.originalName(), StandardCharsets.UTF_8)
                .build()
            : ContentDisposition.attachment()
                .filename(storedFile.originalName(), StandardCharsets.UTF_8)
                .build();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(storedFile.mediaType()))
        .contentLength(storedFile.byteSize())
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .header("X-Content-Type-Options", "nosniff")
        .body(storedFileService.resource(storedFile));
  }
}
