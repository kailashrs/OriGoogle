package io.github.kailashrs.origoogle.interaction

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.widget.ImageView
import io.github.kailashrs.origoogle.core.CornerAssistant
import io.github.kailashrs.origoogle.core.CornerAssistant.Corner
import io.github.kailashrs.origoogle.core.Hooks
import java.lang.reflect.Field

/**
 * The Interaction Cube (com.vivo.upslide, InteractionActivity) lets the user pick what each bottom
 * corner swipe does through small selection dialogs built by LeftBottomCornerPanelBuilder and
 * RightBottomCornerPanelBuilder. After the stock panel is built, a "Google Assistant" option is added
 * before "None": a stock option is never removed or reordered. The stock action list (one Runnable per
 * option) is reused, so vivo's own bookkeeping for each option stays intact.
 *
 * Choosing the assistant runs the stock "None" action, then flags the corner in [CornerAssistant].
 * The launcher hook (LauncherHooks) turns the flag into AssistantTouchConsumer for that corner only.
 */
object InteractionCubeHooks {
    private const val GUIDE = "com.vivo.interaction.guide"
    private const val LEFT_SIDE_APP_LIB = "side_app_lib"
    private const val LEFT_SIDE_SMALL_WINDOW = "side_small_window"
    private const val ASSISTANT_LABEL = "Google Assistant"

    /** Ids outside every installed package; the dialogs only pass them to ImageView.setImageResource. */
    private const val ICON_LEFT_ON = 0x7e0f0001
    private const val ICON_LEFT_OFF = 0x7e0f0002
    private const val ICON_RIGHT_ON = 0x7e0f0003
    private const val ICON_RIGHT_OFF = 0x7e0f0004

    /** OriginOS 6 card size in dp, the drawing units of [AssistantIcon]. */
    private const val WIDTH = 105f
    private const val HEIGHT = 138f

    /** Set while the assistant choice runs the stock "None" action, which must not clear the flag. */
    private val applying = ThreadLocal<Boolean>()

    private class Slot(val on: Int, val off: Int, val label: String, val selected: Boolean)

    fun install(loader: ClassLoader) {
        val panels = "$GUIDE.control.panel"
        hookPanel(Hooks.findClass("$panels.LeftBottomCornerPanelBuilder", loader), Corner.LEFT, loader)
        hookPanel(Hooks.findClass("$panels.RightBottomCornerPanelBuilder", loader), Corner.RIGHT, loader)

        // Any other choice in the cube (or vivo resetting the corner) replaces the assistant.
        val utils = Hooks.findClass("com.vivo.upslide.navigation.NavigationSettingsUtils", loader)
        Hooks.hook(Hooks.method(utils, "E1", Context::class.java, String::class.java)) { chain ->
            val result = chain.proceed()
            if (applying.get() != true) CornerAssistant.choose(chain.args[0] as Context, Corner.LEFT, false)
            result
        }
        Hooks.hook(Hooks.method(utils, "V1", Context::class.java, Int::class.javaPrimitiveType!!)) { chain ->
            val result = chain.proceed()
            if (applying.get() != true) CornerAssistant.choose(chain.args[0] as Context, Corner.RIGHT, false)
            result
        }

        Hooks.hook(ImageView::class.java.getMethod("setImageResource", Int::class.javaPrimitiveType!!)) { chain ->
            val icon = icon(chain.thisObject as ImageView, chain.args[0] as Int)
            if (icon == null) {
                chain.proceed()
            } else {
                (chain.thisObject as ImageView).setImageDrawable(icon)
                null
            }
        }
    }

    private fun hookPanel(panel: Class<*>, corner: Corner, loader: ClassLoader) {
        Hooks.hook(Hooks.method(panel, "a")) { chain ->
            val result = chain.proceed()
            runCatching { addAssistant(chain.thisObject!!, corner, loader) }
                .onFailure { Hooks.error("Interaction Cube $corner: stock options kept", it) }
            result
        }
    }

