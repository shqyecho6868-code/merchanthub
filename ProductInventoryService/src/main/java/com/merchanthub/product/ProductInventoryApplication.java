package com.merchanthub.product;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * product-inventory service. The package is split into two subdomains:
 * catalog (products, SKUs) and inventory (stock, reservation records). Subdomains call each other only through service interfaces, never each other's mappers,
 * so if inventory becomes a hotspot and needs its own service later, the boundary is already here.
 */
@SpringBootApplication(scanBasePackages = {"com.merchanthub.product", "com.merchanthub.common"})
@MapperScan({"com.merchanthub.product.catalog.mapper", "com.merchanthub.product.inventory.mapper"})
@EnableFeignClients(basePackages = {"com.merchanthub.product.inventory.client", "com.merchanthub.product.catalog.client"})
public class ProductInventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductInventoryApplication.class, args);
    }
}
