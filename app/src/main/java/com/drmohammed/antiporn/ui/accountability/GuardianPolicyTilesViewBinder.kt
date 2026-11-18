package com.drmohammed.antiporn.ui.accountability

import android.content.Intent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.pageshield.ui.PageShieldSettingsActivity
import com.drmohammed.antiporn.ui.control.curfew.CurfewSettingsActivity
import com.drmohammed.antiporn.ui.control.other.OtherSettingsActivity
import com.drmohammed.antiporn.ui.control.web.WebFilterSettingsActivity
import com.drmohammed.antiporn.waqfa.ui.settings.WaqfaSettingsActivity

/**
 * View binder responsible for binding the Guardian Control Hub's distributed
 * policy navigation tiles and toggling their enabled/alpha readiness state.
 */
internal class GuardianPolicyTilesViewBinder(
    private val activity: AppCompatActivity,
    private val stateProvider: () -> GuardianControlUiState,
    private val onLaunchSubScreen: (() -> Intent) -> Unit
) {

    fun setupListeners() {
        activity.findViewById<View>(R.id.tileCurfew)?.setOnClickListener {
            onLaunchSubScreen {
                val state = stateProvider()
                Intent(activity, CurfewSettingsActivity::class.java).apply {
                    putExtra(CurfewSettingsActivity.EXTRA_IS_REMOTE, true)
                    putExtra(CurfewSettingsActivity.EXTRA_WARD_ID, state.wardId)
                    putExtra(CurfewSettingsActivity.EXTRA_POLICY_JSON, state.currentPolicy.toJson())
                }
            }
        }

        activity.findViewById<View>(R.id.tileWebFilter)?.setOnClickListener {
            onLaunchSubScreen {
                val state = stateProvider()
                Intent(activity, WebFilterSettingsActivity::class.java).apply {
                    putExtra(WebFilterSettingsActivity.EXTRA_IS_REMOTE, true)
                    putExtra(WebFilterSettingsActivity.EXTRA_WARD_ID, state.wardId)
                    putExtra(WebFilterSettingsActivity.EXTRA_POLICY_JSON, state.currentPolicy.toJson())
                }
            }
        }

        activity.findViewById<View>(R.id.tileAppManager)?.setOnClickListener {
            onLaunchSubScreen {
                val state = stateProvider()
                Intent(activity, RemoteAppManagerActivity::class.java).apply {
                    putExtra(RemoteAppManagerActivity.EXTRA_IS_LOCAL, false)
                    putExtra(RemoteAppManagerActivity.EXTRA_WARD_ID, state.wardId)
                    putExtra(RemoteAppManagerActivity.EXTRA_WARD_NAME, state.wardName)
                }
            }
        }

        activity.findViewById<View>(R.id.tileWaqfa)?.setOnClickListener {
            onLaunchSubScreen {
                val state = stateProvider()
                Intent(activity, WaqfaSettingsActivity::class.java).apply {
                    putExtra(WaqfaSettingsActivity.EXTRA_IS_REMOTE, true)
                    putExtra(WaqfaSettingsActivity.EXTRA_WARD_ID, state.wardId)
                    putExtra(WaqfaSettingsActivity.EXTRA_POLICY_JSON, state.currentPolicy.toJson())
                }
            }
        }

        activity.findViewById<View>(R.id.tilePageShield)?.setOnClickListener {
            onLaunchSubScreen {
                val state = stateProvider()
                Intent(activity, PageShieldSettingsActivity::class.java).apply {
                    putExtra(PageShieldSettingsActivity.EXTRA_IS_REMOTE, true)
                    putExtra(PageShieldSettingsActivity.EXTRA_WARD_ID, state.wardId)
                    putExtra(PageShieldSettingsActivity.EXTRA_POLICY_JSON, state.currentPolicy.toJson())
                }
            }
        }

        activity.findViewById<View>(R.id.tileOtherSettings)?.setOnClickListener {
            onLaunchSubScreen {
                val state = stateProvider()
                Intent(activity, OtherSettingsActivity::class.java).apply {
                    putExtra(OtherSettingsActivity.EXTRA_IS_REMOTE, true)
                    putExtra(OtherSettingsActivity.EXTRA_WARD_ID, state.wardId)
                    putExtra(OtherSettingsActivity.EXTRA_POLICY_JSON, state.currentPolicy.toJson())
                }
            }
        }

        activity.findViewById<View>(R.id.tileSelfControlPermissions)?.setOnClickListener {
            onLaunchSubScreen {
                val state = stateProvider()
                Intent(activity, GuardianSelfControlSettingsActivity::class.java).apply {
                    putExtra(GuardianSelfControlSettingsActivity.EXTRA_WARD_ID, state.wardId)
                    putExtra(GuardianSelfControlSettingsActivity.EXTRA_POLICY_JSON, state.currentPolicy.toJson())
                }
            }
        }
    }

    fun renderPolicyReadyState(policyReady: Boolean) {
        val tileAlpha = if (policyReady) 1.0f else 0.55f
        for (tileId in POLICY_TILE_IDS) {
            activity.findViewById<View>(tileId)?.let { tile ->
                tile.isEnabled = policyReady
                tile.alpha = tileAlpha
            }
        }
    }

    companion object {
        private val POLICY_TILE_IDS = intArrayOf(
            R.id.tileCurfew,
            R.id.tileWebFilter,
            R.id.tileAppManager,
            R.id.tileWaqfa,
            R.id.tilePageShield,
            R.id.tileOtherSettings,
            R.id.tileSelfControlPermissions
        )
    }
}
