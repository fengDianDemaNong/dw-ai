package com.dwai.platform.auth;

import com.dwai.platform.DwaiProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

@Component
public class LlmCrypto {
  private final byte[] key;
  private final SecureRandom random = new SecureRandom();

  public LlmCrypto(DwaiProperties props) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      this.key = md.digest(props.getLlmSecret().getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("llm secret", e);
    }
  }

  public byte[] encrypt(String plain) {
    if (plain == null || plain.isBlank()) return null;
    try {
      byte[] iv = new byte[12];
      random.nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      byte[] enc = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.allocate(iv.length + enc.length).put(iv).put(enc).array();
    } catch (Exception e) {
      throw new IllegalStateException("encrypt llm key", e);
    }
  }

  public String decrypt(byte[] packed) {
    if (packed == null || packed.length < 13) return null;
    try {
      ByteBuffer buf = ByteBuffer.wrap(packed);
      byte[] iv = new byte[12];
      buf.get(iv);
      byte[] enc = new byte[buf.remaining()];
      buf.get(enc);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      return new String(c.doFinal(enc), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("decrypt llm key", e);
    }
  }
}
