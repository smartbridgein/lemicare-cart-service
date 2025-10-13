package com.lemicare.shoppingcart.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryAddressDTO {

    @NotBlank(message = "Address line 1 is required")
    private String addressLine1;

    private String addressLine2; // Optional

    @NotBlank(message = "City is required")
    private String city;

    @NotBlank(message = "State/Province is required")
    private String stateProvince;

    @NotBlank(message = "Postal code is required")
    // Example regex for basic validation, adjust as needed for specific countries
    @Pattern(regexp = "^[a-zA-Z0-9 -]+$", message = "Invalid postal code format")
    private String pinCode;

    @NotBlank(message = "Country is required")
    private String country; // Use ISO 3166-1 alpha-2 codes (e.g., "US", "CA", "IN")

    private String landmark; // Optional

    private String contactName; // Optional, for delivery person

    @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "Invalid phone number format") // Basic phone validation
    private String contactPhoneNumber; // Optional, for delivery person
}
