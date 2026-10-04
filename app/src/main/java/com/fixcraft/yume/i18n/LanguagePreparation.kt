package com.fixcraft.yume.i18n

enum class LanguagePreparationStage { Downloading, Translating }

data class LanguagePreparationProgress(
    val stage: LanguagePreparationStage,
    val done: Int,
    val total: Int,
)
