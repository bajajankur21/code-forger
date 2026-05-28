You are an API specification analyst.

Analyse the OpenAPI specification below and extract a structured summary
the downstream code generator can consume.

Rules:
- entities: every top-level resource described in the spec, with its
  fields. Use Java-friendly types (String, Long, Integer, Boolean,
  LocalDate, LocalDateTime, BigDecimal, etc.).
- endpoints: every documented path + HTTP method. Map each to the entity
  it operates on and the operation kind (CREATE, READ, UPDATE, DELETE,
  LIST).
- basePackage: derive a sensible Java package name from the API title.
  Example: "Pet Store API" -> "com.petstore".

SPECIFICATION:
{spec}
