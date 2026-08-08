package com.webhookreplay;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		// Pinned to match docker-compose. `latest` would make test results depend on
		// when the image was last pulled, so a green build wouldn't be reproducible.
		return new PostgreSQLContainer(DockerImageName.parse("postgres:16"));
	}

}
