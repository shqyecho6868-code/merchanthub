package com.merchanthub.common.job;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * XXL-Job 执行器配置（E3）。每个服务是一个"执行器"：启动时向调度中心注册自己的地址，
 * 调度中心按 Cron 选一台执行器、通过 HTTP 调它的 @XxlJob 方法。
 */
@Data
@ConfigurationProperties(prefix = "merchanthub.job.xxl")
public class XxlJobProperties {

    /** 默认关：测试、没起调度中心的本地环境不注册执行器；dev 配置里打开 */
    private boolean enabled = false;
    /** 调度中心地址，多个用逗号分隔，如 http://127.0.0.1:8090/xxl-job-admin */
    private String adminAddresses;
    /** 调度中心与执行器之间的通信令牌，两边必须一致（防止任何人都能调执行器触发任务） */
    private String accessToken;
    /** 执行器 AppName：调度中心按它把同名实例归成一组（多实例 = 同一个 AppName 多个地址）。默认取服务名 */
    private String appname;
    /**
     * 执行器对调度中心公布的回调地址；为空时用"本机 IP:port"。本地开发调度中心在容器里、服务在宿主机上，
     * 要填 http://host.docker.internal:{port}/，否则调度中心可能连不回来
     */
    private String address;
    /** 执行器内嵌 HTTP 服务端口：同一台机器跑多个服务时必须各不相同 */
    private int port;
    /** 任务执行日志目录：默认 /data/applogs 在 Mac 上没有写权限，改到临时目录 */
    private String logPath = System.getProperty("java.io.tmpdir") + "/xxl-job";
    private int logRetentionDays = 7;
}
