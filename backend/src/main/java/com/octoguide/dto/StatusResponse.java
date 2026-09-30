package com.octoguide.dto;

/** 状态统计接口响应。 */
public class StatusResponse {
    private final long knowledgeCount;
    private final long termCount;
    private final boolean aiConfigured;
    private final boolean webResearchEnabled;
    private final long autoCount;
    private final long manualCount;
    private final long totalCount;
    private final String autoUpdatedAt;
    private final String schedule;
    private final java.util.Map<String, Long> sourceBreakdown;
    private final String aiModel;
    private final long manualCategoryCount;
    private final long autoCategoryCount;

    public StatusResponse(long knowledgeCount, long termCount, boolean aiConfigured, boolean webResearchEnabled) {
        this(knowledgeCount, knowledgeCount, knowledgeCount, termCount, aiConfigured, webResearchEnabled,
                null, "每周日 18:00 自动更新", singletonOther(knowledgeCount), null, 0, 0);
    }

    public StatusResponse(long autoCount, long manualCount, long totalCount, long termCount,
                          boolean aiConfigured, boolean webResearchEnabled, String autoUpdatedAt,
                          String schedule, java.util.Map<String, Long> sourceBreakdown, String aiModel,
                          long manualCategoryCount, long autoCategoryCount) {
        this.knowledgeCount = totalCount;
        this.termCount = termCount;
        this.aiConfigured = aiConfigured;
        this.webResearchEnabled = webResearchEnabled;
        this.autoCount = autoCount;
        this.manualCount = manualCount;
        this.totalCount = totalCount;
        this.autoUpdatedAt = autoUpdatedAt;
        this.schedule = schedule;
        this.sourceBreakdown = sourceBreakdown;
        this.aiModel = aiModel;
        this.manualCategoryCount = manualCategoryCount;
        this.autoCategoryCount = autoCategoryCount;
    }

    private static java.util.Map<String, Long> singletonOther(long count) {
        java.util.Map<String, Long> result = new java.util.LinkedHashMap<String, Long>();
        result.put("other", count);
        return result;
    }

    public long getKnowledgeCount() { return knowledgeCount; }
    public long getTermCount() { return termCount; }
    public boolean isAiConfigured() { return aiConfigured; }
    public boolean isWebResearchEnabled() { return webResearchEnabled; }
    public long getAutoCount() { return autoCount; }
    public long getManualCount() { return manualCount; }
    public long getTotalCount() { return totalCount; }
    public String getAutoUpdatedAt() { return autoUpdatedAt; }
    public String getSchedule() { return schedule; }
    public java.util.Map<String, Long> getSourceBreakdown() { return sourceBreakdown; }
    public String getAiModel() { return aiModel; }
    public long getManualCategoryCount() { return manualCategoryCount; }
    public long getAutoCategoryCount() { return autoCategoryCount; }
}
