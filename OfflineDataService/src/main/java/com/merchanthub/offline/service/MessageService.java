package com.merchanthub.offline.service;


import com.baomidou.mybatisplus.extension.service.IService;
import com.merchanthub.common.model.dto.MessageRequest;
import com.merchanthub.common.model.vo.MessageResponse;
import com.merchanthub.offline.model.dto.HistoryMessageRequest;
import com.merchanthub.offline.model.dto.OfflineMessageRequest;
import com.merchanthub.offline.model.dto.SessionSummaryRequest;
import com.merchanthub.offline.model.entity.Message;

import java.util.List;
import java.util.Map;


public interface MessageService extends IService<Message> {


    void saveMessageToMySQL(MessageRequest messageRequest);

    /**
     * 获取离线消息
     *
     * @return Map<sessionId, 消息列表>
     */
    Map<Long, List<MessageResponse>> getOfflineMessages(OfflineMessageRequest request);

    List<MessageResponse> getHistoryMessages(HistoryMessageRequest request);

    String getSummary(SessionSummaryRequest sessionSummaryRequest);
}