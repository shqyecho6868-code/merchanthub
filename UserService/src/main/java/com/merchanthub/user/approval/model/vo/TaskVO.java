package com.merchanthub.user.approval.model.vo;

import java.util.Date;
import java.util.Map;

/**
 * A pending task. processKey tells which kind of approval it is, summary is for list display (product name / shop name etc.), instead of dumping all process variables
 */
public record TaskVO(String taskId, String taskName, String taskKey, String processKey, String processInstanceId,
                     Date createdAt, Map<String, Object> summary) {
}
