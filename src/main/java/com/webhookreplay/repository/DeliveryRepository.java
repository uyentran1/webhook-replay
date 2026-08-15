package com.webhookreplay.repository;

import com.webhookreplay.domain.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * The claim query does <em>not</em> belong here as a derived method. It needs
 * {@code FOR UPDATE SKIP LOCKED} and (per DESIGN.md §7b) a {@code ROW_NUMBER() OVER
 * (PARTITION BY endpoint_id …)}, neither of which Spring Data can derive. It lands in
 * week 3 as a {@code @Query(nativeQuery = true)}.
 */
public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

}
