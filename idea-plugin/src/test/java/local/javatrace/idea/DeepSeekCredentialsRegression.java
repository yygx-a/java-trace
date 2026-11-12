package local.javatrace.idea;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import java.io.IOException;

/** 使用 SDK 接口的内存替身，不初始化真实 PasswordSafe，不接触用户凭据库。 */
public final class DeepSeekCredentialsRegression {
    private static int assertions;
    private static void check(boolean condition) { assertions++; if (!condition) throw new AssertionError("Credential persistence contract failed"); }
    private interface Call { void run() throws IOException; }
    private static void rejects(Call call) throws IOException {
        try { call.run(); throw new AssertionError("Expected storage rejection"); }
        catch (IOException expected) { check(!expected.getMessage().contains("synthetic-test-key")); }
    }
    public static void main(String[] args) throws Exception {
        Fake persistent = new Fake();
        check(DeepSeekCredentials.load(persistent) == null);
        DeepSeekCredentials.save(persistent, "synthetic-test-key");
        check(DeepSeekCredentials.load(persistent).equals("synthetic-test-key"));
        check(persistent.writes == 1);
        Fake memory = new Fake(); memory.memory = true;
        rejects(() -> DeepSeekCredentials.load(memory));
        rejects(() -> DeepSeekCredentials.save(memory, "synthetic-test-key"));
        check(memory.writes == 0);
        Fake readFallback = new Fake(); readFallback.fallbackOnRead = true;
        rejects(() -> DeepSeekCredentials.load(readFallback));
        Fake writeFallback = new Fake(); writeFallback.fallbackOnWrite = true;
        rejects(() -> DeepSeekCredentials.save(writeFallback, "synthetic-test-key"));
        Fake lost = new Fake(); lost.discardWrite = true;
        rejects(() -> DeepSeekCredentials.save(lost, "synthetic-test-key"));
        Fake helperOnly = new Fake(); helperOnly.helperOnly = true;
        rejects(() -> DeepSeekCredentials.save(helperOnly, "synthetic-test-key"));
        helperOnly.value = new Credentials(null, "synthetic-test-key");
        rejects(() -> DeepSeekCredentials.load(helperOnly));
        System.out.println("Credential persistence regression: " + assertions + " assertions passed; synthetic store only");
    }
    private static final class Fake extends PasswordSafe {
        boolean memory, fallbackOnRead, fallbackOnWrite, discardWrite, helperOnly;
        int writes;
        Credentials value;
        @Override public boolean isRememberPasswordByDefault() { return !memory; }
        @Override public void setRememberPasswordByDefault(boolean remember) { }
        @Override public boolean isMemoryOnly() { return memory; }
        @Override public Credentials get(CredentialAttributes attributes) { if (fallbackOnRead) memory = true; return value; }
        @Override public void set(CredentialAttributes attributes, Credentials credentials) {
            writes++; if (fallbackOnWrite) memory = true; if (!discardWrite) value = credentials;
        }
        @Override public void set(CredentialAttributes attributes, Credentials credentials, boolean memoryOnly) { set(attributes, credentials); }
        @Override public boolean isPasswordStoredOnlyInMemory(CredentialAttributes attributes, Credentials credentials) { return memory || helperOnly; }
    }
}
