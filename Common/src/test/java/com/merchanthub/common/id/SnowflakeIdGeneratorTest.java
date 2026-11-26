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
 * Unit tests for the lease state logic: Redis interaction uses Mockito doubles, and time comes from a fake clock advanced by hand.
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
    @DisplayName("Slot splits into workerId = low 5 bits, dataCenterId = high 5 bits, both encoded in the ID")
    void slotIsEncodedIntoId() {
        long id = start(37).nextId(); // 37 = 1*32 + 5 → dataCenterId=1, workerId=5
        // Snowflake ID layout: ... | dataCenterId(5) | workerId(5) | sequence(12)
        assertThat((id >> 12) & 0x1F).isEqualTo(5);
        assertThat((id >> 17) & 0x1F).isEqualTo(1);
    }

    @Test
    @DisplayName("Consecutive IDs from one instance never repeat")
    void idsAreUnique() {
        start(0);
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(generator.nextId());
        }
        assertThat(ids).hasSize(10_000);
    }

    @Test
    @DisplayName("Renewal finds the slot taken by someone else → stop issuing immediately")
    void leaseLost_stopsIssuing() {
        start(0);
        when(allocator.renew(anyInt(), anyString())).thenReturn(false);
        generator.renewOnce();
        assertThatThrownBy(generator::nextId).hasMessageContaining("lease lost");
    }

    @Test
    @DisplayName("Redis temporarily unavailable: keep issuing until the lease runs out, stop only after the full lease duration")
    void redisDown_keepsIssuingUntilDeadline() {
        start(0);
        when(allocator.renew(anyInt(), anyString())).thenThrow(new RuntimeException("redis down"));

        advanceSeconds(30);
        generator.renewOnce();
        assertThat(generator.nextId()).isPositive();

        advanceSeconds(60); // 90 seconds since claiming = lease duration
        assertThatThrownBy(generator::nextId).hasMessageContaining("lease expired");
    }

    @Test
    @DisplayName("A successful renewal pushes the deadline back")
    void renewSuccess_extendsDeadline() {
        start(0);
        when(allocator.renew(anyInt(), anyString())).thenReturn(true);
        advanceSeconds(80);
        generator.renewOnce();
        advanceSeconds(80); // 160 seconds since claiming, but only 80 since the last renewal
        assertThat(generator.nextId()).isPositive();
    }

    @Test
    @DisplayName("Release the slot on graceful shutdown")
    void destroy_releasesSlot() {
        start(3);
        generator.destroy();
        verify(allocator).release(anyInt(), anyString());
        generator = null;
    }
}
