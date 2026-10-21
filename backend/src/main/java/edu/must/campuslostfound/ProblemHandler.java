package edu.must.campuslostfound;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class ProblemHandler {
    private Map<String,Object> body(String code,String message,Map<String,String> fields) {return Map.of("code",code,"message",message,"fieldErrors",fields);}
    @ExceptionHandler(ApiProblem.class) ResponseEntity<?> problem(ApiProblem p) {return ResponseEntity.status(p.status).body(body(p.code,p.getMessage(),p.fields));}
    @ExceptionHandler({HttpMessageNotReadableException.class,MissingServletRequestPartException.class,MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid(Exception e) {return ResponseEntity.unprocessableEntity().body(body("VALIDATION","Some fields are invalid or unsupported. Check the form and try again.",Map.of("notice","Use only the supported notice fields and valid values.")));}
    @ExceptionHandler(MaxUploadSizeExceededException.class) ResponseEntity<?> size() {return ResponseEntity.status(413).body(body("PHOTO_LIMIT","Each photo must be at most 5 MB, with no more than 3 photos.",Map.of("photos","Choose smaller JPEG/PNG photos.")));}
    @ExceptionHandler(Exception.class) ResponseEntity<?> failure(Exception e) {
        // The transaction rolls back. Do not send a stack trace, SQL, password or uploaded content to the browser.
        return ResponseEntity.status(503).body(body("SAVE_FAILED","The operation could not be completed. Your previous saved notice is unchanged; you may retry safely.",Map.of()));
    }
}
