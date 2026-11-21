package com.merchanthub.product;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * product-inventory 服务。包内分两个子域（D2）：
 * catalog（商品、SKU）和 inventory（库存、预占记录）。子域之间只通过 service 接口调用，不直接用对方的 mapper，
 * 以后库存成为热点要单独拆服务时，边界已经在这里了。
 */
@SpringBootApplication(scanBasePackages = {"com.merchanthub.product", "com.merchanthub.common"})
@MapperScan({"com.merchanthub.product.catalog.mapper", "com.merchanthub.product.inventory.mapper"})
@EnableFeignClients(basePackages = "com.merchanthub.product.inventory.client")
@EnableScheduling
public class ProductInventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductInventoryApplication.class, args);
    }
}
