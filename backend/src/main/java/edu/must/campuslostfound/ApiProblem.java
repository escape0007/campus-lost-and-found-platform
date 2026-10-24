package edu.must.campuslostfound;

import java.util.Map;
import org.springframework.http.HttpStatus;

final class ApiProblem extends RuntimeException {
    final HttpStatus status;
    final String code;
    final Map<String,String> fields;
    ApiProblem(HttpStatus status, String code, String message) { this(status, code, message, Map.of()); }
    ApiProblem(HttpStatus status, String code, String message, Map<String,String> fields) {
        super(message); this.status=status; this.code=code; this.fields=fields;
    }
    static ApiProblem invalid(Map<String,String> fields) {
        return new ApiProblem(HttpStatus.UNPROCESSABLE_ENTITY,"VALIDATION","Please check the highlighted fields.",fields);
    }
    static ApiProblem missing() { return new ApiProblem(HttpStatus.NOT_FOUND,"UNAVAILABLE","This notice is unavailable."); }
    static ApiProblem forbidden() { return new ApiProblem(HttpStatus.FORBIDDEN,"FORBIDDEN","This action is not permitted."); }
    static ApiProblem conflict(String message) { return new ApiProblem(HttpStatus.CONFLICT,"CONFLICT",message); }
}
