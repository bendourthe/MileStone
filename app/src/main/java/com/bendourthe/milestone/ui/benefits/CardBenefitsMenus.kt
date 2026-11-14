package com.bendourthe.milestone.ui.benefits

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.bendourthe.milestone.domain.BenefitsDisplayPreferences
import com.bendourthe.milestone.domain.BenefitsSort
import com.bendourthe.milestone.domain.CardKey

@Composable
internal fun CardBenefitsMenus(key: CardKey, preferences: BenefitsDisplayPreferences, enabled: Boolean,
                               onSort: (BenefitsSort) -> Unit, onCompletedLast: (Boolean) -> Unit) {
    var sorting by rememberSaveable(key.programId, key.lastDigits) { mutableStateOf(false) }
    var filtering by rememberSaveable(key.programId, key.lastDigits) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { sorting = true }, enabled = enabled, modifier = Modifier.testTag("benefitsSort")) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort benefits: ${preferences.sort.label}")
        }
        DropdownMenu(expanded = sorting, onDismissRequest = { sorting = false }, modifier = Modifier.testTag("benefitsSortMenu")) {
            BenefitsSort.entries.forEach { choice ->
                DropdownMenuItem(text = { Text(choice.label) }, leadingIcon = { if (choice == preferences.sort) Icon(Icons.Filled.Check, contentDescription = null) },
                    modifier = Modifier.semantics { selected = choice == preferences.sort },
                    onClick = { onSort(choice); sorting = false })
            }
        }
    }
    Box {
        IconButton(onClick = { filtering = true }, enabled = enabled, modifier = Modifier.testTag("benefitsFilters")) {
            Icon(Icons.Filled.FilterList, contentDescription = "Filters: completed last ${if (preferences.completedLast) "on" else "off"}",
                tint = if (preferences.completedLast) MaterialTheme.colorScheme.primary else LocalContentColor.current)
        }
        DropdownMenu(expanded = filtering, onDismissRequest = { filtering = false }, modifier = Modifier.testTag("benefitsFilterMenu")) {
            DropdownMenuItem(text = { Text("Completed last") }, leadingIcon = {
                Icon(if (preferences.completedLast) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, contentDescription = null)
            }, modifier = Modifier.testTag("benefitsCompletedLast").semantics {
                selected = preferences.completedLast
                stateDescription = if (preferences.completedLast) "On" else "Off"
            }, onClick = { onCompletedLast(!preferences.completedLast); filtering = false })
        }
    }
}
