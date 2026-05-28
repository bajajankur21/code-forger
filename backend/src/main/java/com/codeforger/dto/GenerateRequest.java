package com.codeforger.dto;

import jakarta.validation.constraints.NotBlank;

public record GenerateRequest(
        @NotBlank String specUrl
) {}
