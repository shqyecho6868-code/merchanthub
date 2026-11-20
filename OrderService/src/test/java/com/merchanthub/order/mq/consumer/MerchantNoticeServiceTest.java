package com.merchanthub.order.mq.consumer;

import com.merchanthub.order.mapper.ConsumedEventMapper;
import com.merchanthub.order.mapper.MerchantOrderNoticeMapper;
import com.merchanthub.order.mq.OrderCreatedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantNoticeServiceTest {

    private final ConsumedEventMapper consumed = mock(ConsumedEventMapper.class);
    private final MerchantOrderNoticeMapper notices = mock(MerchantOrderNoticeMapper.class);
    private final MerchantNoticeService service = new MerchantNoticeService(consumed, notices);
    private final OrderCreatedEvent event = new OrderCreatedEvent(1L, 9001L, 7L, 1001L, 9900L, LocalDateTime.now());

    @Test
    void firstTime_writesNotice() {
        assertThat(service.handle(event)).isTrue();
        verify(notices).insert(any(com.merchanthub.order.model.entity.MerchantOrderNotice.class));
    }

    @Test
    void duplicate_skipsBusiness() {
        when(consumed.insert(any(com.merchanthub.order.model.entity.ConsumedEvent.class)))
                .thenThrow(new DuplicateKeyException("uk_group_event"));
        assertThat(service.handle(event)).isFalse();
        verify(notices, never()).insert(any(com.merchanthub.order.model.entity.MerchantOrderNotice.class));
    }
}
