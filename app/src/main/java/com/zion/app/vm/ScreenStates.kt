package com.zion.app.vm

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.zion.app.core.ConfigBuilder
import com.zion.app.core.DirectSites
import com.zion.app.core.Format
import com.zion.app.core.ProxyParser
import com.zion.app.core.SubscriptionService
import com.zion.app.model.ExcludedApp
import com.zion.app.model.ProxyItem
import com.zion.app.model.SubscriptionEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

// ============================================================================== server list

/** One row of the server list: a section title, a provider's divider, or a server. */
sealed interface ServerRow {
    val key: String

    class Header(val title: String, val count: Int, val sectionKey: String) : ServerRow {
        override val key = "h:$sectionKey"
        /** Upper-case, like the section titles in settings. */
        val caption: String get() = title.uppercase()
    }

    class SubHeader(val source: ProxyItem) : ServerRow {
        override val key = "d:" + System.identityHashCode(source)
        val title: String get() = source.dividerTitle
    }

    class Server(val proxy: ProxyItem) : ServerRow {
        override val key = "s:" + System.identityHashCode(proxy)
    }
}

class ServerSection(val key: String, val title: String, val members: List<ProxyItem>)

class ServerListState(private val main: MainViewModel) {
    var undoMessage by mutableStateOf("")
        private set
    var isUndoVisible by mutableStateOf(false)
        private set
    private var undoAction: (() -> Unit)? = null
    private var undoJob: Job? = null

    var isDeleteAllConfirmOpen by mutableStateOf(false)
    var deleteAllConfirmText by mutableStateOf("")
        private set

    /** Real servers in the list (provider dividers are not counted). */
    val serverCount: Int get() = main.proxies.count { !it.isDivider }

    /** What the list shows: favourites first, then each subscription, then manual servers. */
    fun rows(): List<ServerRow> {
        val sections = buildSections(main.proxies, main.config.subscriptions)
        val showTitles = sections.size > 1 // a single section needs no title
        val rows = mutableListOf<ServerRow>()
        for (s in sections) {
            if (showTitles) rows += ServerRow.Header(s.title, s.members.count { !it.isDivider }, s.key)
            for (p in s.members) rows += if (p.isDivider) ServerRow.SubHeader(p) else ServerRow.Server(p)
        }
        return rows
    }

    fun toggleFavorite(p: ProxyItem) {
        p.isFavorite = !p.isFavorite
        main.saveConfig()
        main.bump()
    }

    fun refreshSubscriptions() {
        main.scope.launch { main.refreshAllSubscriptions() }
    }

    fun checkServers() {
        if (!main.isCheckingServers && serverCount > 0) main.scope.launch { main.checkServers() }
    }

    fun deleteProxy(proxy: ProxyItem) {
        if (main.proxies.size <= 1) return
        val index = main.proxies.indexOf(proxy)
        main.deleteProxy(proxy)
        showUndo("Сервер ${Format.countryAndCity(proxy.country)} удалён", 6000) {
            if (index in 0..main.proxies.size) main.proxies.add(index, proxy) else main.proxies += proxy
            main.saveConfig()
            main.bump()
        }
    }

    fun askDeleteAll() {
        val count = main.deletableProxyCount
        if (count == 0) return
        val inUse = main.serverInUse
        var text = if (inUse == null) "Из списка будут удалены все серверы ($count)."
        else "Будут удалены серверы ($count). «${inUse.cleanName}» останется: вы сейчас через него подключены."
        if (main.config.subscriptions.isNotEmpty()) text += " Подписки сохранятся: кнопка обновления вернёт их серверы."
        deleteAllConfirmText = text
        isDeleteAllConfirmOpen = true
    }

    fun confirmDeleteAll() {
        isDeleteAllConfirmOpen = false
        if (main.deletableProxyCount == 0) return
        val snapshot = main.deleteAllProxies()
        showUndo("Удалено серверов: ${snapshot.removedCount}", 10_000) { main.restoreProxies(snapshot) }
    }

