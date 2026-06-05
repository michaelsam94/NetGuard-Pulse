package com.michael.netguardplus.data.parental

import android.content.Context
import android.content.SharedPreferences
import com.michael.netguardplus.domain.model.FamilyDnsProvider
import com.michael.netguardplus.domain.model.ParentalCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ParentalControlStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _enabledCategories = MutableStateFlow(readEnabledCategories())
    val enabledCategories: StateFlow<Set<ParentalCategory>> = _enabledCategories.asStateFlow()

    private val _selectedDnsProvider = MutableStateFlow(readDnsProvider())
    val selectedDnsProvider: StateFlow<FamilyDnsProvider> = _selectedDnsProvider.asStateFlow()

    fun setCategoryEnabled(category: ParentalCategory, enabled: Boolean) {
        val updated = _enabledCategories.value.toMutableSet()
        if (enabled) updated.add(category) else updated.remove(category)
        prefs.edit()
            .putStringSet(KEY_ENABLED_CATEGORIES, updated.map { it.name }.toSet())
            .apply()
        _enabledCategories.value = updated
    }

    fun setDnsProvider(provider: FamilyDnsProvider) {
        prefs.edit()
            .putString(KEY_DNS_PROVIDER, provider.name)
            .apply()
        _selectedDnsProvider.value = provider
    }

    fun upstreamDnsServers(): List<String> {
        return _selectedDnsProvider.value.serverIps
    }

    private fun readEnabledCategories(): Set<ParentalCategory> {
        val stored = prefs.getStringSet(KEY_ENABLED_CATEGORIES, emptySet()).orEmpty()
        return stored.mapNotNull { name ->
            runCatching { ParentalCategory.valueOf(name) }.getOrNull()
        }.toSet()
    }

    private fun readDnsProvider(): FamilyDnsProvider {
        val name = prefs.getString(KEY_DNS_PROVIDER, FamilyDnsProvider.SYSTEM_DEFAULT.name)
        return runCatching { FamilyDnsProvider.valueOf(name!!) }
            .getOrDefault(FamilyDnsProvider.SYSTEM_DEFAULT)
    }

    companion object {
        private const val PREFS_NAME = "netguard_parental_prefs"
        private const val KEY_ENABLED_CATEGORIES = "enabled_categories"
        private const val KEY_DNS_PROVIDER = "dns_provider"
    }
}
