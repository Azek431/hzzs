package top.azek431.hzzs.runtime.vision

enum class VisionAlgorithm(
    val preferenceValue: String,
    val displayName: String,
    val workWidth: Int,
    val automaticActionCalibrated: Boolean,
) {
    BAMBOO_STUDY(
        preferenceValue = "bamboo_study",
        displayName = "竹影书屋（新赛季，默认）",
        workWidth = 320,
        automaticActionCalibrated = false,
    ),
    SWEET_FACTORY_LEGACY(
        preferenceValue = "sweet_factory_legacy",
        displayName = "甜品工厂（原算法）",
        workWidth = 480,
        automaticActionCalibrated = true,
    ),
    ;

    companion object {
        val DEFAULT = BAMBOO_STUDY

        fun fromPreference(value: String?): VisionAlgorithm =
            entries.firstOrNull { it.preferenceValue == value } ?: DEFAULT
    }
}
