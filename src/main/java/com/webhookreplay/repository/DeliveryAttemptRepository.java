package com.webhookreplay.repository;

import com.webhookreplay.domain.DeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Note the id type is {@code Long}, not {@code UUID} — this is the one table keyed by
 * {@code bigserial}.
 */
public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, Long> {

}
