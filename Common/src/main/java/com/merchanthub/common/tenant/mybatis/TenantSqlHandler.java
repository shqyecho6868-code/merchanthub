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
 * MyBatis-Plus 租户插件的规则（C1）：决定每条 SQL 要不要拼 {@code tenant_id = ?}、拼哪个值。
 * <p>
 * 规则只看 {@link TenantContext}：
 * <ul>
 *   <li>有 tenantId（商家请求、按商家恢复的 MQ 消费）→ 所有业务表都拼 tenant_id = 它</li>
 *   <li>没有 tenantId，但明确是 BUYER / PLATFORM_ADMIN / SYSTEM → 不拼（买家按 buyer_id 过滤，平台和系统任务本来就跨租户）</li>
 *   <li>线程上根本没有上下文 → <b>抛异常（fail-closed）</b>：说明某个入口忘了设置上下文，不能不带条件查全表</li>
 * </ul>
 * 默认所有表都是租户表（黑名单而不是白名单）：以后新加一张业务表忘了登记，结果是"报错"而不是"漏过滤"。
 */
public class TenantSqlHandler implements TenantLineHandler {

    public static final String TENANT_COLUMN = "tenant_id";

    private final Set<String> ignoreTables;

    public TenantSqlHandler(Set<String> ignoreTables) {
        this.ignoreTables = ignoreTables.stream().map(t -> t.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    @Override
    public Expression getTenantId() {
        // 只有 ignoreTable 返回 false 时才会走到这里，此时上下文里一定有 tenantId
        return new LongValue(TenantContext.requireTenantId());
    }

    @Override
    public String getTenantIdColumn() {
        return TENANT_COLUMN;
    }

    /** @return true = 这张表这次不拼 tenant_id */
    @Override
    public boolean ignoreTable(String tableName) {
        if (ignoreTables.contains(unquote(tableName))) {
            return true;
        }
        TenantInfo info = TenantContext.get();
        if (info == null) {
            throw new TenantContextMissingException("访问业务表 " + tableName + " 时没有租户上下文："
                    + "Web 请求检查网关是否注入了 X-Role；MQ 消费 / 定时任务 / 线程池检查是否设置了 TenantContext");
        }
        return info.tenantId() == null;
    }

    private static String unquote(String table) {
        return table.replace("`", "").toLowerCase(Locale.ROOT);
    }
}
