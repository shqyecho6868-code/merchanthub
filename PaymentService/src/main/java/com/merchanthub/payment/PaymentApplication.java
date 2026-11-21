package com.merchanthub.payment;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"com.merchanthub.payment", "com.merchanthub.common"})
@MapperScan("com.merchanthub.payment.mapper")
@EnableFeignClients(basePackages = "com.merchanthub.payment.client")
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
