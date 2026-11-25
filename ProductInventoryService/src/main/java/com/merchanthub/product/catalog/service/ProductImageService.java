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
 * 商品主图上传（G-上）。两步：
 * <ol>
 *   <li>申请：服务按 {@code products/{tenantId}/{productId}/{uuid}.ext} 生成 key 并签 PUT URL，客户端直传 S3</li>
 *   <li>确认：客户端传回 key，服务校验"key 属于这个商家的这个商品 + 对象确实存在"后写进 product.main_image</li>
 * </ol>
 * key 由服务端生成、确认时再校验前缀：防止商家 A 把商家 B 的图片 key（或随便编的 key）挂到自己商品上。
 * 商品查询走租户插件，跨租户的 productId 直接"不存在"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductImageService {

    /** 只允许常见图片类型；扩展名由类型决定，不信任客户端给的文件名 */
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
        // 更新同样经过租户插件拼 tenant_id，双保险
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
