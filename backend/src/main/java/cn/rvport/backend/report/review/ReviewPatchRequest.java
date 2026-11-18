package cn.rvport.backend.report.review;

import java.util.List;
import java.util.UUID;

public record ReviewPatchRequest(Long expectedVersion, List<Item> items) {
    public record Item(UUID id, ReviewAction action, String reason, String reasonEn) { }
}
