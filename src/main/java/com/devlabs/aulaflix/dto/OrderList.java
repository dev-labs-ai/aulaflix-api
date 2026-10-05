package com.devlabs.aulaflix.dto;

import java.util.List;

/** The Student's Orders, newest first. */
public record OrderList(List<Order> items) {
}
