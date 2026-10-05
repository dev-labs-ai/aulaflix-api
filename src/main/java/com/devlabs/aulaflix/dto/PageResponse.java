package com.devlabs.aulaflix.dto;

import java.util.List;

/** One page of a paginated list: its items, which page it is (from 0), its size, and how many there are in all. */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
}
