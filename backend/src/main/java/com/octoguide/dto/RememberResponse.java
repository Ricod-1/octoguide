package com.octoguide.dto;

/** 知识记忆接口响应，说明保存成功或命中重复条目。 */
public class RememberResponse {
    private final boolean ok; private final boolean duplicate; private final String id; private final String existingId;
    public RememberResponse(boolean ok, boolean duplicate, String id) { this(ok, duplicate, id, null); }
    public RememberResponse(boolean ok, boolean duplicate, String id, String existingId) { this.ok=ok; this.duplicate=duplicate; this.id=id; this.existingId=existingId; }
    public boolean isOk() { return ok; } public boolean isDuplicate() { return duplicate; } public String getId() { return id; } public String getExistingId() { return existingId; }
}
