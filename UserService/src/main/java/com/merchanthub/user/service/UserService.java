package com.merchanthub.user.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.merchanthub.user.model.dto.request.UserLoginCodeRequest;
import com.merchanthub.user.model.dto.request.UserLoginPasswordRequest;
import com.merchanthub.user.model.dto.request.UserRegisterRequest;
import com.merchanthub.user.model.entity.User;
import com.merchanthub.user.model.vo.LoginAndRegisterResponse;
import com.merchanthub.user.model.vo.TokenResponse;
import com.merchanthub.common.model.vo.UserInfosResponse;
import com.merchanthub.user.model.dto.request.UpdateAvatarRequest;
import com.merchanthub.user.model.vo.UploadUrlResponse;

import java.util.List;
import java.util.Map;


public interface UserService extends IService<User> {

    void sendCaptcha(String targetEmail);


    LoginAndRegisterResponse register(UserRegisterRequest userRegisterRequest);


    LoginAndRegisterResponse loginPassword(UserLoginPasswordRequest userLoginPasswordRequest);



    LoginAndRegisterResponse loginCode(UserLoginCodeRequest userLoginCodeRequest);

    boolean logout(String userId);

    TokenResponse refreshToken(String refreshToken);

    String refreshUri(Long userId);

    UploadUrlResponse uploadUrl(String fileName) ;

    Boolean updateAvatar(UpdateAvatarRequest updateAvatarRequest);

    Map<Long, String> getUserNickName(Long sessionId);

    Map<Long, UserInfosResponse> getUserInfos(List<Long> userIds);
}
