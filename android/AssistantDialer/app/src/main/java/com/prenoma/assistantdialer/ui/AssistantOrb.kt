package com.prenoma.assistantdialer.ui

import android.animation.ValueAnimator
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.prenoma.assistantdialer.sip.CallState

@Composable
fun AssistantOrb(
    state: CallState,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = state != CallState.IDLE && state != CallState.ENDED && state != CallState.ERROR
    val animationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    var shaderTime by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animationsEnabled) {
        if (!animationsEnabled) return@LaunchedEffect
        val startedAt = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                shaderTime = (now - startedAt) / 1_000_000_000f
            }
        }
    }
    val transition = rememberInfiniteTransition(label = "assistant-orb")
    val pulse by transition.animateFloat(
        initialValue = 0.965f,
        targetValue = if (active) 1.025f else 0.985f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (active) 1_250 else 2_400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "assistant-orb-pulse",
    )
    val blue = when (state) {
        CallState.CONNECTED -> Color(0xFF2389FF)
        CallState.DIALING, CallState.RINGING -> Color(0xFF6B63FF)
        CallState.ERROR -> MaterialTheme.colorScheme.error
        else -> Color(0xFF1A73F2)
    }
    val label = when (state) {
        CallState.IDLE, CallState.ENDED -> "CALL\nASTRA"
        CallState.DIALING -> "CONNECTING"
        CallState.RINGING -> "RINGING"
        CallState.CONNECTED -> "LISTENING"
        CallState.HELD -> "ON HOLD"
        CallState.ERROR -> "TRY AGAIN"
    }

    Box(
        modifier = modifier
            .size(232.dp)
            .graphicsLayer(scaleX = pulse, scaleY = pulse)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (enabled) "Call ASTRA" else label },
        contentAlignment = Alignment.Center,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            FluidShaderOrb(
                color = blue,
                timeSeconds = if (animationsEnabled) shaderTime else 0f,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            StaticOrb(color = blue, modifier = Modifier.fillMaxSize())
        }
        Text(
            text = label,
            color = Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun FluidShaderOrb(
    color: Color,
    timeSeconds: Float,
    modifier: Modifier = Modifier,
) {
    val shader = remember { runCatching { RuntimeShader(FLUID_ORB_SHADER) }.getOrNull() }
    if (shader == null) {
        StaticOrb(color = color, modifier = modifier)
        return
    }
    Canvas(modifier) {
        shader.setFloatUniform("u_resolution", size.width, size.height)
        shader.setFloatUniform("u_time", timeSeconds)
        shader.setFloatUniform("u_color", color.red, color.green, color.blue)
        drawRect(ShaderBrush(shader))
    }
}

@Composable
private fun StaticOrb(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawCircle(color.copy(alpha = 0.14f), radius = size.minDimension * 0.5f)
        drawCircle(color, radius = size.minDimension * 0.43f)
        drawCircle(
            color = Color.White.copy(alpha = 0.36f),
            radius = size.minDimension * 0.27f,
            center = center.copy(y = center.y - size.minDimension * 0.12f),
        )
        drawCircle(
            color = Color(0xFF071321).copy(alpha = 0.2f),
            radius = size.minDimension * 0.24f,
            center = center.copy(
                x = center.x + size.minDimension * 0.13f,
                y = center.y + size.minDimension * 0.14f,
            ),
        )
    }
}

/** AGSL port of the WebGL fragment shader used by the Mac visualizer. */
private const val FLUID_ORB_SHADER = """
uniform float2 u_resolution;
uniform float u_time;
uniform float3 u_color;

float hash(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453123);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(hash(i), hash(i + float2(1.0, 0.0)), u.x),
        mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0)), u.x),
        u.y
    );
}

float fbm(float2 p) {
    float value = 0.0;
    float amplitude = 0.6;
    for (int i = 0; i < 3; i++) {
        value += amplitude * noise(p);
        p *= 2.0;
        amplitude *= 0.5;
    }
    return value;
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / u_resolution;
    uv.y = 1.0 - uv.y;
    float t = u_time * 0.22;
    float2 drift = float2(
        sin(t) + 0.6 * sin(t * 1.7 + 1.3),
        cos(t * 0.8) + 0.6 * cos(t * 1.3 + 2.1)
    );
    float2 p = float2(uv.x * 1.8, uv.y) + drift * 0.7;
    float2 q = float2(fbm(p + drift), fbm(p + float2(3.2, 1.5) - drift));
    float field = fbm(p + 1.2 * q);
    float gradient = clamp(1.0 - uv.y, 0.0, 1.0);
    float anchor = smoothstep(0.0, 0.3, uv.y);
    float shade = clamp(gradient + (field - 0.5) * 0.8 * anchor, 0.0, 1.0);
    float3 white = float3(0.99, 1.0, 1.0);
    float3 light = mix(white, u_color, 0.5);
    float3 color = mix(white, light, smoothstep(0.28, 0.52, shade));
    color = mix(color, u_color, smoothstep(0.58, 0.88, shade));
    float edge = smoothstep(0.5, 0.49, distance(uv, float2(0.5)));
    return half4(color * edge, edge);
}
"""
