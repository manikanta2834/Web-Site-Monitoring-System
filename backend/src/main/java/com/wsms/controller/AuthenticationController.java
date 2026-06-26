package com.wsms.controller;

import com.wsms.dto.LoginRequestDto;
import com.wsms.dto.RegisterRequestDto;
import com.wsms.dto.TokenResponseDto;
import com.wsms.service.AuthenticationService;
import com.wsms.service.EncryptionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthenticationController {

    private final AuthenticationService authenticationService;
    private final EncryptionService encryptionService;

    public AuthenticationController(AuthenticationService authenticationService, EncryptionService encryptionService) {
        this.authenticationService = authenticationService;
        this.encryptionService = encryptionService;
    }

    @GetMapping("/public-key")
    public ResponseEntity<Map<String, String>> getPublicKey() {
        return ResponseEntity.ok(Map.of("publicKey", encryptionService.getPublicKeyBase64()));
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponseDto> login(@Valid @RequestBody LoginRequestDto request) {
        return ResponseEntity.ok(authenticationService.login(request));
    }

    @PostMapping("/register")
    public ResponseEntity<String> register(@Valid @RequestBody RegisterRequestDto request) {
        authenticationService.register(request);
        return ResponseEntity.ok("User registered successfully");
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponseDto> refresh(@RequestBody Map<String, String> request) {
        String refreshToken = request.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(authenticationService.refresh(refreshToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody Map<String, String> request) {
        String refreshToken = request.get("refreshToken");
        if (refreshToken != null && !refreshToken.isBlank()) {
            authenticationService.logout(refreshToken);
        }
        return ResponseEntity.noContent().build();
    }
}
