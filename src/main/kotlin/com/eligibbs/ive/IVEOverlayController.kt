package com.eligibbs.ive

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.awt.AWTEvent
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.JViewport
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class IVEOverlayController(private val project: Project) : Disposable {
    private val log = Logger.getInstance(IVEOverlayController::class.java)

    private var overlay: IVEOverlayPanel? = null
    private var attachedRoot: JRootPane? = null
    private var lastFile: VirtualFile? = null

    private val connection = project.messageBus.connect(this).apply {
        subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                scheduleRefresh()
            }

            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                scheduleRefresh()
            }

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                scheduleRefresh()
            }
        })
    }

    init {
        log.info("IVEOverlayController initialized for project: ${project.name}")
        ApplicationManager.getApplication().invokeLater {
            refresh()
        }
    }

    override fun dispose() {
        detachOverlay()
        connection.dispose()
    }

    private fun scheduleRefresh() {
        ApplicationManager.getApplication().invokeLater {
            refresh()
        }
    }

    private fun refresh() {
        log.info("Refreshing overlay")

        val fem = FileEditorManager.getInstance(project)
        val editor: FileEditor = fem.selectedEditor ?: run {
            log.info("No selected editor")
            detachOverlay()
            return
        }

        val file = fem.selectedFiles.firstOrNull() ?: run {
            log.info("No selected file")
            detachOverlay()
            return
        }

        if (!isImageFile(file)) {
            log.info("Selected file is not an image: ${file.path}")
            detachOverlay()
            return
        }

        val editorComponent = editor.component as? JComponent ?: run {
            log.warn("Editor component is not a JComponent")
            detachOverlay()
            return
        }

        val rootPane = SwingUtilities.getRootPane(editorComponent) ?: run {
            log.warn("Could not resolve JRootPane for editor component")
            detachOverlay()
            return
        }

        if (overlay != null && attachedRoot === rootPane && lastFile == file) {
            log.info("Overlay already attached; refreshing position")
            overlay?.refreshForCurrentState()
            return
        }

        detachOverlay()

        val panel = IVEOverlayPanel(project, editor, file)
        overlay = panel
        attachedRoot = rootPane
        lastFile = file

        val layered = rootPane.layeredPane
        layered.add(panel, JLayeredPane.PALETTE_LAYER)
        layered.setLayer(panel, JLayeredPane.PALETTE_LAYER)
        layered.revalidate()
        layered.repaint()

        log.info("Overlay attached to layered pane: ${layered.javaClass.name}")
        panel.start()
    }

    private fun detachOverlay() {
        overlay?.stop()

        val rootPane = attachedRoot
        val panel = overlay
        if (rootPane != null && panel != null) {
            rootPane.layeredPane.remove(panel)
            rootPane.layeredPane.revalidate()
            rootPane.layeredPane.repaint()
            log.info("Overlay detached")
        }

        overlay = null
        attachedRoot = null
        lastFile = null
    }

    private fun isImageFile(file: VirtualFile?): Boolean {
        val ext = file?.extension?.lowercase() ?: return false
        return ext in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
    }
}

