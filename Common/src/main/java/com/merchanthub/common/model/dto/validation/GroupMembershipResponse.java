package com.merchanthub.common.model.dto.validation;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * Group membership response
 *
 * Returned by the internal validation API with group membership info
 * Shared by UserService and other services
 */
@Data
public class GroupMembershipResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * User ID
     */
    private Long userId;

    /**
     * Session ID (group chat ID)
     */
    private Long sessionId;

    /**
     * Whether the user is a group member
     */
    private Boolean isMember;

    /**
     * Member role
     * 0: owner
     * 1: admin
     * 2: regular member
     * null: not a member
     */
    private Integer role;

    /**
     * Group member role constant: owner
     */
    public static final int ROLE_OWNER = 0;

    /**
     * Group member role constant: admin
     */
    public static final int ROLE_ADMIN = 1;

    /**
     * Group member role constant: regular member
     */
    public static final int ROLE_MEMBER = 2;

    /**
     * Create a membership response
     *
     * @param userId    user ID
     * @param sessionId session ID
     * @param isMember  whether the user is a member
     * @param role      role
     * @return the response
     */
    public static GroupMembershipResponse of(Long userId, Long sessionId, Boolean isMember, Integer role) {
        GroupMembershipResponse response = new GroupMembershipResponse();
        response.setUserId(userId);
        response.setSessionId(sessionId);
        response.setIsMember(isMember);
        response.setRole(role);
        return response;
    }

}