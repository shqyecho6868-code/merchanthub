package com.merchanthub.common.tenant.redis;

import com.merchanthub.common.tenant.Role;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantContextMissingException;
import com.merchanthub.common.tenant.TenantInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantKeySerializerTest {

    private final TenantKeySerializer serializer = new TenantKeySerializer();

    @Test
    @DisplayName("Same business key lands on different Redis keys for merchant 7 and merchant 8; prefix stripped on read-back")
    void prefixedPerTenant() {
        byte[] k7 = TenantContext.callAs(new TenantInfo(7L, 1L, Role.MERCHANT_ADMIN), () -> serializer.serialize("sku:101"));
        byte[] k8 = TenantContext.callAs(new TenantInfo(8L, 1L, Role.MERCHANT_ADMIN), () -> serializer.serialize("sku:101"));
        assertThat(new String(k7, StandardCharsets.UTF_8)).isEqualTo("t:7:sku:101");
        assertThat(new String(k8, StandardCharsets.UTF_8)).isEqualTo("t:8:sku:101");
        assertThat(TenantContext.callAs(TenantInfo.systemOf(7L), () -> serializer.deserialize(k7))).isEqualTo("sku:101");
    }

    @Test
    @DisplayName("No merchant context (including buyers) → throw")
    void requiresTenant() {
        assertThatThrownBy(() -> serializer.serialize("sku:101")).isInstanceOf(TenantContextMissingException.class);
        assertThatThrownBy(() -> TenantContext.runAs(new TenantInfo(null, 50001L, Role.BUYER), () -> serializer.serialize("x")))
                .isInstanceOf(TenantContextMissingException.class);
    }
}
