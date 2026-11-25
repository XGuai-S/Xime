package com.kingzcheung.xime.viewmodel

internal fun updateModelItemsById(
    items: List<ModelItemState>,
    modelId: String,
    transform: (ModelItemState) -> ModelItemState,
): List<ModelItemState> {
    val index = items.indexOfFirst { it.model.id == modelId }
    if (index < 0) return items
    return items.toMutableList().also { it[index] = transform(it[index]) }
}
