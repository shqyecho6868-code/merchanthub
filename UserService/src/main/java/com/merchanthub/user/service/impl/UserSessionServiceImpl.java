package com.merchanthub.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.merchanthub.user.mapper.UserSessionMapper;
import com.merchanthub.user.model.entity.UserSession;
import com.merchanthub.user.service.UserSessionService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;


@Service
public class UserSessionServiceImpl extends ServiceImpl<UserSessionMapper, UserSession>
    implements UserSessionService {


    @Override
    public List<Long> getUserIdBySessionId(Long sessionId) {
        QueryWrapper<UserSession> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("session_id", sessionId);
        List<UserSession> userSessions = this.list(queryWrapper);
        return userSessions.stream().map(UserSession::getUserId).collect(Collectors.toList());
    }

    @Override
    public List<Long> getSessionIdsByUserId(Long userId) {
        QueryWrapper<UserSession> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId);
        List<UserSession> userSessions = this.list(queryWrapper);
        return userSessions.stream().map(UserSession::getSessionId).collect(Collectors.toList());
    }
}