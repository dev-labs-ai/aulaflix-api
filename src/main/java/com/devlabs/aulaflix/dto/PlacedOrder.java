package com.devlabs.aulaflix.dto;

/** The Order a placement answers with, and whether it is new or the one already awaiting payment by that method. */
public record PlacedOrder(Order order, boolean created) {
}
