package com.soltini.app.services

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.soltini.app.overlay.OverlayService
import com.soltini.app.security.VoiceBiometricsManager
import com.soltini.app.security.VoiceUnlockActivity
import com.soltini.app.settings.AppSettings

/**
 * BankingModeTileService — Quick Settings Tile for 1-tap toggling of Banking Mode.
 * Allows user to quickly pause/resume Soltini overlays before opening banking apps.
 * Protected by Voice Biometrics when sensitive overlay toggle is requested.
 */
@RequiresApi(Build.VERSION_CODES.N)
class BankingModeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val voiceManager = VoiceBiometricsManager.getInstance(this)
        val appSettings = AppSettings(this)

        // If voice lock is active and not recently verified, require biometric voice authentication
        if (voiceManager.isEnrolled() && voiceManager.isVoiceLockEnabled() && !voiceManager.isRecentAuthValid()) {
            val intent = Intent(this, VoiceUnlockActivity::class.java).apply {
                putExtra(VoiceUnlockActivity.EXTRA_MODE, VoiceUnlockActivity.MODE_VERIFY_BANKING)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ pending intent / activity launch
                try {
                    startActivityAndCollapse(intent)
                } catch (_: Exception) {
                    startActivity(intent)
                }
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }

        val newPausedState = !appSettings.isBankingModePaused
        appSettings.isBankingModePaused = newPausedState

        val overlay = OverlayService.getInstance()
        if (overlay != null) {
            overlay.setHiddenForBanking(newPausedState)
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val appSettings = AppSettings(this)
        val isPaused = appSettings.isBankingModePaused || (OverlayService.getInstance()?.isHiddenForBanking() == true)

        if (isPaused) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Myra: Paused"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Banking Mode ON"
            }
        } else {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Myra: Active"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Overlay Active"
            }
        }
        tile.updateTile()
    }
}
