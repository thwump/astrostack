package com.astrostack.app.ui.theme

import androidx.compose.ui.graphics.Color

// ─── Deep-red night-vision palette ───────────────────────────────────────────
// Astronomers use red lighting at the telescope to preserve dark adaptation.
// Blue/white light destroys night vision in minutes; red light < 620 nm is safe.

// Primary — deep red (safelight red)
val Astro_Red900   = Color(0xFF4A0000)
val Astro_Red800   = Color(0xFF7B0000)
val Astro_Red700   = Color(0xFFAE0000)
val Astro_Red600   = Color(0xFFD32F2F)
val Astro_Red500   = Color(0xFFEF5350)   // primary
val Astro_Red400   = Color(0xFFEF9A9A)
val Astro_Red200   = Color(0xFFFFCDD2)

// Surface tones — very dark, near-black with a warm red tint
val Astro_Surface  = Color(0xFF120A0A)
val Astro_Surface2 = Color(0xFF1E1010)
val Astro_Surface3 = Color(0xFF2A1515)

// On-surface text — dim amber/red, not bright white
val Astro_OnSurface       = Color(0xFFEECCCC)
val Astro_OnSurfaceVariant = Color(0xFF996666)

// Error tones
val Astro_Error    = Color(0xFFFF6E40)
val Astro_OnError  = Color(0xFF1C0000)

// Backgrounds
val Astro_Background    = Color(0xFF0D0505)
val Astro_OnBackground  = Color(0xFFEECCCC)
