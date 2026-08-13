package com.threefees.file.application;

import com.threefees.file.domain.StoredFile;
import com.threefees.identity.application.BusinessRuleException;
import com.threefees.identity.application.ResourceNotFoundException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class StoredFileService {

  private static final byte[] OLE_SIGNATURE = {
    (byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1
  };

  private final StoredFileRepository repository;
  private final String configuredRoot;

  public StoredFileService(
      StoredFileRepository repository, @Value("${app.file.root:}") String configuredRoot) {
    this.repository = repository;
    this.configuredRoot = configuredRoot;
  }

  public StoredFile storeUpload(
      MultipartFile multipartFile, Set<String> allowedExtensions, String purpose, String actor) {
    if (multipartFile.isEmpty()) {
      throw new BusinessRuleException("FILE_EMPTY", "上传文件不能为空");
    }
    String originalName = safeOriginalName(multipartFile.getOriginalFilename());
    String extension = extension(originalName);
    if (!allowedExtensions.contains(extension)) {
      throw new BusinessRuleException("FILE_TYPE_NOT_ALLOWED", "不支持该文件类型");
    }
    try (InputStream input = multipartFile.getInputStream()) {
      return storeStream(input, originalName, extension, purpose, actor, true);
    } catch (IOException exception) {
      throw new BusinessRuleException("FILE_READ_FAILED", "无法读取上传文件");
    }
  }

  public StoredFile storeGenerated(
      byte[] bytes, String originalName, String mediaType, String purpose, String actor) {
    String safeName = safeOriginalName(originalName);
    String extension = extension(safeName);
    try {
      return storeStream(
          new ByteArrayInputStream(bytes), safeName, extension, purpose, actor, false, mediaType);
    } catch (IOException exception) {
      throw new IllegalStateException("Generated file could not be stored", exception);
    }
  }

  public StoredFile find(String publicId) {
    return repository.findByPublicId(publicId).orElseThrow(() -> new ResourceNotFoundException("文件"));
  }

  public StoredFile find(long id) {
    return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("文件"));
  }

  public InputStreamResource resource(StoredFile storedFile) {
    Path path = storedPath(storedFile);
    try {
      return new InputStreamResource(Files.newInputStream(path));
    } catch (IOException exception) {
      throw new ResourceNotFoundException("文件内容");
    }
  }

  public byte[] readBytes(StoredFile storedFile) {
    Path path = storedPath(storedFile);
    try {
      return Files.readAllBytes(path);
    } catch (IOException exception) {
      throw new ResourceNotFoundException("文件内容");
    }
  }

  public void deletePhysical(StoredFile storedFile) {
    try {
      Files.deleteIfExists(storedPath(storedFile));
    } catch (IOException exception) {
      throw new IllegalStateException("Rolled back file could not be removed", exception);
    }
  }

  /** Removes a newly generated, still-unreferenced file during application-level compensation. */
  public void deleteGenerated(StoredFile storedFile) {
    if (!repository.deleteById(storedFile.id())) {
      throw new IllegalStateException("Generated file metadata could not be removed");
    }
    deletePhysical(storedFile);
  }

  private StoredFile storeStream(
      InputStream input,
      String originalName,
      String extension,
      String purpose,
      String actor,
      boolean validate)
      throws IOException {
    return storeStream(input, originalName, extension, purpose, actor, validate, mediaType(extension));
  }

  private StoredFile storeStream(
      InputStream input,
      String originalName,
      String extension,
      String purpose,
      String actor,
      boolean validate,
      String mediaType)
      throws IOException {
    Path root = root();
    Files.createDirectories(root);
    String publicId = UUID.randomUUID().toString();
    String storageName = publicId + "." + extension;
    Path temporary = Files.createTempFile(root, ".upload-", ".tmp");
    Path destination = root.resolve(storageName).normalize();
    boolean moved = false;
    MessageDigest digest = sha256Digest();
    try {
      long size;
      try (var digestInput = new DigestInputStream(input, digest)) {
        size = Files.copy(digestInput, temporary, StandardCopyOption.REPLACE_EXISTING);
      }
      if (size == 0) {
        throw new BusinessRuleException("FILE_SIZE_INVALID", "文件不能为空");
      }
      if (validate) {
        validateSignature(temporary, extension);
      }
      if (!destination.startsWith(root)) {
        throw new IllegalStateException("Generated storage name escaped the configured root");
      }
      moveAtomically(temporary, destination);
      moved = true;
      String hash = HexFormat.of().formatHex(digest.digest());
      try {
        return repository.create(
            publicId, storageName, originalName, mediaType, size, hash, purpose, actor);
      } catch (RuntimeException exception) {
        Files.deleteIfExists(destination);
        throw exception;
      }
    } finally {
      Files.deleteIfExists(temporary);
      if (!moved) {
        Files.deleteIfExists(destination);
      }
    }
  }

  private void validateSignature(Path path, String extension) throws IOException {
    switch (extension) {
      case "xlsx" -> requireZipEntries(path, Set.of("[Content_Types].xml", "xl/workbook.xml"));
      case "docx" -> requireZipEntries(path, Set.of("[Content_Types].xml", "word/document.xml"));
      case "doc" -> requirePrefix(path, OLE_SIGNATURE, "Word 二进制签名不正确");
      case "xls" -> requirePrefix(path, OLE_SIGNATURE, "Excel 二进制签名不正确");
      case "csv" -> validateCsvEncoding(path);
      case "png" -> {
        requirePrefix(
            path, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}, "PNG 签名不正确");
        validateImageDimensions(path);
      }
      case "jpg", "jpeg" -> {
        requirePrefix(path, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}, "JPEG 签名不正确");
        validateImageDimensions(path);
      }
      default -> throw new BusinessRuleException("FILE_TYPE_NOT_ALLOWED", "不支持该文件类型");
    }
  }

  private void validateImageDimensions(Path path) throws IOException {
    try (var imageInput = ImageIO.createImageInputStream(path.toFile())) {
      if (imageInput == null) {
        throw new BusinessRuleException("IMAGE_INVALID", "图片无法解码");
      }
      var readers = ImageIO.getImageReaders(imageInput);
      if (!readers.hasNext()) {
        throw new BusinessRuleException("IMAGE_INVALID", "图片无法解码");
      }
      var reader = readers.next();
      try {
        reader.setInput(imageInput, true, true);
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        if (width <= 0 || height <= 0) {
          throw new BusinessRuleException("IMAGE_DIMENSIONS_INVALID", "图片像素尺寸不正确");
        }
      } finally {
        reader.dispose();
      }
    }
  }

  private void requireZipEntries(Path path, Set<String> requiredEntries) throws IOException {
    Set<String> remaining = new java.util.HashSet<>(requiredEntries);
    try (var zip = new ZipInputStream(Files.newInputStream(path))) {
      var entry = zip.getNextEntry();
      int count = 0;
      while (entry != null && count < 10_000) {
        remaining.remove(entry.getName());
        entry = zip.getNextEntry();
        count++;
      }
    }
    if (!remaining.isEmpty()) {
      throw new BusinessRuleException("FILE_SIGNATURE_INVALID", "Office 文件结构不正确");
    }
  }

  private void validateCsvEncoding(Path path) {
    if (canDecode(path, StandardCharsets.UTF_8) || canDecode(path, Charset.forName("GB18030"))) {
      return;
    }
    throw new BusinessRuleException("CSV_ENCODING_INVALID", "CSV 文件编码必须是 UTF-8 或 GB18030");
  }

  private boolean canDecode(Path path, Charset charset) {
    try (var reader =
        new InputStreamReader(
            Files.newInputStream(path),
            charset
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT))) {
      char[] buffer = new char[8192];
      while (reader.read(buffer) != -1) {
        // Decode the full stream.
      }
      return true;
    } catch (CharacterCodingException exception) {
      return false;
    } catch (IOException exception) {
      throw new BusinessRuleException("FILE_READ_FAILED", "无法读取上传文件");
    }
  }

  private void requirePrefix(Path path, byte[] expected, String message) throws IOException {
    byte[] bytes = new byte[expected.length];
    try (InputStream input = Files.newInputStream(path)) {
      int read = input.read(bytes);
      if (read != expected.length) {
        throw new BusinessRuleException("FILE_SIGNATURE_INVALID", message);
      }
    }
    for (int index = 0; index < expected.length; index++) {
      if (bytes[index] != expected[index]) {
        throw new BusinessRuleException("FILE_SIGNATURE_INVALID", message);
      }
    }
  }

  private void moveAtomically(Path source, Path destination) throws IOException {
    try {
      Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException exception) {
      Files.move(source, destination);
    }
  }

  private Path storedPath(StoredFile storedFile) {
    Path path = root().resolve(storedFile.storageName()).normalize();
    if (!path.startsWith(root())) {
      throw new IllegalStateException("Stored file path escaped the configured root");
    }
    return path;
  }

  private Path root() {
    if (configuredRoot == null || configuredRoot.isBlank()) {
      throw new IllegalStateException("APP_FILE_ROOT must be configured before file operations");
    }
    return Path.of(configuredRoot).toAbsolutePath().normalize();
  }

  private String safeOriginalName(String value) {
    String name;
    try {
      name = value == null ? "upload" : Path.of(value).getFileName().toString().trim();
    } catch (InvalidPathException exception) {
      throw new BusinessRuleException("FILE_NAME_INVALID", "文件名不正确");
    }
    if (name.isBlank() || name.length() > 255 || name.contains("\u0000")) {
      throw new BusinessRuleException("FILE_NAME_INVALID", "文件名不正确");
    }
    return name;
  }

  private String extension(String name) {
    int separator = name.lastIndexOf('.');
    if (separator < 0 || separator == name.length() - 1) {
      throw new BusinessRuleException("FILE_EXTENSION_REQUIRED", "文件缺少扩展名");
    }
    return name.substring(separator + 1).toLowerCase(Locale.ROOT);
  }

  private String mediaType(String extension) {
    return switch (extension) {
      case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
      case "xls" -> "application/vnd.ms-excel";
      case "csv" -> "text/csv;charset=UTF-8";
      case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
      case "doc" -> "application/msword";
      case "pdf" -> "application/pdf";
      case "zip" -> "application/zip";
      case "png" -> "image/png";
      case "jpg", "jpeg" -> "image/jpeg";
      default -> "application/octet-stream";
    };
  }

  private MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }
}
