package com.lemicare.shoppingcart.client;

import com.cosmicdoc.common.model.StorefrontProduct;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "storefront-service", url = "${services.cms.url}")
public interface StorefrontServiceClient {

    @GetMapping("/api/internal/storefront/{orgId}/product/{productId}/details")
    StorefrontProduct getProductDetails(@PathVariable("orgId") String orgId, @PathVariable("productId") String productId);
}
