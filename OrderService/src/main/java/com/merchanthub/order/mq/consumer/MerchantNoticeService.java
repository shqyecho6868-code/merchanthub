package com.merchanthub.order.mq.consumer;

import com.merchanthub.order.mapper.ConsumedEventMapper;
import com.merchanthub.order.mapper.MerchantOrderNoticeMapper;
import com.merchanthub.order.model.entity.ConsumedEvent;
import com.merchanthub.order.model.entity.MerchantOrderNotice;
import com.merchanthub.order.mq.OrderCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 演示下游：收到"订单已创建"后给商家写一条新订单通知。幂等的关键是<b>去重记录和业务处理在同一个本地事务里</b>：
 * <ul>
 *   <li>业务成功 → 两者一起提交，以后同一个 event_id 再来，去重记录的唯一键挡住</li>
 *   <li>业务失败 → 两者一起回滚，RocketMQ 重投时还能重新处理（不会被误判成"处理过了"而丢掉）</li>
 * </ul>
 * 为什么不用 Redis SETNX 去重：Redis 和 MySQL 不在一个事务里，两种失败顺序分别导致丢消息或重复处理（B2 笔记 Q4）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantNoticeService {

    public static final String CONSUMER_GROUP = "order-merchant-notice";

    private final ConsumedEventMapper consumedEventMapper;
    private final MerchantOrderNoticeMapper noticeMapper;

    /** @return true 本次真正处理了；false 重复消息，已跳过 */
    @Transactional(rollbackFor = Exception.class)
    public boolean handle(OrderCreatedEvent event) {
        ConsumedEvent mark = new ConsumedEvent();
        mark.setTenantId(event.tenantId());
        mark.setConsumerGroup(CONSUMER_GROUP);
        mark.setEventId(event.eventId());
        try {
            // 先插去重记录：唯一键 (consumer_group, event_id)。并发的两条重复消息，第二条会在这里等第一条的行锁，
            // 第一条提交后第二条拿到唯一键冲突。不用"先 SELECT 判断再插入"：两条同时 SELECT 都查不到，就都处理了
            consumedEventMapper.insert(mark);
        } catch (DuplicateKeyException e) {
            log.info("重复消息，跳过 group={} eventId={} orderNo={} tenantId={}",
                    CONSUMER_GROUP, event.eventId(), event.orderNo(), event.tenantId());
            return false;
        }

        MerchantOrderNotice notice = new MerchantOrderNotice();
        notice.setTenantId(event.tenantId());
        notice.setOrderNo(event.orderNo());
        notice.setBuyerId(event.buyerId());
        notice.setPayAmount(event.payAmount());
        notice.setEventId(event.eventId());
        notice.setReadStatus(0);
        noticeMapper.insert(notice);
        log.info("商家新订单通知已写入 eventId={} orderNo={} tenantId={}", event.eventId(), event.orderNo(), event.tenantId());
        return true;
    }
}
