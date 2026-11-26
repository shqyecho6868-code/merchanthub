package com.merchanthub.common.tenant.mybatis;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantContextMissingException;
import com.merchanthub.common.tenant.TenantInfo;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Rules for the MyBatis-Plus tenant plugin (C1): decides whether each SQL gets {@code tenant_id = ?} and with which value.
 * <p>
 * The rules only look at {@link TenantContext}:
 * <ul>
 *   <li>Has a tenantId (merchant request, MQ consumption restored as a merchant) → every business table gets tenant_id = it</li>
 *   <li>No tenantId but explicitly BUYER / PLATFORM_ADMIN / SYSTEM → no condition (buyers are filtered by buyer_id; platform and system jobs are cross-tenant by nature)</li>
 *   <li>No context on the thread at all → <b>throw (fail-closed)</b>: some entry point forgot to set the context, and we must not query the whole table unfiltered</li>
 * </ul>
 * Every table is a tenant table by default (a blocklist, not an allowlist): if a new business table is never registered, the result is "an error" rather than "missing filter".
 */
public class TenantSqlHandler implements TenantLineHandler {

    public static final String TENANT_COLUMN = "tenant_id";

    private final Set<String> ignoreTables;

    public TenantSqlHandler(Set<String> ignoreTables) {
        this.ignoreTables = ignoreTables.stream().map(t -> t.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    @Override
    public Expression getTenantId() {
        // We only get here when ignoreTable returned false, so the context is guaranteed to have a tenantId
        return new LongValue(TenantContext.requireTenantId());
    }

    @Override
    public String getTenantIdColumn() {
        return TENANT_COLUMN;
    }

    /** @return true = skip tenant_id for this table this time */
    @Override
    public boolean ignoreTable(String tableName) {
        if (ignoreTables.contains(unquote(tableName))) {
            return true;
        }
        TenantInfo info = TenantContext.get();
        if (info == null) {
            throw new TenantContextMissingException("No tenant context when accessing business table " + tableName + ": "
                    + "for web requests check that the gateway injected X-Role; for MQ consumers / scheduled jobs / thread pools check that TenantContext is set");
        }
        return info.tenantId() == null;
    }

    private static String unquote(String table) {
        return table.replace("`", "").toLowerCase(Locale.ROOT);
    }
}
