package com.bendourthe.milestone.ui.benefits

import com.bendourthe.milestone.domain.*
import com.bendourthe.milestone.sync.background.BenefitReminders
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

data class BenefitAlertUi(val id: String, val generation: Long, val message: String)

/** Alert generations belong to their source section; failed reads cannot advance them. */
object BenefitsAlerts {
    fun listFreshness(card: Card, benefits: List<Benefit>, prefs: BenefitsDisplayPreferences): String? {
        val state = prefs.readState
        if (state.generation == 0L || (state.readEpochMs != card.benefitsReadEpochMs || state.cardGeneration != card.benefitsReadGeneration)) {
            return if (benefits.any { it.section == BenefitSection.LISTED }) "Listed benefits freshness is unavailable until the next successful refresh." else null
        }
        return if (state.generationOf(BenefitSection.LISTED) != state.generation) "Listed benefits did not refresh. Previously saved entries are shown." else null
    }

    fun active(card: Card, benefits: List<Benefit>, prefs: BenefitsDisplayPreferences, now: Long, zone: ZoneId, locale: Locale): List<BenefitAlertUi> {
        val state = prefs.readState
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val alerts = mutableListOf<BenefitAlertUi>()
        BenefitsViewModel.staleNote(card, now, zone, locale)?.let {
            alerts += BenefitAlertUi("stale", state.generation, it)
        }
        if (state.generation > 0 && state.readEpochMs == card.benefitsReadEpochMs && state.cardGeneration == card.benefitsReadGeneration && state.generationOf(BenefitSection.LISTED) != state.generation) {
            alerts += BenefitAlertUi("listed-refresh", state.generationOf(BenefitSection.LISTED), "Listed benefits did not refresh. Previously saved entries are shown.")
        }
        BenefitsPresentation.presented(benefits).forEach { presented ->
            val benefit = presented.benefit
            if (benefit.enrollment == Enrollment.ENROLLMENT_REQUIRED) {
                val source = checkNotNull(presented.enrollmentSource)
                alerts += BenefitAlertUi("enrollment:${source.stableKey}", state.generationOf(source.section), "Enrollment required: ${benefit.title}")
            }
            if (BenefitReminders.isDue(card, benefit, today, now)) {
                alerts += BenefitAlertUi("due:${benefit.stableKey}:${benefit.periodEnd}", state.generationOf(BenefitSection.CREDIT_EARNED), "${benefit.title}: ${BenefitsViewModel.money(benefit.remainingCents!!, locale)} left, ends ${benefit.periodEnd}")
            }
        }
        prefs.welcomeBonus?.let { setup ->
            val deadline = setup.deadline
            if (setup.spentCents < setup.targetCents && deadline != null && ChronoUnit.DAYS.between(today, deadline) in 0L..7L) {
                alerts += BenefitAlertUi("welcome:${setup.spentCents}:${setup.targetCents}:$deadline", 0, "Welcome bonus deadline: $deadline. Update your local spend progress.")
            }
        }
        return alerts.filterNot { prefs.isAcknowledged(it.id, it.generation) }
    }
}
