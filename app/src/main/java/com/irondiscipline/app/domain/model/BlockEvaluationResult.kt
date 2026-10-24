package com.irondiscipline.app.domain.model

enum class BlockReasonType {
    NONE,
    SCHEDULE_WINDOW,
    CUSTOM_APP_SCHEDULE,
    DAILY_QUOTA_EXHAUSTED,
    BROWSER_SOCIAL_DOMAIN,
    STRICT_MODE_SETTINGS_GUARD
}

data class BlockEvaluationResult(
    val shouldBlock: Boolean,
    val targetKey: String = "",
    val displayTitle: String = "",
    val reasonType: BlockReasonType = BlockReasonType.NONE,
    val reasonDetail: String = "",
    val canUseEmergencyOverride: Boolean = false,
    val currentStreakDays: Int = 1,
    val todayDeepWorkMinutes: Int = 0,
    val motivationalQuote: String = ""
)
