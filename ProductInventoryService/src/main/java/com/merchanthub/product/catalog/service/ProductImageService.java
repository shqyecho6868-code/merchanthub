package com.merchanthub.product.catalog.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.product.catalog.mapper.ProductMapper;
import com.merchanthub.product.catalog.model.entity.Product;
import com.merchanthub.product.catalog.model.vo.ImageUploadVO;
import com.merchanthub.product.catalog.storage.ImageStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Product main image upload (G-上). Two steps:
 * <ol>
 *   <li>Request: the service generates a key as {@code products/{tenantId}/{productId}/{uuid}.ext} and signs a PUT URL; the client uploads directly to S3</li>
 *   <li>Confirm: the client passes the key back; the service checks "the key belongs to this merchant's product + the object really exists" and then writes it to product.main_image</li>
 * </ol>
 * The key is generated server-side and its prefix re-checked on confirm: prevents merchant A from attaching merchant B's image key (or a made-up key) to its own product.
 * Product lookups go through the tenant plugin, so a cross-tenant productId is simply "not found".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductImageService {

    /** Only common image types are allowed; the extension is derived from the type, never from the client-supplied filename */
    static final Map<String, String> ALLOWED_TYPES = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");
    static final Duration UPLOAD_TTL = Duration.ofMinutes(10);

    private final ProductMapper productMapper;
    private final ImageStorage imageStorage;

    public ImageUploadVO createUploadUrl(long productId, String contentType) {
        String ext = ALLOWED_TYPES.get(contentType);
        if (ext == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "只支持 jpeg / png / webp 图片");
        }
        Product p = requireProduct(productId);
        String key = keyPrefix(p) + UUID.randomUUID() + "." + ext;
        ImageStorage.PresignedUpload upload = imageStorage.presignUpload(key, contentType, UPLOAD_TTL);
        return new ImageUploadVO(key, upload.uploadUrl(), upload.expiresAt(), imageStorage.publicUrl(key));
    }

    public String confirm(long productId, String objectKey) {
        Product p = requireProduct(productId);
        if (!objectKey.startsWith(keyPrefix(p)) || objectKey.contains("..")) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "图片不属于该商品");
        }
        if (!imageStorage.exists(objectKey)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片还没有上传成功");
        }
        String url = imageStorage.publicUrl(objectKey);
        // The update also goes through the tenant plugin which appends tenant_id — belt and braces
        int rows = productMapper.update(null, new LambdaUpdateWrapper<Product>()
                .eq(Product::getId, productId).set(Product::getMainImage, url));
        if (rows != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "商品不存在");
        }
        log.info("商品主图更新 productId={} key={}", productId, objectKey);
        return url;
    }

    private Product requireProduct(long productId) {
        Product p = productMapper.selectById(productId);
        if (p == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "商品不存在");
        }
        return p;
    }

    private static String keyPrefix(Product p) {
        return "products/" + p.getTenantId() + "/" + p.getId() + "/";
    }
}
