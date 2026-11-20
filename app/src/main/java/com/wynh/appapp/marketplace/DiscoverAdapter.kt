package com.wynh.appapp.marketplace

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import com.wynh.appapp.R
import com.wynh.appapp.databinding.DiscoverHeaderBinding
import com.wynh.appapp.databinding.ItemMarketplaceAppBinding
import com.wynh.appapp.databinding.ItemMarketplaceByoBinding
import com.wynh.appapp.databinding.PopupDetailActionsBinding
import com.wynh.appapp.databinding.SheetMarketplaceDetailBinding
import com.wynh.appapp.host.HostCompatibility
import com.wynh.appapp.workspace.SubappRecord

internal class DiscoverAdapter(
    private val inflater: LayoutInflater,
    private val onCardClick: (CatalogItemState) -> Unit,
    private val onPrimaryAction: (CatalogItemState) -> Unit,
    private val onImportGitHub: () -> Unit,
    private val onImportZip: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<CatalogItemState> = emptyList()
    private var cachedIcons: Map<String, Bitmap> = emptyMap()
    private var syncedFromRemote: Boolean = false
    private var refreshing: Boolean = false
    private var taskBusy: Boolean = false
    private var headerBinding: DiscoverHeaderBinding? = null

    fun submit(
        newItems: List<CatalogItemState>,
        icons: Map<String, Bitmap>,
        synced: Boolean,
        isRefreshing: Boolean,
        busy: Boolean,
    ) {
        items = newItems
        cachedIcons = icons
        syncedFromRemote = synced
        refreshing = isRefreshing
        taskBusy = busy
        bindHeaderStatus()
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size + 2

    override fun getItemViewType(position: Int): Int = when (position) {
        0 -> TYPE_HEADER
        items.size + 1 -> TYPE_BYO_FOOTER
        else -> TYPE_APP
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> {
                val header = DiscoverHeaderBinding.inflate(inflater, parent, false)
                headerBinding = header
                bindHeaderStatus()
                object : RecyclerView.ViewHolder(header.root) {}
            }
            TYPE_BYO_FOOTER -> {
                val footer = ItemMarketplaceByoBinding.inflate(inflater, parent, false)
                footer.discoverImportGithub.setOnClickListener { onImportGitHub() }
                footer.discoverImportZip.setOnClickListener { onImportZip() }
                object : RecyclerView.ViewHolder(footer.root) {}
            }
            else -> AppHolder(ItemMarketplaceAppBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder !is AppHolder) return
        val state = items[position - 1]
        val app = state.app
        val row = holder.binding
        val context = row.root.context

        MarketplaceUi.bindIcon(app, cachedIcons[app.id], row.catalogIconBg, row.catalogIconVector, row.catalogIconImage)
        row.catalogName.text = app.name
        val author = state.resolvedAuthor
        row.catalogMeta.text = if (author != null) {
            context.getString(R.string.marketplace_card_meta_with_author, app.category, author)
        } else {
            app.category
        }
        row.catalogSummary.text = app.summary

        val installed = state.installedRecords.isNotEmpty()
        val severalCopies = state.installedRecords.size > 1
        val updateReady = state.updateRecord != null && !severalCopies
        val incompatible = !state.hostCompatible && !installed

        row.catalogAction.apply {
            isEnabled = !taskBusy && !incompatible
            when {
                updateReady -> {
                    setText(R.string.marketplace_action_update)
                    backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primary))
                    setTextColor(ContextCompat.getColor(context, R.color.on_primary))
                }
                installed -> {
                    setText(if (severalCopies) R.string.marketplace_action_view else R.string.marketplace_action_open)
                    backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.surface_elevated))
                    setTextColor(ContextCompat.getColor(context, R.color.on_surface))
                }
                incompatible -> {
                    setText(R.string.marketplace_action_update_host)
                    backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.surface_variant))
                    setTextColor(ContextCompat.getColor(context, R.color.on_surface_muted))
                }
                else -> {
                    setText(R.string.marketplace_action_get)
                    backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primary))
                    setTextColor(ContextCompat.getColor(context, R.color.on_primary))
                }
            }
            setOnClickListener { onPrimaryAction(state) }
        }

        MarketplaceUi.populateChips(inflater, row.catalogChips, state)
        row.catalogCard.setOnClickListener { onCardClick(state) }
    }

    private fun bindHeaderStatus() {
        val header = headerBinding ?: return
        val status = header.discoverSyncStatus
        when {
            refreshing -> {
                status.visibility = View.VISIBLE
                status.setText(R.string.marketplace_refreshing)
            }
            syncedFromRemote -> {
                status.visibility = View.VISIBLE
                status.setText(R.string.marketplace_synced)
            }
            else -> status.visibility = View.GONE
        }
    }

    private class AppHolder(val binding: ItemMarketplaceAppBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_APP = 1
        private const val TYPE_BYO_FOOTER = 2
    }
}

