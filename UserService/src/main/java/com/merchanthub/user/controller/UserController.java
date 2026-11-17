package com.merchanthub.user.controller;


import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.common.exception.ThrowUtils;
import com.merchanthub.common.utils.JwtUtil;

import com.merchanthub.user.constant.UserConstant;

import com.merchanthub.user.model.dto.request.UpdateAvatarRequest;
import com.merchanthub.user.model.dto.request.UserLoginCodeRequest;
import com.merchanthub.user.model.dto.request.UserLoginPasswordRequest;
import com.merchanthub.user.model.dto.request.UserRegisterRequest;
import com.merchanthub.user.model.vo.LoginAndRegisterResponse;
import com.merchanthub.user.model.vo.TokenResponse;
import com.merchanthub.user.model.vo.UploadUrlResponse;
import io.jsonwebtoken.Claims;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.apache.commons.lang3.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/user")
@Validated
public class UserController {

    @Resource
    private com.merchanthub.user.service.UserService userService;

    @GetMapping("/sendCaptcha")
    public BaseResponse<String> sendCaptcha(@NotBlank(message = "邮箱不能为空") @Email(message = "邮箱格式不正确")  @RequestParam String targetEmail) {

        userService.sendCaptcha(targetEmail);
        return ResultUtils.success(UserConstant.SEND_EMAIL_SUCCESS);
    }


    @PostMapping("/register")
    public BaseResponse<LoginAndRegisterResponse> register(@Valid @RequestBody UserRegisterRequest userRegisterRequest) {
        return ResultUtils.success(userService.register(userRegisterRequest));
    }


    @PostMapping("/login/password")
    public BaseResponse<LoginAndRegisterResponse> loginPassword(@Valid @RequestBody UserLoginPasswordRequest userLoginPasswordRequest) {
        return ResultUtils.success(userService.loginPassword(userLoginPasswordRequest));
    }


    @PostMapping("/login/code")
    public BaseResponse<LoginAndRegisterResponse> loginCode(@Valid @RequestBody UserLoginCodeRequest userLoginCodeRequest) {
        return ResultUtils.success(userService.loginCode(userLoginCodeRequest));
    }



    @GetMapping("/logout")
    public BaseResponse<Boolean> logout(HttpServletRequest request) {
        String accessToken = request.getHeader("Access-Token");
        Claims claims = JwtUtil.parse(accessToken);
        ThrowUtils.throwIf(claims == null, ErrorCode.NOT_LOGIN_ERROR);
        return ResultUtils.success(userService.logout(claims.getSubject()));
    }


    @PostMapping("/refresh")
    public BaseResponse<TokenResponse> refreshToken(HttpServletRequest request) {
        String refreshToken = request.getHeader("Refresh-Token");
        ThrowUtils.throwIf(StringUtils.isBlank(refreshToken), ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(userService.refreshToken(refreshToken));
    }


    @GetMapping("/refresh/uri")
    public BaseResponse<String> refreshUri(@RequestParam Long userId) {
        return ResultUtils.success(userService.refreshUri(userId));
    }


    @GetMapping("/uploadUrl")
    public BaseResponse<UploadUrlResponse> getUploadUrl(@RequestParam String fileName) {
        return ResultUtils.success(userService.uploadUrl(fileName));
    }

    @PostMapping("/update/avatar")
    public BaseResponse<Boolean> updateAvatar(@RequestBody UpdateAvatarRequest updateAvatarRequest)  {
        return ResultUtils.success(userService.updateAvatar(updateAvatarRequest));
    }

    @GetMapping("/get/nickname")
    public Map<Long, String> getUserNickName(@RequestParam("sessionId")  Long sessionId) {
        return userService.getUserNickName(sessionId);
    }
}


