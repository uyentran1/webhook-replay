package com.webhookreplay;

/**
 * Fixture credentials, declared by the tests that use them.
 *
 * <p>They live here rather than in {@code application.properties} because Boot <em>merges</em>
 * {@code Map}-typed properties across sources instead of replacing them: a key committed to
 * the packaged configuration cannot be removed by an operator supplying their own, it can
 * only be added to. A test fixture in the shipped artifact would be a permanent backdoor.
 *
 * <p>All {@code static final String} concatenations of constants, so they remain compile-time
 * constants and can be used in {@code @SpringBootTest(properties = ...)}.
 */
final class TestApiKeys {

	static final String ALICE = "sk_test_alice";

	static final String BOB = "sk_test_bob";

	static final String ALICE_TENANT = "11111111-1111-1111-1111-111111111111";

	static final String BOB_TENANT = "22222222-2222-2222-2222-222222222222";

	static final String ALICE_PROPERTY = "webhook-replay.api-keys[" + ALICE + "]=" + ALICE_TENANT;

	static final String BOB_PROPERTY = "webhook-replay.api-keys[" + BOB + "]=" + BOB_TENANT;

	private TestApiKeys() {
	}

}
