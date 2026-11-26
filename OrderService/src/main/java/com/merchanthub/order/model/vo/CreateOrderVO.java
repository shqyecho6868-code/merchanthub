package com.merchanthub.order.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderVO {
    /** Order number returned as a string: snowflake IDs have 19 digits, beyond JS Number's safe integer range, so using a long directly loses precision on the frontend */
    private String orderNo;
    private Integer status;
    private Long payAmount;
    /** UTC */
    private LocalDateTime expireAt;
}
