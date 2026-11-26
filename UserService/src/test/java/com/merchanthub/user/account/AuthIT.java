package com.merchanthub.user.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchanthub.common.constant.CommonConstant;
import com.merchanthub.common.security.JwtCodec;
import com.merchanthub.user.UserItSupport;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** New login (BCrypt + configurable JWT secret), token written to Redis for the gateway to check */
class AuthIT extends UserItSupport {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private JwtCodec jwt;
    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void clean() {
        cleanBusinessTables();
    }

    private JsonNode login(String username, String password) throws Exception {
        String body = mvc.perform(post("/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return om.readTree(body);
    }

    @Test
    @DisplayName("Buyer register -> login: token signed with the configured secret, identity fields correct, written to Redis; DB stores BCrypt, not plaintext")
    void registerAndLogin() throws Exception {
        mvc.perform(post("/user/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"buyer_01\",\"password\":\"password123\"}"))
                .andExpect(jsonPath("$.code").value(200));

        String hash = jdbc.queryForObject("SELECT password_hash FROM account WHERE username = 'buyer_01'", String.class);
        assertThat(hash).startsWith("$2a$").doesNotContain("password123");

        JsonNode r = login("buyer_01", "password123");
        assertThat(r.at("/code").asInt()).isEqualTo(200);
        String access = r.at("/data/accessToken").asText();
        Claims c = jwt.parse(access);
        assertThat(c.get(JwtCodec.CLAIM_ROLE)).isEqualTo("BUYER");
        assertThat(c.get(JwtCodec.CLAIM_TENANT_ID)).isNull();
        assertThat(redis.opsForValue().get(CommonConstant.ACCESS_TOKEN_PREFIX + c.getSubject())).isEqualTo(access);
    }

    @Test
    @DisplayName("Merchant admin login: token carries tenantId; can't log in after the merchant is suspended")
    void merchantLogin_tenantClaim_andDisabledTenant() throws Exception {
        insertTenant(7, "Merchant7", 1);
        insertTenant(8, "Merchant8", 0);
        insertAccount(7L, "m7admin", "password123", "MERCHANT_ADMIN");
        insertAccount(8L, "m8admin", "password123", "MERCHANT_ADMIN");

        JsonNode r = login("m7admin", "password123");
        assertThat(jwt.parse(r.at("/data/accessToken").asText()).get(JwtCodec.CLAIM_TENANT_ID)).isEqualTo("7");
        assertThat(r.at("/data/tenantId").asLong()).isEqualTo(7);

        assertThat(login("m8admin", "password123").at("/code").asInt()).isEqualTo(70005);
    }

    @Test
    @DisplayName("Wrong password / unknown user return the same error code; duplicate registration 70001")
    void wrongPassword_unknownUser_duplicate() throws Exception {
        insertAccount(null, "buyer_02", "password123", "BUYER");
        assertThat(login("buyer_02", "wrong-password").at("/code").asInt()).isEqualTo(70005);
        assertThat(login("nobody", "password123").at("/code").asInt()).isEqualTo(70005);

        mvc.perform(post("/user/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"buyer_02\",\"password\":\"password123\"}"))
                .andExpect(jsonPath("$.code").value(70001));
    }

    @Test
    @DisplayName("Refresh-Token exchanges for a new token; the old refresh token is invalid once replaced (401)")
    void refresh() throws Exception {
        insertAccount(null, "buyer_03", "password123", "BUYER");
        String refresh = login("buyer_03", "password123").at("/data/refreshToken").asText();
        Thread.sleep(1100);   // JWT iat has second precision: a new token signed within the same second is identical to the old one

        String body = mvc.perform(post("/user/refresh").header("Refresh-Token", refresh))
                .andExpect(jsonPath("$.code").value(200)).andReturn().getResponse().getContentAsString();
        String newRefresh = om.readTree(body).at("/data/refreshToken").asText();
        assertThat(newRefresh).isNotEqualTo(refresh);

        mvc.perform(post("/user/refresh").header("Refresh-Token", refresh)).andExpect(status().isUnauthorized());
        mvc.perform(post("/user/refresh").header("Refresh-Token", "garbage")).andExpect(status().isUnauthorized());
    }
}
