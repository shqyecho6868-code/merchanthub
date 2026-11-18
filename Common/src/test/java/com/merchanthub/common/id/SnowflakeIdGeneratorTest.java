package com.merchanthub.common.id;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 租约状态判断的单元测试：Redis 交互用 Mockito 替身，时钟用可手动拨动的假时钟。
 */
@ExtendWith(MockitoExtension.class)
class SnowflakeIdGeneratorTest {

    @Mock
    private SnowflakeSlotAllocator allocator;

    private final SnowflakeProperties props = new SnowflakeProperties();
    private final AtomicLong clock = new AtomicLong(0);
    private SnowflakeIdGenerator generator;

    private SnowflakeIdGenerator start(int slot) {
        when(allocator.acquire(anyString())).thenReturn(slot);
        generator = new SnowflakeIdGenerator(allocator, props, clock::get);
        generator.afterPropertiesSet();
        return generator;
    }

    @AfterEach
    void tearDown() {
        if (generator != null) {
            generator.destroy();
        }
    }

    private void advanceSeconds(long s) {
        clock.addAndGet(TimeUnit.SECONDS.toNanos(s));
    }

    @Test
    @DisplayName("槽位拆成 workerId=低 5 位、dataCenterId=高 5 位，并写进 ID 里")
    void slotIsEncodedIntoId() {
        long id = start(37).nextId(); // 37 = 1*32 + 5 → dataCenterId=1, workerId=5
        // 雪花 ID 布局：... | dataCenterId(5) | workerId(5) | sequence(12)
        assertThat((id >> 12) & 0x1F).isEqualTo(5);
        assertThat((id >> 17) & 0x1F).isEqualTo(1);
    }

    @Test
    @DisplayName("同一实例连续发号不重复")
    void idsAreUnique() {
        start(0);
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(generator.nextId());
        }
        assertThat(ids).hasSize(10_000);
    }

    @Test
    @DisplayName("续期时发现号被别人拿走 → 立即停止发号")
    void leaseLost_stopsIssuing() {
        start(0);
        when(allocator.renew(anyInt(), anyString())).thenReturn(false);
        generator.renewOnce();
        assertThatThrownBy(generator::nextId).hasMessageContaining("租约已丢失");
    }

    @Test
    @DisplayName("Redis 暂时不可用：租约到期前照常发号，超过租约时长才停")
    void redisDown_keepsIssuingUntilDeadline() {
        start(0);
        when(allocator.renew(anyInt(), anyString())).thenThrow(new RuntimeException("redis down"));

        advanceSeconds(30);
        generator.renewOnce();
        assertThat(generator.nextId()).isPositive();

        advanceSeconds(60); // 距领号已 90 秒 = 租约时长
        assertThatThrownBy(generator::nextId).hasMessageContaining("租约已过期");
    }

    @Test
    @DisplayName("续期成功会把截止时间往后推")
    void renewSuccess_extendsDeadline() {
        start(0);
        when(allocator.renew(anyInt(), anyString())).thenReturn(true);
        advanceSeconds(80);
        generator.renewOnce();
        advanceSeconds(80); // 距领号 160 秒，但距上次续期只有 80 秒
        assertThat(generator.nextId()).isPositive();
    }

    @Test
    @DisplayName("正常下线时归还槽位")
    void destroy_releasesSlot() {
        start(3);
        generator.destroy();
        verify(allocator).release(anyInt(), anyString());
        generator = null;
    }
}
