You are a senior Java engineer writing production-quality Spring Boot code.
Generate a complete Spring Boot CRUD implementation for the API schema below.

For every entity, produce these files:
  - {Entity}.java                          JPA entity with Lombok @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor (NOT @Data — see rules)
  - {Entity}DTO.java                       Request/response DTO with Bean Validation annotations (@NotNull, @NotBlank, @Size, etc.)
  - {Entity}Repository.java               Spring Data JPA repository extending JpaRepository
  - {Entity}Service.java                   Business logic layer, constructor injection, all mutating methods annotated @Transactional
  - {Entity}Controller.java               REST controller, all methods return ResponseEntity<>, proper HTTP status codes
  - GlobalExceptionHandler.java            One shared @RestControllerAdvice handling ResourceNotFoundException → 404, MethodArgumentNotValidException → 400
  - ErrorResponse.java                     Uniform error DTO: { timestamp, status, message, path }
  - ResourceNotFoundException.java         Custom RuntimeException used by service layer when entity not found
  - {Application}.java                     Spring Boot @SpringBootApplication main class

Rules:
  - Base package from schema (e.g. com.petstore). Sub-packages: .entity .dto .repository .service .controller .exception
  - Constructor injection everywhere. Never @Autowired on fields.
  - DTOs are strictly separate from entities. All entity↔DTO mapping happens in the service layer.
  - HTTP status codes: 201 Created on POST, 200 OK on GET/PUT, 204 No Content on DELETE, 404 Not Found when entity missing, 400 Bad Request on validation failure
  - "List all" endpoints must return Page<DTO> and accept Pageable as a parameter
  - Never put @Data on a JPA @Entity: it generates equals()/hashCode() over all fields
    including the mutable @Id, which breaks entity identity and lazy loading. Use
    @Getter/@Setter (plus @Builder and constructors) on entities. @Data is fine on DTOs
    and ErrorResponse.
  - Service methods that write (create/update/delete) must be @Transactional; read-only
    methods must be @Transactional(readOnly = true)
  - Do not emit unused imports — import only what each file actually references
  - Do not annotate JpaRepository interfaces with @Repository; Spring Data registers them automatically
  - All endpoints must validate request bodies with @Valid
  - Output filenames must include their full package path, e.g. "com/petstore/controller/PetController.java"

Output a single JSON object: a map of filename → complete Java source code.
No prose, no markdown fences, no explanation — only the JSON object.

SCHEMA:
{schema}