internal object MarketplaceUi {
    fun bindIcon(
        app: MarketplaceApp,
        cachedIcon: Bitmap?,
        bgView: ImageView,
        vectorView: ImageView,
        imageView: ShapeableImageView,
    ) {
        val cornerPx = bgView.resources.displayMetrics.density * 12f
        imageView.shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setAllCorners(CornerFamily.ROUNDED, cornerPx)
            .build()
        if (cachedIcon != null) {
            imageView.setImageBitmap(cachedIcon)
            imageView.visibility = View.VISIBLE
            bgView.visibility = View.GONE
            vectorView.visibility = View.GONE
            return
        }
        val bundledIconRes = when (app.iconKey) {
            "lingomage" -> R.drawable.catalog_icon_lingomage
            "working_hours" -> R.drawable.catalog_icon_working_hours
            "starter_demo" -> R.mipmap.ic_launcher_round
            else -> null
        }
        if (bundledIconRes != null) {
            imageView.setImageResource(bundledIconRes)
            imageView.visibility = View.VISIBLE
            bgView.visibility = View.GONE
            vectorView.visibility = View.GONE
            return
        }
        imageView.visibility = View.GONE
        bgView.visibility = View.VISIBLE
        vectorView.visibility = View.VISIBLE
        bgView.setImageResource(R.drawable.bg_catalog_icon_default)
        vectorView.setImageResource(R.drawable.ic_catalog_default)
    }

    fun populateChips(
        inflater: LayoutInflater,
        container: LinearLayout,
        state: CatalogItemState,
    ) {
        container.removeAllViews()
        val context = container.context
        if (state.installedRecords.isNotEmpty()) {
            addChip(
                inflater = inflater,
                container = container,
                label = context.getString(R.string.marketplace_installed_badge),
                bgRes = R.drawable.bg_chip_installed,
                textColorRes = R.color.chip_installed_text,
            )
        }
        for (capability in state.app.capabilities) {
            addChip(
                inflater = inflater,
                container = container,
                label = capabilityLabel(context, capability),
                bgRes = R.drawable.bg_chip_neutral,
                textColorRes = R.color.on_secondary,
            )
        }
        when {
            state.app.source is CatalogSource.BundledStarter -> addChip(
                inflater = inflater,
                container = container,
                label = context.getString(R.string.marketplace_cap_builtin),
                bgRes = R.drawable.bg_chip_neutral,
                textColorRes = R.color.on_secondary,
            )
            state.app.offlineOnly -> addChip(
                inflater = inflater,
                container = container,
                label = context.getString(R.string.marketplace_cap_offline),
                bgRes = R.drawable.bg_chip_neutral,
                textColorRes = R.color.on_secondary,
            )
        }
    }

    private fun addChip(
        inflater: LayoutInflater,
        container: LinearLayout,
        label: String,
        bgRes: Int,
        textColorRes: Int,
    ) {
        val chip = inflater.inflate(R.layout.item_marketplace_chip, container, false) as TextView
        chip.text = label
        chip.setBackgroundResource(bgRes)
        chip.setTextColor(ContextCompat.getColor(container.context, textColorRes))
        container.addView(chip)
    }

    private fun capabilityLabel(context: Context, capability: CatalogCapability): String {
        val resId = when (capability) {
            CatalogCapability.AI -> R.string.marketplace_cap_ai
            CatalogCapability.SPEECH -> R.string.marketplace_cap_speech
            CatalogCapability.REMINDERS -> R.string.marketplace_cap_reminders
            CatalogCapability.INTERNET -> R.string.marketplace_cap_internet
            CatalogCapability.TEMPLATE -> R.string.marketplace_cap_template
            CatalogCapability.FILES -> R.string.marketplace_cap_files
            CatalogCapability.SHARE -> R.string.marketplace_cap_share
        }
        return context.getString(resId)
    }

