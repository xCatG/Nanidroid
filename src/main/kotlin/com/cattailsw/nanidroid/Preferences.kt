package com.cattailsw.nanidroid

import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.selection.toggleable
import com.cattailsw.nanidroid.compose.NanidroidTheme

/**
 * Compose replacement for the former single-item PreferenceActivity.
 *
 * The preference is intentionally stored in the historical default shared
 * preferences file under the existing key so analytics initialization and an
 * installed user's choice retain their original behavior.
 */
class Preferences : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        setContent {
            var analyticsEnabled by remember {
                mutableStateOf(preferences.getBoolean(Setup.PREF_KEY_USE_ANALYTICS, true))
            }
            PreferencesScreen(
                analyticsEnabled = analyticsEnabled,
                onAnalyticsEnabledChanged = { enabled ->
                    analyticsEnabled = enabled
                    preferences.edit().putBoolean(Setup.PREF_KEY_USE_ANALYTICS, enabled).apply()
                },
            )
        }
    }
}

@Composable
internal fun PreferencesScreen(
    analyticsEnabled: Boolean,
    onAnalyticsEnabledChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    NanidroidTheme {
        Surface(modifier = modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = analyticsEnabled,
                        role = Role.Checkbox,
                        onValueChange = onAnalyticsEnabledChanged,
                    )
                    .testTag("analytics-preference")
                    .padding(dimensionResource(R.dimen.preferences_content_padding)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.enable_analytic_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.enable_analytic_desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Checkbox(
                    checked = analyticsEnabled,
                    onCheckedChange = null,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreferencesScreenPreview() {
    PreferencesScreen(
        analyticsEnabled = true,
        onAnalyticsEnabledChanged = {},
    )
}
