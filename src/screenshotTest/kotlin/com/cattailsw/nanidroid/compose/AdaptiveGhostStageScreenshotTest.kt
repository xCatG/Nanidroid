package com.cattailsw.nanidroid.compose

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "grid_400x400", group = "grid", widthDp = 400, heightDp = 400, device = "spec:width=400dp,height=400dp,dpi=320")
@Composable
fun Grid400x400Preview() = AdaptiveGhostStageScreenshot("grid_400x400")

@PreviewTest
@Preview(name = "grid_400x500", group = "grid", widthDp = 400, heightDp = 500, device = "spec:width=400dp,height=500dp,dpi=320")
@Composable
fun Grid400x500Preview() = AdaptiveGhostStageScreenshot("grid_400x500")

@PreviewTest
@Preview(name = "grid_400x1000", group = "grid", widthDp = 400, heightDp = 1000, device = "spec:width=400dp,height=1000dp,dpi=320")
@Composable
fun Grid400x1000Preview() = AdaptiveGhostStageScreenshot("grid_400x1000")

@PreviewTest
@Preview(name = "grid_610x400", group = "grid", widthDp = 610, heightDp = 400, device = "spec:width=610dp,height=400dp,dpi=320")
@Composable
fun Grid610x400Preview() = AdaptiveGhostStageScreenshot("grid_610x400")

@PreviewTest
@Preview(name = "grid_610x500", group = "grid", widthDp = 610, heightDp = 500, device = "spec:width=610dp,height=500dp,dpi=320")
@Composable
fun Grid610x500Preview() = AdaptiveGhostStageScreenshot("grid_610x500")

@PreviewTest
@Preview(name = "grid_610x1000", group = "grid", widthDp = 610, heightDp = 1000, device = "spec:width=610dp,height=1000dp,dpi=320")
@Composable
fun Grid610x1000Preview() = AdaptiveGhostStageScreenshot("grid_610x1000")

@PreviewTest
@Preview(name = "grid_900x400", group = "grid", widthDp = 900, heightDp = 400, device = "spec:width=900dp,height=400dp,dpi=320")
@Composable
fun Grid900x400Preview() = AdaptiveGhostStageScreenshot("grid_900x400")

@PreviewTest
@Preview(name = "grid_900x500", group = "grid", widthDp = 900, heightDp = 500, device = "spec:width=900dp,height=500dp,dpi=320")
@Composable
fun Grid900x500Preview() = AdaptiveGhostStageScreenshot("grid_900x500")

@PreviewTest
@Preview(name = "grid_900x1000", group = "grid", widthDp = 900, heightDp = 1000, device = "spec:width=900dp,height=1000dp,dpi=320")
@Composable
fun Grid900x1000Preview() = AdaptiveGhostStageScreenshot("grid_900x1000")

@PreviewTest
@Preview(name = "phone_portrait_one_bubble", group = "product", widthDp = 360, heightDp = 720, device = "spec:width=360dp,height=720dp,dpi=320")
@Composable
fun PhonePortraitOneBubblePreview() = AdaptiveGhostStageScreenshot("phone_portrait_one_bubble")

@PreviewTest
@Preview(name = "phone_portrait_two_bubbles", group = "product", widthDp = 360, heightDp = 720, device = "spec:width=360dp,height=720dp,dpi=320")
@Composable
fun PhonePortraitTwoBubblesPreview() = AdaptiveGhostStageScreenshot("phone_portrait_two_bubbles")

@PreviewTest
@Preview(name = "compact_landscape_empty", group = "product", widthDp = 720, heightDp = 360, device = "spec:width=720dp,height=360dp,dpi=320")
@Composable
fun CompactLandscapeEmptyPreview() = AdaptiveGhostStageScreenshot("compact_landscape_empty")

@PreviewTest
@Preview(name = "compact_landscape_one", group = "product", widthDp = 720, heightDp = 360, device = "spec:width=720dp,height=360dp,dpi=320")
@Composable
fun CompactLandscapeOnePreview() = AdaptiveGhostStageScreenshot("compact_landscape_one")

@PreviewTest
@Preview(name = "compact_landscape_two", group = "product", widthDp = 720, heightDp = 360, device = "spec:width=720dp,height=360dp,dpi=320")
@Composable
fun CompactLandscapeTwoPreview() = AdaptiveGhostStageScreenshot("compact_landscape_two")

@PreviewTest
@Preview(name = "compact_landscape_long", group = "product", widthDp = 720, heightDp = 360, device = "spec:width=720dp,height=360dp,dpi=320")
@Composable
fun CompactLandscapeLongPreview() = AdaptiveGhostStageScreenshot("compact_landscape_long")

@PreviewTest
@Preview(name = "tall_phone_two", group = "product", widthDp = 400, heightDp = 1000, device = "spec:width=400dp,height=1000dp,dpi=320")
@Composable
fun TallPhoneTwoPreview() = AdaptiveGhostStageScreenshot("tall_phone_two")

