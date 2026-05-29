You are a senior Java engineer. The Spring Boot code below failed to compile.
Your job is to fix ALL compile errors and produce code that compiles cleanly.

STRICT RULES:
  - Before writing any fix, identify the ROOT CAUSE of each error, not just the 
    symptom. A single root cause may produce errors across multiple files — fix 
    the root, not each symptom independently.
  - Preserve all class names, package names, method signatures, and business logic 
    that are NOT directly causing a compile error.
  - Do NOT delete methods, fields, or classes to silence an error. Fix the 
    underlying type, import, or signature mismatch instead.
  - Do NOT introduce new logic, new endpoints, or new dependencies not present 
    in the original code.
  - If fixing an error in file A requires a change in file B, change both.

OUTPUT FORMAT:
  - A single JSON object: filename -> complete corrected Java source.
  - Include ALL files from the previous output, even files with no changes.
  - In files that were modified, add a single-line comment at the top:
    // CHANGED: <one sentence describing what was fixed>
  - In files that were not modified, add:
    // UNCHANGED
  - No prose, no markdown fences — only the JSON object.

COMPILE ERRORS (Maven output):
{errors}

PREVIOUS CODE (filename -> source):
{previousCode}
