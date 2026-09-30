package com.payflow.merchant;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.payflow.auth.JwtAuthFilter;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/merchants")
public class MerchantController {

    private final MerchantService merchantService;

    public MerchantController(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(merchantService.register(request));
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return merchantService.login(request);
    }

    // Protected by JwtAuthFilter: this only runs if the request had a valid token,
    // and merchantId is the id the filter read from that token.
    @GetMapping("/me")
    public Merchant me(@RequestAttribute(JwtAuthFilter.MERCHANT_ID) UUID merchantId) {
        return merchantService.get(merchantId);
    }
}
