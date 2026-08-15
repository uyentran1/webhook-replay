package com.webhookreplay.repository;

import com.webhookreplay.domain.Endpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Was deliberately bare. Spring Data derives an implementation from the interface, so every
 * declared method is a query — and a query with no caller is a guess about what the API
 * will need. This one arrived with its caller.
 */
public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {

	/**
	 * The fan-out set: every endpoint that should receive an event of this type.
	 *
	 * <p>Native because {@code = any(text[])} has no JPQL expression — the same reason
	 * week 3's claim query will be native. ORM for the CRUD, hand-written SQL where the
	 * database has something JPQL cannot say.
	 *
	 * <p>The null check is the load-bearing half. {@code event_types is null} means "all
	 * types" and an empty array means "no types"; SQL would collapse both to no match,
	 * because {@code x = any(null)} is NULL rather than false. Without the explicit branch,
	 * every endpoint registered without a filter silently receives nothing.
	 *
	 * <p>{@code state = 'active'} excludes {@code circuit_open} and {@code disabled}. Note
	 * what that means for week 8: an event arriving while a breaker is open produces no
	 * delivery row at all, rather than a held one. DESIGN.md section 7c wants deliveries
	 * *held*, so this predicate is one the breaker work will have to revisit.
	 */
	@Query(value = """
			select * from endpoint
			where tenant_id = :tenantId
			  and state = 'active'
			  and (event_types is null or :type = any(event_types))
			""", nativeQuery = true)
	List<Endpoint> findMatching(@Param("tenantId") UUID tenantId, @Param("type") String type);

}
