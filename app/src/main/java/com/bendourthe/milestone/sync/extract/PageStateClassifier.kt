package com.bendourthe.milestone.sync.extract

import com.bendourthe.milestone.domain.ExtractionSpec
import com.bendourthe.milestone.domain.Program
import com.bendourthe.milestone.domain.SyncCapability
import com.bendourthe.milestone.sync.webview.HostPolicy

enum class PageState { LOGIN, DASHBOARD, OTHER }

data class PageSignals(val hasVisiblePasswordField: Boolean)

object PageStateClassifier {
    fun classify(url: String, signals: PageSignals, program: Program): PageState {
        val onIdentityProvider = program.identityProviderHosts.isNotEmpty() && HostPolicy.isAllowed(url, program.identityProviderHosts)
        val onProgramSite = HostPolicy.isAllowed(url, program.allowedHosts)
        if (!onProgramSite && !onIdentityProvider) return PageState.OTHER
        if (onIdentityProvider || signals.hasVisiblePasswordField) return PageState.LOGIN
        val spec = (program.syncCapability as? SyncCapability.Automatic)?.spec as? ExtractionSpec.Selector
            ?: return PageState.OTHER
        return when {
            spec.loginUrlPattern.containsMatchIn(url) -> PageState.LOGIN
            spec.loggedInUrlPattern.containsMatchIn(url) -> PageState.DASHBOARD
            else -> PageState.OTHER
        }
    }
}
