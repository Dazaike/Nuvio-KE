package com.nuvio.tv.ui.screens.plugin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.plugin.ScraperTestReport
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.marquee.LocalMarqueeToast
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueeNameField
import com.nuvio.tv.ui.components.marquee.MarqueePanelActions
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanelDialog
import com.nuvio.tv.ui.components.marquee.MarqueeConfirmPanel
import com.nuvio.tv.ui.screens.addon.StripListRow
import com.nuvio.tv.ui.screens.addon.addonInitials
import com.nuvio.tv.ui.screens.collection.CollectionBadge
import com.nuvio.tv.ui.screens.collection.CollectionFieldPill
import com.nuvio.tv.ui.screens.collection.CollectionRowAction
import com.nuvio.tv.ui.screens.collection.ServePendingFocus
import com.nuvio.tv.ui.screens.collection.rememberCollectionFocusTargets
import com.nuvio.tv.ui.screens.home.MarqueeBreadcrumb
import com.nuvio.tv.ui.screens.settings.SettingsNote
import com.nuvio.tv.ui.screens.settings.SettingsResultLine
import com.nuvio.tv.ui.screens.settings.SettingsResultsPanel
import com.nuvio.tv.ui.screens.settings.SettingsSwitchRow
import com.nuvio.tv.ui.screens.settings.SettingsValueRow
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/** Focus target of the add-repository field (not a repository id). */
private const val FIELD_TARGET = "#field"
private const val RESET_TARGET = "#reset"

/** Streams, steps and script lines one test panel lists before "and N more". */
private const val TEST_STREAM_LINES = 5
private const val TEST_SCRIPT_LINES = 8