    private fun showUndo(message: String, durationMs: Long, undo: () -> Unit) {
        undoAction = undo
        undoMessage = message
        isUndoVisible = true
        undoJob?.cancel()
        undoJob = main.scope.launch {
            delay(durationMs)
            isUndoVisible = false
            undoAction = null
        }
    }

    fun undo() {
        val u = undoAction
        undoAction = null
        undoJob?.cancel()
        isUndoVisible = false
        u?.invoke()
    }

    companion object {
        /**
         * Splits the list into sections: "Избранное", one per subscription (in the subscriptions' order),
         * then "Добавлены вручную". A favourite appears only under "Избранное". Empty sections are dropped.
         */
        fun buildSections(proxies: List<ProxyItem>, subscriptions: List<SubscriptionEntry>): List<ServerSection> {
            val sections = mutableListOf<ServerSection>()
            sections += ServerSection("fav", "Избранное", proxies.filter { it.isFavorite && !it.isDivider })
            val rest = proxies.filter { !(it.isFavorite && !it.isDivider) }
            val placed = HashSet<ProxyItem>()
            for (sub in subscriptions) {
                val members = rest.filter { it.isFromSubscription && SubscriptionService.sameUrl(it.subscriptionUrl, sub.url) }
                placed += members
                sections += ServerSection("sub:" + sub.id, Format.subscriptionName(sub.title, sub.url), members)
            }
            // Subscription servers whose link is no longer in the list, then manual ones
            sections += ServerSection("orphan", "Из подписки", rest.filter { it.isFromSubscription && it !in placed })
            sections += ServerSection("manual", "Добавлены вручную", rest.filter { !it.isFromSubscription })
            return sections.filter { s -> s.members.any { !it.isDivider } }
        }
    }
}

// ============================================================================== subscriptions

/** One card on the subscriptions screen. */
class SubscriptionRow(val entry: SubscriptionEntry) {
    var title by mutableStateOf("")
    var statusText by mutableStateOf("")
    var hasError by mutableStateOf(false)
    var planText by mutableStateOf("")
    var hasQuota by mutableStateOf(false)
    var usedFraction by mutableStateOf(0f)
    var planWarning by mutableStateOf(false)
    var isUpdating by mutableStateOf(false)
    /** First tap on delete arms it for a few seconds; only a second tap removes. */
    var isDeleteArmed by mutableStateOf(false)
    var disarmJob: Job? = null

    /** Host only: the rest of the link usually contains a personal token. */
    val host: String get() = try { URI(entry.url).host ?: "" } catch (_: Exception) { "" }
    val hasPlan: Boolean get() = planText.isNotEmpty()

    fun refresh(serverCount: Int, updating: Boolean) {
        val now = System.currentTimeMillis()
        isUpdating = updating
        title = Format.subscriptionName(entry.title, entry.url)
        val servers = Format.servers(serverCount)
        when {
            updating -> { statusText = "$servers · обновляется…"; hasError = false }
            entry.lastError.isNotEmpty() -> { statusText = "$servers · не обновилась: ${entry.lastError}"; hasError = true }
            else -> {
                statusText = entry.lastUpdate?.let { "$servers · обновлено ${Format.`when`(it, now)}" } ?: servers
                hasError = false
            }
        }
        val plan = Format.plan(entry, now)
        planText = plan.text
        hasQuota = entry.total > 0
        usedFraction = plan.usedFraction
        planWarning = plan.isWarning
    }
}

class SubscriptionsState(private val main: MainViewModel) {
    val rows = mutableStateListOf<SubscriptionRow>()
    var newUrl by mutableStateOf("")
    var isAdding by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    var statusIsError by mutableStateOf(false)
        private set
    var isUndoVisible by mutableStateOf(false)
        private set
    var undoMessage by mutableStateOf("")
        private set
    private var undoAction: (() -> Unit)? = null
    private var undoJob: Job? = null

    init {
        sync()
    }

    val hasNewUrl: Boolean get() = newUrl.isNotBlank()

    fun onUrlChanged(v: String) {
        newUrl = v
        if (!isAdding) status = ""
    }