    private fun addAssistant(panel: Any, corner: Corner, loader: ClassLoader) {
        val context = fieldValue(panel) { Context::class.java.isAssignableFrom(it) } as Context
        val dialog = fieldValue(panel) { it.name == "$GUIDE.bean.DialogBuilder" }!!
        @Suppress("UNCHECKED_CAST")
        val actions = fieldValue(panel) { it == List::class.java } as MutableList<Runnable>

        val dialogType = dialog.javaClass
        val getBean = dialogType.declaredMethods.single { it.parameterCount == 0 && it.returnType == Any::class.java }
        val getMargins = dialogType.declaredMethods.single { it.parameterCount == 0 && it.returnType == IntArray::class.java }
        val stockOptions = when (getBean.also { it.isAccessible = true }.invoke(dialog)?.javaClass?.simpleName) {
            "SelectTypeTopBottomBean" -> 2
            "SelectTypeTopMiddleBottomBean" -> 3
            else -> return Hooks.info("Interaction Cube $corner: unexpected dialog, left alone")
        }
        check(actions.size == stockOptions) { "stock actions (${actions.size}) do not match its options ($stockOptions)" }

        val resources = context.resources
        fun string(name: String) = resources.getIdentifier(name, "string", context.packageName)
            .also { check(it != 0) { "missing string $name" } }.let(resources::getString)
        fun drawable(name: String) = resources.getIdentifier(name, "drawable", context.packageName)
            .also { check(it != 0) { "missing drawable $name" } }

        val assistant = CornerAssistant.chosen(context, corner)
        val title: String
        val before = ArrayList<Slot>()
        val none: Slot
        val moveDp: Int
        when (corner) {
            Corner.LEFT -> {
                val value = Settings.Secure.getString(context.contentResolver, CornerAssistant.LEFT_SETTING)
                val appLib = stockOptions == 3
                val appLibSelected = !assistant && appLib && value == LEFT_SIDE_APP_LIB
                val smallWindowSelected = !assistant && value == LEFT_SIDE_SMALL_WINDOW
                title = string("vivo_interaction_panel_left_bottom_corner")
                if (appLib) {
                    before += Slot(drawable("vivo_interaction_app_lib_on"), drawable("vivo_interaction_app_lib_off"),
                        string("vivo_interaction_panel_app_lib"), appLibSelected)
                }
                before += Slot(drawable("vivo_interaction_small_window_on"), drawable("vivo_interaction_small_window_off"),
                    string("vivo_interaction_panel_app_small_window"), smallWindowSelected)
                none = Slot(drawable("vivo_interaction_none_on"), drawable("vivo_interaction_none_off"),
                    string("vivo_interaction_suggest_none"), !assistant && !appLibSelected && !smallWindowSelected)
                // Each extra option moves the bottom-anchored dialog up by one row (3 options: 252dp, 2: 402dp).
                moveDp = -150
            }
            Corner.RIGHT -> {
                val multiTask = Settings.Secure.getInt(context.contentResolver, CornerAssistant.RIGHT_SETTING, CornerAssistant.RIGHT_DEFAULT) == 1
                title = string("vivo_interaction_panel_right_bottom_corner")
                before += Slot(drawable("vivo_interaction_panel_app_atomic_table_on"), drawable("vivo_interaction_panel_app_atomic_table_off"),
                    string("vivo_interaction_panel_app_atomic_table_v2"), !assistant && multiTask)
                none = Slot(drawable("vivo_interaction_none_on"), drawable("vivo_interaction_none_off"),
                    string("vivo_interaction_suggest_none"), !assistant && !multiTask)
                moveDp = -130
            }
        }
        val google = if (corner == Corner.LEFT) Slot(ICON_LEFT_ON, ICON_LEFT_OFF, ASSISTANT_LABEL, assistant)
        else Slot(ICON_RIGHT_ON, ICON_RIGHT_OFF, ASSISTANT_LABEL, assistant)
        val slots = before + google + none

        // Two stock options become three (top/middle/bottom); three become a two-by-two grid.
        val grid = slots.size > 3
        val beanType = Hooks.findClass(
            if (grid) "$GUIDE.bean.SelectTypeTwoRowsTwoColumnsBean" else "$GUIDE.bean.SelectTypeTopMiddleBottomBean", loader)
        val args = ArrayList<Any>().apply {
            add(title)
            slots.forEach { add(it.on); add(it.off); add(it.label); add(it.selected) }
        }
        val bean = beanType.declaredConstructors.single { it.parameterCount == args.size }.newInstance(*args.toTypedArray())

        val typeEnum = Hooks.findClass("$GUIDE.view.DialogType\$Type", loader)
        val type = typeEnum.enumConstants!!.first {
            (it as Enum<*>).name == if (grid) "SELECT_TYPE_TWO_ROWS_TWO_COLUMNS" else "SELECT_TYPE_TOP_MIDDLE_BOTTOM"
        }
        val margins = (getMargins.also { it.isAccessible = true }.invoke(dialog) as IntArray).clone()
        margins[1] += ((if (grid) 150 else moveDp) * resources.displayMetrics.density).toInt()

        val stockNone = actions.last()
        val choose = Runnable {
            applying.set(true)
            try {
                stockNone.run()
            } finally {
                applying.remove()
            }
            CornerAssistant.choose(context, corner, true)
        }

        fun setter(parameter: Class<*>) = dialogType.declaredMethods
            .single { it.parameterCount == 1 && it.parameterTypes[0] == parameter && it.returnType == Void.TYPE }
            .also { it.isAccessible = true }
        actions.add(actions.size - 1, choose)
        setter(typeEnum).invoke(dialog, type)
        setter(Any::class.java).invoke(dialog, bean)
        setter(IntArray::class.java).invoke(dialog, margins)
        setter(List::class.java).invoke(dialog, actions)
        Hooks.info("Interaction Cube: added Google Assistant to the $corner corner (${slots.size} options, assistant=$assistant)")
    }

