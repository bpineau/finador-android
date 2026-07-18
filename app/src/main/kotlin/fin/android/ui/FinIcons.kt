package fin.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The handful of Material system icons the app draws, inlined as [ImageVector]s so we no longer
 * depend on `androidx.compose.material:material-icons-extended` (frozen upstream at 1.7.8).
 *
 * The path data below is copied verbatim from the androidx material-icons sources (version 1.7.8),
 * which are licensed under the Apache License, Version 2.0. The [finIcon]/[finPath] helpers mirror
 * the library's `materialIcon`/`materialPath` builders: a 24x24 dp icon over a 24x24 viewport,
 * filled solid black (tinted at draw time by [androidx.compose.material3.Icon]). The three
 * direction-carrying icons ([ArrowBack], [KeyboardArrowRight], [TrendingUp]) set `autoMirror` so
 * they flip in right-to-left layouts, exactly as their upstream counterparts do.
 */
object FinIcons {
    val ArrowBack: ImageVector by lazy {
        finIcon(name = "AutoMirrored.Filled.ArrowBack", autoMirror = true) {
            moveTo(20.0f, 11.0f)
            horizontalLineTo(7.83f)
            lineToRelative(5.59f, -5.59f)
            lineTo(12.0f, 4.0f)
            lineToRelative(-8.0f, 8.0f)
            lineToRelative(8.0f, 8.0f)
            lineToRelative(1.41f, -1.41f)
            lineTo(7.83f, 13.0f)
            horizontalLineTo(20.0f)
            verticalLineToRelative(-2.0f)
            close()
        }
    }

    val KeyboardArrowRight: ImageVector by lazy {
        finIcon(name = "AutoMirrored.Filled.KeyboardArrowRight", autoMirror = true) {
            moveTo(8.59f, 16.59f)
            lineTo(13.17f, 12.0f)
            lineTo(8.59f, 7.41f)
            lineTo(10.0f, 6.0f)
            lineToRelative(6.0f, 6.0f)
            lineToRelative(-6.0f, 6.0f)
            lineToRelative(-1.41f, -1.41f)
            close()
        }
    }

    val TrendingUp: ImageVector by lazy {
        finIcon(name = "AutoMirrored.Filled.TrendingUp", autoMirror = true) {
            moveTo(16.0f, 6.0f)
            lineToRelative(2.29f, 2.29f)
            lineToRelative(-4.88f, 4.88f)
            lineToRelative(-4.0f, -4.0f)
            lineTo(2.0f, 16.59f)
            lineTo(3.41f, 18.0f)
            lineToRelative(6.0f, -6.0f)
            lineToRelative(4.0f, 4.0f)
            lineToRelative(6.3f, -6.29f)
            lineTo(22.0f, 12.0f)
            verticalLineTo(6.0f)
            close()
        }
    }

