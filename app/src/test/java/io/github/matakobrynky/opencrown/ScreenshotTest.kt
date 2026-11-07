package io.github.matakobrynky.opencrown

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matakobrynky.opencrown.data.Repo
import io.github.matakobrynky.opencrown.mk.MkHub
import io.github.matakobrynky.opencrown.ui.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ok = BtStatus(true, true, true, true) {}

    private fun save(name: String, dialog: Boolean = false) {
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        root.draw(canvas)
        if (dialog) {
            val dv = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
            canvas.drawColor(0x99000000.toInt())
            canvas.save(); canvas.translate((root.width - dv.width) / 2f, (root.height - dv.height) / 2f); dv.draw(canvas); canvas.restore()
        }
        val dir = File(System.getProperty("user.dir"), "build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    @Config(qualifiers = "w400dp-h860dp-xxhdpi")
    fun home() {
        Repo.load(rule.activity); MkHub.init(rule.activity)
        Repo.upsertModule(io.github.matakobrynky.opencrown.data.HubModule(name = "Crawler H6.0", type = io.github.matakobrynky.opencrown.data.HubType.H6, address = "A4:C1:38:5E:22:10"))
        Ui.setMode(rule.activity, ThemeMode.DARK)
        rule.setContent { MkTheme { androidx.compose.material3.Surface(color = Bg) { HomeScreen(ok) { _, _ -> } } } }
        rule.waitForIdle(); save("home")
    }

    @Test
    @Config(qualifiers = "w400dp-h860dp-xxhdpi")
    fun homeLight() {
        Repo.load(rule.activity); MkHub.init(rule.activity)
        Ui.setMode(rule.activity, ThemeMode.LIGHT)
        Repo.upsertModule(io.github.matakobrynky.opencrown.data.HubModule(name = "H4.0 hub", type = io.github.matakobrynky.opencrown.data.HubType.H4, address = "C0:FF:EE:00:00:01"))
        rule.setContent { MkTheme { androidx.compose.material3.Surface(color = Bg) { HomeScreen(ok) { _, _ -> } } } }
        rule.waitForIdle(); save("home_light")
        Ui.setMode(rule.activity, ThemeMode.SYSTEM)
    }

    @Test
    @Config(qualifiers = "w860dp-h400dp-land-xxhdpi")
    fun controlLight() {
        Repo.load(rule.activity); MkHub.init(rule.activity)
        Ui.setMode(rule.activity, ThemeMode.LIGHT)
        rule.setContent { MkTheme { androidx.compose.material3.Surface(color = Bg) { ControlScreen(Repo.profiles.first().id, false, ok) {} } } }
        rule.waitForIdle(); save("control_light")
        Ui.setMode(rule.activity, ThemeMode.SYSTEM)
    }

    @Test
    @Config(qualifiers = "w860dp-h400dp-land-xxhdpi")
    fun servoSliderRestsAtEnd() {
        Repo.load(rule.activity); MkHub.init(rule.activity)
        Ui.setMode(rule.activity, ThemeMode.LIGHT)
        val hub = io.github.matakobrynky.opencrown.data.HubModule(name = "Hub", type = io.github.matakobrynky.opencrown.data.HubType.H4, address = "C0:FF:EE:00:00:01")
        Repo.upsertModule(hub)
        fun servo(minus: Float) = io.github.matakobrynky.opencrown.data.Binding(hub.id, 0, mode = io.github.matakobrynky.opencrown.data.OutputMode.SERVO,
            servoRange = io.github.matakobrynky.opencrown.data.ServoRange.HIGH, servoMinusDeg = minus, servoPlusDeg = 3600f, servoLinked = false)
        val p = io.github.matakobrynky.opencrown.data.Profile(name = "Servo test", widgets = listOf(
            io.github.matakobrynky.opencrown.data.Widget(type = io.github.matakobrynky.opencrown.data.WidgetType.SLIDER_V, x = 0.05f, y = 0.05f, w = 0.12f, h = 0.8f, label = "One way", primary = servo(0f)),
            io.github.matakobrynky.opencrown.data.Widget(type = io.github.matakobrynky.opencrown.data.WidgetType.SLIDER_V, x = 0.25f, y = 0.05f, w = 0.12f, h = 0.8f, label = "-5.4/+10", primary = servo(5.4f * 360f), color = 1),
            io.github.matakobrynky.opencrown.data.Widget(type = io.github.matakobrynky.opencrown.data.WidgetType.SLIDER_H, x = 0.45f, y = 0.3f, w = 0.45f, h = 0.2f, label = "Motor 100/45", primary = io.github.matakobrynky.opencrown.data.Binding(hub.id, 1, advanced = true, splitMax = true, maxPct = 100, maxRevPct = 45), color = 2),
        ))
        Repo.upsertProfile(p)
        rule.setContent { MkTheme { androidx.compose.material3.Surface(color = Bg) { ControlScreen(p.id, false, ok) {} } } }
        rule.waitForIdle(); save("servo_slider")
        Ui.setMode(rule.activity, ThemeMode.SYSTEM)
    }

    @Test
    @Config(qualifiers = "w860dp-h400dp-land-xxhdpi")
    fun controlEditLight() {
        Repo.load(rule.activity); MkHub.init(rule.activity)
        Ui.setMode(rule.activity, ThemeMode.LIGHT)
        rule.setContent { MkTheme { androidx.compose.material3.Surface(color = Bg) { ControlScreen(Repo.profiles.first().id, true, ok) {} } } }
        rule.waitForIdle(); save("control_edit_light")
        Ui.setMode(rule.activity, ThemeMode.SYSTEM)
    }

    @Test
    @Config(qualifiers = "w860dp-h400dp-land-xxhdpi")
    fun control() {
        Repo.load(rule.activity); MkHub.init(rule.activity)
        Ui.setMode(rule.activity, ThemeMode.DARK)
        rule.setContent { MkTheme { androidx.compose.material3.Surface(color = Bg) { ControlScreen(Repo.profiles.first().id, false, ok) {} } } }
        rule.waitForIdle(); save("control")
        Ui.setMode(rule.activity, ThemeMode.SYSTEM)
    }

    @Test
    @Config(qualifiers = "w860dp-h400dp-land-xxhdpi")
    fun controlEdit() {
        Repo.load(rule.activity); MkHub.init(rule.activity)
        Ui.setMode(rule.activity, ThemeMode.DARK)
        rule.setContent { MkTheme { androidx.compose.material3.Surface(color = Bg) { ControlScreen(Repo.profiles.first().id, true, ok) {} } } }
        rule.waitForIdle(); save("control_edit")
    }

    @Test
    @Config(qualifiers = "w860dp-h400dp-land-xxhdpi")
    fun editor() {
        Repo.load(rule.activity); MkHub.init(rule.activity)
        val w = Repo.profiles.first().widgets.last()
        rule.setContent { MkTheme { WidgetEditorDialog(w, {}, {}, {}) } }
        rule.waitForIdle(); save("editor", dialog = true)
    }

    @Test
    @Config(qualifiers = "w860dp-h400dp-land-xxhdpi")
    fun editorLight() {
        Repo.load(rule.activity); MkHub.init(rule.activity)
        Ui.setMode(rule.activity, ThemeMode.LIGHT)
        val w = Repo.profiles.first().widgets.last()
        rule.setContent { MkTheme { WidgetEditorDialog(w, {}, {}, {}) } }
        rule.waitForIdle(); save("editor_light", dialog = true)
        Ui.setMode(rule.activity, ThemeMode.SYSTEM)
    }

    @Test
    @Config(qualifiers = "w640dp-h1900dp-xhdpi")
    fun motorAdvanced() {
        Repo.load(rule.activity); MkHub.init(rule.activity)
        Ui.setMode(rule.activity, ThemeMode.LIGHT)
        val hub = io.github.matakobrynky.opencrown.data.HubModule(name = "H4.0 hub", type = io.github.matakobrynky.opencrown.data.HubType.H4, address = "C0:FF:EE:00:00:01")
        Repo.upsertModule(hub)
        val w = io.github.matakobrynky.opencrown.data.Widget(
            type = io.github.matakobrynky.opencrown.data.WidgetType.SLIDER_V, x = 0f, y = 0f, w = 0.1f, h = 0.5f, label = "Drive",
            primary = io.github.matakobrynky.opencrown.data.Binding(hub.id, 0, advanced = true, splitMax = true, maxPct = 100, maxRevPct = 45,
                accel = true, accelMs = 1200),
        )
        rule.setContent { MkTheme { WidgetEditorDialog(w, {}, {}, {}) } }
        rule.waitForIdle(); save("motor_advanced", dialog = true)
        Ui.setMode(rule.activity, ThemeMode.SYSTEM)
    }

    @Test
    @Config(qualifiers = "w640dp-h1900dp-xhdpi")
    fun motorEndStops() {
        Repo.load(rule.activity); MkHub.init(rule.activity)
        Ui.setMode(rule.activity, ThemeMode.DARK)
        val hub = io.github.matakobrynky.opencrown.data.HubModule(name = "H4.0 hub", type = io.github.matakobrynky.opencrown.data.HubType.H4, address = "C0:FF:EE:00:00:01")
        Repo.upsertModule(hub)
        val w = io.github.matakobrynky.opencrown.data.Widget(
            type = io.github.matakobrynky.opencrown.data.WidgetType.BUTTON, x = 0f, y = 0f, w = 0.1f, h = 0.2f, label = "Extend",
            primary = io.github.matakobrynky.opencrown.data.Binding(hub.id, 0, maxPct = 70, endStops = true,
                servoRange = io.github.matakobrynky.opencrown.data.ServoRange.HIGH, servoMinusDeg = 0f, servoPlusDeg = 12 * 360f, servoLinked = false),
        )
        rule.setContent { MkTheme { WidgetEditorDialog(w, {}, {}, {}) } }
        rule.waitForIdle(); save("motor_end_stops", dialog = true)
        Ui.setMode(rule.activity, ThemeMode.SYSTEM)
    }

    @Test
    @Config(qualifiers = "w640dp-h1700dp-xhdpi")
    fun servoEditor() {
        Repo.load(rule.activity); MkHub.init(rule.activity)
        val hub = io.github.matakobrynky.opencrown.data.HubModule(name = "Crane H6.0", type = io.github.matakobrynky.opencrown.data.HubType.H6, address = "A4:C1:38:5E:22:10")
        Repo.upsertModule(hub)
        val w = io.github.matakobrynky.opencrown.data.Widget(
            type = io.github.matakobrynky.opencrown.data.WidgetType.SLIDER_H, x = 0f, y = 0f, w = 0.3f, h = 0.2f, label = "Steer",
            primary = io.github.matakobrynky.opencrown.data.Binding(hub.id, 1, mode = io.github.matakobrynky.opencrown.data.OutputMode.SERVO,
                servoCenterDeg = 15f, servoMinusDeg = 90f, servoPlusDeg = 120f, servoLinked = false, servoSpeed = 80),
        )
        rule.setContent { MkTheme { WidgetEditorDialog(w, {}, {}, {}) } }
        rule.waitForIdle(); save("servo_editor", dialog = true)
    }

    @Test
    @Config(qualifiers = "w640dp-h1900dp-xhdpi")
    fun servoEditorHigh() {
        Repo.load(rule.activity); MkHub.init(rule.activity)
        Ui.setMode(rule.activity, ThemeMode.DARK)
        val hub = io.github.matakobrynky.opencrown.data.HubModule(name = "Crane H6.0", type = io.github.matakobrynky.opencrown.data.HubType.H6, address = "A4:C1:38:5E:22:10")
        Repo.upsertModule(hub)
        val w = io.github.matakobrynky.opencrown.data.Widget(
            type = io.github.matakobrynky.opencrown.data.WidgetType.SLIDER_V, x = 0f, y = 0f, w = 0.1f, h = 0.5f, label = "Boom",
            primary = io.github.matakobrynky.opencrown.data.Binding(hub.id, 2, mode = io.github.matakobrynky.opencrown.data.OutputMode.SERVO,
                servoRange = io.github.matakobrynky.opencrown.data.ServoRange.HIGH, servoCenterDeg = 0f, servoMinusDeg = 0f,
                servoPlusDeg = 42 * 360f, servoLinked = false, servoSpeed = 100),
        )
        Ui.collapsed.clear(); Ui.collapsed.addAll(listOf("servo:SETUP", "servo:SPEED & TEST"))
        rule.setContent { MkTheme { WidgetEditorDialog(w, {}, {}, {}) } }
        rule.waitForIdle(); save("servo_editor_high", dialog = true)
        Ui.collapsed.clear()
        Ui.setMode(rule.activity, ThemeMode.SYSTEM)
    }
}
