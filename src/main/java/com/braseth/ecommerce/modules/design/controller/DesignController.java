package com.braseth.ecommerce.modules.design.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.braseth.ecommerce.modules.design.service.DesignService;
import com.braseth.ecommerce.modules.design.service.DesignService.DesignView;
import com.braseth.ecommerce.modules.design.service.DesignService.UploadPolicy;
import com.braseth.ecommerce.security.jwt.CustomUserDetails;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * Artwork upload for the quote form. Open to guests: most quote requests
 * come from people without an account. A signed-in upload is owned by the
 * customer straight away; a guest upload is claimed by the request it is
 * attached to.
 */
@RestController
@RequiredArgsConstructor
public class DesignController {

    private final DesignService designs;

    @GetMapping("/api/v1/designs/upload-policy")
    public UploadPolicy policy() {
        return designs.policy();
    }

    @PostMapping(path = "/api/v1/designs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<DesignView> upload(
            @RequestPart(value = "file", required = false) FilePart file,
            @RequestPart(value = "note", required = false) String note,
            @AuthenticationPrincipal CustomUserDetails principal) {
        if (file == null) {
            return Mono.error(ApiProblem.field("file", "file_required", "Choose a file to upload."));
        }
        return designs.upload(file, note, principal == null ? null : principal.getUserId());
    }
}