    val Add: ImageVector by lazy {
        finIcon(name = "Filled.Add") {
            moveTo(19.0f, 13.0f)
            horizontalLineToRelative(-6.0f)
            verticalLineToRelative(6.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(-6.0f)
            horizontalLineTo(5.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(6.0f)
            verticalLineTo(5.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(6.0f)
            horizontalLineToRelative(6.0f)
            verticalLineToRelative(2.0f)
            close()
        }
    }

    val Close: ImageVector by lazy {
        finIcon(name = "Filled.Close") {
            moveTo(19.0f, 6.41f)
            lineTo(17.59f, 5.0f)
            lineTo(12.0f, 10.59f)
            lineTo(6.41f, 5.0f)
            lineTo(5.0f, 6.41f)
            lineTo(10.59f, 12.0f)
            lineTo(5.0f, 17.59f)
            lineTo(6.41f, 19.0f)
            lineTo(12.0f, 13.41f)
            lineTo(17.59f, 19.0f)
            lineTo(19.0f, 17.59f)
            lineTo(13.41f, 12.0f)
            close()
        }
    }

    val CloudOff: ImageVector by lazy {
        finIcon(name = "Filled.CloudOff") {
            moveTo(19.35f, 10.04f)
            curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
            curveToRelative(-1.48f, 0.0f, -2.85f, 0.43f, -4.01f, 1.17f)
            lineToRelative(1.46f, 1.46f)
            curveTo(10.21f, 6.23f, 11.08f, 6.0f, 12.0f, 6.0f)
            curveToRelative(3.04f, 0.0f, 5.5f, 2.46f, 5.5f, 5.5f)
            verticalLineToRelative(0.5f)
            horizontalLineTo(19.0f)
            curveToRelative(1.66f, 0.0f, 3.0f, 1.34f, 3.0f, 3.0f)
            curveToRelative(0.0f, 1.13f, -0.64f, 2.11f, -1.56f, 2.62f)
            lineToRelative(1.45f, 1.45f)
            curveTo(23.16f, 18.16f, 24.0f, 16.68f, 24.0f, 15.0f)
            curveToRelative(0.0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
            close()
            moveTo(3.0f, 5.27f)
            lineToRelative(2.75f, 2.74f)
            curveTo(2.56f, 8.15f, 0.0f, 10.77f, 0.0f, 14.0f)
            curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
            horizontalLineToRelative(11.73f)
            lineToRelative(2.0f, 2.0f)
            lineTo(21.0f, 20.73f)
            lineTo(4.27f, 4.0f)
            lineTo(3.0f, 5.27f)
            close()
            moveTo(7.73f, 10.0f)
            lineToRelative(8.0f, 8.0f)
            horizontalLineTo(6.0f)
            curveToRelative(-2.21f, 0.0f, -4.0f, -1.79f, -4.0f, -4.0f)
            reflectiveCurveToRelative(1.79f, -4.0f, 4.0f, -4.0f)
            horizontalLineToRelative(1.73f)
            close()
        }
    }

    val ContentPaste: ImageVector by lazy {
        finIcon(name = "Filled.ContentPaste") {
            moveTo(19.0f, 2.0f)
            horizontalLineToRelative(-4.18f)
            curveTo(14.4f, 0.84f, 13.3f, 0.0f, 12.0f, 0.0f)
            curveToRelative(-1.3f, 0.0f, -2.4f, 0.84f, -2.82f, 2.0f)
            lineTo(5.0f, 2.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(16.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(14.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(21.0f, 4.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(12.0f, 2.0f)
            curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
            reflectiveCurveToRelative(-0.45f, 1.0f, -1.0f, 1.0f)
            reflectiveCurveToRelative(-1.0f, -0.45f, -1.0f, -1.0f)
            reflectiveCurveToRelative(0.45f, -1.0f, 1.0f, -1.0f)
            close()
            moveTo(19.0f, 20.0f)
            lineTo(5.0f, 20.0f)
            lineTo(5.0f, 4.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(3.0f)
            horizontalLineToRelative(10.0f)
            lineTo(17.0f, 4.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(16.0f)
            close()
        }
    }

    val DeleteOutline: ImageVector by lazy {
        finIcon(name = "Filled.DeleteOutline") {
            moveTo(6.0f, 19.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(8.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(18.0f, 7.0f)
            lineTo(6.0f, 7.0f)
            verticalLineToRelative(12.0f)
            close()
            moveTo(8.0f, 9.0f)
            horizontalLineToRelative(8.0f)
            verticalLineToRelative(10.0f)
            lineTo(8.0f, 19.0f)
            lineTo(8.0f, 9.0f)
            close()
            moveTo(15.5f, 4.0f)
            lineToRelative(-1.0f, -1.0f)
            horizontalLineToRelative(-5.0f)
            lineToRelative(-1.0f, 1.0f)
            lineTo(5.0f, 4.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(14.0f)
            lineTo(19.0f, 4.0f)
            close()
        }
    }

    val PieChart: ImageVector by lazy {
        finIcon(name = "Filled.PieChart") {
            moveTo(11.0f, 2.0f)
            verticalLineToRelative(20.0f)
            curveToRelative(-5.07f, -0.5f, -9.0f, -4.79f, -9.0f, -10.0f)
            reflectiveCurveToRelative(3.93f, -9.5f, 9.0f, -10.0f)
            close()
            moveTo(13.03f, 2.0f)
            verticalLineToRelative(8.99f)
            lineTo(22.0f, 10.99f)
            curveToRelative(-0.47f, -4.74f, -4.24f, -8.52f, -8.97f, -8.99f)
            close()
            moveTo(13.03f, 13.01f)
            lineTo(13.03f, 22.0f)
            curveToRelative(4.74f, -0.47f, 8.5f, -4.25f, 8.97f, -8.99f)
            horizontalLineToRelative(-8.97f)
            close()
        }
    }

    val Refresh: ImageVector by lazy {
        finIcon(name = "Filled.Refresh") {
            moveTo(17.65f, 6.35f)
            curveTo(16.2f, 4.9f, 14.21f, 4.0f, 12.0f, 4.0f)
            curveToRelative(-4.42f, 0.0f, -7.99f, 3.58f, -7.99f, 8.0f)
            reflectiveCurveToRelative(3.57f, 8.0f, 7.99f, 8.0f)
            curveToRelative(3.73f, 0.0f, 6.84f, -2.55f, 7.73f, -6.0f)
            horizontalLineToRelative(-2.08f)
            curveToRelative(-0.82f, 2.33f, -3.04f, 4.0f, -5.65f, 4.0f)
            curveToRelative(-3.31f, 0.0f, -6.0f, -2.69f, -6.0f, -6.0f)
            reflectiveCurveToRelative(2.69f, -6.0f, 6.0f, -6.0f)
            curveToRelative(1.66f, 0.0f, 3.14f, 0.69f, 4.22f, 1.78f)
            lineTo(13.0f, 11.0f)
            horizontalLineToRelative(7.0f)
            verticalLineTo(4.0f)
            lineToRelative(-2.35f, 2.35f)
            close()
        }
    }

    val Settings: ImageVector by lazy {
        finIcon(name = "Filled.Settings") {
            moveTo(19.14f, 12.94f)
            curveToRelative(0.04f, -0.3f, 0.06f, -0.61f, 0.06f, -0.94f)
            curveToRelative(0.0f, -0.32f, -0.02f, -0.64f, -0.07f, -0.94f)
            lineToRelative(2.03f, -1.58f)
            curveToRelative(0.18f, -0.14f, 0.23f, -0.41f, 0.12f, -0.61f)
            lineToRelative(-1.92f, -3.32f)
            curveToRelative(-0.12f, -0.22f, -0.37f, -0.29f, -0.59f, -0.22f)
            lineToRelative(-2.39f, 0.96f)
            curveToRelative(-0.5f, -0.38f, -1.03f, -0.7f, -1.62f, -0.94f)
            lineTo(14.4f, 2.81f)
            curveToRelative(-0.04f, -0.24f, -0.24f, -0.41f, -0.48f, -0.41f)
            horizontalLineToRelative(-3.84f)
            curveToRelative(-0.24f, 0.0f, -0.43f, 0.17f, -0.47f, 0.41f)
            lineTo(9.25f, 5.35f)
            curveTo(8.66f, 5.59f, 8.12f, 5.92f, 7.63f, 6.29f)
            lineTo(5.24f, 5.33f)
            curveToRelative(-0.22f, -0.08f, -0.47f, 0.0f, -0.59f, 0.22f)
            lineTo(2.74f, 8.87f)
            curveTo(2.62f, 9.08f, 2.66f, 9.34f, 2.86f, 9.48f)
            lineToRelative(2.03f, 1.58f)
            curveTo(4.84f, 11.36f, 4.8f, 11.69f, 4.8f, 12.0f)
            reflectiveCurveToRelative(0.02f, 0.64f, 0.07f, 0.94f)
            lineToRelative(-2.03f, 1.58f)
            curveToRelative(-0.18f, 0.14f, -0.23f, 0.41f, -0.12f, 0.61f)
            lineToRelative(1.92f, 3.32f)
            curveToRelative(0.12f, 0.22f, 0.37f, 0.29f, 0.59f, 0.22f)
            lineToRelative(2.39f, -0.96f)
            curveToRelative(0.5f, 0.38f, 1.03f, 0.7f, 1.62f, 0.94f)
            lineToRelative(0.36f, 2.54f)
            curveToRelative(0.05f, 0.24f, 0.24f, 0.41f, 0.48f, 0.41f)
            horizontalLineToRelative(3.84f)
            curveToRelative(0.24f, 0.0f, 0.44f, -0.17f, 0.47f, -0.41f)
            lineToRelative(0.36f, -2.54f)
            curveToRelative(0.59f, -0.24f, 1.13f, -0.56f, 1.62f, -0.94f)
            lineToRelative(2.39f, 0.96f)
            curveToRelative(0.22f, 0.08f, 0.47f, 0.0f, 0.59f, -0.22f)
            lineToRelative(1.92f, -3.32f)
            curveToRelative(0.12f, -0.22f, 0.07f, -0.47f, -0.12f, -0.61f)
            lineTo(19.14f, 12.94f)
            close()
            moveTo(12.0f, 15.6f)
            curveToRelative(-1.98f, 0.0f, -3.6f, -1.62f, -3.6f, -3.6f)
            reflectiveCurveToRelative(1.62f, -3.6f, 3.6f, -3.6f)
            reflectiveCurveToRelative(3.6f, 1.62f, 3.6f, 3.6f)
            reflectiveCurveTo(13.98f, 15.6f, 12.0f, 15.6f)
            close()
        }
    }
}

/**
 * Builds a Material system icon with the library's defaults: a 24x24 dp icon over a 24x24 viewport,
 * a single solid-black filled path (tinted by the caller). Mirrors `materialIcon` + `materialPath`
 * from androidx material-icons so the copied path data renders identically.
 */
private inline fun finIcon(
    name: String,
    autoMirror: Boolean = false,
    pathBuilder: PathBuilder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24f.dp,
    defaultHeight = 24f.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
    autoMirror = autoMirror,
).path(
    fill = SolidColor(Color.Black),
    fillAlpha = 1f,
    stroke = null,
    strokeAlpha = 1f,
    strokeLineWidth = 1f,
    strokeLineCap = StrokeCap.Butt,
    strokeLineJoin = StrokeJoin.Bevel,
    strokeLineMiter = 1f,
    // materialPath uses vector.DefaultFillType, which is PathFillType.NonZero.
    pathFillType = PathFillType.NonZero,
    pathBuilder = pathBuilder,
).build()
