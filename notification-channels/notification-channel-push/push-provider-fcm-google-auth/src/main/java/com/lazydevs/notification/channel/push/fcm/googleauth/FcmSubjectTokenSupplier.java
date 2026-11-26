package com.lazydevs.notification.channel.push.fcm.googleauth;

import java.io.IOException;

/**
 * Supplies the subject token for workload identity federation without a credential
 * configuration file (DD-37): {@code credentials: external-account-supplier:<name>}.
 *
 * <p>Declare an implementation as a Spring bean. Its {@link #name()} is what the tenant's
 * {@code credentials} value names; the pool, provider and service account it federates into
 * are its own configuration. The Google auth library calls {@link #subjectToken()} whenever
 * it needs a new FCM access token, exchanges the subject token at the Security Token
 * Service, and, with {@link #serviceAccountEmail()}, impersonates that service account.
 * The SPI is cloud-neutral: a supplier may return an Azure managed identity token, an AWS
 * web identity token, a Kubernetes service account token or any other OIDC or SAML token
 * the pool trusts.
 *
 * <p>Implementations must be thread-safe, return a fresh (unexpired) token on every call and
 * never log it. An {@link IOException} fails the token fetch, which the send reports as a
 * failure without sending.
 *
 * @since 1.4.0
 */
public interface FcmSubjectTokenSupplier {

    /** Google's Security Token Service endpoint. */
    String DEFAULT_STS_TOKEN_URL = "https://sts.googleapis.com/v1/token";

    /** Google's IAM Credentials endpoint, which impersonates service accounts. */
    String DEFAULT_IAM_CREDENTIALS_URL = "https://iamcredentials.googleapis.com";

    /** The subject token type of an OIDC ID token or JWT. */
    String JWT_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:jwt";

    /**
     * @return the name a tenant's {@code credentials: external-account-supplier:<name>} uses;
     *         unique among the suppliers of the application
     */
    String name();

    /**
     * @return the workload identity provider audience,
     *         {@code //iam.googleapis.com/projects/<number>/locations/global/workloadIdentityPools/<pool>/providers/<provider>}
     */
    String audience();

    /**
     * @return the type of the subject token; {@value #JWT_TOKEN_TYPE} by default
     */
    default String subjectTokenType() {
        return JWT_TOKEN_TYPE;
    }

    /**
     * @return the email of the service account to impersonate, or {@code null} to use the
     *         federated token directly (direct resource access)
     */
    default String serviceAccountEmail() {
        return null;
    }

    /**
     * @return the URL that impersonates {@link #serviceAccountEmail()}, or {@code null} without
     *         one; by default the {@code generateAccessToken} method of
     *         {@value #DEFAULT_IAM_CREDENTIALS_URL}
     */
    default String serviceAccountImpersonationUrl() {
        String email = serviceAccountEmail();
        return email == null || email.isBlank() ? null : DEFAULT_IAM_CREDENTIALS_URL
                + "/v1/projects/-/serviceAccounts/" + email.strip() + ":generateAccessToken";
    }

    /**
     * @return the Security Token Service endpoint; {@value #DEFAULT_STS_TOKEN_URL} by default
     */
    default String tokenUrl() {
        return DEFAULT_STS_TOKEN_URL;
    }

    /**
     * @return a fresh subject token, never {@code null}
     * @throws IOException when no token can be obtained
     */
    String subjectToken() throws IOException;
}
