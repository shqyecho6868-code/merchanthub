package com.merchanthub.user.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.merchanthub.user.mapper.SessionMapper;
import com.merchanthub.user.model.entity.Session;
import com.merchanthub.user.service.SessionService;
import org.springframework.stereotype.Service;


@Service
public class SessionServiceImpl extends ServiceImpl<SessionMapper, Session>
    implements SessionService {

}