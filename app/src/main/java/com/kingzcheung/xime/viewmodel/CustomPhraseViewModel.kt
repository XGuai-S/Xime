package com.kingzcheung.xime.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.settings.DictEntry
import com.kingzcheung.xime.settings.PersonalDictManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class CustomPhraseUiState(
    val entries: List<DictEntry> = emptyList(),
    val filteredEntries: List<DictEntry> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val showAddDialog: Boolean = false,
    val showEditDialog: Boolean = false,
    val editIndex: Int = -1,
    val editWord: String = "",
    val editCode: String = "",
    val editWeight: String = ""
)

class CustomPhraseViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private var schemaId: String? = null

    private val _uiState = MutableStateFlow(CustomPhraseUiState())
    val uiState: StateFlow<CustomPhraseUiState> = _uiState.asStateFlow()

    fun setSchema(id: String?) {
        if (schemaId == id) return
        schemaId = id
        loadEntries()
    }

    private fun loadEntries() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val entries = withContext(Dispatchers.IO) {
                PersonalDictManager.loadCustomPhrases(context, schemaId)
            }
            _uiState.update {
                it.copy(
                    entries = entries,
                    filteredEntries = filterEntries(entries, ""),
                    searchQuery = "",
                    isLoading = false
                )
            }
        }
    }

    fun addEntry(word: String, code: String, weight: Int? = null) {
        val trimmedWord = word.trim()
        val trimmedCode = code.trim()
        if (trimmedWord.isEmpty() || trimmedCode.isEmpty()) return
        viewModelScope.launch {
            val current = _uiState.value.entries
            // 同词同码去重：列表渲染用「词+码」作 key，重复会触发
            // LazyColumn 重复 key 崩溃；indexOf 反查也会命中错误条目
            val updated = current.filterNot { it.word == trimmedWord && it.code == trimmedCode } +
                DictEntry(trimmedWord, trimmedCode, weight)
            withContext(Dispatchers.IO) { PersonalDictManager.saveCustomPhrases(context, schemaId, updated) }
            _uiState.update {
                val query = it.searchQuery
                it.copy(entries = updated, filteredEntries = filterEntries(updated, query))
            }
        }
    }

    fun updateEntry(index: Int, word: String, code: String, weight: Int? = null) {
        val trimmedWord = word.trim()
        val trimmedCode = code.trim()
        if (trimmedWord.isEmpty() || trimmedCode.isEmpty()) return
        viewModelScope.launch {
            val current = _uiState.value.entries.toMutableList()
            if (index < 0 || index >= current.size) return@launch
            current[index] = DictEntry(trimmedWord, trimmedCode, weight)
            withContext(Dispatchers.IO) { PersonalDictManager.saveCustomPhrases(context, schemaId, current) }
            _uiState.update {
                val query = it.searchQuery
                it.copy(entries = current, filteredEntries = filterEntries(current, query))
            }
        }
    }

    fun deleteEntry(index: Int) {
        viewModelScope.launch {
            val current = _uiState.value.entries.toMutableList()
            if (index < 0 || index >= current.size) return@launch
            current.removeAt(index)
            withContext(Dispatchers.IO) { PersonalDictManager.saveCustomPhrases(context, schemaId, current) }
            _uiState.update {
                val query = it.searchQuery
                it.copy(entries = current, filteredEntries = filterEntries(current, query))
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update {
            it.copy(
                searchQuery = query,
                filteredEntries = filterEntries(it.entries, query)
            )
        }
    }

    fun clearSearch() {
        setSearchQuery("")
    }

    fun showAddDialog() {
        _uiState.update { it.copy(showAddDialog = true, editWord = "", editCode = "", editWeight = "", editIndex = -1) }
    }

    fun hideAddDialog() {
        _uiState.update { it.copy(showAddDialog = false) }
    }

    fun showEditDialog() {
        _uiState.update { it.copy(showEditDialog = true) }
    }

    fun hideEditDialog() {
        _uiState.update { it.copy(showEditDialog = false) }
    }

    fun setEditing(index: Int, entry: DictEntry) {
        _uiState.update { it.copy(
            editIndex = index,
            editWord = entry.word,
            editCode = entry.code,
            editWeight = entry.weight?.toString() ?: ""
        ) }
    }

    fun setEditWord(word: String) {
        _uiState.update { it.copy(editWord = word) }
    }

    fun setEditCode(code: String) {
        _uiState.update { it.copy(editCode = code) }
    }

    fun setEditWeight(weight: String) {
        _uiState.update { it.copy(editWeight = weight) }
    }

    private fun filterEntries(entries: List<DictEntry>, query: String): List<DictEntry> {
        if (query.isEmpty()) return entries
        val lowerQuery = query.lowercase(Locale.ROOT)
        return entries.filter {
            it.word.contains(query) ||
                it.code.contains(query, ignoreCase = true) ||
                it.code.lowercase(Locale.ROOT).contains(lowerQuery)
        }
    }
}
