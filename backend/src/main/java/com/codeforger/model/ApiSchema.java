package com.codeforger.model;

import java.util.List;

public record ApiSchema(
        String basePackage,
        List<Entity> entities,
        List<Endpoint> endpoints
) {
    public record Entity(String name, List<Field> fields) {}

    public record Field(String name, String type, boolean required) {}

    public record Endpoint(String path, String method, String entity, Operation operation) {}

    public enum Operation { CREATE, READ, UPDATE, DELETE, LIST }
}