    fun add() {
        if (isAdding) return
        if (!hasNewUrl) newUrl = main.clipboardText()
        val url = ProxyParser.extractActualSubscriptionUrl(newUrl)
        val ok = try {
            val u = URI(url)
            u.host != null && (u.scheme.equals("http", true) || u.scheme.equals("https", true))
        } catch (_: Exception) { false }
        if (url.isBlank() || !ok) {
            show("Это не похоже на ссылку подписки: она должна начинаться с https://", true)
            return
        }
        val known = SubscriptionService.find(main.config, url) != null
        isAdding = true
        show(if (known) "Эта подписка уже есть, обновляю…" else "Загружаю подписку…", false)
        main.scope.launch {
            try {
                val result = main.updateSubscription(url)
                if (result.ok) {
                    newUrl = ""
                    show(if (known) "Подписка обновлена: ${Format.servers(result.total)}." else "Подписка добавлена: ${Format.servers(result.total)}.", false)
                } else show("Не получилось: ${result.error}.", true)
            } finally {
                isAdding = false
            }
        }
    }

    fun refreshAll() {
        if (rows.isNotEmpty()) main.scope.launch { main.refreshAllSubscriptions() }
    }

    fun refresh(row: SubscriptionRow) {
        if (row.isUpdating) return
        main.scope.launch {
            val r = main.updateSubscription(row.entry.url)
            if (!r.ok && r.error != "Уже обновляется") show("«${row.title}» не обновилась: ${r.error}.", true)
        }
    }

    fun deleteClicked(row: SubscriptionRow) {
        if (!row.isDeleteArmed) {
            row.isDeleteArmed = true
            row.disarmJob?.cancel()
            row.disarmJob = main.scope.launch {
                delay(4000)
                row.isDeleteArmed = false
            }
            return
        }
        row.disarmJob?.cancel()
        row.isDeleteArmed = false
        val removed = main.removeSubscription(row.entry)
        undoAction = { main.restoreSubscription(removed) }
        undoMessage = "Подписка «${row.title}» удалена, серверов: ${removed.removedCount}"
        isUndoVisible = true
        undoJob?.cancel()
        undoJob = main.scope.launch {
            delay(10_000)
            isUndoVisible = false
            undoAction = null
        }
    }

    fun undo() {
        val u = undoAction
        undoAction = null
        undoJob?.cancel()
        isUndoVisible = false
        u?.invoke()
    }

    private fun show(text: String, isError: Boolean) {
        statusIsError = isError
        status = text
    }

    /** Brings the rows in line with the saved subscriptions without recreating the ones that stay. */
    fun sync() {
        val entries = main.config.subscriptions.toList()
        rows.removeAll { it.entry !in entries }
        entries.forEachIndexed { i, e ->
            var row = rows.firstOrNull { it.entry === e }
            if (row == null) {
                row = SubscriptionRow(e)
                rows.add(minOf(i, rows.size), row)
            }
            row.refresh(main.proxies.count { it.isFromSubscription && SubscriptionService.sameUrl(it.subscriptionUrl, e.url) },
                main.isSubscriptionUpdating(e.url))
        }
    }
}

// ============================================================================== apps outside the VPN

class InstalledApp(val packageName: String, val label: String)

class ExcludedAppsState(private val main: MainViewModel) {
    val rows = mutableStateListOf<ExcludedApp>().apply { addAll(main.config.directApps) }
    var status by mutableStateOf("")
        private set
    var isPickerOpen by mutableStateOf(false)
    var isLoadingPicker by mutableStateOf(false)
        private set
    var search by mutableStateOf("")
    private var installed by mutableStateOf<List<InstalledApp>>(emptyList())
    private val icons = ConcurrentHashMap<String, ImageBitmap>()

    /** One line for the settings screen: "Steam, Discord и ещё 2" or "Не выбраны". */
    val summary: String
        get() {
            if (rows.isEmpty()) return "Не выбраны"
            val text = rows.take(2).joinToString(", ") { it.displayName.ifBlank { it.packageName } }
            return if (rows.size > 2) "$text и ещё ${rows.size - 2}" else text
        }

    val pickerItems: List<InstalledApp>
        get() {
            val q = search.trim()
            return installed.filter { q.isEmpty() || it.label.contains(q, true) || it.packageName.contains(q, true) }
        }

