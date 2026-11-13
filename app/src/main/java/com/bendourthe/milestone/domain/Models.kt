package com.bendourthe.milestone.domain

import java.math.BigDecimal
import java.net.URI
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class ProgramCategory(val label: String) {
    CREDIT_CARD("Credit cards"),
    AIRLINE("Airlines"),
    HOTEL("Hotels"),
}

enum class SelectorStrategy { CSS, XPATH }

/** How a program's balance is located on its dashboard page. */
sealed interface ExtractionSpec {
    data class Selector(
        val strategy: SelectorStrategy,
        val expression: String,
        val loggedInUrlPattern: Regex,
        val loginUrlPattern: Regex,
        /** A CSS selector for the control that shows the balance (Marriott's account panel), pressed once if needed. */
        val revealSelector: String? = null,
    ) : ExtractionSpec
}

/** What a balance counts. Cash balances are stored as whole cents. */
enum class BalanceUnit(val typeLabel: String) {
    POINTS("Points and miles"),
    CASH_CENTS("Cash back"),
}

/**
 * Where and how a program's card benefits are read (ADR-0008). [entryUrl] opens the read; card and benefit
 * messages are accepted only from https pages on [host] whose path starts with one of [paths]. [selectors] are
 * CSS selectors the page script reads by name (keys listed in [SELECTOR_KEYS]).
 */
data class BenefitsSpec(
    val entryUrl: String,
    val host: String,
    val paths: Set<String>,
    val selectors: Map<String, String>,
) {
    init {
        require(entryUrl.startsWith("https://$host/")) { "benefits entryUrl must be https on $host" }
        require(paths.isNotEmpty() && paths.all { it.startsWith("/") }) { "benefits paths must be absolute" }
        require(selectors.keys == SELECTOR_KEYS) { "benefits selectors must be exactly $SELECTOR_KEYS" }
    }

    /** True only for an https address on [host] whose path is one of [paths] or below it. */
    fun accepts(url: String?): Boolean {
        val uri = runCatching { URI(url ?: return false) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.host != host || uri.port != -1) return false
        val path = uri.rawPath.orEmpty()
        return paths.any { path == it || path.startsWith("$it/") }
    }

    /**
     * The benefits page kind of [url] by path prefix, as `extract.js` classifies it (ADR-0008, 2026-11-12
     * clarification): activity, then viewAll, then rewards; null for any other address.
     */
    fun kindOf(url: String?): String? {
        val path = runCatching { URI(url ?: return null) }.getOrNull()?.rawPath ?: return null
        return listOf("activity" to "activityPath", "viewAll" to "viewAllPath", "rewards" to "rewardsPath")
            .firstOrNull { (_, key) -> selectors[key]?.let { path.startsWith(it) } == true }?.first
    }

    companion object {
        val SELECTOR_KEYS = setOf(
            "combobox", "option", "optionName", "optionDigits", "selected",
            "tracker", "trackerHeading", "trackerSpent", "trackerSpentSection", "trackerRemaining", "trackerDone",
            "trackerDescription", "listLink", "listMore", "listDescription", "activityLink", "activityPath", "viewAllLink",
            "rewardsPath", "viewAllPath",
        )
    }
}

sealed interface SyncCapability {
    data class Automatic(val spec: ExtractionSpec) : SyncCapability
    data class ManualOnly(val reason: String) : SyncCapability
}

data class Program(
    val programId: String,
    val displayName: String,
    val category: ProgramCategory,
    val monogram: String,
    val logoKey: String?,
    val isPopular: Boolean,
    val brandColorArgb: Long,
    val unitLabel: String,
    val loginUrl: String,
    val dashboardUrl: String,
    val allowedHosts: Set<String>,
    val identityProviderHosts: Set<String>,
    val syncCapability: SyncCapability,
    val balanceUnit: BalanceUnit = BalanceUnit.POINTS,
    /** Lowercase names a verification text from this program contains; empty means codes are never read for it. */
    val textKeywords: Set<String> = emptySet(),
    /** The site's password sign-in also asks for the member's last name (Hyatt). */
    val asksLastName: Boolean = false,
    /** Where the program's card benefits are read; null for programs without a Benefits tab entry. */
    val benefits: BenefitsSpec? = null,
) {
    init {
        require(programId.isNotBlank()) { "programId is blank" }
        require(monogram.length in 1..3) { "$programId: monogram must be 1 to 3 characters" }
        require(isHttps(loginUrl)) { "$programId: loginUrl must be https" }
        require(isHttps(dashboardUrl)) { "$programId: dashboardUrl must be https" }
        require(allowedHosts.isNotEmpty()) { "$programId: allowedHosts is empty" }
    }

    val canSync: Boolean get() = syncCapability is SyncCapability.Automatic

    /** "142,500 pts" for points, "$4.56 cash" for cash held in cents. */
    fun formatBalance(value: Long, locale: Locale): String = when (balanceUnit) {
        BalanceUnit.POINTS -> "${NumberFormat.getIntegerInstance(locale).format(value)} $unitLabel"
        BalanceUnit.CASH_CENTS -> "${NumberFormat.getCurrencyInstance(Locale.US).format(BigDecimal.valueOf(value, 2))} $unitLabel"
    }

    private companion object {
        fun isHttps(url: String): Boolean =
            runCatching { URI(url) }.getOrNull()?.let { it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrEmpty() } == true
    }
}

