package com.lemicare.shoppingcart.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
 public class AddItemToWishlistRequest {
    private String productId;
}