    fun showDetailSheet(
        activity: AppCompatActivity,
        state: CatalogItemState,
        cachedIcon: Bitmap?,
        taskBusy: Boolean,
        onInstall: (MarketplaceApp) -> Unit,
        onOpen: (SubappRecord) -> Unit,
        onCreateShortcut: (SubappRecord) -> Unit,
        onBackup: (SubappRecord) -> Unit,
        onConfigure: (SubappRecord) -> Unit,
        onPermissions: (SubappRecord) -> Unit,
        onDelete: (SubappRecord) -> Unit,
        onUpdate: (SubappRecord) -> Unit,
        onShowCopies: () -> Unit,
    ): BottomSheetDialog {
        val app = state.app
        val sheet = BottomSheetDialog(activity, R.style.ThemeOverlay_AppApp_BottomSheetDialog)
        val viewBinding = SheetMarketplaceDetailBinding.inflate(activity.layoutInflater)
        sheet.setContentView(viewBinding.root)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true

        bindIcon(app, cachedIcon, viewBinding.detailIconBg, viewBinding.detailIconVector, viewBinding.detailIconImage)
        viewBinding.detailName.text = app.name

        // Record actions need exactly one target. Several copies (added manually from Your apps)
        // are managed there, where each row names the copy it acts on.
        val copies = state.installedRecords
        val installedRecord = copies.singleOrNull()
        val updateRecord = state.updateRecord
        val incompatible = !state.hostCompatible && copies.isEmpty()

        bindDetailSubtitle(activity, viewBinding.detailSubtitle, state.resolvedAuthor, copies.isNotEmpty())
        viewBinding.detailDescription.text = app.description
        bindDetailSpecs(activity, viewBinding, state, installedRecord != null)
        if (copies.size > 1) {
            viewBinding.detailCopiesNote.visibility = View.VISIBLE
            viewBinding.detailCopiesNote.text = activity.resources.getQuantityString(
                R.plurals.marketplace_copies_note,
                copies.size,
                copies.size,
            )
        } else {
            viewBinding.detailCopiesNote.visibility = View.GONE
        }

        when (val source = app.source) {
            is CatalogSource.GitHub -> {
                viewBinding.detailSourceButton.visibility = View.VISIBLE
                viewBinding.detailSourceButton.setOnClickListener {
                    try {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.repoUrl)))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(activity, R.string.marketplace_browser_missing, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            CatalogSource.BundledStarter -> {
                viewBinding.detailSourceButton.visibility = View.GONE
                viewBinding.detailSourceButton.setOnClickListener(null)
            }
        }

        if (incompatible) {
            viewBinding.detailHostWarning.visibility = View.VISIBLE
            viewBinding.detailHostWarning.text = activity.getString(
                R.string.marketplace_host_required,
                app.minHostCompatibility,
                HostCompatibility.LEVEL,
            )
        } else {
            viewBinding.detailHostWarning.visibility = View.GONE
        }

        var activePopup: PopupWindow? = null
        sheet.setOnDismissListener {
            activePopup?.dismiss()
            activePopup = null
        }

        val primary = viewBinding.detailPrimaryAction
        val moreButton = viewBinding.detailMoreAction

        when {
            incompatible -> {
                primary.setText(R.string.marketplace_action_update_host)
                primary.isEnabled = false
                primary.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.surface_variant))
                primary.setTextColor(ContextCompat.getColor(activity, R.color.on_surface_muted))
                moreButton.visibility = View.GONE
            }
            copies.isEmpty() -> {
                primary.setText(R.string.marketplace_action_install)
                primary.isEnabled = !taskBusy
                primary.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.primary))
                primary.setTextColor(ContextCompat.getColor(activity, R.color.on_primary))
                primary.setOnClickListener {
                    sheet.dismiss()
                    onInstall(app)
                }
                moreButton.visibility = View.GONE
            }
            installedRecord == null -> {
                // Updates are reviewed per copy too, from the copy's row in Your apps.
                primary.setText(R.string.marketplace_action_view_copies)
                primary.setOnClickListener {
                    sheet.dismiss()
                    onShowCopies()
                }
                primary.isEnabled = !taskBusy
                primary.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.primary))
                primary.setTextColor(ContextCompat.getColor(activity, R.color.on_primary))
                moreButton.visibility = View.GONE
            }
            else -> {
                if (updateRecord != null) {
                    primary.setText(R.string.marketplace_action_update)
                    primary.setOnClickListener {
                        sheet.dismiss()
                        onUpdate(updateRecord)
                    }
                } else {
                    primary.setText(R.string.marketplace_action_open)
                    primary.setOnClickListener {
                        sheet.dismiss()
                        onOpen(installedRecord)
                    }
                }
                primary.isEnabled = !taskBusy
                primary.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.primary))
                primary.setTextColor(ContextCompat.getColor(activity, R.color.on_primary))

                moreButton.visibility = View.VISIBLE
                moreButton.isEnabled = !taskBusy
                moreButton.setOnClickListener { anchor ->
                    activePopup?.dismiss()
                    activePopup = showDetailOverflowMenu(
                        activity = activity,
                        anchor = anchor,
                        installedRecord = installedRecord,
                        hasUpdate = updateRecord != null,
                        onDismissSheet = { sheet.dismiss() },
                        onOpen = onOpen,
                        onCreateShortcut = onCreateShortcut,
                        onBackup = onBackup,
                        onConfigure = onConfigure,
                        onPermissions = onPermissions,
                        onDelete = onDelete,
                    )
                }
            }
        }

        sheet.show()
        return sheet
    }

    private fun bindDetailSubtitle(
        context: Context,
        subtitleView: TextView,
        author: String?,
        installed: Boolean,
    ) {
        val byAuthor = author?.let { context.getString(R.string.marketplace_by_author, it) }
        val installedText = context.getString(R.string.marketplace_installed_label)
        val mutedColor = ContextCompat.getColor(context, R.color.on_surface_muted)
        val installedColor = ContextCompat.getColor(context, R.color.chip_installed_text)

        when {
            byAuthor != null && installed -> {
                subtitleView.visibility = View.VISIBLE
                subtitleView.setTextColor(mutedColor)
                val fullText = "$byAuthor · $installedText"
                val start = fullText.length - installedText.length
                subtitleView.text = SpannableStringBuilder(fullText).apply {
                    setSpan(
                        ForegroundColorSpan(installedColor),
                        start,
                        fullText.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            byAuthor != null -> {
                subtitleView.visibility = View.VISIBLE
                subtitleView.setTextColor(mutedColor)
                subtitleView.text = byAuthor
            }
            installed -> {
                subtitleView.visibility = View.VISIBLE
                subtitleView.setTextColor(installedColor)
                subtitleView.text = installedText
            }
            else -> {
                subtitleView.visibility = View.GONE
            }
        }
    }

    private fun bindDetailSpecs(
        context: Context,
        viewBinding: SheetMarketplaceDetailBinding,
        state: CatalogItemState,
        installed: Boolean,
    ) {
        val app = state.app
        viewBinding.detailSpecCategory.text = app.category
        val usesLabels = app.capabilities
            .filter { it != CatalogCapability.TEMPLATE }
            .map { capabilityLabel(context, it) }
        viewBinding.detailSpecUses.text = if (usesLabels.isEmpty()) {
            context.getString(R.string.marketplace_uses_local_only)
        } else {
            usesLabels.joinToString(", ")
        }

        val installedVersion = state.installedVersion
        if (installed && !installedVersion.isNullOrBlank()) {
            viewBinding.detailSpecThirdLabel.setText(R.string.marketplace_spec_version)
            viewBinding.detailSpecThirdValue.text = installedVersion
        } else {
            viewBinding.detailSpecThirdLabel.setText(R.string.marketplace_spec_license)
            viewBinding.detailSpecThirdValue.text = app.license
        }
    }

    private fun showDetailOverflowMenu(
        activity: AppCompatActivity,
        anchor: View,
        installedRecord: SubappRecord,
        hasUpdate: Boolean,
        onDismissSheet: () -> Unit,
        onOpen: (SubappRecord) -> Unit,
        onCreateShortcut: (SubappRecord) -> Unit,
        onBackup: (SubappRecord) -> Unit,
        onConfigure: (SubappRecord) -> Unit,
        onPermissions: (SubappRecord) -> Unit,
        onDelete: (SubappRecord) -> Unit,
    ): PopupWindow {
        val binding = PopupDetailActionsBinding.inflate(activity.layoutInflater)
        val popup = PopupWindow(
            binding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            elevation = activity.resources.getDimension(R.dimen.library_actions_popup_elevation)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        binding.detailMenuOpen.visibility = if (hasUpdate) View.VISIBLE else View.GONE
        binding.detailMenuOpen.setOnClickListener {
            popup.dismiss()
            onDismissSheet()
            onOpen(installedRecord)
        }

        binding.detailMenuShortcut.setOnClickListener {
            popup.dismiss()
            onDismissSheet()
            onCreateShortcut(installedRecord)
        }

        binding.detailMenuBackup.setOnClickListener {
            popup.dismiss()
            onDismissSheet()
            onBackup(installedRecord)
        }

        binding.detailMenuConfigure.setOnClickListener {
            popup.dismiss()
            onDismissSheet()
            onConfigure(installedRecord)
        }

        binding.detailMenuPermissions.setOnClickListener {
            popup.dismiss()
            onDismissSheet()
            onPermissions(installedRecord)
        }

        binding.detailMenuDelete.setOnClickListener {
            popup.dismiss()
            onDismissSheet()
            onDelete(installedRecord)
        }

        binding.root.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val gapPx = (8f * activity.resources.displayMetrics.density).toInt()
        val xOff = anchor.width - binding.root.measuredWidth
        val yOff = -(anchor.height + binding.root.measuredHeight + gapPx)
        popup.showAsDropDown(anchor, xOff, yOff)
        return popup
    }
}
