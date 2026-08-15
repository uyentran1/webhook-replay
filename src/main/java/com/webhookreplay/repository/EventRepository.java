package com.webhookreplay.repository;

import com.webhookreplay.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID> {

}
