package com.lemicare.shoppingcart.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AddItemRequest {
    @NotBlank(message = "Product ID cannot be blank")
    private String productId;

    @Min(value = 1, message = "Quantity must be at least 1")
    private int quantity;

    private String userId; // Optional, can be derived from JWT
    private String guestId; // Optional, derived from cookie
}
