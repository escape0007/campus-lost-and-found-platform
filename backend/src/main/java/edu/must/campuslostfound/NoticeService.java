package edu.must.campuslostfound;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static edu.must.campuslostfound.NoticeModels.*;

@Service
public class NoticeService {
    static final List<String> CATEGORIES=List.of("KEYS","UMBRELLA","BOTTLE","BAG","ELECTRONICS","OTHER");
    static final List<String> AREAS=List.of("LIBRARY","TEACHING_BUILDING","CANTEEN","DORMITORY","SPORTS_AREA","OTHER");
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final AlertService alerts;
    NoticeService(JdbcTemplate db,ObjectMapper json,Clock clock,AlertService alerts) {this.db=db;this.json=json;this.clock=clock;this.alerts=alerts;}
    LocalDate today() {return LocalDate.now(clock.withZone(ZoneId.of("Asia/Macau")));}
    private String now() {return clock.instant().toString();}
    record Row(String id,String authorId,String authorName,String type,String title,String category,String date,boolean approximate,
               String area,String description,String status,boolean hidden,long version,long materialVersion,String createdAt,String updatedAt,String closeReason) {}
    private Row row(ResultSet r,int n) throws SQLException {
        return new Row(r.getString("id"),r.getString("author_id"),r.getString("author_name"),r.getString("type"),r.getString("title"),
                r.getString("category"),r.getDate("occurrence_date").toLocalDate().toString(),r.getBoolean("approximate"),r.getString("area"),
                r.getString("description"),r.getString("status"),r.getBoolean("hidden"),r.getLong("version"),r.getLong("material_version"),
                r.getString("created_at"),r.getString("updated_at"),r.getString("closed_reason"));
    }
    private Row require(String id,boolean lock) {
        var rows=db.query("SELECT * FROM notices WHERE id=?"+(lock?" FOR UPDATE":""),this::row,id);
        if(rows.isEmpty()||rows.getFirst().hidden()) throw ApiProblem.missing();
        return rows.getFirst();
    }
    private boolean unresolved(String id) {
        return db.queryForObject("SELECT COUNT(*) FROM claim_guards WHERE (found_id=? OR lost_id=?) AND status IN ('PENDING','HELD','APPROVED')",Long.class,id,id)>0;
    }
    private boolean reserved(String id) {return db.queryForObject("SELECT COUNT(*) FROM active_reservations WHERE notice_id=?",Long.class,id)>0;}
    private String locked(Row row) {
        if(row.status().equals("RETURNED")||row.status().equals("CLOSED")) return "This notice is read-only. Archiving does not confirm a physical return.";
        if(unresolved(row.id())||reserved(row.id())||!row.status().equals("OPEN")) return "An unresolved claim or reservation locks editing and archiving.";
        return "";
    }
    private void owner(Row row,Actor actor) {if(!row.authorId().equals(actor.id())) throw ApiProblem.forbidden();}
    private void editable(Row row,long expected) {
        if(row.version()!=expected) throw ApiProblem.conflict("This notice has changed. Refresh the latest version before saving.");
        String reason=locked(row); if(!reason.isEmpty()) throw ApiProblem.conflict(reason);
    }
    private View view(Row r,Actor actor) {
        String lock=locked(r); boolean own=r.authorId().equals(actor.id());
        var photos=db.query("SELECT id,content_type FROM notice_photos WHERE notice_id=? AND active=TRUE ORDER BY id",(s,n)->
                new Photo(s.getString(1),"/api/notices/"+r.id()+"/photos/"+s.getString(1),s.getString(2)),r.id());
        var history=db.query("SELECT * FROM notice_revisions WHERE notice_id=? ORDER BY version DESC",(s,n)->
                new History(s.getLong("version"),s.getString("action"),s.getString("actor"),s.getString("occurred_at"),s.getString("reason"),strings(s.getString("changed_fields")),s.getString("status")),r.id());
        return new View(r.id(),r.type(),r.title(),r.category(),r.date(),r.approximate(),r.area(),r.description(),r.status(),r.version(),r.materialVersion(),
                r.createdAt(),r.updatedAt(),own&&lock.isEmpty(),own&&lock.isEmpty(),own?lock:"Only the notice author can edit or archive this notice.",photos,history);
    }
    @Transactional(readOnly=true)
    public View get(String id,Actor actor) {return view(require(id,false),actor);}
    @Transactional(readOnly=true)
    public PhotoContent photo(String id,String photoId,Actor actor) {
        require(id,false);
        var photos=db.query("SELECT content_type,data FROM notice_photos WHERE notice_id=? AND id=? AND active=TRUE",(s,n)->new PhotoContent(s.getString(1),s.getBytes(2)),id,photoId);
        if(photos.isEmpty()) throw ApiProblem.missing();
        return photos.getFirst();
    }
    @Transactional(readOnly=true)
    public Page list(String scope,int page,int size,String keyword,String type,String category,String area,String from,String to,String openOnly,Actor actor) {
        Map<String,String> errors=new LinkedHashMap<>();
        if(!List.of("mine","available").contains(scope)||page<0||page>100000) errors.put("page","Choose a valid list and page.");
        if(size<1||size>50) errors.put("size","Choose 1–50 notices per page.");
        String words=keyword==null?"":keyword.strip();
        if(length(words)>80) errors.put("keyword","Use at most 80 characters.");
        String typeValue=blank(type);
        if(!typeValue.isEmpty()&&!List.of("LOST","FOUND").contains(typeValue)) errors.put("type","Choose Lost or Found.");
        String categoryValue=blank(category);
        if(!categoryValue.isEmpty()&&!CATEGORIES.contains(categoryValue)) errors.put("category","Choose a listed category.");
        String areaValue=blank(area);
        if(!areaValue.isEmpty()&&!AREAS.contains(areaValue)) errors.put("area","Choose a listed campus area.");
        LocalDate start=calendarDate(from,"from",errors), end=calendarDate(to,"to",errors);
        if(start!=null&&end!=null&&start.isAfter(end)) errors.put("to","The end date must be on or after the start date.");
        boolean onlyOpen=openOnly(openOnly,errors);
        if(!errors.isEmpty()) throw ApiProblem.invalid(errors);
        StringBuilder where=new StringBuilder("n.hidden=FALSE");
        List<Object> args=new ArrayList<>();
        if(scope.equals("mine")) {
            where.append(" AND n.author_id=?"); args.add(actor.id());
            if(onlyOpen) where.append(" AND n.status='OPEN'");
        } else {
            where.append(onlyOpen?" AND n.status='OPEN'":" AND n.status IN ('OPEN','CLAIM_UNDER_REVIEW')");
            where.append(" AND NOT EXISTS (SELECT 1 FROM active_reservations a WHERE a.notice_id=n.id)");
        }
        if(!typeValue.isEmpty()) {where.append(" AND n.type=?"); args.add(typeValue);}
        if(!categoryValue.isEmpty()) {where.append(" AND n.category=?"); args.add(categoryValue);}
        if(!areaValue.isEmpty()) {where.append(" AND n.area=?"); args.add(areaValue);}
        if(start!=null) {where.append(" AND n.occurrence_date>=?"); args.add(start);}
        if(end!=null) {where.append(" AND n.occurrence_date<=?"); args.add(end);}
        for(String token:words.isEmpty()?List.<String>of():List.of(words.split("(?U)\\s+"))) {
            String pattern=likePattern(token);
            where.append(" AND (LOWER(n.title) LIKE ? ESCAPE '\\' OR LOWER(n.description) LIKE ? ESCAPE '\\')");
            args.add(pattern); args.add(pattern);
        }
        long total=db.queryForObject("SELECT COUNT(*) FROM notices n WHERE "+where,Long.class,args.toArray());
        List<Object> pageArgs=new ArrayList<>(args); pageArgs.add(size); pageArgs.add((long)page*size);
        var rows=db.query("SELECT n.* FROM notices n WHERE "+where+" ORDER BY n.created_at DESC, n.id LIMIT ? OFFSET ?",this::row,pageArgs.toArray());
        return new Page(rows.stream().map(r->view(r,actor)).toList(),page,size,(page+1L)*size<total,total);
    }
    private String blank(String value) {return value==null?"":value.strip();}
    private LocalDate calendarDate(String raw,String field,Map<String,String> errors) {
        String value=blank(raw); if(value.isEmpty()) return null;
        try {return LocalDate.parse(value);} catch(RuntimeException ex) {errors.put(field,"Enter a valid date."); return null;}
    }
    private boolean openOnly(String raw,Map<String,String> errors) {
        String value=blank(raw);
        if(value.isEmpty()||value.equalsIgnoreCase("false")) return false;
        if(value.equalsIgnoreCase("true")) return true;
        errors.put("openOnly","Choose available notices or Open only.");
        return false;
    }
    private String likePattern(String token) {
        return "%"+token.toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
    }
    private Write validate(Write in) {
        Map<String,String> e=new LinkedHashMap<>();
        if(in==null) throw ApiProblem.invalid(Map.of("notice","Notice details are required."));
        String title=in.title()==null?"":in.title().strip(), desc=in.description()==null?"":in.description().strip();
        if(!List.of("LOST","FOUND").contains(in.type()==null?"":in.type())) e.put("type","Choose Lost or Found.");
        if(length(title)<5||length(title)>80) e.put("title","Use 5–80 Unicode characters.");
        if(length(desc)<20||length(desc)>1000) e.put("description","Use 20–1,000 Unicode characters and keep ownership evidence private.");
        if(in.category()==null||!CATEGORIES.contains(in.category())) e.put("category","Choose a listed category.");
        if(in.area()==null||!AREAS.contains(in.area())) e.put("area","Choose a listed campus area.");
        try {if(LocalDate.parse(in.occurrenceDate()).isAfter(today())) e.put("occurrenceDate","The date cannot be later than today in Asia/Macau.");}
        catch(Exception ex) {e.put("occurrenceDate","Enter a valid occurrence date.");}
        var retained=in.retainedPhotoIds()==null?List.<String>of():in.retainedPhotoIds();
        if(retained.size()>3||new HashSet<>(retained).size()!=retained.size()||retained.stream().anyMatch(Objects::isNull)) e.put("photos","Choose at most 3 distinct photos.");
        if(!e.isEmpty()) throw ApiProblem.invalid(e);
        return new Write(in.type(),title,in.category(),in.occurrenceDate(),Boolean.TRUE.equals(in.approximate()),in.area(),desc,in.version(),retained);
    }
    private int length(String s) {return s.codePointCount(0,s.length());}
    // This common transaction gate is also the commit-order seam for future claim and preference modules.
    private long gate() {return db.queryForObject("SELECT counter FROM commit_order WHERE id=1 FOR UPDATE",Long.class);}
    private String key(String key) {
        if(key==null||!key.matches("[A-Za-z0-9_-]{8,128}")) throw ApiProblem.invalid(Map.of("requestKey","A valid Idempotency-Key is required."));
        return key;
    }
    private String fingerprint(Object request,List<PreparedPhoto> photos) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");digest.update(json.writeValueAsBytes(request));
            for(var photo:photos) {digest.update(photo.contentType().getBytes(StandardCharsets.UTF_8));digest.update(MessageDigest.getInstance("SHA-256").digest(photo.data()));}
            return HexFormat.of().formatHex(digest.digest());
        } catch(Exception e) {throw new IllegalStateException("Cannot fingerprint request",e);}
    }
    private String replay(Actor actor,String operation,String key,String hash) {
        var entries=db.query("SELECT request_hash,notice_id FROM idempotency_records WHERE actor_id=? AND operation=? AND request_key=?",(r,n)->List.of(r.getString(1),r.getString(2)),actor.id(),operation,key);
        if(entries.isEmpty()) return null;
        if(!entries.getFirst().getFirst().equals(hash)) throw ApiProblem.conflict("This request key was already used for different content. Submit again with a new key.");
        Row latest=require(entries.getFirst().get(1),false);owner(latest,actor);
        return latest.id();
    }
    private void remember(Actor actor,String operation,String key,String hash,String id) {
        db.update("INSERT INTO idempotency_records(actor_id,operation,request_key,request_hash,notice_id) VALUES(?,?,?,?,?)",actor.id(),operation,key,hash,id);
    }
    @Transactional
    public View create(Write input,List<PreparedPhoto> photos,String requestKey,Actor actor) {
        Write in=validate(input);String key=key(requestKey),hash=fingerprint(in,photos);long order=gate();
        String previous=replay(actor,"CREATE",key,hash);if(previous!=null) return get(previous,actor);
        if(!in.retainedPhotoIds().isEmpty()||photos.size()>3) throw ApiProblem.invalid(Map.of("photos","A new notice may have at most 3 new photos."));
        String id=UUID.randomUUID().toString(),time=now();
        db.update("INSERT INTO notices(id,author_id,author_name,type,title,category,occurrence_date,approximate,area,description,status,hidden,version,material_version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,'OPEN',FALSE,1,1,?,?)",
                id,actor.id(),actor.displayName(),in.type(),in.title(),in.category(),LocalDate.parse(in.occurrenceDate()),in.approximate(),in.area(),in.description(),time,time);
        savePhotos(id,photos);
        record(require(id,false),actor,"CREATE","",List.of("type","title","category","occurrenceDate","approximate","area","description","photos"),null,"PUBLISHED",order+1);
        remember(actor,"CREATE",key,hash,id);return get(id,actor);
    }
    @Transactional
    public View update(String id,Write input,List<PreparedPhoto> photos,String requestKey,Actor actor) {
        Write in=validate(input);String key=key(requestKey),operation="UPDATE:"+id,hash=fingerprint(in,photos);long order=gate();
        Row old=require(id,true);owner(old,actor);
        String previous=replay(actor,operation,key,hash);if(previous!=null) return get(previous,actor);
        if(in.version()==null) throw ApiProblem.invalid(Map.of("version","Reload the notice version before editing."));
        editable(old,in.version());
        var existing=db.queryForList("SELECT id FROM notice_photos WHERE notice_id=? AND active=TRUE",String.class,id);
        if(in.retainedPhotoIds().size()+photos.size()>3||!existing.containsAll(in.retainedPhotoIds())) throw ApiProblem.invalid(Map.of("photos","Keep only this notice's current photos, up to 3 photos in total."));
        var fields=new ArrayList<String>();
        if(!old.type().equals(in.type())) fields.add("type");
        if(!old.title().equals(in.title())) fields.add("title");
        if(!old.category().equals(in.category())) fields.add("category");
        if(!old.date().equals(in.occurrenceDate())) fields.add("occurrenceDate");
        if(old.approximate()!=in.approximate()) fields.add("approximate");
        if(!old.area().equals(in.area())) fields.add("area");
        if(!old.description().equals(in.description())) fields.add("description");
        if(!new HashSet<>(existing).equals(new HashSet<>(in.retainedPhotoIds()))||!photos.isEmpty()) fields.add("photos");
        if(fields.isEmpty()) {remember(actor,operation,key,hash,id);return get(id,actor);}
        boolean material=fields.stream().anyMatch(Set.of("title","category","occurrenceDate","area","description")::contains);
        String time=now();
        int count=db.update("UPDATE notices SET type=?,title=?,category=?,occurrence_date=?,approximate=?,area=?,description=?,version=?,material_version=?,updated_at=? WHERE id=? AND version=?",
                in.type(),in.title(),in.category(),LocalDate.parse(in.occurrenceDate()),in.approximate(),in.area(),in.description(),old.version()+1,old.materialVersion()+(material?1:0),time,id,old.version());
        if(count!=1) throw ApiProblem.conflict("This notice has changed. Refresh before saving.");
        db.update("UPDATE notice_photos SET active=FALSE WHERE notice_id=?",id);
        for(String photoId:in.retainedPhotoIds()) db.update("UPDATE notice_photos SET active=TRUE WHERE notice_id=? AND id=?",id,photoId);
        savePhotos(id,photos);
        String kind=fields.contains("type")?"TYPE_CHANGED":material?"MATERIAL_CHANGED":"NOTICE_UPDATED";
        record(require(id,false),actor,"UPDATE","",fields,old.status(),kind,order+1);
        remember(actor,operation,key,hash,id);return get(id,actor);
    }
    @Transactional
    public View close(String id,Close request,String requestKey,Actor actor) {
        if(request==null||request.version()==null) throw ApiProblem.invalid(Map.of("version","The current notice version is required."));
        String reason=request.reason()==null?"":request.reason().strip();
        if(reason.isBlank()||length(reason)>1000) throw ApiProblem.invalid(Map.of("reason","Give a public archive reason of 1–1,000 Unicode characters."));
        String key=key(requestKey),operation="CLOSE:"+id,hash=fingerprint(new Close(request.version(),reason),List.of());long order=gate();
        Row old=require(id,true);owner(old,actor);
        String previous=replay(actor,operation,key,hash);if(previous!=null) return get(previous,actor);
        editable(old,request.version());
        db.update("UPDATE notices SET status='CLOSED',closed_reason=?,version=version+1,updated_at=? WHERE id=?",reason,now(),id);
        record(require(id,false),actor,"ARCHIVE",reason,List.of("status"),old.status(),"ARCHIVED",order+1);
        remember(actor,operation,key,hash,id);return get(id,actor);
    }
    private void savePhotos(String id,List<PreparedPhoto> photos) {
        for(var p:photos) db.update("INSERT INTO notice_photos(id,notice_id,content_type,data,active) VALUES(?,?,?,?,TRUE)",p.id(),id,p.contentType(),p.data());
    }
    private void record(Row row,Actor actor,String action,String reason,List<String> fields,String previous,String kind,long order) {
        Map<String,Object> snapshot=new LinkedHashMap<>();
        snapshot.put("type",row.type());snapshot.put("title",row.title());snapshot.put("category",row.category());snapshot.put("occurrenceDate",row.date());
        snapshot.put("approximate",row.approximate());snapshot.put("area",row.area());snapshot.put("description",row.description());snapshot.put("status",row.status());
        snapshot.put("photoIds",db.queryForList("SELECT id FROM notice_photos WHERE notice_id=? AND active=TRUE ORDER BY id",String.class,row.id()));
        db.update("INSERT INTO notice_revisions(notice_id,version,action,actor,occurred_at,reason,changed_fields,status,snapshot_json) VALUES(?,?,?,?,?,?,?,?,?)",row.id(),row.version(),action,actor.displayName(),row.updatedAt(),reason,encode(fields),row.status(),encode(snapshot));
        db.update("INSERT INTO notice_events(id,notice_id,kind,version,material_version,trigger_order,created_at) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),row.id(),kind,row.version(),row.materialVersion(),order,row.updatedAt());
        db.update("INSERT INTO notice_audit(id,notice_id,actor_id,action,previous_state,new_state,occurred_at) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),row.id(),actor.id(),action,previous,row.status(),row.updatedAt());
        db.update("UPDATE commit_order SET counter=? WHERE id=1",order);
        scheduleDelivery();
    }
    // Alert rows are written after this notice transaction commits, using the stored trigger order.
    private void scheduleDelivery() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { alerts.deliverQuietly(); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { alerts.deliverQuietly(); }
        });
    }
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot encode notice record",e);}}
    private List<String> strings(String value) {try{return json.readValue(value,new TypeReference<List<String>>(){});}catch(Exception e){throw new IllegalStateException("Cannot read history",e);}}
}
