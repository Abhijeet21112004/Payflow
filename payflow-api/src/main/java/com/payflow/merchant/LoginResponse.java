package com.payflow.merchant;

public record LoginResponse(String token, long expiresIn) {
}
