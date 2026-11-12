package com.bendourthe.milestone.data.catalog

import com.bendourthe.milestone.domain.BalanceUnit
import com.bendourthe.milestone.domain.CatalogRepository
import com.bendourthe.milestone.domain.CatalogSearch
import com.bendourthe.milestone.domain.ExtractionSpec
import com.bendourthe.milestone.domain.Program
import com.bendourthe.milestone.domain.ProgramCategory
import com.bendourthe.milestone.domain.ProgramCategory.AIRLINE
import com.bendourthe.milestone.domain.ProgramCategory.CREDIT_CARD
import com.bendourthe.milestone.domain.ProgramCategory.HOTEL
import com.bendourthe.milestone.domain.SelectorStrategy
import com.bendourthe.milestone.domain.SyncCapability

/**
 * The 14 supported programs. URLs, hosts, and capabilities come from
 * `docs/releases/v0/v0.1/development/v0.1.0-site-feasibility.md`; hosts must never be added from anywhere else.
 */
class StaticCatalogRepository : CatalogRepository {
    override val programs: List<Program> = PROGRAMS.sortedBy { it.displayName.lowercase() }

    private val byId = programs.associateBy { it.programId }

    override fun program(programId: String): Program? = byId[programId]

    override fun search(query: String): List<Program> = CatalogSearch.filter(programs, query)

