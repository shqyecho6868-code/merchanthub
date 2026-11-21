package com.merchanthub.payment.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 模拟第三方渠道的"支付成功"异步通知。真实渠道还会带签名，验签失败直接拒绝；本项目不接真实渠道，省略验签。
 *
 * @param payNo          我们下单时传给渠道的商户单号
 * @param channelTradeNo 渠道自己的流水号（同一笔支付的重复通知，这个值相同）
 * @param amount         渠道实际收款金额（分），必须和支付单金额一致
 */
public record MockCallbackRequest(@NotNull Long payNo, @NotBlank String channelTradeNo, @NotNull Long amount) {
}