enum class BalanceOrigin { SCRAPED }

enum class AccountStatus { CONNECTED, NEEDS_RECONNECT, ERROR, UNSUPPORTED }

enum class SortMode(val label: String) {
    BY_CATEGORY("By category"),
    BY_TYPE("By type"),
    ALPHABETICAL("Alphabetical"),
    CUSTOM("Custom"),
}

/**
 * When every program refreshes by itself, at local midnight; the refresh button and a tile's menu refresh any time.
 * Opening MileStone catches up only on a refresh the phone missed.
 */
enum class RefreshInterval(val label: String) {
    DAILY("Every day at midnight"),
    WEEKLY("Every Sunday at midnight"),
    MONTHLY("Last day of the month at midnight");

    /** The latest scheduled refresh time at or before [nowMs]. */
    fun lastAt(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val date = when (this) {
            DAILY -> today
            WEEKLY -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            MONTHLY -> today.with(TemporalAdjusters.lastDayOfMonth()).let { if (it.isAfter(today)) today.minusMonths(1).with(TemporalAdjusters.lastDayOfMonth()) else it }
        }
        return date.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** The first scheduled refresh time after [nowMs]. */
    fun nextAt(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val date = when (this) {
            DAILY -> today.plusDays(1)
            WEEKLY -> today.with(TemporalAdjusters.next(DayOfWeek.SUNDAY))
            MONTHLY -> today.with(TemporalAdjusters.lastDayOfMonth()).let { if (!it.isAfter(today)) today.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth()) else it }
        }
        return date.atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

sealed class FailureReason(val code: String) {
    data object Timeout : FailureReason("timeout")
    data object NeedsAttention : FailureReason("needs_attention")
    data class Network(val httpCode: Int?) : FailureReason("network")
    data object Tls : FailureReason("tls")
    data object Unparseable : FailureReason("unparseable")
    data object SelectorAmbiguous : FailureReason("selector_ambiguous")
    data object Implausible : FailureReason("implausible")
    data object RendererGone : FailureReason("renderer_gone")
    data object Unexpected : FailureReason("unexpected")

    /** The site showed its sign-in form again after MileStone submitted the saved password. */
    data object PasswordRefused : FailureReason("password_refused")

    /** The site asked for a texted code that MileStone could not enter. */
    data object CodeMissing : FailureReason("code_missing")

    /** MileStone's sign-in stopped on a page it does not recognize, or found no form to fill. */
    data object SignInUnfinished : FailureReason("signin_unfinished")

    /** A stopped automatic sign-in, as opposed to a failure reading the balance. */
    val isSignInStop: Boolean get() = this == PasswordRefused || this == CodeMissing || this == SignInUnfinished

    companion object {
        /** Maps a stored code back to a reason; unknown codes become [Unexpected]. */
        fun fromCode(code: String?): FailureReason? = when (code) {
            null -> null
            Timeout.code -> Timeout
            NeedsAttention.code -> NeedsAttention
            "network" -> Network(null)
            Tls.code -> Tls
            Unparseable.code -> Unparseable
            SelectorAmbiguous.code -> SelectorAmbiguous
            Implausible.code -> Implausible
            RendererGone.code -> RendererGone
            PasswordRefused.code -> PasswordRefused
            CodeMissing.code -> CodeMissing
            SignInUnfinished.code -> SignInUnfinished
            else -> Unexpected
        }
    }
}

sealed interface SyncResult {
    data class Success(val balance: Long) : SyncResult
    data object NeedsReconnect : SyncResult
    data class Failure(val reason: FailureReason) : SyncResult
    data object Unsupported : SyncResult
}

data class LoyaltyAccount(
    val programId: String,
    val balance: Long?,
    val balanceOrigin: BalanceOrigin?,
    val lastUpdatedEpochMs: Long?,
    val lastAttemptEpochMs: Long?,
    val consecutiveFailures: Int,
    val sortOrder: Int,
    val status: AccountStatus,
    val lastError: FailureReason?,
)

data class BalanceSnapshot(val programId: String, val balance: Long, val origin: BalanceOrigin, val epochMs: Long)
