package com.devlabs.aulaflix.service;

import com.devlabs.aulaflix.domain.OrderStatus;
import com.devlabs.aulaflix.domain.entity.CourseEntity;
import com.devlabs.aulaflix.domain.entity.OrderEntity;
import com.devlabs.aulaflix.dto.Order;
import com.devlabs.aulaflix.dto.OrderedCourse;
import com.devlabs.aulaflix.dto.PixPayment;

/** An Order as its Student sees it, mapped by hand. The Course must be loaded. */
final class OrderViews {

    private OrderViews() {
    }

    /** As the list shows it: never with the means of payment. */
    static Order summary(OrderEntity order) {
        return view(order, null);
    }

    /** As its placement and its own read show it: with the Pix QR code while it awaits payment. */
    static Order withPayment(OrderEntity order) {
        boolean payable = order.getStatus() == OrderStatus.AWAITING_PAYMENT && order.getPixQrCodePng() != null;
        return view(order, payable
                ? new PixPayment(order.getPixQrCodePng(), order.getPixCopyPasteCode(), order.getExpiresAt())
                : null);
    }

    private static Order view(OrderEntity order, PixPayment pix) {
        CourseEntity course = order.getCourse();
        return new Order(order.getCode(), order.getStatus(), order.getMethod(),
                new OrderedCourse(course.getId(), course.getSlug(), course.getTitle()), order.getAmountCents(),
                order.getCreatedAt(), order.getPaidAt(), order.isDuplicatePayment(), pix);
    }
}
