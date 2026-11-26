package com.merchanthub.common.model.dto;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Common pagination response
 * <p>
 * Response format:
 * <pre>
 * {
 *   "list": [...],         // data on the current page
 *   "total": 100,          // total records
 *   "pageSize": 20,        // page size
 *   "pageNum": 1,          // current page number
 *   "pages": 5,            // total pages
 *   "hasNext": true,       // whether there is a next page
 *   "hasPrevious": false   // whether there is a previous page
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Data on the current page
     */
    private List<T> list;

    /**
     * Total records
     */
    private Long total;

    /**
     * Page size
     */
    private Long pageSize;

    /**
     * Current page number
     */
    private Long pageNum;

    /**
     * Total pages
     */
    private Long pages;

    /**
     * Whether there is a next page
     */
    private Boolean hasNext;

    /**
     * Whether there is a previous page
     */
    private Boolean hasPrevious;

    /**
     * Convert from a MyBatis-Plus IPage
     *
     * @param page MyBatis-Plus page result
     * @param <T>  data type
     * @return common pagination response
     */
    public static <T> PageResponse<T> of(IPage<T> page) {
        return PageResponse.<T>builder()
                .list(page.getRecords())
                .total(page.getTotal())
                .pageSize(page.getSize())
                .pageNum(page.getCurrent())
                .pages(page.getPages())
                .hasNext(page.getCurrent() < page.getPages())
                .hasPrevious(page.getCurrent() > 1)
                .build();
    }

    /**
     * Convert from a MyBatis-Plus IPage (with element conversion)
     *
     * @param page      MyBatis-Plus page result
     * @param converter element conversion function
     * @param <T>       source type
     * @param <R>       target type
     * @return common pagination response
     */
    public static <T, R> PageResponse<R> of(IPage<T> page, Function<T, R> converter) {
        List<R> convertedList = page.getRecords().stream()
                .map(converter)
                .collect(Collectors.toList());

        return PageResponse.<R>builder()
                .list(convertedList)
                .total(page.getTotal())
                .pageSize(page.getSize())
                .pageNum(page.getCurrent())
                .pages(page.getPages())
                .hasNext(page.getCurrent() < page.getPages())
                .hasPrevious(page.getCurrent() > 1)
                .build();
    }

    /**
     * Build a pagination response manually (for in-memory pagination)
     *
     * @param allData  all data
     * @param pageNum  current page number
     * @param pageSize page size
     * @param <T>      data type
     * @return common pagination response
     */
    public static <T> PageResponse<T> of(List<T> allData, int pageNum, int pageSize) {
        if (allData == null || allData.isEmpty()) {
            return PageResponse.<T>builder()
                    .list(Collections.emptyList())
                    .total(0L)
                    .pageSize((long) pageSize)
                    .pageNum((long) pageNum)
                    .pages(0L)
                    .hasNext(false)
                    .hasPrevious(false)
                    .build();
        }

        long total = allData.size();
        long pages = (total + pageSize - 1) / pageSize;

        int fromIndex = (pageNum - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, allData.size());

        List<T> list = fromIndex < allData.size()
                ? allData.subList(fromIndex, toIndex)
                : Collections.emptyList();

        return PageResponse.<T>builder()
                .list(list)
                .total(total)
                .pageSize((long) pageSize)
                .pageNum((long) pageNum)
                .pages(pages)
                .hasNext(pageNum < pages)
                .hasPrevious(pageNum > 1)
                .build();
    }

    /**
     * Build an empty pagination response
     *
     * @param pageNum  current page number
     * @param pageSize page size
     * @param <T>      data type
     * @return empty pagination response
     */
    public static <T> PageResponse<T> empty(int pageNum, int pageSize) {
        return PageResponse.<T>builder()
                .list(Collections.emptyList())
                .total(0L)
                .pageSize((long) pageSize)
                .pageNum((long) pageNum)
                .pages(0L)
                .hasNext(false)
                .hasPrevious(false)
                .build();
    }
}