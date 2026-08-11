package com.threefees.identity.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserQueryService {

  private final UserRepository userRepository;

  public UserQueryService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  @Transactional(readOnly = true)
  public UserPage findPage(int page, int size) {
    long totalElements = userRepository.count();
    int totalPages = totalElements == 0 ? 0 : (int) ((totalElements + size - 1) / size);
    return new UserPage(
        userRepository.findPage(Math.multiplyExact(page, size), size),
        page,
        size,
        totalElements,
        totalPages);
  }

  @Transactional(readOnly = true)
  public UserPage findPage(
      String keyword, String cityCode, Boolean enabled, String sort, int page, int size) {
    long totalElements = userRepository.count(keyword, cityCode, enabled);
    int totalPages = totalElements == 0 ? 0 : (int) ((totalElements + size - 1) / size);
    return new UserPage(
        userRepository.findPage(
            keyword, cityCode, enabled, sort, Math.multiplyExact(page, size), size),
        page,
        size,
        totalElements,
        totalPages);
  }
}
