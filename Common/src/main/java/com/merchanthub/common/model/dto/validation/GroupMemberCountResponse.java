package com.merchanthub.common.model.dto.validation;

import java.io.Serial;
import java.io.Serializable;

import lombok.Data;

/**
 * Group member count response
 *
 * Returned by the internal validation API with the group's member count
 * Shared by UserService and other services
 */
@Data
public class GroupMemberCountResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Session ID (group chat ID)
     */
    private Long sessionId;

    /**
     * Number of group members
     */
    private Integer memberCount;

    /**
     * Create a response
     *
     * @param sessionId   session ID
     * @param memberCount member count
     * @return the response
     */
    public static GroupMemberCountResponse of(Long sessionId, Integer memberCount) {
        GroupMemberCountResponse response = new GroupMemberCountResponse();
        response.setSessionId(sessionId);
        response.setMemberCount(memberCount);
        return response;
    }
}