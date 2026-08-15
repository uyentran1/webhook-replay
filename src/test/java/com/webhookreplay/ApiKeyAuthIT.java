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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The perimeter. Every case here is a way in that must not work, plus the one path that must
 * stay open without a credential.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ApiKeyAuthIT {

	private static final String BODY = """
			{"type": "order.paid", "payload": {}}""";

	@Autowired
	MockMvc mockMvc;

	@Test
	void rejectsAMissingAuthorizationHeader() throws Exception {
		mockMvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isUnauthorized())
				// Not a WWW-Authenticate/Basic challenge: this is a JSON API, and the default
				// entry point would tell a browser to open a login box.
				.andExpect(jsonPath("$.error").value("unauthorized"));
	}

	@Test
	void rejectsAnUnknownKey() throws Exception {
		mockMvc.perform(post("/v1/events")
						.header("Authorization", "Bearer sk_test_not_a_real_key")
						.contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isUnauthorized());
	}

	/**
	 * The scheme matters. Without the {@code Bearer } prefix check, a key pasted bare into
	 * the header would authenticate, and the API would accept two credential formats — one
	 * of them undocumented.
	 */
	@Test
	void rejectsAKeyWithoutTheBearerScheme() throws Exception {
		mockMvc.perform(post("/v1/events")
						.header("Authorization", "sk_test_alice")
						.contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isUnauthorized());
	}

	/**
	 * Boot's {@code ManagementWebSecurityAutoConfiguration} used to permit this for us and
	 * backed off as soon as {@code SecurityConfig} declared a chain. This is the test that
	 * catches it if that handover is ever broken — a load balancer cannot present an API key.
	 */
	@Test
	void leavesHealthOpenWithoutACredential() throws Exception {
		mockMvc.perform(get("/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

}
