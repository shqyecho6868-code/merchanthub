package com.merchanthub.common.tenant.mybatis;

import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.merchanthub.common.tenant.Role;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantContextMissingException;
import com.merchanthub.common.tenant.TenantInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the MyBatis-Plus tenant plugin directly and inspects the rewritten SQL (no database).
 * The "AND tenant_id = 7" seen in the logs during the 🧑 hands-on experiment is added by this step.
 */
class TenantSqlHandlerTest {

    private final TenantLineInnerInterceptor plugin =
            new TenantLineInnerInterceptor(new TenantSqlHandler(Set.of("flyway_schema_history")));

    private String rewrite(String sql) {
        return plugin.parserSingle(sql, null);
    }

    @Test
    @DisplayName("商家 7 查订单：WHERE 后面自动追加 tenant_id = 7")
    void merchantSelect() {
        String sql = TenantContext.callAs(new TenantInfo(7L, 2001L, Role.MERCHANT_ADMIN),
                () -> rewrite("SELECT * FROM orders WHERE order_no = ?"));
        assertThat(sql).isEqualTo("SELECT * FROM orders WHERE order_no = ? AND tenant_id = 7");
    }

    @Test
    @DisplayName("UPDATE / JOIN 也会带上：改不到、连不到别的商家的行")
    void updateAndJoin() {
        TenantContext.runAs(TenantInfo.systemOf(8L), () -> {
            assertThat(rewrite("UPDATE sku SET price = ? WHERE id = ?"))
                    .isEqualTo("UPDATE sku SET price = ? WHERE id = ? AND tenant_id = 8");
            assertThat(rewrite("SELECT s.id FROM sku s JOIN product p ON p.id = s.product_id WHERE s.id = ?"))
                    .contains("s.tenant_id = 8").contains("p.tenant_id = 8");
        });
    }

    @Test
    @DisplayName("买家 / 平台管理员 / 系统任务：不拼 tenant_id（买家按 buyer_id 在代码里过滤）")
    void bypassRoles() {
        String original = "SELECT * FROM orders WHERE order_no = ?";
        for (TenantInfo info : new TenantInfo[]{new TenantInfo(null, 50001L, Role.BUYER),
                new TenantInfo(null, 1L, Role.PLATFORM_ADMIN), TenantInfo.system()}) {
            assertThat(TenantContext.callAs(info, () -> rewrite(original))).isEqualTo(original);
        }
    }

    @Test
    @DisplayName("fail-closed：线程上没有任何上下文就访问业务表 → 直接报错，不裸查全表")
    void noContextFails() {
        assertThatThrownBy(() -> rewrite("SELECT * FROM orders WHERE order_no = 1"))
                .isInstanceOf(TenantContextMissingException.class);
    }

    @Test
    @DisplayName("登记为非租户表的不拼")
    void ignoredTable() {
        String sql = "SELECT * FROM flyway_schema_history";
        assertThat(rewrite(sql)).isEqualTo(sql);
    }
}
