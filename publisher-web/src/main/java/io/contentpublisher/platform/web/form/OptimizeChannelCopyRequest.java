package io.contentpublisher.platform.web.form;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OptimizeChannelCopyRequest(
        @Min(1) int articleVersion,
        @NotBlank @Pattern(regexp = "original|en") String language,
        @NotBlank @Size(max = 500) String title,
        @NotBlank @Size(max = 100000) String body) { }
