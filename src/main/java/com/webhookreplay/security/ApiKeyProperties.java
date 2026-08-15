package com.webhookreplay.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The tenant→key mapping of DESIGN.md §3, held in configuration.
 *
 * <p>There is deliberately no tenant table: v1 has no self-serve signup (§9), so tenants are
 * created by an operator editing configuration and restarting. That is the honest shape of
 * the requirement, and a table would imply a lifecycle nothing yet manages.
 *
 * <p>Keyed by API key rather than by tenant because that is the direction every request
 * resolves — key in, tenant out, one hash lookup on the hot ingest path. The inverse map
 * would mean scanning every tenant on every request.
 *
 * <p><strong>v1 shortcut, priced in:</strong> the keys are plaintext in a properties file, so
 * anyone who can read the config can mint requests for any tenant, and rotating a key is a
 * redeploy. Real key storage is hashed-at-rest with a prefix lookup. See DECISIONS.md #10.
 */
@ConfigurationProperties(prefix = "webhook-replay")
public record ApiKeyProperties(Map<String, UUID> apiKeys) {

	public Optional<UUID> tenantFor(String apiKey) {
		return Optional.ofNullable(apiKeys.get(apiKey));
	}

}
