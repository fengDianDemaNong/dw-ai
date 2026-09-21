package com.dwai.lineage.service.metadata;

import com.dwai.lineage.exception.MetadataConfigException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 元数据服务凭据的加解密。
 *
 * <p>算法 AES-256-GCM。选 GCM 而不是 CBC 是因为它自带完整性校验：
 * 库里的密文被改过会直接解密失败，而不是悄悄解出一段垃圾再拿去登录。
 *
 * <p>主密钥从环境变量 {@code METADATA_SECRET_KEY} 注入，<b>不落配置文件、不进代码库</b>。
 * 未配置时本类仍能构造（否则本地起个 H2 试用都要先设环境变量），但
 * {@link #encrypt(String)} / {@link #decrypt(String)} 会抛出带明确指引的异常 ——
 * 宁可保存失败，也不能明文落库。
 *
 * <p>存储格式：{@code Base64(iv ‖ ciphertext ‖ tag)}。IV 每次随机生成并随密文存放，
 * GCM 下重复使用同一个 IV 会直接泄漏密钥流，所以绝不能固定。
 */
@Component
public class CredentialCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private static final String MISSING_KEY_HINT =
            "未配置凭据加密密钥，无法保存或使用带凭据的元数据服务。"
                    + "请设置环境变量 METADATA_SECRET_KEY（任意长度的随机字符串，"
                    + "建议 32 字符以上）后重启服务。";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(@Value("${metadata.secret-key:}") String secretKey) {
        this.key = (secretKey == null || secretKey.isBlank()) ? null : deriveKey(secretKey);
    }

    /** 密钥是否已配置。用于在页面上提前提示，而不是等用户填完表单才报错。 */
    public boolean isConfigured() {
        return key != null;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return null;
        }
        requireKey();
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (MetadataConfigException e) {
            throw e;
        } catch (Exception e) {
            // 不把异常消息往上抛：它可能包含密钥长度等信息
            throw new IllegalStateException("凭据加密失败", e);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return null;
        }
        requireKey();
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            if (combined.length <= IV_LENGTH) {
                throw new IllegalStateException("密文长度不足");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plain = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (MetadataConfigException e) {
            throw e;
        } catch (Exception e) {
            throw new MetadataConfigException(
                    "凭据解密失败。若近期更换过 METADATA_SECRET_KEY，需要重新录入各元数据服务的凭据", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            // 用 MetadataConfigException 而不是 IllegalStateException：
            // 后者会落到全局兜底，被替换成「服务内部错误，请联系管理员」，
            // 上面这句唯一有用的指引就传不到页面上了
            throw new MetadataConfigException(MISSING_KEY_HINT);
        }
    }

    /**
     * 把任意长度的用户密钥摊平成 256 位。
     *
     * <p>用 SHA-256 而不是直接截断，是为了让用户可以随便写一串字符当密钥，
     * 不必自己保证正好 16/24/32 字节。
     */
    private static SecretKeySpec deriveKey(String secretKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new SecretKeySpec(digest.digest(secretKey.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("派生凭据加密密钥失败", e);
        }
    }
}