    private fun fieldValue(owner: Any, matches: (Class<*>) -> Boolean): Any? {
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            val field: Field? = type.declaredFields.firstOrNull { matches(it.type) }
            if (field != null) return field.also { it.isAccessible = true }.get(owner)
            type = type.superclass
        }
        throw NoSuchFieldException("${owner.javaClass.name}: no field of the wanted type")
    }

    private fun icon(view: ImageView, id: Int): Drawable? {
        val on = id == ICON_LEFT_ON || id == ICON_RIGHT_ON
        val right = id == ICON_RIGHT_ON || id == ICON_RIGHT_OFF
        if (id != ICON_LEFT_ON && id != ICON_LEFT_OFF && !right) return null
        val context = view.context
        // Take the size from vivo's own "none" card: 105 x 138 dp on OriginOS 6, 70 x 92 dp on OriginOS 7.
        val stock = runCatching {
            val res = context.resources.getIdentifier("vivo_interaction_none_on", "drawable", context.packageName)
            context.getDrawable(res)
        }.getOrNull()
        val density = context.resources.displayMetrics.density
        val width = stock?.intrinsicWidth?.takeIf { it > 0 } ?: (WIDTH * density).toInt()
        val height = stock?.intrinsicHeight?.takeIf { it > 0 } ?: (HEIGHT * density).toInt()
        val night = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        // OriginOS 7's smaller cards leave a wider margin around the phone (12 of 70 dp, against 8 of 105).
        val compact = width < 90 * density
        return AssistantIcon(on, right, night, compact, width, height)
    }

    /**
     * Matches the cube's phone-card illustrations: the corner being configured is highlighted, and the
     * assistant's four dots sit on the screen. Drawn in OriginOS 6's 105 x 138 units and scaled to the
     * stock card size; OriginOS 7 uses a dark card in night mode.
     */
    private class AssistantIcon(
        private val on: Boolean,
        private val right: Boolean,
        private val night: Boolean,
        compact: Boolean,
        private val width: Int,
        private val height: Int,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val card = if (compact) RectF(17.5f, 17.5f, 87.5f, 122.5f) else RectF(8f, 8f, 97f, 130f)

        override fun getIntrinsicWidth() = width

        override fun getIntrinsicHeight() = height

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / WIDTH, bounds.height() / HEIGHT)
            // Element sizes follow the card, which is 89 units wide on OriginOS 6.
            val s = card.width() / 89f
            val radius = 12f * s

            paint.style = Paint.Style.FILL
            paint.color = if (night) 0xFF292929.toInt() else 0xFFEEEEEE.toInt()
            canvas.drawRoundRect(card, radius, radius, paint)

            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(card, radius, radius, Path.Direction.CW) })
            val cornerX = if (right) card.right else card.left
            val direction = if (right) -1f else 1f
            paint.color = when {
                on && night -> 0xFF2D4F86.toInt()
                on -> 0xFF8CB8FF.toInt()
                night -> 0xFF3A3A3A.toInt()
                else -> 0xFFC9C9C9.toInt()
            }
            canvas.drawCircle(cornerX, card.bottom, 27f * s, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f * s
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = if (on) (if (night) 0xFF5B9DFF.toInt() else 0xFF2F7BFF.toInt()) else 0xFF8E8E8E.toInt()
            val fromX = cornerX + direction * 7f * s
            val fromY = card.bottom - 7f * s
            val toX = cornerX + direction * 19f * s
            val toY = card.bottom - 19f * s
            canvas.drawLine(fromX, fromY, toX, toY, paint)
            canvas.drawLine(toX, toY, toX - direction * 8f * s, toY, paint)
            canvas.drawLine(toX, toY, toX, toY + 8f * s, paint)
            canvas.restore()

            paint.style = Paint.Style.FILL
            for (index in 0..3) {
                paint.color = if (on) DOTS[index] else if (night) 0xFF5C5C5C.toInt() else 0xFFA8A8A8.toInt()
                canvas.drawCircle(card.centerX() + (index - 1.5f) * 17f * s, card.top + 50f * s, 6.5f * s, paint)
            }
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT

        private companion object {
            val DOTS = intArrayOf(0xFF4285F4.toInt(), 0xFFEA4335.toInt(), 0xFFFBBC05.toInt(), 0xFF34A853.toInt())
        }
    }
}
