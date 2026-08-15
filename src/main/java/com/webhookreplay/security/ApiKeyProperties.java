package com.webhookreplay.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

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
public record ApiKeyProperties(@DefaultValue Map<String, UUID> apiKeys) {

	/**
	 * {@code @DefaultValue} is not decoration. Boot's {@code ValueObjectBinder} binds an
	 * unset constructor parameter to {@code null} unless it carries one, and since no keys
	 * ship in the packaged {@code application.properties}, the unset case is the *normal*
	 * one — an instance started without the {@code local} profile or an external key file
	 * has no map at all. Without this, every request carrying a {@code Bearer} header would
	 * NPE into a 500 instead of a clean 401, with nothing said at startup.
	 */
	public Optional<UUID> tenantFor(String apiKey) {
		return Optional.ofNullable(apiKeys.get(apiKey));
	}

}
