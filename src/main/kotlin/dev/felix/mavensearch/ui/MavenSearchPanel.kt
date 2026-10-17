package dev.felix.mavensearch.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.felix.mavensearch.i18n.MavenSearchBundle
import dev.felix.mavensearch.model.ArtifactCoordinates
import dev.felix.mavensearch.model.ArtifactVersion
import dev.felix.mavensearch.model.ClassMatch
import dev.felix.mavensearch.model.DependencyFormat
import dev.felix.mavensearch.search.QueryClassifier
import dev.felix.mavensearch.search.QueryIntent
import dev.felix.mavensearch.search.SearchMode
import dev.felix.mavensearch.service.MavenSearchService
import dev.felix.mavensearch.settings.MavenSearchSettings
import dev.felix.mavensearch.snippet.DependencySnippetBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class MavenSearchPanel(private val project: Project) : Disposable {

    private val service = MavenSearchService.getInstance()
    private val settings: MavenSearchSettings.State
        get() = MavenSearchSettings.getInstance().state

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

    private val queryField = JBTextField()
    private val sourceCombo = ComboBox<SourceItem>()
    private val modeCombo = ComboBox<SearchMode>()
    private val intentLabel = JBLabel()

    private val artifactModel = CollectionListModel<ArtifactRow>()
    private val artifactList = JBList(artifactModel)
    private val versionModel = CollectionListModel<ArtifactVersion>()
    private val versionList = JBList(versionModel)
    private val versionHeader = JBLabel()

    private val statusLabel = JBLabel()
    private val formatCombo = ComboBox<DependencyFormat>()
    private val showRelocatedCheck = JCheckBox()
    private val copyButton = JButton()

    private val splitter = OnePixelSplitter(true, "MavenSearch.splitter", 0.55f)

    private var searchJob: Job? = null
    private var versionJob: Job? = null

    /**
     * 世代号是防串台的最后一道保险：Job.cancel() 打断不了已经发出的阻塞式 socket 读，
     * 只有回到 EDT 后比对世代号，才能保证旧请求不会覆盖新结果。
     */
    private var searchGeneration = 0L
    private var versionGeneration = 0L

    private var lastRows: List<ArtifactRow> = emptyList()
    private var persistentStatus: String = ""
    private var initializing = true

    private val statusResetTimer = Timer(2000) { restoreStatus() }.apply { isRepeats = false }

    val component: JComponent

    init {
        configureWidgets()
        component = buildComponent()
        loadSources()
        installListeners()
        installKeyBindings()
        initializing = false
        updateIntentHint()
    }

    fun requestFocus() {
        queryField.requestFocusInWindow()
    }

    // ---------------------------------------------------------------- 搜索

    private fun scheduleSearch(immediate: Boolean) {
        val text = queryField.text.trim()
        val generation = ++searchGeneration
        searchJob?.cancel()

        if (text.isEmpty()) {
            clearResults()
            updateIntentHint(null)
            return
        }

        val mode = modeCombo.selectedItem as? SearchMode ?: SearchMode.AUTO
        val intent = resolveIntent(text, mode)
        updateIntentHint(intent)

        if (mode == SearchMode.CLASS && !text.contains('.')) {
            clearResults()
            persistentStatus = MavenSearchBundle.message("status.class.needs.fqcn")
            restoreStatus()
            return
        }

        val delayMs = if (immediate) 0L else settings.debounceMs.coerceAtLeast(0).toLong()

        searchJob = uiScope.launch {
            delay(delayMs)
            setSearching(true)
            try {
                val startedAt = System.nanoTime()
                val outcome = runQuery(text, intent)
                if (generation != searchGeneration) return@launch
                setSearching(false)
                applyOutcome(outcome, (System.nanoTime() - startedAt) / 1_000_000)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != searchGeneration) return@launch
                setSearching(false)
                showError(e)
            }
        }
    }

    private fun resolveIntent(text: String, mode: SearchMode): QueryIntent = when (mode) {
        SearchMode.KEYWORD -> QueryIntent.Keyword(text)
        SearchMode.CLASS -> QueryIntent.ClassName(text)
        SearchMode.AUTO -> QueryClassifier.classify(text)
    }

    private suspend fun runQuery(text: String, intent: QueryIntent): QueryOutcome = when (intent) {
        is QueryIntent.ClassName -> {
            val outcome = service.searchByClassName(intent.fqcn, settings.pageSize)
            val exact = outcome.matches.filter { it.confidence == ClassMatch.Confidence.EXACT }
            val relocated = outcome.matches.filter { it.confidence == ClassMatch.Confidence.RELOCATED }
            QueryOutcome(
                rows = exact.map { it.toRow() } + relocated.map { it.toRow() },
                exactCount = exact.size,
                relocatedCount = relocated.size,
                kind = OutcomeKind.CLASS,
                degraded = outcome.degraded,
                fqcn = intent.fqcn,
            )
        }

        else -> {
            val results = service.searchArtifacts(text, settings.pageSize)
            QueryOutcome(
                // 不展示搜索索引里的 latestVersion：它可能滞后数月（实测 guava 差 2 个版本），
                // 真正的版本信息以选中后加载的 maven-metadata.xml 为准。
                rows = results.map { result ->
                    val count = result.versionCount ?: 0
                    ArtifactRow(
                        coordinates = result.coordinates,
                        hint = if (count > 0) {
                            MavenSearchBundle.message("artifact.versionCount", count)
                        } else {
                            null
                        },
                    )
                },
                exactCount = results.size,
                relocatedCount = 0,
                kind = OutcomeKind.KEYWORD,
            )
        }
    }

    private fun ClassMatch.toRow(): ArtifactRow = ArtifactRow(
        coordinates = coordinates,
        matchedClassName = matchedClassNames.firstOrNull(),
        relocated = confidence == ClassMatch.Confidence.RELOCATED,
        verifyVersion = matchedVersion,
    )

    private fun applyOutcome(outcome: QueryOutcome, elapsedMs: Long) {
        lastRows = outcome.rows

        showRelocatedCheck.isVisible =
            outcome.kind == OutcomeKind.CLASS && outcome.relocatedCount > 0

        renderRows()
        persistentStatus = buildStatus(outcome, elapsedMs)
        restoreStatus()

        // 降级路径本身已经逐条校验过 jar，不需要再验一遍
        if (outcome.kind == OutcomeKind.CLASS && !outcome.degraded) {
            outcome.fqcn?.let { launchVerification(it, outcome.rows) }
        }
    }

    /**
     * 结果先展示，再在后台用 repo1 的 jar 中央目录逐个确认。
     * 这是独立于搜索索引的第二来源：索引陈旧或返回错误时也能被兜住。
     */
    private fun launchVerification(fqcn: String, rows: List<ArtifactRow>) {
        val generation = searchGeneration
        rows.asSequence()
            .filter { !it.relocated && it.matchedClassName != null }
            .take(MAX_AUTO_VERIFY)
            .forEach { row ->
                uiScope.launch {
                    val verified = try {
                        service.verifyClass(row.coordinates, row.verifyVersion, fqcn)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return@launch
                    }
                    if (generation != searchGeneration) return@launch
                    markVerified(row.coordinates, verified)
                }
            }
    }

    private fun markVerified(coordinates: ArtifactCoordinates, verified: Boolean) {
        val index = lastRows.indexOfFirst { it.coordinates == coordinates }
        if (index < 0) return

        val updated = lastRows[index].copy(verified = verified)
        lastRows = lastRows.toMutableList().also { it[index] = updated }

        // 只替换模型元素，不重建模型：避免触发选中事件导致版本列表重复加载
        val visibleIndex = (0 until artifactModel.size).firstOrNull {
            artifactModel.getElementAt(it).coordinates == coordinates
        } ?: return
        artifactModel.setElementAt(updated, visibleIndex)
    }

    private fun renderRows() {
        val selected = artifactList.selectedValue?.coordinates
        val visible = lastRows.filter { showRelocatedCheck.isSelected || !it.relocated }
        artifactModel.replaceAll(visible)

        val index = visible.indexOfFirst { it.coordinates == selected }
        artifactList.selectedIndex = when {
            index >= 0 -> index
            visible.isNotEmpty() -> 0
            else -> -1
        }
    }

    private fun buildStatus(outcome: QueryOutcome, elapsedMs: Long): String = when (outcome.kind) {
        OutcomeKind.KEYWORD ->
            if (outcome.rows.isEmpty()) {
                MavenSearchBundle.message("status.no.results")
            } else {
                MavenSearchBundle.message("status.found", outcome.rows.size, elapsedMs)
            }

        OutcomeKind.CLASS -> when {
            outcome.degraded && outcome.exactCount == 0 ->
                MavenSearchBundle.message("status.class.degraded.none")

            outcome.degraded ->
                MavenSearchBundle.message(
                    "status.class.degraded.found",
                    outcome.exactCount,
                    elapsedMs,
                )

            outcome.exactCount > 0 -> buildString {
                append(MavenSearchBundle.message("status.class.found", outcome.exactCount, elapsedMs))
                if (outcome.relocatedCount > 0) {
                    append("  ·  ")
                    append(MavenSearchBundle.message("status.class.hidden", outcome.relocatedCount))
                }
            }

            outcome.relocatedCount > 0 ->
                MavenSearchBundle.message("status.class.no.exact", outcome.relocatedCount)

            else -> MavenSearchBundle.message("status.class.no.hits")
        }
    }

    private fun setSearching(busy: Boolean) {
        if (busy) {
            persistentStatus = MavenSearchBundle.message("status.searching")
            restoreStatus()
        }
        copyButton.isEnabled = !busy && canCopy()
    }

    // ---------------------------------------------------------------- 版本

    private fun loadVersions(coordinates: ArtifactCoordinates) {
        val generation = ++versionGeneration
        versionJob?.cancel()
        versionModel.removeAll()
        versionHeader.text = MavenSearchBundle.message("version.loading", coordinates.id)
        updateCopyButton()

        versionJob = uiScope.launch {
            val list = try {
                service.listVersions(coordinates)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == versionGeneration) {
                    versionHeader.text =
                        MavenSearchBundle.message("version.failed", e.message ?: "")
                }
                return@launch
            }

            if (generation != versionGeneration) return@launch

            versionModel.replaceAll(list.versions)
            versionHeader.text =
                MavenSearchBundle.message("version.header", coordinates.id, list.versions.size)
            if (list.versions.isNotEmpty()) versionList.selectedIndex = 0
            updateCopyButton()
        }
    }

    private fun clearVersions() {
        ++versionGeneration
        versionJob?.cancel()
        versionModel.removeAll()
        versionHeader.text = MavenSearchBundle.message("version.header.empty")
        updateCopyButton()
    }

    private fun clearResults() {
        lastRows = emptyList()
        showRelocatedCheck.isVisible = false
        artifactModel.removeAll()
        clearVersions()
        persistentStatus = ""
        restoreStatus()
    }

    // ---------------------------------------------------------------- 复制

    private fun copySelected() {
        val row = artifactList.selectedValue ?: return
        val version = versionList.selectedValue ?: return
        val format = formatCombo.selectedItem as? DependencyFormat ?: DependencyFormat.MAVEN_XML

        val snippet = try {
            DependencySnippetBuilder.build(row.coordinates, version.version, format)
        } catch (e: IllegalArgumentException) {
            showError(e)
            return
        }

        CopyPasteManager.getInstance().setContents(StringSelection(snippet))
        settings.defaultFormat = format.name
        flashStatus(
            MavenSearchBundle.message(
                "status.copied",
                "${row.coordinates.id}:${version.version}",
            ),
        )
    }

    private fun canCopy(): Boolean =
        artifactList.selectedValue != null && versionList.selectedValue != null

    private fun updateCopyButton() {
        copyButton.isEnabled = canCopy()
    }

    private fun flashStatus(message: String) {
        statusLabel.text = message
        statusLabel.foreground = JBColor.GREEN
        statusResetTimer.restart()
    }

    private fun restoreStatus() {
        statusLabel.text = persistentStatus
        statusLabel.foreground = JBColor.GRAY
    }

    private fun showError(error: Exception) {
        val message = error.message ?: error.javaClass.simpleName
        persistentStatus = MavenSearchBundle.message("status.error", message)
        restoreStatus()
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Maven Search")
            .createNotification(
                MavenSearchBundle.message("notification.title"),
                message,
                NotificationType.WARNING,
            )
            .notify(project)
    }

    // ---------------------------------------------------------------- 初始化

    private fun configureWidgets() {
        queryField.emptyText.text = MavenSearchBundle.message("search.placeholder")

        sourceCombo.renderer = listCellRenderer<SourceItem> { it?.displayName ?: "" }
        modeCombo.renderer = listCellRenderer<SearchMode> { it?.let { mode -> modeLabel(mode) }.orEmpty() }
        formatCombo.renderer =
            listCellRenderer<DependencyFormat> { it?.let { format -> formatLabel(format) }.orEmpty() }

        SearchMode.entries.forEach { modeCombo.addItem(it) }
        modeCombo.selectedItem = SearchMode.AUTO

        DependencyFormat.entries.forEach { formatCombo.addItem(it) }
        formatCombo.selectedItem = runCatching {
            DependencyFormat.valueOf(settings.defaultFormat)
        }.getOrDefault(DependencyFormat.MAVEN_XML)

        artifactList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        artifactList.cellRenderer = ArtifactCellRenderer()
        artifactList.emptyText.text = MavenSearchBundle.message("artifact.empty")

        versionList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        versionList.cellRenderer = VersionCellRenderer()
        versionList.emptyText.text = MavenSearchBundle.message("version.empty")

        versionHeader.text = MavenSearchBundle.message("version.header.empty")
        statusLabel.foreground = JBColor.GRAY
        intentLabel.foreground = JBColor.GRAY

        showRelocatedCheck.text = MavenSearchBundle.message("checkbox.show.relocated")
        showRelocatedCheck.isSelected = settings.showRelocatedClassMatches
        showRelocatedCheck.isVisible = false

        copyButton.text = MavenSearchBundle.message("button.copy")
        copyButton.isEnabled = false
    }

    private fun loadSources() {
        val sources = service.availableSources
        sources.forEach { sourceCombo.addItem(SourceItem(it)) }
        if (sources.isEmpty()) return

        val index = sources.indexOfFirst { it.id == settings.sourceId }.coerceAtLeast(0)
        sourceCombo.selectedIndex = index
        settings.sourceId = sources[index].id
    }

    private fun installListeners() {
        queryField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = scheduleSearch(immediate = false)
            override fun removeUpdate(e: DocumentEvent) = scheduleSearch(immediate = false)
            override fun changedUpdate(e: DocumentEvent) = scheduleSearch(immediate = false)
        })

        sourceCombo.addActionListener {
            if (initializing) return@addActionListener
            val item = sourceCombo.selectedItem as? SourceItem ?: return@addActionListener
            settings.sourceId = item.source.id
            scheduleSearch(immediate = true)
        }

        modeCombo.addActionListener {
            if (initializing) return@addActionListener
            updateIntentHint()
            scheduleSearch(immediate = true)
        }

        artifactList.addListSelectionListener { event ->
            if (event.valueIsAdjusting) return@addListSelectionListener
            updateCopyButton()
            val row = artifactList.selectedValue
            if (row != null) loadVersions(row.coordinates) else clearVersions()
        }

        versionList.addListSelectionListener { updateCopyButton() }
        copyButton.addActionListener { copySelected() }

        showRelocatedCheck.addActionListener {
            if (initializing) return@addActionListener
            settings.showRelocatedClassMatches = showRelocatedCheck.isSelected
            renderRows()
        }
    }

    private fun installKeyBindings() {
        // 搜索框里按 Enter 跳过防抖，立即搜索
        queryField.addActionListener { scheduleSearch(immediate = true) }
        queryField.registerKeyboardAction(
            { queryField.text = "" },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_FOCUSED,
        )

        val copyAction = object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) = copySelected()
        }
        versionList.actionMap.put("mavenSearch.copy", copyAction)
        versionList.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            "mavenSearch.copy",
        )
        versionList.inputMap.put(
            KeyStroke.getKeyStroke(
                KeyEvent.VK_C,
                Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx,
            ),
            "mavenSearch.copy",
        )

        versionList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) copySelected()
            }
        })
    }

    private fun updateIntentHint(intent: QueryIntent? = null) {
        val text = queryField.text.trim()
        val mode = modeCombo.selectedItem as? SearchMode ?: SearchMode.AUTO
        val effective = intent ?: if (text.isEmpty()) null else resolveIntent(text, mode)

        intentLabel.text = when {
            text.isEmpty() -> ""
            mode == SearchMode.CLASS && !text.contains('.') ->
                MavenSearchBundle.message("intent.class.needs.fqcn")

            effective is QueryIntent.ClassName ->
                MavenSearchBundle.message("intent.class", effective.fqcn)

            else -> ""
        }
    }

    private fun buildComponent(): JComponent {
        val searchRow = JPanel(BorderLayout(6, 0)).apply {
            add(queryField, BorderLayout.CENTER)
            add(sourceCombo, BorderLayout.EAST)
        }

        val modeRow = JPanel(BorderLayout(6, 0)).apply {
            add(modeCombo, BorderLayout.WEST)
            add(intentLabel, BorderLayout.CENTER)
        }

        val north = JPanel(BorderLayout(0, 4)).apply {
            border = JBUI.Borders.empty(0, 0, 6, 0)
            add(searchRow, BorderLayout.NORTH)
            add(modeRow, BorderLayout.CENTER)
        }

        val lower = JPanel(BorderLayout()).apply {
            add(versionHeader, BorderLayout.NORTH)
            add(JBScrollPane(versionList), BorderLayout.CENTER)
        }

        splitter.firstComponent = JBScrollPane(artifactList)
        splitter.secondComponent = lower

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            add(showRelocatedCheck)
            add(formatCombo)
            add(copyButton)
        }

        val south = JPanel(BorderLayout(6, 0)).apply {
            border = JBUI.Borders.empty(6, 0, 0, 0)
            add(statusLabel, BorderLayout.CENTER)
            add(actions, BorderLayout.EAST)
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6)
            add(north, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
            add(south, BorderLayout.SOUTH)
        }
    }

    private fun modeLabel(mode: SearchMode): String = when (mode) {
        SearchMode.AUTO -> MavenSearchBundle.message("mode.auto")
        SearchMode.KEYWORD -> MavenSearchBundle.message("mode.keyword")
        SearchMode.CLASS -> MavenSearchBundle.message("mode.class")
    }

    private fun formatLabel(format: DependencyFormat): String = when (format) {
        DependencyFormat.MAVEN_XML -> MavenSearchBundle.message("format.maven")
        DependencyFormat.GRADLE_GROOVY -> MavenSearchBundle.message("format.groovy")
        DependencyFormat.GRADLE_KOTLIN -> MavenSearchBundle.message("format.kotlin")
        DependencyFormat.PLAIN -> MavenSearchBundle.message("format.plain")
    }

    override fun dispose() {
        statusResetTimer.stop()
        uiScope.cancel()
    }

    private enum class OutcomeKind { KEYWORD, CLASS }

    private data class QueryOutcome(
        val rows: List<ArtifactRow>,
        val exactCount: Int,
        val relocatedCount: Int,
        val kind: OutcomeKind,
        val degraded: Boolean = false,
        val fqcn: String? = null,
    )

    private companion object {
        const val MAX_AUTO_VERIFY = 5
    }
}
