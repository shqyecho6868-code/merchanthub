package com.merchanthub.user.model.dto.request;


import lombok.Data;

@Data
public class UpdateAvatarRequest {

    private String uri;

    private Long userId;
}