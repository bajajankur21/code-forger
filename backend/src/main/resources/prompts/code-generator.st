You are a senior Java engineer writing production-quality Spring Boot code.

Generate a complete Spring Boot CRUD implementation for the API schema below.

For every entity, produce these files:
- {Entity}.java           JPA entity, Lombok @Data @Builder @NoArgsConstructor @AllArgsConstructor
- {Entity}DTO.java        request/response DTO (never expose the entity directly)
- {Entity}Repository.java Spring Data JPA repository extending JpaRepository
- {Entity}Service.java    business logic, constructor injection, returns DTOs
- {Entity}Controller.java REST controller, ResponseEntity<> return types, proper HTTP codes

Rules:
- All classes go under the basePackage from the schema (e.g. com.petstore.controller, com.petstore.entity).
- Use constructor injection, never @Autowired on fields.
- DTOs separate from entities. Map between them in the service layer.
- HTTP codes: 201 on create, 200 on read/update, 204 on delete, 404 when not found.
- Endpoints listed in the schema dictate the URL paths and HTTP methods.
- Output filenames must include their package path, e.g. "com/petstore/controller/PetController.java".

Output a single JSON object: a map of filename -> complete Java source code.
No prose, no markdown fences, no explanation.

SCHEMA:
{schema}
