package com.merchanthub.common.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JWT secret from config + strict Base64 */
class JwtCodecTest {

    private static final String SECRET = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    @Test
    @DisplayName("Issued token parses back to userId / role / tenantId")
    void issueAndParse() {
        JwtCodec codec = new JwtCodec(SECRET);
        Claims c = codec.parse(codec.issue(2001L, 7L, "MERCHANT_ADMIN", Duration.ofMinutes(5)));
        assertThat(c.getSubject()).isEqualTo("2001");
        assertThat(c.get(JwtCodec.CLAIM_ROLE)).isEqualTo("MERCHANT_ADMIN");
        assertThat(c.get(JwtCodec.CLAIM_TENANT_ID)).isEqualTo("7");
    }

    @Test
    @DisplayName("Buyer token carries no tenantId")
    void buyerHasNoTenant() {
        JwtCodec codec = new JwtCodec(SECRET);
        assertThat(codec.parse(codec.issue(1L, null, "BUYER", Duration.ofMinutes(5))).get(JwtCodec.CLAIM_TENANT_ID)).isNull();
    }

    @Test
    @DisplayName("Signed with another key / tampered / expired / empty → null")
    void rejectsBadTokens() {
        JwtCodec codec = new JwtCodec(SECRET);
        JwtCodec other = new JwtCodec(Base64.getEncoder().encodeToString("ffffffffffffffffffffffffffffffff".getBytes()));
        String token = codec.issue(1L, null, "BUYER", Duration.ofMinutes(5));

        assertThat(other.parse(token)).isNull();
        // Tamper with a character in the middle of the signature. Not the last one: in base64url of a 32-byte signature the last character has only 4 significant bits and 2 padding bits,
        // so the old "replace the last two chars with xx" had no effect when the signature happened to end in an equivalent character (~1/1000), making CI flaky
        int i = token.lastIndexOf('.') + 5;
        char tampered = token.charAt(i) == 'A' ? 'B' : 'A';
        assertThat(codec.parse(token.substring(0, i) + tampered + token.substring(i + 1))).isNull();
        assertThat(codec.parse(codec.issue(1L, null, "BUYER", Duration.ofMillis(-1000)))).isNull();
        assertThat(codec.parse("")).isNull();
        assertThat(codec.parse(null)).isNull();
        assertThat(codec.parse("not-a-jwt")).isNull();
    }

    @Test
    @DisplayName("Old hard-coded secret (62 chars, no = padding) fails at startup")
    void rejectsUnpaddedLegacyKey() {
        String legacy = "shanyangcodebackendInfiniteChatshanyangcodebackendInfiniteChat";
        assertThatThrownBy(() -> new JwtCodec(legacy)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("multiple of 4");
    }

    @Test
    @DisplayName("Missing / illegal characters / too short → fail at startup rather than settle for a weak secret")
    void rejectsMissingOrWeakKey() {
        assertThatThrownBy(() -> new JwtCodec(null)).hasMessageContaining("not configured");
        assertThatThrownBy(() -> new JwtCodec("  ")).hasMessageContaining("not configured");
        assertThatThrownBy(() -> new JwtCodec("!!!!")).hasMessageContaining("Base64");
        assertThatThrownBy(() -> new JwtCodec(Base64.getEncoder().encodeToString(new byte[16]))).hasMessageContaining("32 bytes");
    }
}
