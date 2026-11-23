package com.merchanthub.user.approval.controller;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.common.tenant.RequireRole;
import com.merchanthub.common.tenant.Role;
import com.merchanthub.user.approval.model.dto.DecisionRequest;
import com.merchanthub.user.approval.model.vo.TaskVO;
import com.merchanthub.user.approval.service.ApprovalTaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 我的待办 + 通过 / 驳回（对外 /api/approvals/**）。接口级 RBAC 先挡角色，任务级的"是不是你的"在 service 里查 */
@RestController
@RequestMapping("/approvals/tasks")
@RequiredArgsConstructor
public class ApprovalTaskController {

    private final ApprovalTaskService taskService;

    @GetMapping
    @RequireRole({Role.PLATFORM_ADMIN, Role.MERCHANT_ADMIN, Role.BUYER})
    public BaseResponse<List<TaskVO>> myTasks() {
        return ResultUtils.success(taskService.myTasks());
    }

    @PostMapping("/{taskId}/approve")
    @RequireRole({Role.PLATFORM_ADMIN, Role.MERCHANT_ADMIN})
    public BaseResponse<Void> approve(@PathVariable String taskId, @RequestBody(required = false) @Valid DecisionRequest req) {
        taskService.decide(taskId, true, req == null ? null : req.comment());
        return ResultUtils.success(null);
    }

    @PostMapping("/{taskId}/reject")
    @RequireRole({Role.PLATFORM_ADMIN, Role.MERCHANT_ADMIN})
    public BaseResponse<Void> reject(@PathVariable String taskId, @RequestBody @Valid DecisionRequest req) {
        taskService.decide(taskId, false, req.comment());
        return ResultUtils.success(null);
    }
}
