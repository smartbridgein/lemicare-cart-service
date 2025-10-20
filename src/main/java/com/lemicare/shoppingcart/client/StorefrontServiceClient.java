package com.lemicare.shoppingcart.client;

import com.cosmicdoc.common.model.StorefrontProduct;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Set;

@FeignClient(name = "storefront-service", url = "${services.cms.url}")
public interface StorefrontServiceClient {

    @GetMapping("/api/internal/storefront/{orgId}/product/{productId}/details")
    StorefrontProduct getProductDetails(@PathVariable("orgId") String orgId, @PathVariable("productId") String productId);


    @GetMapping("/api/internal/storefront/{organizationId}/products")
    List<StorefrontProduct> getProductsByIds (
            @PathVariable("organizationId") String organizationId,
            @RequestParam("productIds") List<String> productIds);

}
