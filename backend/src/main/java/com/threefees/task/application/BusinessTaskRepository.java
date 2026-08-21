package com.threefees.task.application;

import com.threefees.task.domain.BusinessTask;
import com.threefees.task.domain.TaskType;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface BusinessTaskRepository {

  BusinessTask create(
      TaskType taskType, String businessKey, String payloadJson, String actor, int maxAttempts);

  Optional<BusinessTask> findByPublicId(String publicId);

  Optional<BusinessTask> findByTypeAndBusinessKey(TaskType taskType, String businessKey);

  List<BusinessTask> claimAvailable(String leaseOwner, Duration leaseDuration, int limit);

  boolean renewLease(BusinessTask task, Duration leaseDuration);

  boolean succeed(BusinessTask task, String resultJson);

  boolean fail(BusinessTask task, String errorCode, boolean retryable, Duration retryDelay);

  boolean retry(String publicId);

  boolean requeueWithPayload(String publicId, String payloadJson, String actor);
}
