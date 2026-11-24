package com.merchanthub.common.recon;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 对账 / 补偿发现"自动修不了"的问题时调用：记一行 recon_exception（每个服务自己库里一张）+ 打 ERROR 告警日志（E3）。
 * <p>
 * 为什么写库而不是只打日志：日志会滚动丢失，也没法统计"还有几条没处理"；异常表是人工处理的待办清单，
 * 处理完把 handle_status 改成 1。生产环境再让监控按这张表 / 这条日志的关键字发告警。
 * <p>
 * 用 (type, biz_key) 唯一键 + INSERT IGNORE 做幂等：对账每轮都会扫到同一个问题，只记一次、只告警一次，不刷屏。
 * 用 JdbcTemplate 不走 MyBatis：这是基础设施表，不需要租户插件拼条件（tenant_id 显式写入）。
 */
@Slf4j
public class ReconAlarm {

    // 告警类型（type 列）
    /** 支付成功但订单已关闭：钱收了货不发，待退款（退款范围外） */
    public static final String PAID_AFTER_CLOSED = "PAID_AFTER_CLOSED";
    /** 同一订单被两张支付单支付成功：多收的那笔待退款 */
    public static final String DUPLICATE_PAYMENT = "DUPLICATE_PAYMENT";
    /** 支付金额 / 商家和订单对不上 */
    public static final String PAY_MISMATCH = "PAY_MISMATCH";
    /** 支付成功事件补发多次，订单侧仍没处理 */
    public static final String PAY_RESEND_EXHAUSTED = "PAY_RESEND_EXHAUSTED";
    /** 本地消息表投递失败达到上限 */
    public static final String OUTBOX_FAILED = "OUTBOX_FAILED";
    /** 消息重试耗尽、进入死信队列 */
    public static final String DEAD_LETTER = "DEAD_LETTER";

    private final JdbcTemplate jdbc;

    public ReconAlarm(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true 本次新记录（第一次发现）；false 之前已经记过 */
    public boolean raise(long tenantId, String type, String bizKey, String detail) {
        int n = jdbc.update("INSERT IGNORE INTO recon_exception (tenant_id, type, biz_key, detail, handle_status) "
                + "VALUES (?, ?, ?, ?, 0)", tenantId, type, bizKey, truncate(detail));
        if (n == 1) {
            log.error("【对账告警】type={} bizKey={} tenantId={} detail={}", type, bizKey, tenantId, detail);
            return true;
        }
        return false;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }
}
