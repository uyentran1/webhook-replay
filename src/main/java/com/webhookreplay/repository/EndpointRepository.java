package com.webhookreplay.repository;

import com.webhookreplay.domain.Endpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Deliberately bare. Spring Data derives an implementation from the interface, so every
 * declared method is a query — and a query with no caller is a guess about what the API
 * will need. Finders arrive with the endpoints that call them in week 3.
 */
public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {

}
