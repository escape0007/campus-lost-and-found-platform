package edu.must.campuslostfound;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static edu.must.campuslostfound.NoticeModels.*;

@RestController
@RequestMapping("/api")
class NoticeController {
    private final NoticeService service;private final PhotoProcessor photos;
    NoticeController(NoticeService service,PhotoProcessor photos) {this.service=service;this.photos=photos;}
    private Actor actor(Principal user) {
        if(user==null) throw new ApiProblem(HttpStatus.UNAUTHORIZED,"SIGN_IN_REQUIRED","Please sign in to continue.");
        return new Actor(user.getName(),switch(user.getName()) {case "alice"->"Alice Demo";case "bob"->"Bob Demo";default->"Student";});
    }
    @GetMapping("/session") Map<String,Object> session(HttpServletRequest request,Principal user) {
        CsrfToken token=(CsrfToken)request.getAttribute(CsrfToken.class.getName());
        Actor actor=user==null?null:actor(user);
        return Map.of("authenticated",user!=null,"username",user==null?"":actor.id(),"displayName",user==null?"":actor.displayName(),"csrfToken",token.getToken(),"csrfHeaderName",token.getHeaderName());
    }
    @GetMapping("/options") Map<String,Object> options() {
        return Map.of("categories",NoticeService.CATEGORIES.stream().map(s->Map.of("value",s,"label",label(s))).toList(),
                "areas",NoticeService.AREAS.stream().map(s->Map.of("value",s,"label",label(s))).toList(),"today",service.today().toString());
    }
    private String label(String value) {return value.charAt(0)+value.substring(1).toLowerCase().replace('_',' ');}
    @GetMapping("/notices") Page list(@RequestParam(defaultValue="available") String scope,@RequestParam(defaultValue="0") int page,Principal user) {return service.list(scope,page,actor(user));}
    @GetMapping("/notices/{id}") View get(@PathVariable String id,Principal user) {return service.get(id,actor(user));}
    @PostMapping(value="/notices",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<View> create(@RequestPart("notice") Write notice,@RequestPart(value="photos",required=false) List<MultipartFile> files,
                                @RequestHeader(value="Idempotency-Key",required=false) String key,Principal user) {
        View view=service.create(notice,photos.prepare(files==null?List.of():files),key,actor(user));
        return ResponseEntity.status(201).header("Location","/api/notices/"+view.id()).body(view);
    }
    @PutMapping(value="/notices/{id}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    View update(@PathVariable String id,@RequestPart("notice") Write notice,@RequestPart(value="photos",required=false) List<MultipartFile> files,
                @RequestHeader(value="Idempotency-Key",required=false) String key,Principal user) {
        return service.update(id,notice,photos.prepare(files==null?List.of():files),key,actor(user));
    }
    @PostMapping("/notices/{id}/close") View close(@PathVariable String id,@RequestBody Close request,@RequestHeader(value="Idempotency-Key",required=false) String key,Principal user) {
        return service.close(id,request,key,actor(user));
    }
    @GetMapping("/notices/{id}/photos/{photoId}") ResponseEntity<byte[]> photo(@PathVariable String id,@PathVariable String photoId,Principal user) {
        var photo=service.photo(id,photoId,actor(user));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(photo.contentType())).cacheControl(CacheControl.noStore())
                .header("Content-Disposition","inline; filename=\""+photoId+(photo.contentType().equals("image/png")?".png":".jpg")+"\"").body(photo.data());
    }
}

@Controller
class AppEntry {
    @GetMapping("/") String home() {return "redirect:/app/";}
    @GetMapping({"/app","/app/"}) String app() {return "forward:/app/index.html";}
}
