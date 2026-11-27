package com.merchanthub.common.job;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * XXL-Job executor settings. Each service is an "executor": on startup it registers its address with the admin,
 * and the admin picks an executor per Cron trigger and calls its @XxlJob method over HTTP.
 */
@Data
@ConfigurationProperties(prefix = "merchanthub.job.xxl")
public class XxlJobProperties {

    /** Off by default: tests and local setups without an admin don't register an executor; enabled in the dev profile */
    private boolean enabled = false;
    /** Admin address(es), comma-separated, e.g. http://127.0.0.1:8090/xxl-job-admin */
    private String adminAddresses;
    /** Access token between admin and executor; must match on both sides (so not just anyone can call the executor to trigger jobs) */
    private String accessToken;
    /** Executor AppName: the admin groups instances with the same name (multiple instances = one AppName, several addresses). Defaults to the service name */
    private String appname;
    /**
     * Callback address the executor advertises to the admin; empty means "local IP:port". In local dev the admin runs in a container and the service on the host,
     * so set it to http://host.docker.internal:{port}/, otherwise the admin may not be able to reach back
     */
    private String address;
    /**
     * Pick the executor IP from the local interface whose address starts with this prefix (e.g. "10.20."), when address is empty.
     * A Fargate task also has a 169.254.172.x link-local interface; XXL-Job's own IP detection may register that one, which the admin can't reach
     * (same problem as Nacos, solved there by spring.cloud.inetutils.preferred-networks)
     */
    private String ipPrefix;
    /** Port of the executor's embedded HTTP server: must differ per service when several run on one machine */
    private int port;
    /** Job log directory: the default /data/applogs isn't writable on a Mac, so use the temp dir */
    private String logPath = System.getProperty("java.io.tmpdir") + "/xxl-job";
    private int logRetentionDays = 7;
}