/**
 * Settings > Content & Discovery > Plugins. Same shape as the Addon Manager (breadcrumb, the add
 * field, one strip row per item whose actions open on OK): global switches, the add-repository
 * field, then each repository followed by its providers. A provider tests in place: its result
 * panel opens under its row. Everything is reachable with the D-pad; the on-screen keyboard opens
 * from the field. A profile that shares the primary profile's plugins sees rows but no actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PluginsScreen(
    viewModel: PluginsViewModel = hiltViewModel(),
    onBackPress: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = LocalMarqueeToast.current
    val marquee = NuvioTheme.marquee
    val tokens = marquee.collections
    val safeHorizontal = NuvioTheme.layout.tvSafeHorizontal
    val focusTargets = rememberCollectionFocusTargets()
    val addButton = remember { FocusRequester() }
    var pendingRemoval by remember { mutableStateOf<PluginRepository?>(null) }
    var confirmingReset by remember { mutableStateOf(false) }

    BackHandler { onBackPress() }

    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(state.isLoading) {
        if (!entered && !state.isLoading) {
            entered = true
            focusTargets.pending = state.repositories.firstOrNull()?.id ?: FIELD_TARGET
        }
    }
    focusTargets.ServePendingFocus(content = state.repositories, fallback = FIELD_TARGET)

    LaunchedEffect(state.transientMessage) {
        val message = state.transientMessage ?: return@LaunchedEffect
        toast?.show(message)
        viewModel.clearTransientMessage()
    }

    Box(modifier = Modifier.fillMaxSize().background(NuvioPrimitives.black)) {
        Box(modifier = Modifier.fillMaxSize().background(tokens.veil))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = tokens.listTop)
                .verticalScroll(rememberScrollState())
                .padding(start = safeHorizontal, end = safeHorizontal, bottom = NuvioTheme.layout.tvSafeVertical),
            verticalArrangement = Arrangement.spacedBy(tokens.rowGap)
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                Text(
                    text = stringResource(R.string.plugins_title),
                    style = NuvioTheme.textStyles.settingsTitle,
                    color = NuvioPrimitives.white
                )
                if (!state.isLoading && state.repositories.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.plugins_repo_facts, state.scrapers.size, state.enabledScraperCount),
                        style = NuvioTheme.textStyles.meta,
                        color = marquee.onImage3,
                        modifier = Modifier.padding(bottom = NuvioTheme.spacing.xxs)
                    )
                }
            }

            if (state.readOnly) {
                SettingsNote(text = stringResource(R.string.plugins_readonly_notice), color = marquee.onImage2)
            } else {
                SettingsNote(text = stringResource(R.string.plugins_notice_third_party), color = marquee.onImage2)
            }
            if (state.tmdbKeyMissing) {
                SettingsNote(text = stringResource(R.string.plugins_notice_no_tmdb), color = NuvioPrimitives.warning)
            }

            SettingsSwitchRow(
                title = stringResource(R.string.plugins_global_title),
                subtitle = stringResource(R.string.plugins_global_desc),
                checked = state.pluginsEnabled,
                onCheckedChange = viewModel::setPluginsEnabled,
                enabled = !state.readOnly
            )
            SettingsSwitchRow(
                title = stringResource(R.string.plugins_group_title),
                subtitle = stringResource(R.string.plugins_group_desc),
                checked = state.groupStreamsByRepository,
                onCheckedChange = viewModel::setGroupStreamsByRepository,
                enabled = !state.readOnly && state.pluginsEnabled
            )

            if (!state.readOnly) {
                // The URL reads left to right in RTL locales too.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    CollectionFieldPill(
                        icon = Icons.Default.Add,
                        value = state.installUrl,
                        onValueChange = viewModel::onInstallUrlChange,
                        placeholder = stringResource(R.string.plugins_install_placeholder),
                        actionLabel = stringResource(
                            if (state.isInstalling) R.string.plugins_installing else R.string.plugins_install_btn
                        ),
                        onAction = viewModel::installRepository,
                        actionFocus = addButton,
                        fieldFocus = focusTargets.requester(FIELD_TARGET),
                        keyboardType = KeyboardType.Uri
                    )
                }
            }

            Text(
                text = stringResource(R.string.plugins_section_repositories).uppercase(),
                style = NuvioTheme.textStyles.eyebrow,
                color = marquee.onImage3,
                modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
            )
            if (state.repositories.isEmpty() && !state.isLoading) {
                Text(
                    text = stringResource(R.string.plugins_empty),
                    style = NuvioTheme.textStyles.settingsNote,
                    color = marquee.onImage2
                )
            }

            var position = 0
            state.repositories.forEach { repository ->
                val scrapers = state.scrapersOf(repository.id)
                key(repository.id) {
                    RepositoryRow(
                        repository = repository,
                        scrapers = scrapers,
                        position = position++,
                        busy = repository.id in state.busyRepositoryIds,
                        readOnly = state.readOnly,
                        focusRequester = focusTargets.requester(repository.id),
                        onRefresh = { viewModel.refreshRepository(repository.id) },
                        onEnableAll = { viewModel.setAllScrapersEnabled(repository, true) },
                        onDisableAll = { viewModel.setAllScrapersEnabled(repository, false) },
                        onToggleEnabled = { viewModel.setRepositoryEnabled(repository.id, !repository.enabled) },
                        onRemove = { pendingRemoval = repository }
                    )
                }
                if (scrapers.isEmpty()) {
                    Text(
                        text = stringResource(R.string.plugins_scrapers_empty),
                        style = NuvioTheme.textStyles.settingsNote,
                        color = marquee.onImage3,
                        modifier = Modifier.padding(start = SCRAPER_INDENT)
                    )
                }
                scrapers.forEach { scraper ->
                    key(scraper.id) {
                        ScraperRow(
                            scraper = scraper,
                            repositoryEnabled = repository.enabled,
                            position = position++,
                            readOnly = state.readOnly,
                            testing = state.testingScraperId == scraper.id,
                            hasReport = state.testReport?.scraper?.id == scraper.id,
                            focusRequester = focusTargets.requester(scraper.id),
                            onToggle = { viewModel.setScraperEnabled(scraper, !scraper.enabled) },
                            onSettings = { viewModel.openSettings(scraper) },
                            onTest = { viewModel.testScraper(scraper) },
                            onHideResults = viewModel::clearTestReport
                        )
                        val report = state.testReport?.takeIf { it.scraper?.id == scraper.id }
                        if (report != null) {
                            TestReportPanel(report)
                        }
                    }
                }
            }

            if (!state.readOnly && state.repositories.isNotEmpty()) {
                SettingsValueRow(
                    title = stringResource(R.string.plugins_reset_title),
                    subtitle = stringResource(R.string.plugins_reset_desc),
                    value = null,
                    onClick = { confirmingReset = true },
                    destructive = true,
                    trailingIcon = null,
                    modifier = Modifier
                        .padding(top = NuvioTheme.spacing.md)
                        .focusRequester(focusTargets.requester(RESET_TARGET))
                )
            }
        }

        MarqueeBreadcrumb(listOf(stringResource(R.string.nav_settings), stringResource(R.string.plugins_title)))
    }

    pendingRemoval?.let { repository ->
        MarqueeConfirmPanel(
            eyebrow = stringResource(R.string.plugins_remove_eyebrow, repository.name),
            body = stringResource(R.string.plugins_remove_body),
            confirmLabel = stringResource(R.string.plugins_remove_confirm),
            cancelLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                val ids = state.repositories.map { it.id }
                val index = ids.indexOf(repository.id)
                focusTargets.pending = ids.getOrNull(index + 1) ?: ids.getOrNull(index - 1) ?: FIELD_TARGET
                viewModel.removeRepository(repository.id)
                pendingRemoval = null
            },
            onDismiss = {
                focusTargets.pending = repository.id
                pendingRemoval = null
            },
            destructive = true
        )
    }

    if (confirmingReset) {
        MarqueeConfirmPanel(
            eyebrow = stringResource(R.string.plugins_reset_eyebrow),
            body = stringResource(R.string.plugins_reset_body),
            confirmLabel = stringResource(R.string.plugins_reset_confirm),
            cancelLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                focusTargets.pending = FIELD_TARGET
                viewModel.resetAll()
                confirmingReset = false
            },
            onDismiss = {
                focusTargets.pending = RESET_TARGET
                confirmingReset = false
            },
            destructive = true
        )
    }

    state.settingsEditor?.let { editor ->
        ScraperSettingsPanel(
            editor = editor,
            onSave = { values ->
                focusTargets.pending = editor.scraper.id
                viewModel.saveSettings(values)
            },
            onDismiss = {
                focusTargets.pending = editor.scraper.id
                viewModel.dismissSettings()
            }
        )
    }

    state.pendingConsent?.let { consent ->
        val name = when (consent) {
            is PluginConsent.EnableScraper -> consent.scraperName
            is PluginConsent.EnableAll -> consent.repositoryName
            is PluginConsent.Test -> consent.scraperName
        }
        val returnTo = when (consent) {
            is PluginConsent.EnableScraper -> consent.scraperId
            is PluginConsent.EnableAll -> consent.repositoryId
            is PluginConsent.Test -> consent.scraperId
        }
        MarqueeConfirmPanel(
            eyebrow = stringResource(R.string.plugins_consent_eyebrow),
            body = stringResource(R.string.plugins_consent_body, name),
            confirmLabel = stringResource(R.string.plugins_consent_confirm),
            cancelLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                focusTargets.pending = returnTo
                viewModel.confirmConsent()
            },
            onDismiss = {
                focusTargets.pending = returnTo
                viewModel.dismissConsent()
            }
        )
    }
}

private val SCRAPER_INDENT = 28.dp

/** Longest value one field takes; matches what the manager stores. */
private const val SETTING_VALUE_MAX = 4096

