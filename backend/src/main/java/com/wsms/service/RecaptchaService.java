package com.wsms.service;

import com.wsms.dto.RecaptchaResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecaptchaService {
    private static final Logger log = LoggerFactory.getLogger(RecaptchaService.class);

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${google.recaptcha.secret}")
    private String recaptchaSecret;

    @Value("${google.recaptcha.threshold:0.5}")
    private double recaptchaThreshold;

    @Value("${cloudflare.turnstile.secret}")
    private String turnstileSecret;

    private static final String GOOGLE_VERIFY_URL = "https://www.google.com/recaptcha/api/siteverify";
    private static final String CLOUDFLARE_VERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    public void verifyToken(String token, String provider) {
        if (token == null || token.isBlank()) {
            log.warn("Missing captcha token");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing captcha token verification");
        }

        String verifyUrl;
        String secret;
        if ("turnstile".equalsIgnoreCase(provider)) {
            verifyUrl = CLOUDFLARE_VERIFY_URL;
            secret = turnstileSecret;
            log.debug("Verifying token with Cloudflare Turnstile");
        } else {
            verifyUrl = GOOGLE_VERIFY_URL;
            secret = recaptchaSecret;
            log.debug("Verifying token with Google reCAPTCHA v3");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        map.add("secret", secret);
        map.add("response", token);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, headers);

        try {
            ResponseEntity<RecaptchaResponse> responseEntity = restTemplate.postForEntity(verifyUrl, request, RecaptchaResponse.class);
            RecaptchaResponse body = responseEntity.getBody();

            if (body == null || !body.isSuccess()) {
                String errors = (body != null && body.getErrorCodes() != null) ? String.join(", ", body.getErrorCodes()) : "unknown error";
                log.warn("Captcha verification failed. Provider: {}, Error codes: {}", provider, errors);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bot check verification failed: " + errors);
            }

            // Google reCAPTCHA v3 risk score validation
            if (!"turnstile".equalsIgnoreCase(provider)) {
                log.debug("reCAPTCHA v3 score: {}", body.getScore());
                if (body.getScore() < recaptchaThreshold) {
                    log.warn("reCAPTCHA risk score {} is below threshold {}", body.getScore(), recaptchaThreshold);
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Security threat detected. High risk profile score: " + body.getScore());
                }
            }

            log.info("Captcha verification succeeded for provider: {}", provider);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error connecting to captcha verification API: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Captcha verification service is temporarily unavailable");
        }
    }
}
