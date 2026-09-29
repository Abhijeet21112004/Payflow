package com.payflow.merchant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The JSON body of POST /merchants/register. The annotations are the input checks. */
public record RegisterRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 72) String password) {   // 72: BCrypt ignores anything longer
}
