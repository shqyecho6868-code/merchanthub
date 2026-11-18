package com.merchanthub.product.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.merchanthub.product.inventory.model.entity.InventoryReservation;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface InventoryReservationMapper extends BaseMapper<InventoryReservation> {

    default List<InventoryReservation> selectByOrderNo(long orderNo) {
        return selectList(Wrappers.<InventoryReservation>lambdaQuery().eq(InventoryReservation::getOrderNo, orderNo));
    }
}
