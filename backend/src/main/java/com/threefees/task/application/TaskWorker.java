package com.threefees.task.application;

import com.threefees.task.domain.BusinessTask;
import com.threefees.task.domain.TaskType;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression(
    "'${three-fees.process-role:api}' == 'worker' or '${three-fees.process-role:api}' == 'all'")
public class TaskWorker {

  private static final Logger LOGGER = LoggerFactory.getLogger(TaskWorker.class);

  private final BusinessTaskRepository repository;
  private final Map<TaskType, TaskProcessor> processors;
  private final String leaseOwner = "worker-" + UUID.randomUUID();
  private final Duration leaseDuration;
  private final int concurrency;
  private final AtomicInteger runningTasks = new AtomicInteger();
  private final ExecutorService taskExecutor;
  private final ScheduledExecutorService leaseRenewer =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "three-fees-task-lease-renewer");
            thread.setDaemon(true);
            return thread;
          });

  public TaskWorker(
      BusinessTaskRepository repository,
      List<TaskProcessor> processors,
      @Value("${app.worker.lease-seconds:30}") long leaseSeconds,
      @Value("${app.worker.concurrency:1}") int concurrency) {
    this.repository = repository;
    var mapped = new EnumMap<TaskType, TaskProcessor>(TaskType.class);
    for (TaskProcessor processor : processors) {
      if (mapped.put(processor.taskType(), processor) != null) {
        throw new IllegalStateException("Duplicate task processor: " + processor.taskType());
      }
    }
    if (leaseSeconds < 3) {
      throw new IllegalArgumentException("WORKER_LEASE_SECONDS must be at least 3");
    }
    if (concurrency < 1) {
      throw new IllegalArgumentException("WORKER_CONCURRENCY must be at least 1");
    }
    this.processors = Map.copyOf(mapped);
    this.leaseDuration = Duration.ofSeconds(leaseSeconds);
    this.concurrency = concurrency;
    this.taskExecutor =
        Executors.newFixedThreadPool(
            concurrency,
            runnable -> {
              Thread thread = new Thread(runnable, "three-fees-task-worker");
              thread.setDaemon(true);
              return thread;
            });
  }

  @Scheduled(fixedDelayString = "${app.worker.poll-delay:500}")
  public void poll() {
    int availableSlots = concurrency - runningTasks.get();
    if (availableSlots <= 0) {
      return;
    }
    for (BusinessTask task : repository.claimAvailable(leaseOwner, leaseDuration, availableSlots)) {
      runningTasks.incrementAndGet();
      taskExecutor.submit(
          () -> {
            try {
              execute(task);
            } finally {
              runningTasks.decrementAndGet();
            }
          });
    }
  }

  private void execute(BusinessTask task) {
    TaskProcessor processor = processors.get(task.type());
    if (processor == null) {
      requireTransition(
          repository.fail(task, "TASK_PROCESSOR_NOT_FOUND", false, Duration.ZERO), task);
      return;
    }
    long renewalSeconds = Math.max(1, leaseDuration.toSeconds() / 3);
    ScheduledFuture<?> renewal =
        leaseRenewer.scheduleAtFixedRate(
            () -> renewLease(task), renewalSeconds, renewalSeconds, TimeUnit.SECONDS);
    try {
      requireTransition(repository.succeed(task, processor.process(task)), task);
    } catch (TaskExecutionException exception) {
      LOGGER.warn(
          "Task failed taskId={} type={} code={} retryable={}",
          task.publicId(),
          task.type(),
          exception.code(),
          exception.retryable());
      requireTransition(
          repository.fail(task, exception.code(), exception.retryable(), Duration.ofSeconds(2)),
          task);
    } catch (RuntimeException exception) {
      LOGGER.error(
          "Unexpected task failure taskId={} type={} exceptionType={}",
          task.publicId(),
          task.type(),
          exception.getClass().getName());
      requireTransition(
          repository.fail(task, "TASK_UNEXPECTED_FAILURE", true, Duration.ofSeconds(2)), task);
    } finally {
      renewal.cancel(false);
    }
  }

  private void renewLease(BusinessTask task) {
    if (!repository.renewLease(task, leaseDuration)) {
      LOGGER.error("Task lease renewal was rejected taskId={}", task.publicId());
    }
  }

  private void requireTransition(boolean transitioned, BusinessTask task) {
    if (!transitioned) {
      throw new IllegalStateException(
          "Task transition lost its lease or state: " + task.publicId());
    }
  }

  @PreDestroy
  void closeLeaseRenewer() {
    taskExecutor.shutdownNow();
    leaseRenewer.shutdownNow();
  }
}
