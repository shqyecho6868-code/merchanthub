package com.merchanthub.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.merchanthub.order.model.entity.PayAnomaly;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PayAnomalyMapper extends BaseMapper<PayAnomaly> {

    /** Pending payment anomalies (using index (handle_status, created_at)) */
    @Select("SELECT * FROM pay_anomaly WHERE handle_status = 0 AND deleted = 0 ORDER BY created_at LIMIT #{limit}")
    List<PayAnomaly> selectPending(@Param("limit") int limit);

    /** 0 -> 2 (marked pending refund and alerted). Conditioned on the old status: if two instances process the same row, only one succeeds */
    @Update("UPDATE pay_anomaly SET handle_status = 2 WHERE id = #{id} AND handle_status = 0")
    int markRefundPending(@Param("id") long id);
}
