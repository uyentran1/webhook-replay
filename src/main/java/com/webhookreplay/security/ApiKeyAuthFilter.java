package com.webhookreplay.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Resolves {@code Authorization: Bearer <key>} to the tenant every downstream query is
 * scoped by.
 *
 * <p>The filter never rejects. A missing or unknown key simply leaves the context
 * unauthenticated and lets the chain continue; {@code SecurityConfig}'s authorization rules
 * decide whether that matters, which is what keeps {@code /health} reachable without a key.
 * Throwing here would make every unauthenticated request to a public path a 401.
 *
 * <p>The principal is the tenant {@link UUID} rather than a username, because a tenant is
 * what the application actually authorises against — there is no user model in v1.
 *
 * <p>Deliberately <strong>not</strong> a {@code @Component}. Boot registers any {@code Filter}
 * bean with the servlet container, which would put a second copy of this filter outside the
 * security chain entirely — today that is masked by filter ordering and
 * {@code OncePerRequestFilter}'s already-filtered attribute, but both are incidental, and a
 * second {@code SecurityFilterChain} with a {@code securityMatcher} (the likely shape of the
 * week-11 console) would expose it. {@code SecurityConfig} constructs it instead, so it exists
 * only where it is wired.
 */
class ApiKeyAuthFilter extends OncePerRequestFilter {

	private static final String BEARER = "Bearer ";

	private final ApiKeyProperties apiKeyProperties;

	ApiKeyAuthFilter(ApiKeyProperties apiKeyProperties) {
		this.apiKeyProperties = apiKeyProperties;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {

		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.startsWith(BEARER)) {
			apiKeyProperties.tenantFor(header.substring(BEARER.length()))
					// No authorities: v1 has no roles or scopes (§9 rules out RBAC), so
					// authentication and authorisation collapse into the same question —
					// is this a known key?
					.map(tenantId -> new UsernamePasswordAuthenticationToken(tenantId, null, List.of()))
					.ifPresent(authentication -> {
						// A fresh context, not a mutation of the existing one. Mutating the
						// shared instance is safe under the default MODE_THREADLOCAL and
						// becomes cross-request authentication leakage under MODE_GLOBAL —
						// a one-property change with a very non-local consequence.
						SecurityContext context = SecurityContextHolder.createEmptyContext();
						context.setAuthentication(authentication);
						SecurityContextHolder.setContext(context);
					});
		}

		// SecurityContextHolder is ThreadLocal-backed, and with virtual threads enabled every
		// request gets a fresh carrier-independent thread, so there is no pooled thread to
		// leak a previous tenant's context into. Spring Security clears it after the chain
		// regardless; this is why that matters less here than it would on a platform pool.
		chain.doFilter(request, response);
	}

}