/**
 * The values a scraper's manifest asks for (a cookie, a key), one field each, in a side panel. OK on a
 * field opens the keyboard; Done moves to the next field and then to Save. Nothing is written until Save.
 */
@Composable
private fun ScraperSettingsPanel(
    editor: ScraperSettingsEditor,
    onSave: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    val settings = NuvioTheme.marquee.settings
    val stream = NuvioTheme.marquee.stream
    val fields = editor.scraper.settings
    val drafts = remember { mutableStateMapOf<String, String>().apply { putAll(editor.values) } }
    val fieldFocus = remember(fields) { fields.map { FocusRequester() } }
    val saveFocus = remember { FocusRequester() }
    MarqueeSidePanelDialog(
        onDismissRequest = onDismiss,
        width = settings.pickerWidth,
        insetVertical = settings.pickerInsetVertical,
        initialFocus = fieldFocus.first()
    ) {
        PanelEyebrow(text = stringResource(R.string.plugins_settings_eyebrow, editor.scraper.name))
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.marquee.collections.panelGap)) {
            fields.forEachIndexed { index, field ->
                Text(
                    text = field.label,
                    style = NuvioTheme.textStyles.settingsResultLabel,
                    color = NuvioPrimitives.white,
                    modifier = Modifier.padding(horizontal = stream.dialogTextInset)
                )
                field.description?.let {
                    Text(
                        text = it,
                        style = NuvioTheme.textStyles.settingsNote,
                        color = NuvioTheme.marquee.onImage2,
                        modifier = Modifier.padding(horizontal = stream.dialogTextInset)
                    )
                }
                // A value reads left to right in RTL locales too.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    MarqueeNameField(
                        value = drafts[field.key].orEmpty(),
                        onValueChange = { drafts[field.key] = it },
                        maxLength = SETTING_VALUE_MAX,
                        placeholder = field.placeholder ?: field.label,
                        focusRequester = fieldFocus[index],
                        onDone = { (fieldFocus.getOrNull(index + 1) ?: saveFocus).requestFocus() },
                        keyboardType = KeyboardType.Ascii,
                        showCounter = false,
                        keyboardOnOk = true
                    )
                }
            }
            Text(
                text = stringResource(R.string.plugins_settings_note),
                style = NuvioTheme.textStyles.settingsNote,
                color = NuvioTheme.marquee.onImage2,
                modifier = Modifier.padding(horizontal = stream.dialogTextInset)
            )
        }
        MarqueePanelActions {
            MarqueeGlassTextButton(label = stringResource(R.string.action_cancel), onClick = onDismiss)
            MarqueeGlassTextButton(
                label = stringResource(R.string.action_save),
                onClick = { onSave(drafts.toMap()) },
                modifier = Modifier.focusRequester(saveFocus)
            )
        }
    }
}