    companion object {
        /** Programs with a `res/drawable-nodpi/logo_<id>.png`; the rest show their monogram. */
        private val BUNDLED_LOGOS = setOf(
            "amex", "chase", "capitalone", "citi", "delta", "united", "alaska",
            "aeroplan", "aadvantage", "marriott", "hyatt", "hilton",
        )

        private const val NO_ACCOUNT = "Not verified: no account was available to test sign-in."

        private fun css(selector: String, loggedIn: String, login: String) = SyncCapability.Automatic(
            ExtractionSpec.Selector(SelectorStrategy.CSS, selector, Regex(loggedIn), Regex(login)),
        )

        /** Verified against a signed-in capture: the available points in the account overview's loyalty row. */
        const val ALASKA_CSS = ".loyalty-info-row .available-points .display-md"

        /** Verified against a signed-in capture: the points beside the member name in the signed-in account menu. */
        const val AEROPLAN_CSS = ".ac-account-menu-button-logged-in .ac-account-menu-user-name-points .ac-labels-l-bold"

        /**
         * Verified against a signed-in capture: the header member card repeats three times (drawer, mobile nav, tab
         * bar) with generated class names; only the drawer copy has a stable ARIA label.
         */
        const val HYATT_CSS = "[role=dialog][aria-label='Your account'] .hbe-header_member-card-points-balance"

        /** Verified against a signed-in capture: the points in the member info block at the top of My Account. */
        const val HILTON_CSS = "[data-testid=honorsPointsBlock] span.font-headline"

        private fun xpath(expression: String, loggedIn: String, login: String) = SyncCapability.Automatic(
            ExtractionSpec.Selector(SelectorStrategy.XPATH, expression, Regex(loggedIn), Regex(login)),
        )

        /** Verified against a signed-in capture; the same overview also lists co-brand Delta and Hilton balances. */
        const val AMEX_XPATH = "//div[@data-testid='reward-balance']" +
            "[contains(following::text()[normalize-space()][1], 'Membership Rewards')]" +
            "/div[contains(@class, 'heading-sans-small-bold')]"

        /** The balance is only in the web component's `label` attribute; its text renders inside a shadow root. */
        const val CHASE_XPATH = "//mds-list[@data-testid='rewards-multi-navigation-tile-TileListId']" +
            "/mds-list-item[contains(@description, 'Ultimate Rewards')]/@label"

        /** Verified against a signed-in home page: the miles under the member name in the header account button. */
        const val DELTA_XPATH = "//div[@id='mach-core-login-flyout-name-container']/span[contains(., 'miles')]"

        /**
         * Verified against a signed-in My United page: the number under the MILES heading. Class names end
         * in a generated suffix, so only their stable prefix is matched; the header greeting and recent activity also
         * show miles and are left out.
         */
        const val UNITED_CSS = "[class*='MileageBalance__totalMiles'] > span"

        /** Verified against a signed-in home page: the points in the account panel the header's member button opens. */
        const val MARRIOTT_CSS = "[role=dialog][aria-labelledby=account-panel-label] h4[data-testid=points]"
        const val MARRIOTT_PANEL_BUTTON = "button[data-testid=common-static-SignInDrawer]"

        /** A card that earns Rewards cash: the amount is split into sign, dollars, and superscript cents. */
        const val CAPITALONE_XPATH = "//c1-ease-rewards-tile[.//*[contains(@class, 'labels__balance')][contains(., 'Rewards cash')]]" +
            "//div[contains(concat(' ', normalize-space(@class), ' '), ' primary-detail__balance ')]"

        private fun program(
            id: String,
            name: String,
            category: ProgramCategory,
            monogram: String,
            color: Long,
            unit: String,
            loginUrl: String,
            dashboardUrl: String,
            hosts: Set<String>,
            capability: SyncCapability,
            popular: Boolean,
            balanceUnit: BalanceUnit = BalanceUnit.POINTS,
            textKeywords: Set<String> = emptySet(),
            asksLastName: Boolean = false,
        ) = Program(
            programId = id,
            displayName = name,
            category = category,
            monogram = monogram,
            logoKey = "logo_$id".takeIf { id in BUNDLED_LOGOS },
            isPopular = popular,
            brandColorArgb = color,
            unitLabel = unit,
            loginUrl = loginUrl,
            dashboardUrl = dashboardUrl,
            allowedHosts = hosts,
            identityProviderHosts = emptySet(),
            syncCapability = capability,
            balanceUnit = balanceUnit,
            textKeywords = textKeywords,
            asksLastName = asksLastName,
        )

        private val PROGRAMS = listOf(
            program(
                "amex", "American Express", CREDIT_CARD, "AX", 0xFF006FCF, "pts",
                loginUrl = "https://www.americanexpress.com/en-us/account/login",
                dashboardUrl = "https://global.americanexpress.com/overview",
                hosts = setOf("www.americanexpress.com", "global.americanexpress.com"),
                capability = xpath(AMEX_XPATH, "^https://global\\.americanexpress\\.com/overview", "/account/login"),
                popular = true,
                textKeywords = setOf("american express", "amex"),
            ),
            program(
                "chase", "Chase", CREDIT_CARD, "CH", 0xFF117ACA, "pts",
                loginUrl = "https://secure.chase.com/web/auth/dashboard",
                dashboardUrl = "https://secure.chase.com/web/auth/dashboard",
                hosts = setOf("secure.chase.com"),
                capability = xpath(CHASE_XPATH, "^https://secure\\.chase\\.com/web/auth/dashboard", "logon"),
                popular = true,
                textKeywords = setOf("chase"),
            ),
            program(
                "capitalone", "Capital One", CREDIT_CARD, "C1", 0xFF004977, "cash",
                loginUrl = "https://verified.capitalone.com/auth/signin",
                dashboardUrl = "https://myaccounts.capitalone.com/accountSummary",
                hosts = setOf("verified.capitalone.com", "myaccounts.capitalone.com"),
                capability = xpath(CAPITALONE_XPATH, "^https://myaccounts\\.capitalone\\.com/accountSummary", "^https://verified\\.capitalone\\.com/"),
                popular = true,
                balanceUnit = BalanceUnit.CASH_CENTS,
                textKeywords = setOf("capital one"),
            ),
            program(
                "citi", "Citi", CREDIT_CARD, "CT", 0xFF003B70, "pts",
                loginUrl = "https://www.citi.com/",
                dashboardUrl = "https://www.citi.com/",
                hosts = setOf("www.citi.com"),
                capability = SyncCapability.ManualOnly(NO_ACCOUNT),
                popular = false,
            ),
            program(
                "bilt", "Bilt", CREDIT_CARD, "BI", 0xFF1A1A1A, "pts",
                loginUrl = "https://www.bilt.com/login",
                dashboardUrl = "https://www.bilt.com/login",
                hosts = setOf("www.bilt.com"),
                capability = SyncCapability.ManualOnly(NO_ACCOUNT),
                popular = false,
            ),
            program(
                "delta", "Delta SkyMiles", AIRLINE, "DL", 0xFF003A70, "miles",
                loginUrl = "https://www.delta.com/login/loginPage",
                dashboardUrl = "https://www.delta.com/",
                hosts = setOf("www.delta.com", "signin.delta.com"),
                capability = xpath(DELTA_XPATH, "^https://www\\.delta\\.com/(\\?|#|$)", "^https://(www\\.delta\\.com/login/|signin\\.delta\\.com/)"),
                popular = true,
            ),
            program(
                "united", "United MileagePlus", AIRLINE, "UA", 0xFF002244, "miles",
                loginUrl = "https://www.united.com/en/us/myunited",
                dashboardUrl = "https://www.united.com/en/us/myunited",
                hosts = setOf("www.united.com"),
                capability = css(UNITED_CSS, "^https://www\\.united\\.com/[a-z]{2}/[a-z]{2}/myunited", "/(sign-?in|login)"),
                popular = true,
                textKeywords = setOf("united"),
            ),
            program(
                "alaska", "Alaska Atmos Rewards", AIRLINE, "AS", 0xFF01426A, "pts",
                loginUrl = "https://www.alaskaair.com/atmosrewards/account/overview/",
                dashboardUrl = "https://www.alaskaair.com/atmosrewards/account/overview/",
                hosts = setOf("www.alaskaair.com", "auth0.alaskaair.com"),
                capability = css(ALASKA_CSS, "^https://www\\.alaskaair\\.com/atmosrewards/account/overview", "^https://auth0\\.alaskaair\\.com/"),
                popular = false,
            ),
            program(
                "aeroplan", "Air Canada Aeroplan", AIRLINE, "AC", 0xFFD22630, "pts",
                loginUrl = "https://www.aircanada.com/aeroplan/member/dashboard",
                dashboardUrl = "https://www.aircanada.com/aeroplan/member/dashboard",
                hosts = setOf("www.aircanada.com", "login.aircanada.com", "auth.api-gw.dbaas.aircanada.com", "akamai-gw.dbaas.aircanada.com"),
                capability = css(
                    AEROPLAN_CSS,
                    "^https://www\\.aircanada\\.com/aeroplan/member/dashboard",
                    "^https://(login\\.aircanada\\.com|(auth\\.api|akamai)-gw\\.dbaas\\.aircanada\\.com)/",
                ),
                popular = false,
                textKeywords = setOf("air canada", "aeroplan"),
            ),
            program(
                "aadvantage", "American Airlines AAdvantage", AIRLINE, "AA", 0xFF0078D2, "miles",
                loginUrl = "https://www.aa.com/loyalty/login",
                dashboardUrl = "https://www.aa.com/loyalty/login",
                hosts = setOf("www.aa.com"),
                capability = SyncCapability.ManualOnly(NO_ACCOUNT),
                popular = false,
            ),
            program(
                "britishairways", "British Airways Club", AIRLINE, "BA", 0xFF075AAA, "Avios",
                loginUrl = "https://www.britishairways.com/travel/loginr/public/en_us",
                dashboardUrl = "https://www.britishairways.com/travel/loginr/public/en_us",
                hosts = setOf("www.britishairways.com"),
                capability = SyncCapability.ManualOnly(NO_ACCOUNT),
                popular = false,
            ),
            program(
                "marriott", "Marriott Bonvoy", HOTEL, "MB", 0xFF8E1A23, "pts",
                loginUrl = "https://www.marriott.com/sign-in.mi",
                dashboardUrl = "https://www.marriott.com/default.mi",
                hosts = setOf("www.marriott.com"),
                capability = SyncCapability.Automatic(
                    ExtractionSpec.Selector(
                        SelectorStrategy.CSS, MARRIOTT_CSS, Regex("^https://www\\.marriott\\.com/default\\.mi"), Regex("/sign-in\\.mi"),
                        revealSelector = MARRIOTT_PANEL_BUTTON,
                    ),
                ),
                popular = true,
            ),
            program(
                "hyatt", "World of Hyatt", HOTEL, "WH", 0xFF00436A, "pts",
                loginUrl = "https://www.hyatt.com/profile/en-US/account-overview",
                dashboardUrl = "https://www.hyatt.com/profile/en-US/account-overview",
                hosts = setOf("www.hyatt.com", "world.hyatt.com"),
                capability = css(HYATT_CSS, "^https://www\\.hyatt\\.com/profile/[A-Za-z-]+/account-overview", "/(sign-?in|login)"),
                popular = false,
                asksLastName = true,
            ),
            program(
                "hilton", "Hilton Honors", HOTEL, "HH", 0xFF104C97, "pts",
                loginUrl = "https://www.hilton.com/en/hilton-honors/login/",
                dashboardUrl = "https://www.hilton.com/en/hilton-honors/guest/my-account/",
                hosts = setOf("www.hilton.com"),
                capability = css(HILTON_CSS, "^https://www\\.hilton\\.com/en/hilton-honors/guest/my-account", "/hilton-honors/login"),
                popular = false,
            ),
        )
    }
}
