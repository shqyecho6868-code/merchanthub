package com.merchanthub.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * 只扫描网关自己的包：网关只用 Common 里的静态工具（JwtUtil、ErrorCode 等），
 * 不需要 Common 里面向 Servlet 的 Bean（全局异常处理、日志切面、MyBatis 配置）。
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class}, scanBasePackages = {"com.merchanthub.gateway"})
public class GateWayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GateWayApplication.class, args);
    }

}