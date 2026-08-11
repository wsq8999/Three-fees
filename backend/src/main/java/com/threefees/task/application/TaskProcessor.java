package com.threefees.task.application;

import com.threefees.task.domain.BusinessTask;
import com.threefees.task.domain.TaskType;

public interface TaskProcessor {

  TaskType taskType();

  String process(BusinessTask task);
}
