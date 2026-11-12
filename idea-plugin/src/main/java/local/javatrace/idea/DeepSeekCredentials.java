package local.javatrace.idea;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import java.io.IOException;

/** 所有方法均由后台线程调用；Key 不进入项目状态、记录或日志。 */
final class DeepSeekCredentials {
    private static final CredentialAttributes ATTRIBUTES = new CredentialAttributes(
            CredentialAttributesKt.generateServiceName("Java Trace", "DeepSeek API Key"));
    private DeepSeekCredentials() {}
    static String load() throws IOException { return load(PasswordSafe.getInstance()); }
    static String load(PasswordSafe safe) throws IOException {
        // getPassword 可触发提供者初始化；KeePass 失败后平台可能自动退回 MEMORY_ONLY。
        String key = safe.getPassword(ATTRIBUTES);
        requirePersistent(safe, key);
        return key;
    }
    static void save(String key) throws IOException { save(PasswordSafe.getInstance(), key); }
    static void save(PasswordSafe safe, String key) throws IOException {
        requirePersistent(safe, null);
        safe.setPassword(ATTRIBUTES, key);
        String restored = safe.getPassword(ATTRIBUTES);
        requirePersistent(safe, restored);
        if (!key.equals(restored)) throw new IOException("未能确认 API Key 已存入凭据库，未发送请求。请检查 IDEA Passwords 设置。");
    }
    private static void requirePersistent(PasswordSafe safe, String key) throws IOException {
        if (safe.isMemoryOnly() || (key != null && safe.isPasswordStoredOnlyInMemory(ATTRIBUTES, new Credentials(null, key))))
            throw new IOException("IDEA 凭据库目前只在内存保存，重启会丢失。请先修复 Passwords 中的 KeePass 配置；未发送请求。");
    }
}
