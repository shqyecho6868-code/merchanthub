package com.merchanthub.realtime.client;

import com.merchanthub.common.model.dto.ChatRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;


@FeignClient(name = "AiService")
public interface AiServiceClient {


    @PostMapping("/api/ai/chat")
    String chat(@RequestBody ChatRequest chatRequest);

}