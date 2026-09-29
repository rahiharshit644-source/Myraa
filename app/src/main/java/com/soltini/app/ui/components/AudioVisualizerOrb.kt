package com.soltini.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.soltini.app.ui.theme.AccentBlue
import com.soltini.app.ui.theme.AccentIndigo
import com.soltini.app.ui.theme.AccentPink
import com.soltini.app.ui.theme.AccentPurple
import com.soltini.app.ui.theme.AccentTeal
import com.soltini.app.ui.theme.DarkSurfaceVariant

@Composable
fun AudioVisualizerOrb(
    micAmplitude: Float,
    speakerAmplitude: Float,
    isLive: Boolean,
    isSpeaking: Boolean,
    isMuted: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScaleAnim by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val activeAmplitude = if (isSpeaking) speakerAmplitude else micAmplitude
    val animatedAmp by animateFloatAsState(
        targetValue = activeAmplitude,
        animationSpec = tween(durationMillis = 80),
        label = "amp"
    )

    val baseScale = if (isLive) pulseScaleAnim else 1f
    val dynamicRadiusScale = baseScale * (1f + animatedAmp * 1.5f) // Siri-like big bounce

    // Determine target colors based on state
    val targetCenter = when {
        !isLive -> DarkSurfaceVariant
        isMuted -> Color.DarkGray
        isSpeaking -> AccentPink
        else -> AccentTeal
    }

    val targetEdge = when {
        !isLive -> Color.DarkGray
        isMuted -> DarkSurfaceVariant
        isSpeaking -> AccentPurple
        else -> AccentBlue
    }

    val colorCenter by animateColorAsState(targetCenter, tween(500), label = "centerColor")
    val colorEdge by animateColorAsState(targetEdge, tween(500), label = "edgeColor")

    Box(
        modifier = modifier
            .size(300.dp)
            .testTag("audio_visualizer_orb"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension * 0.25f // Base size of the orb
            
            val currentRadius = baseRadius * dynamicRadiusScale

            // Compute an infinite phase for the morphing animation
            val phase = (System.currentTimeMillis() % 10000L) / 10000f * (2 * Math.PI.toFloat())
            
            // Function to generate a smooth blob path
            fun createBlobPath(baseR: Float, ampMultiplier: Float = 1f): androidx.compose.ui.graphics.Path {
                val path = androidx.compose.ui.graphics.Path()
                val points = 8
                val angleStep = Math.PI * 2 / points
                val vertexes = mutableListOf<Offset>()
                
                for (i in 0 until points) {
                    val angle = (i * angleStep).toFloat()
                    val wave1 = kotlin.math.sin(angle * 3 + phase).toFloat() * 0.08f
                    val wave2 = kotlin.math.cos(angle * 2 - phase * 1.5f).toFloat() * 0.12f
                    
                    val deformation = (wave1 + wave2) * (1f + animatedAmp * 2.5f * ampMultiplier)
                    val r = baseR * (1f + deformation)
                    
                    val x = center.x + r * kotlin.math.cos(angle)
                    val y = center.y + r * kotlin.math.sin(angle)
                    vertexes.add(Offset(x, y))
                }
                
                // Draw smooth curve using midpoints
                for (i in 0 until points) {
                    val p1 = vertexes[i]
                    val p2 = vertexes[(i + 1) % points]
                    val midX = (p1.x + p2.x) / 2f
                    val midY = (p1.y + p2.y) / 2f
                    
                    if (i == 0) {
                        path.moveTo(midX, midY)
                    } else {
                        path.quadraticTo(p1.x, p1.y, midX, midY)
                    }
                }
                // Close the loop smoothly
                val pFirst = vertexes[0]
                val pSecond = vertexes[1]
                val midFirstX = (pFirst.x + pSecond.x) / 2f
                val midFirstY = (pFirst.y + pSecond.y) / 2f
                path.quadraticTo(pFirst.x, pFirst.y, midFirstX, midFirstY)
                path.close()
                return path
            }

            // 1. Outer faint ring (circle)
            drawCircle(
                color = colorEdge.copy(alpha = 0.15f),
                radius = currentRadius * 1.8f,
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
            )

            // 2. Middle aura (morphing blob)
            drawPath(
                path = createBlobPath(currentRadius * 1.3f, 0.5f),
                color = colorCenter.copy(alpha = 0.25f)
            )

            // 3. Inner core gradient orb (Siri-style morphing blob)
            drawPath(
                path = createBlobPath(currentRadius, 1f),
                brush = Brush.radialGradient(
                    0.0f to Color.White,
                    0.4f to colorCenter,
                    0.8f to colorEdge,
                    1.0f to Color.Transparent,
                    center = center,
                    radius = currentRadius * 1.2f
                )
            )
        }
    }
}
