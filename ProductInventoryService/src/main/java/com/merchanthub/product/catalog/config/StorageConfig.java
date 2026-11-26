package com.merchanthub.product.catalog.config;

import com.merchanthub.product.catalog.storage.DisabledImageStorage;
import com.merchanthub.product.catalog.storage.ImageStorage;
import com.merchanthub.product.catalog.storage.S3ImageStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 is enabled only in the cloud (aws profile); local and tests use DisabledImageStorage, so the app starts without AWS credentials.
 */
@Configuration
public class StorageConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "merchanthub.storage.s3.enabled", havingValue = "true")
    public S3Presigner s3Presigner(@Value("${merchanthub.storage.s3.region}") String region) {
        return S3Presigner.builder().region(Region.of(region)).build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "merchanthub.storage.s3.enabled", havingValue = "true")
    public S3Client s3Client(@Value("${merchanthub.storage.s3.region}") String region) {
        return S3Client.builder().region(Region.of(region)).httpClient(UrlConnectionHttpClient.create()).build();
    }

    @Bean
    @ConditionalOnProperty(name = "merchanthub.storage.s3.enabled", havingValue = "true")
    public ImageStorage s3ImageStorage(S3Presigner presigner, S3Client s3,
                                       @Value("${merchanthub.storage.s3.bucket}") String bucket,
                                       @Value("${merchanthub.storage.s3.region}") String region) {
        return new S3ImageStorage(presigner, s3, bucket, region);
    }

    @Bean
    @ConditionalOnMissingBean(ImageStorage.class)
    public ImageStorage disabledImageStorage() {
        return new DisabledImageStorage();
    }
}