private class IVEOverlayPanel(
    private val project: Project,
    private val editor: FileEditor,
    private val file: VirtualFile
) : JPanel(null), Disposable {

    private val log = Logger.getInstance(IVEOverlayPanel::class.java)

    private var listeningComponent: JComponent? = null
    private var mouseListener: MouseMotionAdapter? = null
    private var awtListener: AWTEventListener? = null

    private var image: BufferedImage? = null
    private var lockEnabled: Boolean = false
    private var lockedPx: Int? = null
    private var lockedPy: Int? = null
    private var lockedRgb: Int? = null
    private var lastPx: Int? = null
    private var lastPy: Int? = null
    private var lastRgb: Int? = null

    private val label = JLabel("x: -, y: -").apply {
        foreground = Color.WHITE
        isOpaque = false
    }

    init {
        isOpaque = true
        background = Color(35, 35, 35, 225)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color(90, 90, 90, 220), 1, true),
            BorderFactory.createEmptyBorder(6, 10, 6, 10)
        )
        preferredSize = Dimension(360, 32)
        size = preferredSize
        cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
        add(label)
    }

    fun start() {
        log.info("Overlay start for file: ${file.path}")

        val root = editor.component as? JComponent ?: run {
            log.warn("Editor component is not a JComponent")
            setTextSafe("Image preview unavailable")
            return
        }

        image = extractBufferedImage(editor)
            ?: extractBufferedImage(root)
                    ?: decodeFile(file)

        val img = image ?: run {
            log.warn("Unable to load image")
            setTextSafe("Image preview unavailable")
            return
        }

        log.info("Image loaded: ${img.width}x${img.height}")

        val canvas = findBestImageCanvas(root, img) ?: root
        log.info("Chosen canvas: ${canvas.javaClass.name}, size=${canvas.width}x${canvas.height}")

        attachListeners(canvas)
        updatePosition(canvas)
        setTextSafe("x: -, y: -")
    }

    fun stop() {
        log.info("Overlay stop")
        detachListeners()
        image = null
        lockEnabled = false
        lockedPx = null
        lockedPy = null
        lockedRgb = null
        lastPx = null
        lastPy = null
        lastRgb = null
    }

    override fun dispose() {
        stop()
    }

    fun refreshForCurrentState() {
        val root = editor.component as? JComponent ?: return
        val img = image ?: return
        val canvas = findBestImageCanvas(root, img) ?: root
        updatePosition(canvas)
    }

    override fun doLayout() {
        label.setBounds(10, 6, width - 20, height - 12)
    }

    private fun setTextSafe(s: String) {
        if (label.text == s) return
        label.text = s
        repaint()
    }

    private fun attachListeners(canvas: JComponent) {
        detachListeners()

        val listener = object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                handleMouseMove(e, canvas)
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
                    val p = try {
                        SwingUtilities.convertPoint(src, awtEvent.point, lc)
                    } catch (_: Throwable) {
                        null
                    } ?: return@AWTEventListener
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
            AWTEvent.MOUSE_MOTION_EVENT_MASK.toLong() or AWTEvent.KEY_EVENT_MASK.toLong()
        )
        awtListener = global
        listeningComponent = canvas

        log.info("Listeners attached to ${canvas.javaClass.name}")
    }

    private fun detachListeners() {
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
    }

    private fun updatePosition(canvas: JComponent) {
        val overlayParent = parent as? JComponent ?: run {
            log.warn("Overlay has no parent yet")
            return
        }

        val canvasTopLeftInOverlayParent = try {
            SwingUtilities.convertPoint(canvas.parent, canvas.location, overlayParent)
        } catch (_: Throwable) {
            null
        } ?: run {
            log.warn("Could not convert canvas location into overlay parent coordinates")
            return
        }

        val viewport = findViewportAncestor(canvas) ?: run {
            log.warn("Could not find viewport ancestor for canvas")
            return
        }

        val viewportInOverlayParent = try {
            SwingUtilities.convertPoint(viewport.parent, viewport.location, overlayParent)
        } catch (_: Throwable) {
            null
        } ?: run {
            log.warn("Could not convert viewport location into overlay parent coordinates")
            return
        }

        val margin = 12
        val x = viewportInOverlayParent.x + margin
        val y = viewportInOverlayParent.y + viewport.height - preferredSize.height - margin

        setBounds(x, y, preferredSize.width, preferredSize.height)
        revalidate()
        repaint()

        log.info("Overlay positioned at x=$x y=$y within ${overlayParent.javaClass.name}")
    }

    private fun handleMouseMove(e: MouseEvent, base: JComponent) {
        val img = image ?: return

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

        val (px, py) = mapPointToImage(pc, bounds, img)
        val rgb = try { img.getRGB(px, py) } catch (_: Throwable) { null }

        lastPx = px
        lastPy = py
        lastRgb = rgb

        updateStatusFromValues(px, py, rgb)
    }

    private fun handleMouseMoveOnComponent(p: Point, comp: JComponent) {
        val img = image ?: return

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

        val (px, py) = mapPointToImage(pc, bounds, img)
        val rgb = try { img.getRGB(px, py) } catch (_: Throwable) { null }

        lastPx = px
        lastPy = py
        lastRgb = rgb

        updateStatusFromValues(px, py, rgb)
    }

    private fun mapPointToImage(pc: Point, bounds: Rectangle, img: BufferedImage): Pair<Int, Int> {
        val sx = bounds.width.toDouble() / img.width
        val sy = bounds.height.toDouble() / img.height
        val fx = (pc.x - bounds.x) / sx
        val fy = (pc.y - bounds.y) / sy
        val px = min(img.width - 1, max(0, fx.toInt()))
        val py = min(img.height - 1, max(0, fy.toInt()))
        return px to py
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
                val uniform = 1.0 - (abs(zx - zy) / max(1.0, max(zx, zy)))
                val leafBonus = if (c !is Container || c.componentCount == 0) 0.5 else 0.0
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

    private fun findBestImageCanvas(root: JComponent, img: BufferedImage): JComponent? {
        return findImageCanvas(root, img)
    }

    private fun findImageCanvasAround(start: JComponent, img: BufferedImage): JComponent? {
        val vp = findViewportAncestor(start)
        val view = vp?.view as? JComponent
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

    private fun findViewportAncestor(start: JComponent): JViewport? {
        var c: Component? = start
        while (c != null) {
            if (c is JViewport) return c
            c = c.parent
        }
        return null
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
            } catch (_: Throwable) {
                // ignore
            }
        }
        return null
    }

    private fun decodeFile(file: VirtualFile): BufferedImage? {
        return try {
            file.inputStream.use { ImageIO.read(it) }
        } catch (_: Throwable) {
            null
        }
    }
}