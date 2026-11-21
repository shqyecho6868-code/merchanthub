package com.merchanthub.product.inventory.client;

import com.merchanthub.common.common.BaseResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/** 调 order 的只读内部接口（/internal/** 不经网关对外暴露）。超时见 application.yml */
@FeignClient(name = "order", contextId = "inventoryOrderClient", path = "/internal/orders")
public interface OrderClient {

    /** 传入一批订单号，返回其中<b>存在</b>的那些（批量，一轮扫描只调一次） */
    @PostMapping("/existing")
    BaseResponse<List<Long>> existing(@RequestBody List<Long> orderNos);
}
