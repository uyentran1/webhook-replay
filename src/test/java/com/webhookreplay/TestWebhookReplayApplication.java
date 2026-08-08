package com.webhookreplay;

import org.springframework.boot.SpringApplication;

public class TestWebhookReplayApplication {

	public static void main(String[] args) {
		SpringApplication.from(WebhookReplayApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
