package com.merchanthub.product.inventory.service.impl;

import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.product.inventory.mapper.InventoryMapper;
import com.merchanthub.product.inventory.mapper.InventoryReservationMapper;
import com.merchanthub.product.inventory.model.dto.ReserveRequest;
import com.merchanthub.product.inventory.model.entity.InventoryReservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预占编排逻辑的单元测试（不连数据库）：顺序、分支、异常处理。
 * 真实的行锁、回滚、并发效果在 InventoryReservationIT / ConcurrentOversellIT 里验证。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryReservationServiceImplTest {

    private static final long TENANT = 7L;
    private static final long ORDER = 9001L;

    @Mock
    private InventoryMapper inventoryMapper;
    @Mock
    private InventoryReservationMapper reservationMapper;
    @Mock
    private TransactionTemplate transactionTemplate;

    private InventoryReservationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new InventoryReservationServiceImpl(inventoryMapper, reservationMapper, transactionTemplate);
        // 替身事务：直接执行代码块（回滚效果由 IT 验证）
        doAnswer(inv -> {
            inv.<Consumer<TransactionStatus>>getArgument(0).accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(reservationMapper.selectByOrderNo(anyLong())).thenReturn(List.of());
        when(inventoryMapper.tryReserve(anyLong(), anyLong(), anyInt())).thenReturn(1);
    }

    private static ReserveRequest req(long... skuQty) {
        List<ReserveRequest.Item> items = new java.util.ArrayList<>();
        for (int i = 0; i < skuQty.length; i += 2) {
            items.add(new ReserveRequest.Item(skuQty[i], (int) skuQty[i + 1]));
        }
        return new ReserveRequest(ORDER, TENANT, items);
    }

    private static InventoryReservation record(long tenant, long sku, int qty) {
        InventoryReservation r = new InventoryReservation();
        r.setTenantId(tenant);
        r.setOrderNo(ORDER);
        r.setSkuId(sku);
        r.setQuantity(qty);
        return r;
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(code.getCode());
    }

    @Test
    void locksSkusInAscendingOrder_insertBeforeUpdate() {
        service.reserve(req(102, 1, 101, 2));
        InOrder order = inOrder(reservationMapper, inventoryMapper);
        order.verify(reservationMapper).insert(any(InventoryReservation.class));
        order.verify(inventoryMapper).tryReserve(TENANT, 101L, 2);
        order.verify(reservationMapper).insert(any(InventoryReservation.class));
        order.verify(inventoryMapper).tryReserve(TENANT, 102L, 1);
    }

    @Test
    void zeroRowsUpdated_throwsInsufficient() {
        when(inventoryMapper.tryReserve(TENANT, 102L, 5)).thenReturn(0);
        assertCode(() -> service.reserve(req(101, 2, 102, 5)), ErrorCode.INVENTORY_INSUFFICIENT);
    }

    @Test
    void duplicateSkuInRequest_rejectedUpFront() {
        assertCode(() -> service.reserve(req(101, 2, 101, 3)), ErrorCode.PARAMS_ERROR);
        verify(transactionTemplate, never()).executeWithoutResult(any());
    }

    @Test
    void fastPath_sameContent_returnsWithoutTransaction() {
        when(reservationMapper.selectByOrderNo(ORDER)).thenReturn(List.of(record(TENANT, 101, 2), record(TENANT, 102, 1)));
        assertThatCode(() -> service.reserve(req(102, 1, 101, 2))).doesNotThrowAnyException();
        verify(transactionTemplate, never()).executeWithoutResult(any());
    }

    @Test
    void fastPath_differentContent_conflict() {
        when(reservationMapper.selectByOrderNo(ORDER)).thenReturn(List.of(record(TENANT, 101, 2)));
        assertCode(() -> service.reserve(req(103, 1)), ErrorCode.RESERVATION_CONFLICT);
    }

    @Test
    void fastPath_differentTenant_conflict() {
        when(reservationMapper.selectByOrderNo(ORDER)).thenReturn(List.of(record(8L, 101, 2)));
        assertCode(() -> service.reserve(req(101, 2)), ErrorCode.RESERVATION_CONFLICT);
    }

    @Test
    void duplicateKeyAfterRollback_recheckedOutsideTransaction() {
        // 第一次查（快速路径）没有记录；事务里撞唯一键；回滚后再查，看到对方提交的记录
        when(reservationMapper.selectByOrderNo(ORDER))
                .thenReturn(List.of())
                .thenReturn(List.of(record(TENANT, 101, 2)));
        when(reservationMapper.insert(any(InventoryReservation.class))).thenThrow(new DuplicateKeyException("uk_order_sku"));

        assertThatCode(() -> service.reserve(req(101, 2))).doesNotThrowAnyException();
        verify(inventoryMapper, never()).tryReserve(anyLong(), eq(101L), anyInt());
    }
}
