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

    /** E3：待处理的支付异常（走索引 (handle_status, created_at)） */
    @Select("SELECT * FROM pay_anomaly WHERE handle_status = 0 AND deleted = 0 ORDER BY created_at LIMIT #{limit}")
    List<PayAnomaly> selectPending(@Param("limit") int limit);

    /** 0 → 2（已标记待退款并告警）。带旧状态条件：两个实例同时处理同一条，只有一个会成功 */
    @Update("UPDATE pay_anomaly SET handle_status = 2 WHERE id = #{id} AND handle_status = 0")
    int markRefundPending(@Param("id") long id);
}
