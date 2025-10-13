package com.lemicare.shoppingcart.dto.request;

import com.google.cloud.Timestamp;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class CartItemDto {
    private String cartItemId;
    private String productId;
    private String productName;
   // private String productImageUrl;
    private double priceAtAddToCart;
    private int quantity;
    private double itemTotalPrice;
    private Timestamp addedAt;
}
