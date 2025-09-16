package com.eligibbs.ive

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JViewport
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class IVEWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = "ive-widget"
    override fun getDisplayName(): String = "Image Viewer Enhancements"
    override fun isAvailable(project: Project): Boolean = true
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
    override fun isEnabledByDefault(): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = IVEWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        Disposer.dispose(widget)
    }
}

private class IVEWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.TextPresentation, Disposable {

    private var statusBar: StatusBar? = null
    private var text: String = "x: -, y: -"

    private var listeningComponent: JComponent? = null
    private var mouseListener: MouseMotionAdapter? = null
    private var awtListener: AWTEventListener? = null

    private var image: BufferedImage? = null
    private var imageBoundsInComponent: Rectangle? = null
    private var zoomFactor: Double? = null

    private var lockEnabled: Boolean = false
    private var lockedPx: Int? = null
    private var lockedPy: Int? = null
    private var lockedRgb: Int? = null

    private var lastPx: Int? = null
    private var lastPy: Int? = null
    private var lastRgb: Int? = null

    private val connection = project.messageBus.connect(this).apply {
        subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                attachToCurrentImageEditor()
            }
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                attachToCurrentImageEditor()
            }
        })
    }

    init {
        ApplicationManager.getApplication().invokeLater {
            attachToCurrentImageEditor()
        }
    }

    override fun ID(): String = "ive-widget"
    override fun install(statusBar: StatusBar) { this.statusBar = statusBar }
    override fun dispose() {
        detachMouse()
        connection.dispose()
        statusBar = null
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun getText(): String = text
    override fun getAlignment(): Float = Component.LEFT_ALIGNMENT
    override fun getTooltipText(): String? =
        if (listeningComponent != null) "Pixel coordinates under cursor" else null

    private fun setTextSafe(s: String) {
        if (s == text) return
        text = s
        statusBar?.updateWidget(ID())
    }

    private fun attachToCurrentImageEditor() {
        detachMouse()

        val fem = FileEditorManager.getInstance(project)
        val editor: FileEditor = fem.selectedEditor ?: run {
            setTextSafe("x: -, y: -")
            return
        }
        val file = fem.selectedFiles.firstOrNull()
        if (!isImageFile(file)) {
            setTextSafe("x: -, y: -")
            return
        }

        val root = (editor.component as? JComponent) ?: run {
            setTextSafe("x: -, y: -")
            return
        }

        image = extractBufferedImage(editor)
            ?: extractBufferedImage(root)
            ?: decodeFile(file)

        val img = image
        if (img == null) {
            val canvas = findViewportView(root) ?: root
            attachListeners(canvas)
            setTextSafe("x: -, y: -")
            return
        }

        val base = findViewportView(root) ?: root
        val canvas = findImageCanvas(base, img) ?: base

        zoomFactor = extractZoom(editor)
        if (zoomFactor == null && canvas.width > 0 && canvas.height > 0) {
            val zx = canvas.width.toDouble() / img.width
            val zy = canvas.height.toDouble() / img.height
            if (zx > 0.0 && zy > 0.0 && kotlin.math.abs(zx - zy) <= 0.05 * kotlin.math.max(zx, zy))
                zoomFactor = (zx + zy) / 2.0
        }

        imageBoundsInComponent = Rectangle(0, 0, canvas.width.coerceAtLeast(1), canvas.height.coerceAtLeast(1))

        attachListeners(canvas)
        setTextSafe("x: -, y: -")
    }

    private fun attachListeners(canvas: JComponent) {
        val listener = object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                handleMouseMoveDynamic(e, canvas)
            }
        }
        canvas.addMouseMotionListener(listener)
        canvas.cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
        mouseListener = listener

        val global = AWTEventListener { awtEvent ->
            when (awtEvent) {
                is MouseEvent -> {
                    if (awtEvent.id != MouseEvent.MOUSE_MOVED && awtEvent.id != MouseEvent.MOUSE_DRAGGED) return@AWTEventListener
                    val lc = listeningComponent ?: return@AWTEventListener
                    val src = awtEvent.component ?: return@AWTEventListener
                    val p = try { SwingUtilities.convertPoint(src, awtEvent.point, lc) } catch (_: Throwable) { null } ?: return@AWTEventListener
                    handleMouseMoveOnComponent(p, lc)
                }
                is KeyEvent -> {
                    if (awtEvent.id != KeyEvent.KEY_PRESSED) return@AWTEventListener
                    val lc = listeningComponent ?: return@AWTEventListener
                    val src = awtEvent.component ?: return@AWTEventListener
                    if (!SwingUtilities.isDescendingFrom(src, lc)) return@AWTEventListener
                    if (awtEvent.keyCode == KeyEvent.VK_L && awtEvent.isControlDown) {
                        toggleLock()
                    }
                }
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(
            global,
            AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.KEY_EVENT_MASK
        )
        awtListener = global

        listeningComponent = canvas
    }

    private fun detachMouse() {
        listeningComponent?.let { c ->
            mouseListener?.let { c.removeMouseMotionListener(it) }
            c.cursor = Cursor.getDefaultCursor()
        }
        awtListener?.let {
            Toolkit.getDefaultToolkit().removeAWTEventListener(it)
        }
        listeningComponent = null
        mouseListener = null
        awtListener = null
        imageBoundsInComponent = null
        image = null
        zoomFactor = null
        lockEnabled = false
        lockedPx = null
        lockedPy = null
        lockedRgb = null
        lastPx = null
        lastPy = null
        lastRgb = null
        setTextSafe("x: -, y: -")
    }

    private fun handleMouseMoveDynamic(e: MouseEvent, base: JComponent) {
        val img = image ?: run { setTextSafe("x: -, y: -"); return }

        if (lockEnabled && lockedPx != null && lockedPy != null) {
            updateStatusFromValues(lockedPx!!, lockedPy!!, lockedRgb)
            return
        }

        val deepest = (SwingUtilities.getDeepestComponentAt(base, e.x, e.y) as? JComponent) ?: base
        val canvas = findImageCanvasAround(deepest, img) ?: base

        val pc = if (canvas === e.component) e.point else SwingUtilities.convertPoint(e.component, e.point, canvas)

        val bounds = Rectangle(0, 0, canvas.width.coerceAtLeast(1), canvas.height.coerceAtLeast(1))
        if (!bounds.contains(pc)) {
            setTextSafe("x: -, y: -")
            return
        }

        val sx = bounds.width.toDouble() / img.width
        val sy = bounds.height.toDouble() / img.height
        val fx = (pc.x - bounds.x) / sx
        val fy = (pc.y - bounds.y) / sy
        val px = kotlin.math.min(img.width - 1, kotlin.math.max(0, fx.toInt()))
        val py = kotlin.math.min(img.height - 1, kotlin.math.max(0, fy.toInt()))

        val rgb = try { img.getRGB(px, py) } catch (_: Throwable) { null }

        lastPx = px
        lastPy = py
        lastRgb = rgb

        updateStatusFromValues(px, py, rgb)
    }

    private fun handleMouseMoveOnComponent(p: java.awt.Point, comp: JComponent) {
        val img = image ?: run { setTextSafe("x: -, y: -"); return }

        if (lockEnabled && lockedPx != null && lockedPy != null) {
            updateStatusFromValues(lockedPx!!, lockedPy!!, lockedRgb)
            return
        }

        val canvas = findImageCanvasAround(comp, img) ?: comp
        val pc = if (canvas === comp) p else SwingUtilities.convertPoint(comp, p, canvas)

        val bounds = Rectangle(0, 0, canvas.width.coerceAtLeast(1), canvas.height.coerceAtLeast(1))
        if (!bounds.contains(pc)) {
            setTextSafe("x: -, y: -")
            return
        }

        val sx = bounds.width.toDouble() / img.width
        val sy = bounds.height.toDouble() / img.height
        val fx = (pc.x - bounds.x) / sx
        val fy = (pc.y - bounds.y) / sy
        val px = kotlin.math.min(img.width - 1, kotlin.math.max(0, fx.toInt()))
        val py = kotlin.math.min(img.height - 1, kotlin.math.max(0, fy.toInt()))

        val rgb = try { img.getRGB(px, py) } catch (_: Throwable) { null }

        lastPx = px
        lastPy = py
        lastRgb = rgb

        updateStatusFromValues(px, py, rgb)
    }

    private fun updateStatusFromValues(px: Int, py: Int, rgb: Int?) {
        val colorText = rgb?.let { formatColorText(it) } ?: ""
        val lockText = if (lockEnabled) " (locked)" else ""
        setTextSafe("x: $px, y: $py$colorText$lockText")
    }

    private fun formatColorText(argb: Int): String {
        val a = argb ushr 24 and 0xFF
        val r = argb ushr 16 and 0xFF
        val g = argb ushr 8 and 0xFF
        val b = argb and 0xFF
        val rgbHex = "#%02X%02X%02X".format(r, g, b)
        val argbHex = "#%02X%02X%02X%02X".format(a, r, g, b)
        val hexPart = if (a == 0xFF) " HEX $rgbHex" else " HEX $rgbHex ($argbHex)"
        return " RGBA($r,$g,$b,$a)$hexPart"
    }

    private fun toggleLock() {
        lockEnabled = !lockEnabled
        if (lockEnabled) {
            val px = lastPx
            val py = lastPy
            if (px != null && py != null) {
                lockedPx = px
                lockedPy = py
                lockedRgb = lastRgb
                updateStatusFromValues(px, py, lockedRgb)
            } else {
                lockEnabled = false
            }
        } else {
            lockedPx = null
            lockedPy = null
            lockedRgb = null
        }
    }

    private fun findImageCanvas(root: JComponent, img: BufferedImage): JComponent? {
        var best: JComponent? = null
        var bestScore = Double.NEGATIVE_INFINITY

        val queue = ArrayDeque<Component>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (c is JComponent && c.isShowing && c.width > 0 && c.height > 0) {
                val zx = c.width.toDouble() / img.width
                val zy = c.height.toDouble() / img.height
                val uniform = 1.0 - (kotlin.math.abs(zx - zy) / kotlin.math.max(1.0, kotlin.math.max(zx, zy)))
                val isLeaf = (c !is Container) || (c is Container && c.componentCount == 0)
                val leafBonus = if (isLeaf) 0.5 else 0.0
                val score = uniform * 2.0 + leafBonus
                if (uniform > 0.90 && score > bestScore) {
                    best = c
                    bestScore = score
                }
            }
            if (c is Container) c.components.forEach { queue.add(it) }
        }
        return best
    }

    private fun findImageCanvasAround(start: JComponent, img: BufferedImage): JComponent? {
        val vp = findViewportAncestor(start)
        val view = (vp?.view as? JComponent)
        if (view != null) {
            findImageCanvas(view, img)?.let { return it }
            return view
        }

        var c: Component? = start
        repeat(5) {
            val jc = c as? JComponent
            if (jc != null) {
                findImageCanvas(jc, img)?.let { return it }
            }
            c = c?.parent
        }
        return start
    }

    private fun computeBoundsForCanvas(canvas: JComponent, img: BufferedImage): Rectangle {
        val z = zoomFactor
        if (z != null && z > 0.0) {
            val scaledW = (img.width * z).roundToInt().coerceAtLeast(1)
            val scaledH = (img.height * z).roundToInt().coerceAtLeast(1)
            return if (scaledW <= canvas.width && scaledH <= canvas.height) {
                val x = (canvas.width - scaledW) / 2
                val y = (canvas.height - scaledH) / 2
                Rectangle(x, y, scaledW, scaledH)
            } else {
                Rectangle(0, 0, scaledW, scaledH)
            }
        }

        val zx = canvas.width.toDouble() / img.width
        val zy = canvas.height.toDouble() / img.height
        return if (zx > 0.0 && zy > 0.0 && abs(zx - zy) <= 0.05 * max(zx, zy)) {
            Rectangle(0, 0, canvas.width, canvas.height)
        } else {
            val scale = min(zx, zy).coerceAtLeast(0.0001)
            val w = (img.width * scale).toInt().coerceAtLeast(1)
            val h = (img.height * scale).toInt().coerceAtLeast(1)
            val x = (canvas.width - w) / 2
            val y = (canvas.height - h) / 2
            Rectangle(x, y, w, h)
        }
    }

    private fun findViewportView(root: JComponent): JComponent? {
        val queue = ArrayDeque<Component>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (c is JViewport) {
                val view = c.view
                if (view is JComponent) return view
            }
            if (c is Container) c.components.forEach { queue.add(it) }
        }
        return null
    }

    private fun findViewportAncestor(start: JComponent): JViewport? {
        var c: Component? = start
        while (c != null) {
            if (c is JViewport) return c
            c = c.parent
        }
        return null
    }

    private fun findBestPaintingComponent(root: JComponent, img: BufferedImage): JComponent? {
        var best: JComponent? = null
        var bestScore = Double.NEGATIVE_INFINITY
        val rootArea = (root.width * root.height).toDouble().coerceAtLeast(1.0)

        val queue = ArrayDeque<Component>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (c is JComponent && c.isShowing && c.width > 0 && c.height > 0) {
                val zx = c.width.toDouble() / img.width
                val zy = c.height.toDouble() / img.height
                val uniform = 1.0 - (abs(zx - zy) / max(1.0, max(zx, zy)))
                val containment = if (c.width <= root.width + 2 && c.height <= root.height + 2) 1.0 else 0.0
                val area = (c.width * c.height).toDouble()
                val score = uniform * 2.0 + containment + area / rootArea
                if (uniform > 0.90 && score > bestScore) {
                    best = c
                    bestScore = score
                }
            }
            if (c is Container) c.components.forEach { queue.add(it) }
        }
        return best
    }

    private fun extractBufferedImage(target: Any): BufferedImage? {
        val cls = target.javaClass
        val tryGetters = listOf("getDocument", "getImage", "getBufferedImage")
        for (mName in tryGetters) {
            try {
                val m = cls.methods.firstOrNull { it.name == mName && it.parameterCount == 0 } ?: continue
                val v = m.invoke(target)
                when (v) {
                    is BufferedImage -> return v
                    else -> {
                        val valGetter = v?.javaClass?.methods?.firstOrNull { it.name == "getValue" && it.parameterCount == 0 }
                        val value = valGetter?.invoke(v)
                        if (value is BufferedImage) return value
                    }
                }
            } catch (_: Throwable) { /* ignore */ }
        }
        return null
    }

    private fun decodeFile(file: VirtualFile?): BufferedImage? {
        if (file == null) return null
        return try { file.inputStream.use { ImageIO.read(it) } } catch (_: Throwable) { null }
    }

    private fun extractZoom(editor: Any): Double? {
        try {
            val zm = editor.javaClass.methods.firstOrNull { it.name == "getZoomModel" && it.parameterCount == 0 }?.invoke(editor)
            if (zm != null) {
                val getter = zm.javaClass.methods.firstOrNull { it.name.contains("Zoom") && it.parameterCount == 0 }
                val value = getter?.invoke(zm)
                if (value is Number) return value.toDouble()
            }
        } catch (_: Throwable) { /* ignore */ }
        return null
    }

    private fun isImageFile(file: VirtualFile?): Boolean {
        val ext = file?.extension?.lowercase() ?: return false
        return ext in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
    }
}