package com.devlabs.aulaflix.dto;

import java.util.List;

/** One page of a paginated list: its items, which page it is (from 0), its size, and how many there are in all. */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    /** The page asked for of a list that matches nothing. */
    public static <T> PageResponse<T> empty(int page, int size) {
        return new PageResponse<>(List.of(), page, size, 0, 0);
    }
}
