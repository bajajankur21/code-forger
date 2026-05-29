package com.codeforger.agents;

import java.util.List;

public record ValidationResult(boolean success, List<CompileError> errors) {

    public static ValidationResult pass() {
        return new ValidationResult(true, List.of());
    }

    public static ValidationResult fail(List<CompileError> errors) {
        return new ValidationResult(false, List.copyOf(errors));
    }
}
