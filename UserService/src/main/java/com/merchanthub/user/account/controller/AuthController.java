package com.merchanthub.user.account.controller;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.user.account.model.dto.LoginRequest;
import com.merchanthub.user.account.model.dto.RegisterRequest;
import com.merchanthub.user.account.model.dto.TokenVO;
import com.merchanthub.user.account.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** External: /api/user/** (gateway whitelist, no token required); the gateway strips /api when forwarding */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public BaseResponse<Long> register(@RequestBody @Valid RegisterRequest req) {
        return ResultUtils.success(authService.registerBuyer(req.username(), req.password()));
    }

    @PostMapping("/login")
    public BaseResponse<TokenVO> login(@RequestBody @Valid LoginRequest req) {
        return ResultUtils.success(authService.login(req.username(), req.password()));
    }

    @PostMapping("/refresh")
    public BaseResponse<TokenVO> refresh(@RequestHeader("Refresh-Token") String refreshToken) {
        return ResultUtils.success(authService.refresh(refreshToken));
    }
}
