package edu.must.campuslostfound;

import java.util.List;

final class NoticeModels {
    private NoticeModels() {}
    record Write(String type, String title, String category, String occurrenceDate, Boolean approximate,
                 String area, String description, Long version, List<String> retainedPhotoIds) {}
    record Close(Long version, String reason) {}
    record Photo(String id, String url, String contentType) {}
    record History(long version,String action,String actor,String occurredAt,String reason,List<String> changedFields,String status) {}
    record View(String id,String type,String title,String category,String occurrenceDate,boolean approximate,
                String area,String description,String status,long version,long materialVersion,String createdAt,String updatedAt,
                boolean canEdit,boolean canClose,String lockReason,List<Photo> photos,List<History> history) {}
    record Page(List<View> items,int page,int size,boolean hasNext,long totalElements) {}
    record PreparedPhoto(String id,String contentType,byte[] data) {}
    record PhotoContent(String contentType,byte[] data) {}
    record Actor(String id,String displayName) {}
}
