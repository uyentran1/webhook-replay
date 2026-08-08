package com.webhookreplay;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
// Boot 4 moved this out of org.springframework.boot.test.autoconfigure.web.servlet
// into a per-module namespace. Most 3.x tutorials will show the old path.
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the whole stack wires up: Spring context, a real Postgres 16 via Testcontainers,
 * Flyway running against it, and actuator serving health at / rather than /actuator.
 *
 * A green run here means the next session can start writing migrations rather than
 * debugging configuration.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class HealthEndpointIT {

	@Autowired
	MockMvc mockMvc;

	@Test
	void healthReturns200AndReportsDatabaseUp() throws Exception {
		mockMvc.perform(get("/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				// If Flyway or the datasource were misconfigured this drops to DOWN
				// while the endpoint itself still answers, so assert the component too.
				.andExpect(jsonPath("$.components.db.status").value("UP"));
	}

}
