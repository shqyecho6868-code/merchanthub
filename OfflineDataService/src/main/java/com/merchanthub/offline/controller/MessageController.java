package com.merchanthub.offline.controller;

import java.util.List;
import java.util.Map;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.common.model.vo.MessageResponse;
import com.merchanthub.offline.model.dto.HistoryMessageRequest;
import com.merchanthub.offline.model.dto.OfflineMessageRequest;

import com.merchanthub.offline.model.dto.SessionSummaryRequest;
import com.merchanthub.offline.service.MessageService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/message")
public class MessageController {

    @Resource
    private MessageService messageService;

    /**
     * 获取离线消息（用户上线后调用）
     * 
     * @return Map<sessionId, List<消息>>
     */
    @PostMapping("/offline")
    public BaseResponse<Map<Long, List<MessageResponse>>> getOfflineMessages(
            @RequestBody OfflineMessageRequest request) {
        return ResultUtils.success(messageService.getOfflineMessages(request));
    }

    /**
     * 获取历史消息（往上翻页）
     */
    @PostMapping("/history")
    public BaseResponse<List<MessageResponse>> getHistoryMessages(
            @RequestBody HistoryMessageRequest request) {
        return ResultUtils.success(messageService.getHistoryMessages(request));
    }

    @PostMapping("/summary")
    public BaseResponse<String> chatSummary(@RequestBody SessionSummaryRequest sessionSummaryRequest) {
        return ResultUtils.success(messageService.getSummary(sessionSummaryRequest));
    }
}