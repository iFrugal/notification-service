package com.lazydevs.notification.channel.push.fcm.googleauth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One Application Default Credentials setup for the whole test JVM.
 *
 * <p>The Google auth library resolves ADC once per JVM and caches it, so every test that
 * uses {@code adc} shares this fixture: one stub server and one service-account key whose
 * {@code token_uri} is {@code https://oauth2.adc.invalid/token} (a test transport sends it
 * to the stub). The key file is found through:
 * <ul>
 *   <li>{@code GOOGLE_APPLICATION_CREDENTIALS}, which the module's Surefire configuration
 *       sets to a file under {@code target/} (the system property {@code fcm.test.adc-file}
 *       names the same file, so real credentials of a developer are never overwritten);</li>
 *   <li>otherwise (an IDE run) the gcloud well-known file under a temporary
 *       {@code user.home}.</li>
 * </ul>
 */
final class AdcFixture {

    static final String TOKEN_HOST = "oauth2.adc.invalid";

    private static AdcFixture instance;

    private final GoogleStubServer stub;

    private AdcFixture(GoogleStubServer stub) {
        this.stub = stub;
    }

    static synchronized AdcFixture get() {
        if (instance == null) {
            Path file = credentialsFile();
            GoogleStubServer stub = new GoogleStubServer();
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, stub.serviceAccountJson("https://" + TOKEN_HOST + "/token"));
            } catch (IOException e) {
                stub.close();
                throw new UncheckedIOException(e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(stub::close));
            instance = new AdcFixture(stub);
        }
        return instance;
    }

    private static Path credentialsFile() {
        String env = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
        if (env != null && !env.isBlank()) {
            assumeTrue(env.equals(System.getProperty("fcm.test.adc-file")),
                    "GOOGLE_APPLICATION_CREDENTIALS names other credentials; ADC tests skipped");
            return Path.of(env);
        }
        assumeTrue(System.getenv("CLOUDSDK_CONFIG") == null, "CLOUDSDK_CONFIG is set; ADC tests skipped");
        try {
            Path home = Files.createTempDirectory("fcm-adc-home");
            System.setProperty("user.home", home.toString());
            return home.resolve(".config").resolve("gcloud").resolve("application_default_credentials.json");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    GoogleStubServer stub() {
        return stub;
    }
}
