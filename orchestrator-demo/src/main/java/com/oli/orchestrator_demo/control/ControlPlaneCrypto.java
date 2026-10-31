/**
 * @ClassName ControlPlaneCrypto
 * @Description Field-level AES-GCM encryption for the secret-bearing fields of
 *              the control plane (currently the environment values of STDIO
 *              MCP servers — API keys and tokens a spawned child process
 *              needs). Values are stored with a versioned
 *              {@code enc:v1:<iv>:<ciphertext>} prefix so legacy plaintext
 *              values load unchanged and a future algorithm bump stays
 *              possible ({@code v2}); the in-memory state always holds
 *              plaintext — encryption is a persistence concern, decryption a
 *              load-time concern.
 *
 *              The key comes from the {@code ORCHESTRATOR_CRYPTO_KEY}
 *              environment variable (configurable) and is hashed with
 *              SHA-256 into an AES-256 key, so any passphrase length works.
 *              Without a key the crypto is a pass-through and secrets rest in
 *              plaintext (the default for the file backend) — the database
 *              backend warns loudly in that case, because reading raw
 *              secrets from the DB is exactly what this class prevents.
 *
 *              The same class owns the {@code ******} masking used by the
 *              HTTP surface: GET responses never carry real secret values;
 *              a masked value PUT back is restored from the previously
 *              stored state instead of overwriting the secret.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 2.0
 */
package com.oli.orchestrator_demo.control;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class ControlPlaneCrypto {

  /** Placeholder the HTTP surface returns instead of a secret value. */
  public static final String MASK = "******";
  /** Prefix of every encrypted value; the v1 payload is AES-256-GCM. */
  public static final String PREFIX = "enc:v1:";

  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  /** A crypto with pass-through behavior (no key material). */
  public static ControlPlaneCrypto disabled() {
    return new ControlPlaneCrypto(null);
  }

  /**
   * @param keyMaterial the raw key/passphrase, or null/blank to disable
   *                    encryption (values then persist as-is).
   */
  public ControlPlaneCrypto(String keyMaterial) {
    if (keyMaterial == null || keyMaterial.isBlank()) {
      this.key = null;
      return;
    }
    try {
      byte[] keyBytes = MessageDigest.getInstance("SHA-256")
          .digest(keyMaterial.getBytes(StandardCharsets.UTF_8));
      this.key = new SecretKeySpec(keyBytes, "AES");
    }
    catch (Exception ex) {
      throw new IllegalStateException("Failed to derive the control-plane crypto key", ex);
    }
  }

  public boolean enabled() {
    return key != null;
  }

  /** Encrypts one value (pass-through when disabled or already encrypted). */
  public String encrypt(String plaintext) {
    if (!enabled() || plaintext == null || plaintext.isBlank() || plaintext.startsWith(PREFIX)) {
      return plaintext;
    }
    try {
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return PREFIX + java.util.Base64.getEncoder().encodeToString(iv) + ":"
          + java.util.Base64.getEncoder().encodeToString(ciphertext);
    }
    catch (Exception ex) {
      throw new IllegalStateException("Failed to encrypt a control-plane secret", ex);
    }
  }

  /**
   * Decrypts one stored value; anything without the {@code enc:v1:} prefix is
   * returned unchanged (legacy plaintext from before the crypto existed).
   */
  public String decrypt(String stored) {
    if (!enabled() || stored == null || !stored.startsWith(PREFIX)) {
      return stored;
    }
    String[] parts = stored.substring(PREFIX.length()).split(":", 2);
    if (parts.length != 2) {
      throw new IllegalStateException("Malformed encrypted control-plane value");
    }
    try {
      byte[] iv = java.util.Base64.getDecoder().decode(parts[0]);
      byte[] ciphertext = java.util.Base64.getDecoder().decode(parts[1]);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }
    catch (Exception ex) {
      throw new IllegalStateException(
          "Failed to decrypt a control-plane secret (wrong ORCHESTRATOR_CRYPTO_KEY?)", ex);
    }
  }

  /** Encrypts every non-blank value of the map (order preserved). */
  public Map<String, String> encryptEnv(Map<String, String> env) {
    if (env.isEmpty()) {
      return env;
    }
    Map<String, String> encrypted = new LinkedHashMap<>();
    env.forEach((name, value) -> encrypted.put(name, encrypt(value)));
    return encrypted;
  }

  /** Decrypts every {@code enc:v1:}-prefixed value of the map (order preserved). */
  public Map<String, String> decryptEnv(Map<String, String> env) {
    if (env.isEmpty()) {
      return env;
    }
    Map<String, String> decrypted = new LinkedHashMap<>();
    env.forEach((name, value) -> decrypted.put(name, decrypt(value)));
    return decrypted;
  }

  /** Every non-blank value becomes {@link #MASK} (order preserved). */
  public static Map<String, String> maskEnv(Map<String, String> env) {
    if (env.isEmpty()) {
      return env;
    }
    Map<String, String> masked = new LinkedHashMap<>();
    env.forEach((name, value) -> masked.put(name, value == null || value.isBlank() ? value : MASK));
    return masked;
  }

  /**
   * Merges an incoming (possibly masked) env map with the previously stored
   * one: masked values keep the stored secret, everything else is taken as
   * the new value. Used by the PUT path so a console that echoes the masked
   * GET response back never destroys a secret.
   */
  public static Map<String, String> mergeMaskedEnv(Map<String, String> incoming, Map<String, String> previous) {
    if (incoming.isEmpty()) {
      return incoming;
    }
    Map<String, String> merged = new LinkedHashMap<>();
    incoming.forEach((name, value) -> {
      if (MASK.equals(value) && previous.containsKey(name)) {
        merged.put(name, previous.get(name));
      }
      else {
        merged.put(name, value);
      }
    });
    return merged;
  }
}
