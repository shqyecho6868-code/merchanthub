package com.merchanthub.product.catalog.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.product.catalog.mapper.ProductMapper;
import com.merchanthub.product.catalog.model.entity.Product;
import com.merchanthub.product.catalog.model.vo.ImageUploadVO;
import com.merchanthub.product.catalog.storage.ImageStorage;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validation logic for main image upload (no S3 / database). Cross-tenant products not being found is guaranteed by the tenant plugin and verified in ProductTenantIsolationIT.
 */
@ExtendWith(MockitoExtension.class)
class ProductImageServiceTest {

    @Mock
    private ProductMapper productMapper;
    @Mock
    private ImageStorage storage;

    private ProductImageService service;

    @BeforeAll
    static void initMybatisMeta() {
        // LambdaUpdateWrapper needs entity metadata to resolve Product::getId; unit tests have no MyBatis bootstrap, so initialize it manually
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Product.class);
    }

    @BeforeEach
    void setUp() {
        service = new ProductImageService(productMapper, storage);
    }

    private static Product product(long tenantId, long id) {
        Product p = new Product();
        p.setId(id);
        p.setTenantId(tenantId);
        return p;
    }

    @Test
    void uploadUrl_keyIsGeneratedUnderTenantAndProduct() {
        when(productMapper.selectById(100L)).thenReturn(product(7, 100));
        when(storage.presignUpload(any(), eq("image/png"), eq(ProductImageService.UPLOAD_TTL)))
                .thenReturn(new ImageStorage.PresignedUpload("https://signed", Instant.EPOCH));
        when(storage.publicUrl(any())).thenAnswer(inv -> "https://cdn/" + inv.getArgument(0));

        ImageUploadVO vo = service.createUploadUrl(100L, "image/png");

        // The key is generated server-side: tenant / product prefix + random name + extension determined by the type
        assertThat(vo.objectKey()).startsWith("products/7/100/").endsWith(".png");
        assertThat(vo.uploadUrl()).isEqualTo("https://signed");
        assertThat(vo.imageUrl()).isEqualTo("https://cdn/" + vo.objectKey());
    }

    @Test
    void uploadUrl_rejectsNonImageType() {
        assertThatThrownBy(() -> service.createUploadUrl(100L, "text/html"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.PARAMS_ERROR.getCode());
        verify(storage, never()).presignUpload(any(), any(), any());
    }

    @Test
    void uploadUrl_productOfOtherTenantIsNotFound() {
        // After the tenant plugin filters, selectById returns null
        when(productMapper.selectById(100L)).thenReturn(null);
        assertThatThrownBy(() -> service.createUploadUrl(100L, "image/jpeg"))
                .extracting("code").isEqualTo(ErrorCode.NOT_FOUND_ERROR.getCode());
    }

    @Test
    void confirm_rejectsKeyOfAnotherProductOrTenant() {
        when(productMapper.selectById(100L)).thenReturn(product(7, 100));
        // Merchant 7 tries to attach merchant 8's image to its own product
        assertThatThrownBy(() -> service.confirm(100L, "products/8/200/a.png"))
                .extracting("code").isEqualTo(ErrorCode.FORBIDDEN_ERROR.getCode());
        // Correct prefix, but escapes the directory with ..
        assertThatThrownBy(() -> service.confirm(100L, "products/7/100/../../8/200/a.png"))
                .extracting("code").isEqualTo(ErrorCode.FORBIDDEN_ERROR.getCode());
        verify(storage, never()).exists(any());
        verify(productMapper, never()).update(any(), any());
    }

    @Test
    void confirm_rejectsWhenObjectNotUploaded() {
        when(productMapper.selectById(100L)).thenReturn(product(7, 100));
        when(storage.exists("products/7/100/a.png")).thenReturn(false);
        assertThatThrownBy(() -> service.confirm(100L, "products/7/100/a.png"))
                .extracting("code").isEqualTo(ErrorCode.PARAMS_ERROR.getCode());
        verify(productMapper, never()).update(any(), any());
    }

    @Test
    void confirm_writesPublicUrlToProduct() {
        when(productMapper.selectById(100L)).thenReturn(product(7, 100));
        when(storage.exists("products/7/100/a.png")).thenReturn(true);
        when(storage.publicUrl("products/7/100/a.png")).thenReturn("https://cdn/products/7/100/a.png");
        when(productMapper.update(any(), any())).thenReturn(1);

        assertThat(service.confirm(100L, "products/7/100/a.png")).isEqualTo("https://cdn/products/7/100/a.png");
        verify(productMapper).update(any(), any());
    }

    @Test
    void confirm_productDeletedConcurrently() {
        when(productMapper.selectById(100L)).thenReturn(product(7, 100));
        when(storage.exists(any())).thenReturn(true);
        when(storage.publicUrl(any())).thenReturn("u");
        when(productMapper.update(any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.confirm(100L, "products/7/100/a.png"))
                .extracting("code").isEqualTo(ErrorCode.NOT_FOUND_ERROR.getCode());
    }
}
