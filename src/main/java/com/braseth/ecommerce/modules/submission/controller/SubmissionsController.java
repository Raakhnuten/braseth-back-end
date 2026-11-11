package com.braseth.ecommerce.modules.submission.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.braseth.ecommerce.modules.submission.dto.SubmissionBodies.ContactBody;
import com.braseth.ecommerce.modules.submission.dto.SubmissionBodies.ContactReceipt;
import com.braseth.ecommerce.modules.submission.dto.SubmissionBodies.NewsletterBody;
import com.braseth.ecommerce.modules.submission.dto.SubmissionBodies.NewsletterReceipt;
import com.braseth.ecommerce.modules.submission.dto.SubmissionBodies.QuoteRequestBody;
import com.braseth.ecommerce.modules.submission.dto.SubmissionBodies.QuoteRequestReceipt;
import com.braseth.ecommerce.modules.submission.service.SubmissionsService;
import com.braseth.ecommerce.security.jwt.CustomUserDetails;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * The public site's forms. No sign-in needed; a quote request sent while
 * signed in is linked to the account so it appears under "My quotes".
 */
@RestController
@RequiredArgsConstructor
public class SubmissionsController {

    private final SubmissionsService submissions;

    @PostMapping("/api/v1/quote-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<QuoteRequestReceipt> quoteRequest(@Valid @RequestBody QuoteRequestBody body,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return submissions.submitQuote(body, principal == null ? null : principal.getUserId());
    }

    @PostMapping("/api/v1/contact-messages")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ContactReceipt> contact(@Valid @RequestBody ContactBody body) {
        return submissions.submitContact(body);
    }

    @PostMapping("/api/v1/newsletter-subscriptions")
    public Mono<NewsletterReceipt> newsletter(@Valid @RequestBody NewsletterBody body) {
        return submissions.subscribe(body.email());
    }
}