@Composable
private fun RepositoryRow(
    repository: PluginRepository,
    scrapers: List<ScraperInfo>,
    position: Int,
    busy: Boolean,
    readOnly: Boolean,
    focusRequester: FocusRequester,
    onRefresh: () -> Unit,
    onEnableAll: () -> Unit,
    onDisableAll: () -> Unit,
    onToggleEnabled: () -> Unit,
    onRemove: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    StripListRow(
        focusRequester = focusRequester,
        position = position,
        dimmedAtRest = !repository.enabled,
        actions = if (readOnly) {
            null
        } else {
            {
                CollectionRowAction(
                    label = stringResource(if (busy) R.string.plugins_refreshing else R.string.plugins_action_refresh),
                    onClick = { if (!busy) onRefresh() },
                    dimmed = busy
                )
                CollectionRowAction(label = stringResource(R.string.plugins_action_enable_all), onClick = onEnableAll)
                CollectionRowAction(label = stringResource(R.string.plugins_action_disable_all), onClick = onDisableAll)
                CollectionRowAction(
                    label = stringResource(
                        if (repository.enabled) R.string.plugins_action_disable_repository else R.string.plugins_action_enable_repository
                    ),
                    onClick = onToggleEnabled
                )
                CollectionRowAction(
                    label = stringResource(R.string.plugins_action_remove),
                    onClick = onRemove,
                    destructive = true
                )
            }
        }
    ) { focusWithin ->
        PluginLogo(logo = null, name = repository.name)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                Text(
                    text = repository.name,
                    style = NuvioTheme.textStyles.button,
                    color = NuvioPrimitives.white,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                repository.version?.let {
                    Text(
                        text = stringResource(R.string.plugins_repo_version, it),
                        style = NuvioTheme.textStyles.caption,
                        color = marquee.onImage3,
                        maxLines = 1
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                Text(
                    text = stringResource(R.string.plugins_repo_facts, scrapers.size, scrapers.count { it.enabled }),
                    style = NuvioTheme.textStyles.caption,
                    color = marquee.onImage2,
                    maxLines = 1
                )
                if (!repository.enabled) {
                    CollectionBadge(text = stringResource(R.string.plugins_badge_off), color = marquee.onImage2)
                }
            }
            val description = repository.description?.takeIf { it.isNotBlank() }
            if (focusWithin && description != null) {
                Text(
                    text = description,
                    style = NuvioTheme.textStyles.caption,
                    color = marquee.onImage2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ScraperRow(
    scraper: ScraperInfo,
    repositoryEnabled: Boolean,
    position: Int,
    readOnly: Boolean,
    testing: Boolean,
    hasReport: Boolean,
    focusRequester: FocusRequester,
    onToggle: () -> Unit,
    onSettings: () -> Unit,
    onTest: () -> Unit,
    onHideResults: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    Box(modifier = Modifier.padding(start = SCRAPER_INDENT)) {
        StripListRow(
            focusRequester = focusRequester,
            position = position,
            dimmedAtRest = !scraper.enabled || !repositoryEnabled,
            actions = if (readOnly) {
                null
            } else {
                {
                    ScraperActions(scraper, testing, hasReport, onToggle, onSettings, onTest, onHideResults)
                }
            }
        ) { focusWithin ->
            PluginLogo(logo = scraper.logo, name = scraper.name)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                    Text(
                        text = scraper.name,
                        style = NuvioTheme.textStyles.button,
                        color = NuvioPrimitives.white,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = stringResource(R.string.plugins_repo_version, scraper.version),
                        style = NuvioTheme.textStyles.caption,
                        color = marquee.onImage3,
                        maxLines = 1
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                    Text(
                        text = scraperFactsLine(scraper),
                        style = NuvioTheme.textStyles.caption,
                        color = marquee.onImage2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                val description = scraper.description.takeIf { it.isNotBlank() }
                if (focusWithin && description != null) {
                    Text(
                        text = description,
                        style = NuvioTheme.textStyles.caption,
                        color = marquee.onImage2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            when {
                testing -> CollectionBadge(text = stringResource(R.string.plugins_testing), color = NuvioPrimitives.white)
                !scraper.manifestEnabled -> CollectionBadge(
                    text = stringResource(R.string.plugins_badge_unavailable),
                    color = marquee.onImage3
                )
                scraper.enabled && !repositoryEnabled -> CollectionBadge(
                    text = stringResource(R.string.plugins_badge_repository_off),
                    color = marquee.onImage2
                )
                scraper.enabled -> CollectionBadge(text = stringResource(R.string.plugins_badge_on), color = marquee.readyText)
                else -> CollectionBadge(text = stringResource(R.string.plugins_badge_off), color = marquee.onImage3)
            }
        }
    }
}

@Composable
private fun RowScope.ScraperActions(
    scraper: ScraperInfo,
    testing: Boolean,
    hasReport: Boolean,
    onToggle: () -> Unit,
    onSettings: () -> Unit,
    onTest: () -> Unit,
    onHideResults: () -> Unit
) {
    CollectionRowAction(
        label = stringResource(if (scraper.enabled) R.string.plugins_action_disable else R.string.plugins_action_enable),
        onClick = onToggle,
        dimmed = !scraper.enabled && !scraper.manifestEnabled
    )
    if (scraper.settings.isNotEmpty()) {
        CollectionRowAction(label = stringResource(R.string.plugins_action_settings), onClick = onSettings)
    }
    CollectionRowAction(
        label = stringResource(if (testing) R.string.plugins_testing else R.string.plugins_action_test),
        onClick = { if (!testing) onTest() },
        dimmed = testing
    )
    if (hasReport) {
        CollectionRowAction(label = stringResource(R.string.plugins_action_hide_results), onClick = onHideResults)
    }
}

/** "Movies, Series · 1080p, 4K · EN": what a provider serves, as its manifest declares it. */
@Composable
private fun scraperFactsLine(scraper: ScraperInfo): String {
    val types = scraper.supportedTypes
        .map { type ->
            when (type.lowercase()) {
                "movie" -> stringResource(R.string.type_movies)
                "tv", "series", "show", "other" -> stringResource(R.string.type_series)
                else -> type
            }
        }
        .distinct()
        .joinToString(", ")
    val formats = scraper.formats.orEmpty().filter { it.isNotBlank() }.take(4).joinToString(", ")
    val languages = scraper.contentLanguage.filter { it.isNotBlank() }.take(4).joinToString(", ") { it.uppercase() }
    return listOf(types, formats, languages).filter { it.isNotEmpty() }.joinToString(" \u00B7 ")
}

/** A provider's test result, in place under its row: outcome, the streams found, then the diagnostics. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TestReportPanel(report: ScraperTestReport) {
    val marquee = NuvioTheme.marquee
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(report) {
        delay(120)
        bringIntoView.bringIntoView()
    }
    SettingsResultsPanel(
        modifier = Modifier
            .padding(start = SCRAPER_INDENT)
            .bringIntoViewRequester(bringIntoView)
    ) {
        val outcome = when {
            report.error != null -> stringResource(R.string.plugins_test_failed_line, report.error)
            report.results.isEmpty() -> stringResource(R.string.plugins_test_none_line, report.durationMs)
            else -> stringResource(R.string.plugins_test_streams_line, report.results.size, report.durationMs)
        }
        Text(
            text = outcome,
            style = NuvioTheme.textStyles.settingsResultLabel,
            color = if (report.error != null) NuvioPrimitives.errorOnImage else NuvioPrimitives.white
        )
        if (report.results.isNotEmpty()) {
            SectionCaption(stringResource(R.string.plugins_test_section_streams))
            report.results.take(TEST_STREAM_LINES).forEach { stream ->
                SettingsResultLine(
                    label = stream.name?.takeIf { it.isNotBlank() } ?: stream.title,
                    value = stream.quality.orEmpty(),
                    detail = listOfNotNull(stream.size, stream.language).joinToString(" \u00B7 ").takeIf { it.isNotEmpty() },
                    valueMuted = stream.quality == null
                )
            }
            val more = report.results.size - TEST_STREAM_LINES
            if (more > 0) {
                Text(
                    text = stringResource(R.string.plugins_test_more, more),
                    style = NuvioTheme.textStyles.settingsNote,
                    color = marquee.onImage3
                )
            }
        }
        val steps = report.diagnostics.steps
        if (steps.isNotEmpty()) {
            SectionCaption(stringResource(R.string.plugins_test_section_steps))
            steps.forEach { line ->
                Text(text = line, style = NuvioTheme.textStyles.settingsNote, color = marquee.onImage2, maxLines = 2)
            }
        }
        val script = report.diagnostics.scriptLines
        if (script.isNotEmpty()) {
            SectionCaption(stringResource(R.string.plugins_test_section_script))
            script.take(TEST_SCRIPT_LINES).forEach { line ->
                Text(text = line, style = NuvioTheme.textStyles.settingsNote, color = marquee.onImage3, maxLines = 2)
            }
        }
    }
}

@Composable
private fun SectionCaption(text: String) {
    Text(
        text = text.uppercase(),
        style = NuvioTheme.textStyles.eyebrow,
        color = NuvioTheme.marquee.onImage3,
        modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
    )
}

/** The logo fitted in a glass tile, else the name's initials. */
@Composable
private fun PluginLogo(logo: String?, name: String) {
    val tokens = NuvioTheme.marquee.collections
    var failed by remember(logo) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(tokens.thumbHeight)
            .clip(RoundedCornerShape(tokens.thumbRadius))
            .background(tokens.thumbFill),
        contentAlignment = Alignment.Center
    ) {
        val url = logo?.takeIf { it.isNotBlank() }
        if (url != null && !failed) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize().padding(NuvioTheme.spacing.xs)
            )
        } else {
            Text(
                text = addonInitials(name),
                style = NuvioTheme.textStyles.button,
                color = NuvioPrimitives.white,
                maxLines = 1
            )
        }
    }
}
