package com.merchanthub.user.approval.controller;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.common.tenant.RequireRole;
import com.merchanthub.common.tenant.Role;
import com.merchanthub.user.approval.model.dto.ApplicationRequest;
import com.merchanthub.user.approval.model.vo.ApplicationVO;
import com.merchanthub.user.approval.service.MerchantApplicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 商家入驻申请（对外 /api/merchant-applications/**）。申请人是已登录的平台账号（BUYER），驳回后靠它确认"是你本人在改" */
@RestController
@RequestMapping("/merchant-applications")
@RequiredArgsConstructor
public class MerchantApplicationController {

    private final MerchantApplicationService service;

    @PostMapping
    @RequireRole(Role.BUYER)
    public BaseResponse<ApplicationVO> submit(@RequestBody @Valid ApplicationRequest req) {
        return ResultUtils.success(service.submit(req));
    }

    @PutMapping("/{id}")
    @RequireRole(Role.BUYER)
    public BaseResponse<ApplicationVO> resubmit(@PathVariable long id, @RequestBody @Valid ApplicationRequest req) {
        return ResultUtils.success(service.resubmit(id, req));
    }

    @PostMapping("/{id}/withdraw")
    @RequireRole(Role.BUYER)
    public BaseResponse<ApplicationVO> withdraw(@PathVariable long id) {
        return ResultUtils.success(service.withdraw(id));
    }

    @GetMapping("/{id}")
    @RequireRole({Role.BUYER, Role.PLATFORM_ADMIN})
    public BaseResponse<ApplicationVO> get(@PathVariable long id) {
        return ResultUtils.success(service.get(id));
    }

    @GetMapping("/mine")
    @RequireRole(Role.BUYER)
    public BaseResponse<List<ApplicationVO>> mine() {
        return ResultUtils.success(service.mineList());
    }
}
