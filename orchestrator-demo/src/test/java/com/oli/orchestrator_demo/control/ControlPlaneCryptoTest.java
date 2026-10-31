/**
 * @ClassName ControlPlaneCryptoTest
 * @Description Verifies the AES-GCM field crypto: round-trip with random IVs,
 *              legacy plaintext passthrough on decrypt, pass-through when no
 *              key is configured, tamper detection and the mask/merge helpers
 *              that close the GET-masked → PUT-restored loop.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 2.0
 */
package com.oli.orchestrator_demo.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ControlPlaneCryptoTest {

  private final ControlPlaneCrypto crypto = new ControlPlaneCrypto("test-passphrase");

  @Test
  void roundTripsWithRandomIvPerValue() {
    String first = crypto.encrypt("super-secret-token");
    String second = crypto.encrypt("super-secret-token");

    assertThat(first).startsWith("enc:v1:").isNotEqualTo(second); // random IV
    assertThat(crypto.decrypt(first)).isEqualTo("super-secret-token");
    assertThat(crypto.decrypt(second)).isEqualTo("super-secret-token");
    assertThat(first).doesNotContain("super-secret");
  }

  @Test
  void passesLegacyPlaintextThroughOnDecrypt() {
    assertThat(crypto.decrypt("plain-old-value")).isEqualTo("plain-old-value");
  }

  @Test
  void isPassThroughWithoutKey() {
    ControlPlaneCrypto disabled = ControlPlaneCrypto.disabled();
    assertThat(disabled.enabled()).isFalse();
    assertThat(disabled.encrypt("value")).isEqualTo("value");
    assertThat(disabled.decrypt("value")).isEqualTo("value");
  }

  @Test
  void rejectsTamperedCiphertext() {
    String stored = crypto.encrypt("secret");
    String tampered = stored.substring(0, stored.length() - 2) + "xx";
    assertThatThrownBy(() -> crypto.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void masksAndRestoresEnvValues() {
    // Every env value of a spawned process is treated as potentially secret.
    Map<String, String> env = Map.of("API_KEY", "abc123", "MODE", "debug");

    Map<String, String> masked = ControlPlaneCrypto.maskEnv(env);
    assertThat(masked).containsEntry("API_KEY", "******").containsEntry("MODE", "******");

    Map<String, String> restored = ControlPlaneCrypto.mergeMaskedEnv(masked, env);
    assertThat(restored).containsEntry("API_KEY", "abc123").containsEntry("MODE", "debug");

    // A genuinely new value passes through the merge untouched.
    Map<String, String> edited = ControlPlaneCrypto.mergeMaskedEnv(Map.of("API_KEY", "new-token"), env);
    assertThat(edited).containsEntry("API_KEY", "new-token");
  }

  @Test
  void encryptsAndDecryptsEnvMaps() {
    Map<String, String> stored = crypto.encryptEnv(Map.of("TOKEN", "t0p-secret"));
    assertThat(stored.get("TOKEN")).startsWith("enc:v1:");
    assertThat(crypto.decryptEnv(stored)).containsEntry("TOKEN", "t0p-secret");
  }
}
