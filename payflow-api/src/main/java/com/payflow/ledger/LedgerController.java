package com.payflow.ledger;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.payflow.auth.JwtAuthFilter;

/** The merchant's view of their money. Protected by the login-token guard (/merchants/me/**). */
@RestController
@RequestMapping("/merchants/me")
public class LedgerController {

    private final LedgerService ledgerService;

    public LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping("/balance")
    public BalanceResponse balance(@RequestAttribute(JwtAuthFilter.MERCHANT_ID) UUID merchantId) {
        return new BalanceResponse(ledgerService.merchantBalance(merchantId), "INR");
    }

    @GetMapping("/ledger")
    public LedgerService.LedgerPage ledger(@RequestAttribute(JwtAuthFilter.MERCHANT_ID) UUID merchantId,
                                           @RequestParam(defaultValue = "20") int limit,
                                           @RequestParam(required = false) String cursor) {
        return ledgerService.merchantHistory(merchantId, limit, cursor);
    }

    public record BalanceResponse(long balance, String currency) {
    }
}
