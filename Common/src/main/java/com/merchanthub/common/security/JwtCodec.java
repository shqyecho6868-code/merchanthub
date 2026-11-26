package com.merchanthub.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

import java.security.Key;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;

/**
 * JWT issuing and verification (D1: replaces the old static JwtUtil).
 * <p>
 * Two differences from the old code:
 * <ol>
 *   <li><b>The secret comes from config</b> ({@code merchanthub.jwt.secret}) instead of being hard-coded in CommonConstant:
 *       hard-coded = anyone who can see the repo can mint a token for any identity, and dev / prod can't use different secrets</li>
 *   <li><b>Only padded standard Base64 is accepted</b>: the old secret had 62 characters with no "=" padding; jjwt's lenient decoder yields one extra byte,
 *       while openssl / base64 drop it, so the two sides computed different signatures (P26). Here a length that isn't a multiple of 4 fails at startup</li>
 * </ol>
 * The gateway only calls {@link #parse}; only user-tenant calls {@link #issue}.
 */
@Slf4j
public final class JwtCodec {

    /** Identity claim names in the JWT (C1). The gateway turns them into X-Tenant-Id / X-Role headers after parsing */
    public static final String CLAIM_TENANT_ID = "tenantId";
    public static final String CLAIM_ROLE = "role";

    /** HS256 needs a key of at least 256 bits, otherwise jjwt rejects it; fail early here with a readable message */
    static final int MIN_KEY_BYTES = 32;

    private final Key key;

    public JwtCodec(String base64Secret) {
        this.key = Keys.hmacShaKeyFor(decodeStrict(base64Secret));
    }

    static byte[] decodeStrict(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("merchanthub.jwt.secret is not configured (locally it lives in JWT_SECRET in .env)");
        }
        String s = secret.trim();
        if (s.length() % 4 != 0) {
            throw new IllegalArgumentException("merchanthub.jwt.secret must be padded standard Base64 (length a multiple of 4, padded with =), "
                    + "current length " + s.length() + ". Generate one with: openssl rand -base64 32");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("merchanthub.jwt.secret is not valid standard Base64", e);
        }
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("merchanthub.jwt.secret decodes to only " + bytes.length + " bytes; HS256 needs at least 32 bytes");
        }
        return bytes;
    }

    /**
     * Issue a token carrying identity: subject = userId, plus role, plus tenantId for merchant roles.
     * Identity lives inside a signed token so the client can't alter it; the gateway trusts only the token, never headers the client sends itself.
     *
     * @param tenantId required for merchant roles; null for buyers / platform admins
     */
    public String issue(long userId, Long tenantId, String role, Duration ttl) {
        Date now = new Date();
        JwtBuilder builder = Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim(CLAIM_ROLE, role)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + ttl.toMillis()))
                .signWith(key, SignatureAlgorithm.HS256);
        if (tenantId != null) {
            builder.claim(CLAIM_TENANT_ID, String.valueOf(tenantId));
        }
        return builder.compact();
    }

    /** Valid signature and not expired → claims; otherwise null (callers only need "can I trust it", not why; the reason goes to the log) */
    public Claims parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
        } catch (ExpiredJwtException e) {
            log.debug("JWT expired: {}", e.getMessage());
            return null;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("JWT has an invalid signature or is malformed: {}", e.getMessage());
            return null;
        }
    }
}
