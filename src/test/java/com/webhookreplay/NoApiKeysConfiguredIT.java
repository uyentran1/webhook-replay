package com.webhookreplay;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The default deployment: no API keys configured anywhere.
 *
 * <p>This is the <em>normal</em> case now, not an exotic one. The packaged
 * {@code application.properties} deliberately ships no keys, so an instance started without
 * the {@code local} profile or an external key file has an empty map — and it must still
 * behave, rather than 500 on the first request that carries a credential.
 *
 * <p>What this pins is the {@code @DefaultValue} on {@link com.webhookreplay.security.ApiKeyProperties}.
 * Boot's {@code ValueObjectBinder} binds an unset constructor parameter to {@code null}
 * without it, and {@code tenantFor} would then throw a {@code NullPointerException} — a 500
 * where a 401 belongs, with nothing said at startup to warn anyone.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class NoApiKeysConfiguredIT {

	@Autowired
	MockMvc mockMvc;

	@Test
	void answers401RatherThan500WhenNoKeysAreConfigured() throws Exception {
		mockMvc.perform(post("/v1/events")
						.header("Authorization", "Bearer " + TestApiKeys.ALICE)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"type": "order.paid", "payload": {}}"""))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void stillStartsAndServesHealth() throws Exception {
		mockMvc.perform(get("/health")).andExpect(status().isOk());
	}

}
