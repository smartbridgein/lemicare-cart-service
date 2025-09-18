package com.lemicare.shoppingcart.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MergeCartRequest {
    @NotBlank(message = "Guest ID cannot be blank")
    private String guestId;

    @NotBlank(message = "User ID cannot be blank")
    private String userId;
}
