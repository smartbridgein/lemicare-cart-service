package com.lemicare.shoppingcart.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "inventory-service", url = "${services.inventory.url}")
public interface InventoryServiceClient {

    @GetMapping("/api/internal/inventory/{orgId}/product/{productId}/stock")
    Integer getProductStock(@PathVariable("orgId") String orgId, @PathVariable("productId") String productId);
}
