package com.devlabs.aulaflix.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.entity.WebhookEventEntity;
import com.devlabs.aulaflix.domain.entity.WebhookEventState;

public interface WebhookEventRepository extends JpaRepository<WebhookEventEntity, Long> {

    /**
     * Stores the delivery, unless one with the same Asaas event id is stored already, in one statement, so that two
     * deliveries of one event racing each other store it once. Answers how many rows it stored: 1, or 0 for a repeat.
     */
    @Transactional
    @Modifying
    @Query(value = """
            insert into webhook_events (id, asaas_event_id, event_type, charge_id, checkout_id, body, received_at,
                                        state)
            values (nextval('seq_webhook_event'), :eventId, :eventType, :chargeId, :checkoutId, :body, :receivedAt,
                    :state)
            on conflict (asaas_event_id) do nothing""", nativeQuery = true)
    int insertUnlessReceived(@Param("eventId") String eventId, @Param("eventType") String eventType,
                             @Param("chargeId") String chargeId, @Param("checkoutId") String checkoutId,
                             @Param("body") byte[] body, @Param("receivedAt") Instant receivedAt,
                             @Param("state") String state);

    /** The events in the state, oldest first. */
    List<WebhookEventEntity> findByStateOrderById(WebhookEventState state);
}
