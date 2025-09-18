package com.lemicare.shoppingcart.dto.request;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class CartDto {
    private String cartId;
    private String orgId;
    private String userId;
    private String guestId;
    private String status;
    private int totalItems;
    private double subtotalAmount;
    private LocalDateTime createdAt;
    private LocalDateTime lastModifiedAt;
    private List<CartItemDto> items;
}
