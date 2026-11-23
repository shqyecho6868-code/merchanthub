package com.merchanthub.user.approval.model.vo;

import java.util.Date;
import java.util.Map;

/**
 * 待办。processKey 区分是哪类审批，summary 给列表页显示（商品名 / 店铺名等），不把所有流程变量都吐出去
 */
public record TaskVO(String taskId, String taskName, String taskKey, String processKey, String processInstanceId,
                     Date createdAt, Map<String, Object> summary) {
}
