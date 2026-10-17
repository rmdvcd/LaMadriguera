package com.lamadriguera.app.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.PrivacyLevel
import com.lamadriguera.app.domain.model.Profile
import com.lamadriguera.app.domain.model.RevealPolicy
import com.lamadriguera.app.domain.repository.AuthRepository
import com.lamadriguera.app.domain.repository.ListingsRepository
import com.lamadriguera.app.domain.repository.ProfilesRepository
import com.lamadriguera.app.domain.usecase.AliasGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Configuración de privacidad: nivel por defecto (public/anon), política de
 * revelación en el chat y alias. El nivel por defecto solo puede cambiar antes
 * de publicar.
 */
data class PrivacySettingsUiState(
    val isLoading: Boolean = true,
    val profile: Profile? = null,
    val privacyLevel: PrivacyLevel = PrivacyLevel.PUBLIC,
    val revealPolicy: RevealPolicy = RevealPolicy.NEVER,
    val aliasInput: String = "",
    val hasPublished: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val saved: Boolean = false
) {
    /** Solo puedes cambiar el nivel antes de publicar. */
    val canChangePrivacyLevel: Boolean
        get() = !hasPublished && !isSaving
}

@HiltViewModel
class PrivacySettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val profiles: ProfilesRepository,
    private val listings: ListingsRepository,
    private val aliasGenerator: AliasGenerator
) : ViewModel() {

    private val _uiState = MutableStateFlow(PrivacySettingsUiState())
    val uiState: StateFlow<PrivacySettingsUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null, saved = false) }
        viewModelScope.launch {
            val session = when (val result = authRepository.currentSession()) {
                is Result.Success -> result.data
                is Result.Error -> null
            }
            val userId = session?.user?.id
            if (userId == null) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "Inicia sesión para configurar tu privacidad")
                }
                return@launch
            }
            when (val result = profiles.find(userId)) {
                is Result.Error -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.message ?: "No se pudo cargar tu configuración"
                    )
                }
                is Result.Success -> {
                    val profile = result.data
                    val published = when (val check = listings.hasPublished(userId)) {
                        is Result.Success -> check.data
                        is Result.Error -> false
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            profile = profile,
                            privacyLevel = profile?.privacyLevel ?: PrivacyLevel.PUBLIC,
                            revealPolicy = profile?.defaultRevealPolicy ?: RevealPolicy.NEVER,
                            aliasInput = profile?.alias.orEmpty(),
                            hasPublished = published
                        )
                    }
                }
            }
        }
    }

    fun onPrivacyLevelChanged(level: PrivacyLevel) {
        if (!_uiState.value.canChangePrivacyLevel) return
        _uiState.update { it.copy(privacyLevel = level, errorMessage = null, saved = false) }
    }

    fun onRevealPolicyChanged(policy: RevealPolicy) {
        _uiState.update { it.copy(revealPolicy = policy, errorMessage = null, saved = false) }
    }

    fun onAliasChanged(alias: String) {
        _uiState.update { it.copy(aliasInput = alias, errorMessage = null, saved = false) }
    }

    /**
     * Guarda la configuración. Si se elige 'anon' sin alias definido, se genera
     * uno automáticamente.
     */
    fun save() {
        val state = _uiState.value
        val profile = state.profile
        if (profile == null || state.isSaving) return
        if (state.hasPublished && state.privacyLevel != profile.privacyLevel) {
            _uiState.update {
                it.copy(errorMessage = "Solo puedes cambiar el nivel antes de publicar")
            }
            return
        }
        val resolvedAlias = aliasGenerator(profile.userId, state.privacyLevel, state.aliasInput)
        _uiState.update { it.copy(isSaving = true, errorMessage = null, saved = false) }
        viewModelScope.launch {
            val updated = profile.copy(
                privacyLevel = state.privacyLevel,
                defaultRevealPolicy = state.revealPolicy,
                alias = resolvedAlias
            )
            _uiState.value = when (val result = profiles.update(profile.userId, updated)) {
                is Result.Success -> _uiState.value.copy(
                    isSaving = false,
                    profile = result.data,
                    aliasInput = result.data.alias.orEmpty(),
                    saved = true
                )
                is Result.Error -> _uiState.value.copy(
                    isSaving = false,
                    errorMessage = result.message ?: "No se pudo guardar la configuración"
                )
            }
        }
    }
}
