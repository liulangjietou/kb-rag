package io.kbrag.api.dto;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.function.Function;

/**
 * Paged payload shared by every listing endpoint.
 *
 * @param items ordered page content
 * @param page  one based page number
 * @param size  page size
 * @param total total number of matching rows
 * @param <T>   item type
 */
public record PageResponse<T>(
        List<T> items,
        long page,
        long size,
        long total) {

    /**
     * Maps a MyBatis-Plus page onto the transport shape.
     *
     * @param source persistence page
     * @param mapper entity to view mapping
     * @param <E>    entity type
     * @param <T>    view type
     * @return paged payload
     */
    public static <E, T> PageResponse<T> from(IPage<E> source, Function<E, T> mapper) {
        return new PageResponse<>(
                source.getRecords().stream().map(mapper).toList(),
                source.getCurrent(),
                source.getSize(),
                source.getTotal());
    }
}
