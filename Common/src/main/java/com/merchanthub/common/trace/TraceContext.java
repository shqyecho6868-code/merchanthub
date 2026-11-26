package com.merchanthub.common.trace;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 链路 traceId：一次请求从网关进来，经 HTTP 头 → MDC → Feign 头 → MQ 消息属性 → 消费者 MDC，
 * 全程用同一个 ID，日志按 traceId 一搜就能串起"一张订单"经过的所有服务。
 * <p>
 * 为什么手写而不上 Micrometer Tracing / OTel：本项目只要"日志能串起来"，不需要 span 树和采样上报；
 * 手写只有一个 MDC key + 三个透传点，没有新依赖，原理一眼能看懂。代价：没有 span 耗时、没有调用拓扑。
 * <p>
 * 和 {@code TenantContext} 一样只提供"用完还原"的 {@link #open} 写法：线程（Tomcat、MQ 消费、线程池）都是复用的，
 * 不还原的话下一个任务会顶着上一个请求的 traceId 打日志，排查时把两条毫不相干的链路串在一起。
 */
public final class TraceContext {

    /** 网关 / 服务间 HTTP 头 */
    public static final String HEADER = "X-Trace-Id";
    /** 日志 MDC key，日志格式里用 %X{traceId} 打出来 */
    public static final String MDC_KEY = "traceId";
    /** RocketMQ 消息的 user property 名（消息没有请求头，和 tenantId 一样放在属性里） */
    public static final String MQ_PROPERTY = "traceId";

    /**
     * 外部传进来的 traceId 只接受这种格式：既能和上游（如 Nginx / 客户端）对齐，又不让任意字符串（换行、超长）
     * 进日志 —— 日志注入、撑爆日志行都靠这一步挡掉。不合法就当没带，重新生成
     */
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private TraceContext() {
    }

    /** 当前线程的 traceId；没有返回 null */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static boolean isValid(String traceId) {
        return traceId != null && VALID.matcher(traceId).matches();
    }

    /** 32 位十六进制（UUID 去掉横线），和 W3C traceparent 的 trace-id 长度一致 */
    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 合法就沿用，否则新生成 */
    public static String reuseOrNew(String candidate) {
        return isValid(candidate) ? candidate : newId();
    }

    /**
     * 把 traceId（不合法 / 为空就新生成）放进 MDC，返回的 Scope 关闭时还原成之前的值（之前没有就 remove）。
     * 用还原而不是直接 remove：比如 MQ 消费里又嵌套了一段带 traceId 的处理，结束后要回到外层的 traceId
     */
    public static Scope open(String traceId) {
        String previous = MDC.get(MDC_KEY);
        String id = reuseOrNew(traceId);
        MDC.put(MDC_KEY, id);
        return new Scope(previous, id);
    }

    /** try-with-resources 用：close 时还原 */
    public static final class Scope implements AutoCloseable {
        private final String previous;
        private final String traceId;
        private boolean closed;

        private Scope(String previous, String traceId) {
            this.previous = previous;
            this.traceId = traceId;
        }

        /** 本次生效的 traceId */
        public String traceId() {
            return traceId;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }
}
