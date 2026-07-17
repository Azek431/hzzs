package top.azek431.hzzs.runtime.vision

enum class VisionAlgorithm(
    val preferenceValue: String,
    val displayName: String,
    val workWidth: Int,
) {
    BAMBOO_STUDY("bamboo_study", "竹影书屋（新赛季，默认）", 320),
    SWEET_FACTORY_LEGACY("sweet_factory_legacy", "甜品工厂（原算法）", 480),
    ;

    companion object {
        val DEFAULT = BAMBOO_STUDY

        fun fromPreference(value: String?): VisionAlgorithm =
            entries.firstOrNull { it.preferenceValue == value } ?: DEFAULT
    }
}