    fun isAdded(pkg: String) = rows.any { it.packageName == pkg }

    fun openPicker() {
        status = ""
        search = ""
        isPickerOpen = true
        isLoadingPicker = true
        main.scope.launch {
            installed = withContext(Dispatchers.IO) { loadLauncherApps() }
            isLoadingPicker = false
        }
    }

    /** Apps that show up in the launcher, Zion itself left out, sorted by name. */
    private fun loadLauncherApps(): List<InstalledApp> {
        val pm = main.app.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != main.app.packageName }
            .map { InstalledApp(it.first, it.second) }
            .sortedBy { it.label.lowercase() }
    }

    fun icon(pkg: String): ImageBitmap? {
        icons[pkg]?.let { return it }
        return try {
            val d = main.app.packageManager.getApplicationIcon(pkg)
            val size = 96
            val bmp = if (d is BitmapDrawable && d.bitmap != null) Bitmap.createScaledBitmap(d.bitmap, size, size, true)
            else Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { b ->
                val c = Canvas(b)
                d.setBounds(0, 0, size, size)
                d.draw(c)
            }
            bmp.asImageBitmap().also { icons[pkg] = it }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun pick(app: InstalledApp) {
        if (isAdded(app.packageName)) return
        isPickerOpen = false
        if (ConfigBuilder.sanitizeDirectApps(listOf(app.packageName), main.app.packageName).isEmpty()) {
            status = "Сам Zion исключать нельзя."
            return
        }
        val entry = ExcludedApp(app.packageName, app.label)
        main.config.directApps += entry
        rows += entry
        status = if (main.isConnected) "Приложение «${app.label}» добавлено. Туннель перезапустится через пару секунд."
        else "Приложение «${app.label}» добавлено."
        main.applyRoutingExceptions()
    }

    fun remove(app: ExcludedApp) {
        main.config.directApps.remove(app)
        rows.remove(app)
        status = "Приложение «${app.displayName.ifBlank { app.packageName }}» снова идёт через VPN."
        main.applyRoutingExceptions()
    }
}

// ============================================================================== sites outside the VPN

class ExcludedSitesState(private val main: MainViewModel) {
    /** Stored and matched form (punycode for non-Latin names). */
    val rows = mutableStateListOf<String>().apply { addAll(DirectSites.sanitize(main.config.directSites)) }
    var newSite by mutableStateOf("")
    var status by mutableStateOf("")
        private set
    var statusIsError by mutableStateOf(false)
        private set

    val hasNewSite: Boolean get() = newSite.isNotBlank()

    /** One line for the settings screen: "kinopoisk.ru, sberbank.ru и ещё 2" or "Не выбраны". */
    val summary: String
        get() {
            if (rows.isEmpty()) return "Не выбраны"
            val text = rows.take(2).joinToString(", ") { DirectSites.display(it) }
            return if (rows.size > 2) "$text и ещё ${rows.size - 2}" else text
        }

    fun onSiteChanged(v: String) {
        newSite = v
        status = ""
    }

    /** Adds what is typed; with an empty field, takes the address from the clipboard. */
    fun add() {
        if (!hasNewSite) newSite = main.clipboardText()
        val n = DirectSites.normalize(newSite)
        val domain = n.domain
        if (domain == null) {
            show(n.error, true)
            return
        }
        if (domain in rows) {
            show("${DirectSites.display(domain)} уже в списке", true)
            return
        }
        main.config.directSites += domain
        rows.add(0, domain)
        newSite = ""
        show(if (main.isConnected) "${DirectSites.display(domain)} добавлен. Туннель перезапустится через пару секунд."
             else "${DirectSites.display(domain)} добавлен", false)
        main.applyRoutingExceptions()
    }

    fun remove(domain: String) {
        main.config.directSites.removeAll { it.equals(domain, true) }
        rows.remove(domain)
        show("${DirectSites.display(domain)} снова идёт через VPN", false)
        main.applyRoutingExceptions()
    }

    private fun show(text: String, isError: Boolean) {
        statusIsError = isError
        status = text
    }
}
