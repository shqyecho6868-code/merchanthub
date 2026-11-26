package com.merchanthub.common.model.dto;

import java.io.Serial;
import java.io.Serializable;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import lombok.Data;

/**
 * Common pagination request parameters
 * <p>
 * Usage: Query Params: ?pageNum=1&pageSize=20
 */
@Data
public class PageRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Current page number (starting at 1)
     */
    private Integer pageNum = 1;

    /**
     * Page size
     */
    private Integer pageSize = 20;

    public Integer getPageNum() {
        return pageNum == null ? 1 : pageNum;
    }

    public Integer getPageSize() {
        return pageSize == null ? 20 : Math.min(pageSize, 100);
    }

    /**
     * Convert to a MyBatis-Plus Page object
     */
    public <T> Page<T> toPage() {
        int num = getPageNum();
        int size = getPageSize();
        return new Page<>(num, size);
    }
}