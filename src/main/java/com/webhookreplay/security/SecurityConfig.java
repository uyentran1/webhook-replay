package com.webhookreplay.security;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * One filter chain for the whole service.
 *
 * <p><strong>Defining this bean is not free.</strong> Boot's
 * {@code ManagementWebSecurityAutoConfiguration} ships a chain that permits {@code /health}
 * and secures everything else — which is why adding the security starter did not break the
 * health check. That auto-configuration backs off the moment a {@code SecurityFilterChain}
 * bean exists, so every rule it used to provide is now this class's responsibility.
 * {@code HealthEndpointIT} is what proves the handover was clean.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(ApiKeyProperties.class)
class SecurityConfig {

	@Bean
	SecurityFilterChain apiFilterChain(HttpSecurity http, ApiKeyAuthFilter apiKeyAuthFilter) throws Exception {
		return http
				// No cookies, no browser, no session to fix on: CSRF defends a credential the
				// browser attaches automatically, and a bearer key is not one. Leaving it on
				// would reject every POST /v1/events for a threat that cannot apply here.
				.csrf(csrf -> csrf.disable())
				// Senders authenticate on every request, so a session would be pure cost —
				// memory per caller and an affinity constraint on horizontal scaling.
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						// Spring Security authorises ERROR dispatches too, so without this a
						// 400 from bean validation is re-authorised on its way to /error and
						// surfaces as a 401 — the wrong status for the wrong reason.
						.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						// Unauthenticated on purpose: a health check that needs a credential
						// is one a load balancer cannot use. It leaks more than it should —
						// show-details=always is on the week-14 list.
						.requestMatchers("/health").permitAll()
						.anyRequest().authenticated())
				.exceptionHandling(handling -> handling.authenticationEntryPoint(jsonUnauthorized()))
				.addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
				.build();
	}

	/**
	 * The default entry point answers with {@code WWW-Authenticate: Basic}, which tells a
	 * browser to pop a login box and tells an API client nothing useful. This is a JSON API;
	 * it answers in JSON.
	 */
	private static AuthenticationEntryPoint jsonUnauthorized() {
		return (request, response, authException) -> {
			response.setStatus(HttpStatus.UNAUTHORIZED.value());
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.getWriter().write("""
					{"error":"unauthorized","message":"Provide a valid API key as 'Authorization: Bearer <key>'."}""");
		};
	}

}
