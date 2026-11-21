package com.lxmusic.tv.presentation.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 屏幕宽度档位：根据有效视口宽度（dp）划分设备形态。
 * 阈值：<600 手机，<840 平板，>=840 电视。
 */
enum class WidthTier { PHONE, TABLET, TV }

/**
 * 响应式尺寸令牌：按宽度档位提供网格列数、间距、侧栏宽、字号、封面高等。
 */
data class LXDimens(
    val gridColumns: Int,
    val gridSpacing: Dp,
    val sidebarWidth: Dp,
    val titleSize: TextUnit,
    val bodySize: TextUnit,
    val captionSize: TextUnit,
    val cardCoverHeight: Dp
)

/** 依据视口宽度（dp）判定档位。 */
fun widthTierFor(screenWidthDp: Int): WidthTier = when {
    screenWidthDp < 600 -> WidthTier.PHONE
    screenWidthDp < 840 -> WidthTier.TABLET
    else -> WidthTier.TV
}

/** 各档位尺寸令牌（TV 档为智慧屏/大屏主目标）。 */
fun lxDimensFor(tier: WidthTier): LXDimens = when (tier) {
    WidthTier.TV -> LXDimens(
        gridColumns = 4,
        gridSpacing = 18.dp,
        sidebarWidth = 96.dp,
        titleSize = 27.sp,
        bodySize = 17.sp,
        captionSize = 13.sp,
        cardCoverHeight = 135.dp
    )
    WidthTier.TABLET -> LXDimens(
        gridColumns = 3,
        gridSpacing = 16.dp,
        sidebarWidth = 84.dp,
        titleSize = 22.sp,
        bodySize = 15.sp,
        captionSize = 12.sp,
        cardCoverHeight = 120.dp
    )
    WidthTier.PHONE -> LXDimens(
        gridColumns = 2,
        gridSpacing = 12.dp,
        sidebarWidth = 64.dp,
        titleSize = 18.sp,
        bodySize = 14.sp,
        captionSize = 11.sp,
        cardCoverHeight = 100.dp
    )
}

/**
 * 默认 TV 档令牌。默认值保证未显式 provide 时（历史调用路径）不崩溃，
 * 而智慧屏（TV 档）恰好是默认目标设备。
 */
val LocalDimens = staticCompositionLocalOf { lxDimensFor(WidthTier.TV) }
