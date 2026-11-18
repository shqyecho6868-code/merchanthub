package com.merchanthub.order.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderVO {
    /** 订单号转成字符串返回：雪花 ID 有 19 位，超过 JS Number 的安全整数范围，前端直接用 long 会丢精度 */
    private String orderNo;
    private Integer status;
    private Long payAmount;
    /** UTC */
    private LocalDateTime expireAt;
}
