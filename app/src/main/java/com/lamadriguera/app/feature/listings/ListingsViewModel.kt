package com.lamadriguera.app.feature.listings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.Category
import com.lamadriguera.app.domain.model.CubanMunicipalities
import com.lamadriguera.app.domain.model.CubanMunicipality
import com.lamadriguera.app.domain.model.ListingKind
import com.lamadriguera.app.domain.model.ListingSearchFilter
import com.lamadriguera.app.domain.model.RankedListing
import com.lamadriguera.app.domain.model.SaleMode
import com.lamadriguera.app.domain.repository.AuthRepository
import com.lamadriguera.app.domain.repository.CategoriesRepository
import com.lamadriguera.app.domain.repository.ListingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Estado de UI del listado de publicaciones con búsqueda por municipio +
 * categoría + filtro offer/request y ordenación por ranking.
 */
data class ListingsUiState(
    val isLoading: Boolean = true,
    val query: String = "",
    val selectedMunicipalityId: Int? = null,
    val selectedCategoryId: String? = null,
    val selectedKind: ListingKind? = null,
    val selectedSaleMode: SaleMode? = null,
    val categories: List<Category> = emptyList(),
    val municipalities: List<CubanMunicipality> = CubanMunicipalities.all,
    val userMunicipalityId: Int? = null,
    val items: List<RankedListing> = emptyList(),
    val errorMessage: String? = null
)

@HiltViewModel
class ListingsViewModel @Inject constructor(
    private val listingsRepository: ListingsRepository,
    private val categoriesRepository: CategoriesRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ListingsUiState())
    val uiState: StateFlow<ListingsUiState> = _uiState.asStateFlow()

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            val userMunicipality = when (val session = authRepository.currentSession()) {
                is Result.Success -> session.data?.profile?.municipalityId
                is Result.Error -> null
            }
            val cats = when (val result = categoriesRepository.list(page = 0, pageSize = 100)) {
                is Result.Success -> result.data
                is Result.Error -> emptyList()
            }
            _uiState.update {
                it.copy(
                    userMunicipalityId = userMunicipality,
                    categories = cats
                )
            }
            refresh()
        }
    }

    fun onQueryChanged(query: String) {
        _uiState.update { it.copy(query = query) }
        refresh()
    }

    fun onMunicipalitySelected(municipalityId: Int?) {
        _uiState.update { it.copy(selectedMunicipalityId = municipalityId) }
        refresh()
    }

    fun onCategorySelected(categoryId: String?) {
        _uiState.update { it.copy(selectedCategoryId = categoryId) }
        refresh()
    }

    fun onKindSelected(kind: ListingKind?) {
        _uiState.update { it.copy(selectedKind = kind) }
        refresh()
    }

    fun onSaleModeSelected(saleMode: SaleMode?) {
        _uiState.update { it.copy(selectedSaleMode = saleMode) }
        refresh()
    }

    fun refresh() {
        val snapshot = _uiState.value
        val filter = ListingSearchFilter(
            query = snapshot.query,
            municipalityId = snapshot.selectedMunicipalityId,
            categoryId = snapshot.selectedCategoryId,
            kind = snapshot.selectedKind,
            saleMode = snapshot.selectedSaleMode
        )
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val referenceMunicipality = snapshot.selectedMunicipalityId ?: snapshot.userMunicipalityId
            when (val result = listingsRepository.searchAndRank(filter, referenceMunicipality)) {
                is Result.Success -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        items = result.data,
                        errorMessage = null
                    )
                }
                is Result.Error -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.message ?: "No se pudieron cargar las publicaciones"
                    )
                }
            }
        }
    }
}