@PreviewTest
@Preview(name = "tablet_portrait", group = "product", widthDp = 800, heightDp = 1280, device = "spec:width=800dp,height=1280dp,dpi=320")
@Composable
fun TabletPortraitPreview() = AdaptiveGhostStageScreenshot("tablet_portrait")

@PreviewTest
@Preview(name = "tablet_landscape", group = "product", widthDp = 1280, heightDp = 800, device = "spec:width=1280dp,height=800dp,dpi=320")
@Composable
fun TabletLandscapePreview() = AdaptiveGhostStageScreenshot("tablet_landscape")

@PreviewTest
@Preview(name = "foldable_flat", group = "product", widthDp = 610, heightDp = 500, device = "spec:width=610dp,height=500dp,dpi=320")
@Composable
fun FoldableFlatPreview() = AdaptiveGhostStageScreenshot("foldable_flat")

@PreviewTest
@Preview(name = "foldable_vertical_separating", group = "product", widthDp = 610, heightDp = 500, device = "spec:width=610dp,height=500dp,dpi=320")
@Composable
fun FoldableVerticalSeparatingPreview() = AdaptiveGhostStageScreenshot("foldable_vertical_separating")

@PreviewTest
@Preview(name = "tiny_wide", group = "product", widthDp = 480, heightDp = 230, device = "spec:width=480dp,height=230dp,dpi=320")
@Composable
fun TinyWidePreview() = AdaptiveGhostStageScreenshot("tiny_wide")

@PreviewTest
@Preview(name = "tiny_tall", group = "product", widthDp = 230, heightDp = 400, device = "spec:width=230dp,height=400dp,dpi=320")
@Composable
fun TinyTallPreview() = AdaptiveGhostStageScreenshot("tiny_tall")

@PreviewTest
@Preview(name = "import_installing", group = "product", widthDp = 360, heightDp = 720, device = "spec:width=360dp,height=720dp,dpi=320")
@Composable
fun ImportInstallingPreview() = AdaptiveGhostStageScreenshot("import_installing")

@PreviewTest
@Preview(name = "import_failed", group = "product", widthDp = 400, heightDp = 1000, device = "spec:width=400dp,height=1000dp,dpi=320")
@Composable
fun ImportFailedPreview() = AdaptiveGhostStageScreenshot("import_failed")

@PreviewTest
@Preview(name = "collision_shapes_combined", group = "product", widthDp = 610, heightDp = 500, device = "spec:width=610dp,height=500dp,dpi=320")
@Composable
fun CollisionShapesCombinedPreview() = AdaptiveGhostStageScreenshot("collision_shapes_combined")

@PreviewTest
@Preview(name = "pair_ltr_light_f100_d160", group = "pairwise", widthDp = 900, heightDp = 500, fontScale = 1.0f, uiMode = Configuration.UI_MODE_NIGHT_NO, device = "spec:width=900dp,height=500dp,dpi=160")
@Composable
fun PairLtrLightF100D160Preview() = AdaptiveGhostStageScreenshot("pair_ltr_light_f100_d160")

@PreviewTest
@Preview(name = "pair_rtl_dark_f100_d320", group = "pairwise", widthDp = 900, heightDp = 500, fontScale = 1.0f, uiMode = Configuration.UI_MODE_NIGHT_YES, device = "spec:width=900dp,height=500dp,dpi=320")
@Composable
fun PairRtlDarkF100D320Preview() = AdaptiveGhostStageScreenshot("pair_rtl_dark_f100_d320")

@PreviewTest
@Preview(name = "pair_ltr_dark_f150_d320", group = "pairwise", widthDp = 900, heightDp = 500, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES, device = "spec:width=900dp,height=500dp,dpi=320")
@Composable
fun PairLtrDarkF150D320Preview() = AdaptiveGhostStageScreenshot("pair_ltr_dark_f150_d320")

@PreviewTest
@Preview(name = "pair_rtl_light_f150_d160", group = "pairwise", widthDp = 900, heightDp = 500, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_NO, device = "spec:width=900dp,height=500dp,dpi=160")
@Composable
fun PairRtlLightF150D160Preview() = AdaptiveGhostStageScreenshot("pair_rtl_light_f150_d160")

@PreviewTest
@Preview(name = "pair_ltr_light_f200_d320", group = "pairwise", widthDp = 900, heightDp = 500, fontScale = 2.0f, uiMode = Configuration.UI_MODE_NIGHT_NO, device = "spec:width=900dp,height=500dp,dpi=320")
@Composable
fun PairLtrLightF200D320Preview() = AdaptiveGhostStageScreenshot("pair_ltr_light_f200_d320")

@PreviewTest
@Preview(name = "pair_rtl_dark_f200_d160", group = "pairwise", widthDp = 900, heightDp = 500, fontScale = 2.0f, uiMode = Configuration.UI_MODE_NIGHT_YES, device = "spec:width=900dp,height=500dp,dpi=160")
@Composable
fun PairRtlDarkF200D160Preview() = AdaptiveGhostStageScreenshot("pair_rtl_dark_f200_d160")
