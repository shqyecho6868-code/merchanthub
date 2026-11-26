// UserInfosResponse.java (in the Common module)
package com.merchanthub.common.model.vo;

import lombok.Data;

@Data
public class UserInfosResponse {

    private Long userId;

    private String nickname;

    private String avatar;
}