package metadata;

import org.junit.Test;
import com.dwai.lineage.exception.MetadataConfigException;
import com.dwai.lineage.service.metadata.CredentialCipher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 凭据加解密。 */
public class CredentialCipherTest {

    private static final String KEY = "unit-test-secret-key-do-not-use-in-prod";

    @Test
    public void roundTrip() {
        CredentialCipher cipher = new CredentialCipher(KEY);
        String plain = "p@ssw0rd 含中文 & 符号";

        String encrypted = cipher.encrypt(plain);

        assertNotEquals("密文不能等于明文", plain, encrypted);
        assertFalse("密文中不得残留明文片段", encrypted.contains("p@ssw0rd"));
        assertEquals(plain, cipher.decrypt(encrypted));
    }

    /** GCM 每次用新的随机 IV，同一明文两次加密的密文必须不同，否则等于泄漏「密码没变」。 */
    @Test
    public void sameInputProducesDifferentCiphertext() {
        CredentialCipher cipher = new CredentialCipher(KEY);

        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"));
    }

    /** GCM 自带完整性校验：密文被改过应当解密失败，而不是解出一段垃圾拿去登录。 */
    @Test
    public void tamperedCiphertextIsRejected() {
        CredentialCipher cipher = new CredentialCipher(KEY);
        String encrypted = cipher.encrypt("secret");

        char[] chars = encrypted.toCharArray();
        chars[chars.length - 2] = chars[chars.length - 2] == 'A' ? 'B' : 'A';

        try {
            cipher.decrypt(new String(chars));
            fail("被篡改的密文不应解密成功");
        } catch (MetadataConfigException expected) {
            assertTrue(expected.getMessage().contains("解密失败"));
        }
    }

    @Test
    public void wrongKeyCannotDecrypt() {
        String encrypted = new CredentialCipher(KEY).encrypt("secret");

        try {
            new CredentialCipher("another-key").decrypt(encrypted);
            fail("换了密钥不应还能解出明文");
        } catch (MetadataConfigException expected) {
            // 提示里要告诉用户换密钥后需要重新录入，否则无从下手
            assertTrue(expected.getMessage().contains("METADATA_SECRET_KEY"));
        }
    }

    /**
     * 未配置密钥时不该在启动阶段就炸（本地起个 H2 试用也要先设环境变量太重），
     * 但一旦真的要加密就必须失败 —— 绝不能明文落库。
     */
    @Test
    public void missingKeyFailsLoudlyOnlyWhenUsed() {
        CredentialCipher cipher = new CredentialCipher("");

        assertFalse(cipher.isConfigured());
        try {
            cipher.encrypt("secret");
            fail("未配置密钥时不应加密成功");
        } catch (MetadataConfigException expected) {
            assertTrue("提示里要给出具体该设哪个环境变量",
                    expected.getMessage().contains("METADATA_SECRET_KEY"));
        }
    }

    @Test
    public void nullAndBlankPassThrough() {
        CredentialCipher cipher = new CredentialCipher(KEY);

        assertNull(cipher.encrypt(null));
        assertNull(cipher.encrypt(""));
        assertNull(cipher.decrypt(null));
        assertNull(cipher.decrypt("  "));
    }
}
