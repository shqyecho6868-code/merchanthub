package com.merchanthub.product.catalog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.merchanthub.product.catalog.model.entity.Product;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {
}
