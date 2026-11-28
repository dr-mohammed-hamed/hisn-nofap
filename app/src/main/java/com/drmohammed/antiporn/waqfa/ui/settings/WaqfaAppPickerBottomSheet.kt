package com.drmohammed.antiporn.waqfa.ui.settings

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.curfew.CurfewAppResolver
import com.drmohammed.antiporn.data.model.InstalledAppDto
import com.drmohammed.antiporn.databinding.BottomSheetWaqfaAppPickerBinding
import com.drmohammed.antiporn.databinding.ItemWaqfaAppPickerBinding
import com.drmohammed.antiporn.ui.accountability.AppIconResolver
import com.drmohammed.antiporn.ui.common.applyBottomSheetInsets
import com.drmohammed.antiporn.ui.common.configureLandscapeFriendlySheet
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * نافذة سفلية منبثقة لاختيار التطبيقات الخاضعة لنظام «وَقْفَة».
 * تدعم وضع الإشراف المحلي والتحكم عن بعد (Remote Supervision) عبر استرداد مستودع تطبيقات الجهاز المدار من Firestore.
 */
class WaqfaAppPickerBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetWaqfaAppPickerBinding? = null
    private val binding get() = _binding ?: throw IllegalStateException("Binding is only valid between onCreateView and onDestroyView")

    data class AppItem(
        val packageName: String,
        val appName: String,
        var isSelected: Boolean
    )

    private val allApps = mutableListOf<AppItem>()
    private val filteredApps = mutableListOf<AppItem>()
    private lateinit var adapter: AppPickerAdapter
    private var currentSavedSelections: Set<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getStringArrayList(KEY_CURRENT_SELECTIONS)?.let {
            currentSavedSelections = it.toSet()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (allApps.isNotEmpty()) {
            val selected = allApps.filter { it.isSelected }.map { it.packageName }
            outState.putStringArrayList(KEY_CURRENT_SELECTIONS, ArrayList(selected))
        } else {
            currentSavedSelections?.let {
                outState.putStringArrayList(KEY_CURRENT_SELECTIONS, ArrayList(it))
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetWaqfaAppPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        configureLandscapeFriendlySheet(enableImeResize = true)
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        applyBottomSheetInsets(view, includeIme = true) { imeVisible ->
            if (_binding != null && isLandscape) {
                binding.btnConfirmSelection.isVisible = !imeVisible
            }
        }

        val isRemote = arguments?.getBoolean(ARG_IS_REMOTE) ?: false

        adapter = AppPickerAdapter(filteredApps, isLocalMode = !isRemote) { item ->
            item.isSelected = !item.isSelected
            val index = filteredApps.indexOf(item)
            if (index != -1) {
                adapter.notifyItemChanged(index, PAYLOAD_CHECKBOX)
            }
            updateConfirmButton()
        }

        binding.rvApps.layoutManager = LinearLayoutManager(requireContext())
        binding.rvApps.adapter = adapter

        binding.etSearchApp.doAfterTextChanged { text ->
            filterApps(text?.toString().orEmpty())
        }

        binding.btnConfirmSelection.isEnabled = false
        binding.btnConfirmSelection.setOnClickListener {
            binding.btnConfirmSelection.isEnabled = false
            val selected = allApps.filter { it.isSelected }.map { it.packageName }.toSet()
            parentFragmentManager.setFragmentResult(
                REQUEST_KEY,
                Bundle().apply {
                    putStringArrayList(RESULT_TARGETS, ArrayList(selected))
                }
            )
            dismiss()
        }

        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        val appContext = context?.applicationContext ?: return
        val isRemote = arguments?.getBoolean(ARG_IS_REMOTE) ?: false
        val wardId = arguments?.getString(ARG_WARD_ID).orEmpty()
        val initialTargets = currentSavedSelections
            ?: arguments?.getStringArrayList(ARG_INITIAL_TARGETS)?.toSet() ?: emptySet()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val criticalPackages = CurfewAppResolver.resolveCoreSystemCriticalPackages(appContext)
                val list = mutableListOf<AppItem>()
                val seenPackages = mutableSetOf<String>()

                if (!isRemote) {
                    val pm = appContext.packageManager
                    val intent = Intent(Intent.ACTION_MAIN, null).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }
                    val activities = pm.queryIntentActivities(intent, 0)
                    for (resolveInfo in activities) {
                        val pkg = resolveInfo.activityInfo?.packageName ?: continue
                        if (seenPackages.contains(pkg) || isPackageRestricted(pkg, appContext.packageName, criticalPackages)) {
                            continue
                        }
                        seenPackages.add(pkg)

                        val name = resolveInfo.loadLabel(pm).toString()
                        list.add(
                            AppItem(
                                packageName = pkg,
                                appName = name,
                                isSelected = initialTargets.contains(pkg)
                            )
                        )
                    }

                    val extraPackages = initialTargets - seenPackages
                    for (orphan in extraPackages) {
                        if (isPackageRestricted(orphan, appContext.packageName, criticalPackages)) continue
                        list.add(
                            AppItem(
                                packageName = orphan,
                                appName = orphan,
                                isSelected = true
                            )
                        )
                    }
                } else if (wardId.isBlank()) {
                    Timber.tag(TAG).w("Refusing remote waqfa app load: blank wardId")
                    withContext(Dispatchers.Main) {
                        if (isAdded) dismissAllowingStateLoss()
                    }
                    return@launch
                } else {
                    val firestore = FirebaseFirestore.getInstance()
                    var inventoryDoc: DocumentSnapshot? = null
                    try {
                        inventoryDoc = firestore.collection("inventories").document(wardId).get().await()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Timber.tag(TAG).w(e, "Failed fetching remote inventory from server, falling back to cache")
                        try {
                            inventoryDoc = firestore.collection("inventories").document(wardId).get(Source.CACHE).await()
                        } catch (cacheEx: Exception) {
                            if (cacheEx is CancellationException) throw cacheEx
                            Timber.tag(TAG).w(cacheEx, "Failed fetching inventory from cache")
                        }
                    }

                    @Suppress("UNCHECKED_CAST")
                    val rawApps = (inventoryDoc?.get("apps") as? List<*>) ?: emptyList<Any?>()
                    val parsedApps = rawApps.mapNotNull { raw ->
                        @Suppress("UNCHECKED_CAST")
                        (raw as? Map<String, Any?>)?.let { InstalledAppDto.fromMap(it) }
                    }.filter { it.packageName.isNotBlank() }

                    for (app in parsedApps) {
                        val pkg = app.packageName
                        if (seenPackages.contains(pkg) || isPackageRestricted(pkg, appContext.packageName, criticalPackages)) {
                            continue
                        }
                        seenPackages.add(pkg)
                        list.add(
                            AppItem(
                                packageName = pkg,
                                appName = app.appName.ifBlank { pkg },
                                isSelected = initialTargets.contains(pkg)
                            )
                        )
                    }

                    val extraPackages = initialTargets - seenPackages
                    for (orphan in extraPackages) {
                        if (isPackageRestricted(orphan, appContext.packageName, criticalPackages)) continue
                        list.add(
                            AppItem(
                                packageName = orphan,
                                appName = orphan,
                                isSelected = true
                            )
                        )
                    }
                }

                list.sortBy { it.appName.lowercase() }

                withContext(Dispatchers.Main) {
                    if (_binding != null) {
                        allApps.clear()
                        allApps.addAll(list)
                        filterApps(binding.etSearchApp.text?.toString().orEmpty())
                        binding.btnConfirmSelection.isEnabled = true
                        updateConfirmButton()
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).e(e, "Failed loading waqfa target apps")
                withContext(Dispatchers.Main) {
                    if (isAdded) dismissAllowingStateLoss()
                }
            }
        }
    }

    private fun isPackageRestricted(
        pkg: String,
        selfPackageName: String,
        criticalPackages: Set<String>
    ): Boolean {
        if (pkg.isBlank()) return true
        if (pkg == selfPackageName) return true
        if (criticalPackages.contains(pkg)) return true
        if (AppPolicyRegistry.isImmune(pkg)) return true
        return false
    }

    private fun filterApps(query: String) {
        filteredApps.clear()
        if (query.isBlank()) {
            filteredApps.addAll(allApps)
        } else {
            val q = query.trim().lowercase()
            filteredApps.addAll(allApps.filter {
                it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            })
        }
        adapter.notifyDataSetChanged()
    }

    private fun updateConfirmButton() {
        val count = allApps.count { it.isSelected }
        if (count > 0) {
            binding.btnConfirmSelection.text = getString(R.string.waqfa_btn_confirm_selection_count, count)
        } else {
            binding.btnConfirmSelection.text = getString(R.string.waqfa_btn_confirm_selection)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    inner class AppPickerAdapter(
        private val items: List<AppItem>,
        private val isLocalMode: Boolean,
        private val onItemClick: (AppItem) -> Unit
    ) : RecyclerView.Adapter<AppPickerAdapter.AppViewHolder>() {

        inner class AppViewHolder(val itemBinding: ItemWaqfaAppPickerBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val b = ItemWaqfaAppPickerBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return AppViewHolder(b)
        }

        override fun onBindViewHolder(
            holder: AppViewHolder,
            position: Int,
            payloads: MutableList<Any>
        ) {
            if (payloads.contains(PAYLOAD_CHECKBOX)) {
                holder.itemBinding.cbSelected.isChecked = items[position].isSelected
                return
            }
            super.onBindViewHolder(holder, position, payloads)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            val item = items[position]
            holder.itemBinding.tvAppName.text = item.appName
            holder.itemBinding.tvPackageName.text = item.packageName

            AppIconResolver.loadIcon(
                imageView = holder.itemBinding.ivAppIcon,
                packageName = item.packageName,
                appName = item.appName,
                isLocalMode = isLocalMode
            )

            holder.itemBinding.cbSelected.isChecked = item.isSelected

            holder.itemBinding.root.setOnClickListener {
                onItemClick(item)
            }
            holder.itemBinding.cbSelected.setOnClickListener {
                onItemClick(item)
            }
        }

        override fun onViewRecycled(holder: AppViewHolder) {
            super.onViewRecycled(holder)
            AppIconResolver.cancelLoad(holder.itemBinding.ivAppIcon)
        }

        override fun getItemCount(): Int = items.size
    }

    companion object {
        const val TAG = "WaqfaAppPicker"
        const val REQUEST_KEY = "waqfa_app_picker_request"
        const val RESULT_TARGETS = "result_waqfa_targets"

        private const val ARG_IS_REMOTE = "arg_is_remote"
        private const val ARG_WARD_ID = "arg_ward_id"
        private const val ARG_INITIAL_TARGETS = "arg_initial_targets"
        private const val KEY_CURRENT_SELECTIONS = "key_current_selections"
        private const val PAYLOAD_CHECKBOX = "payload_checkbox"

        fun newInstance(
            isRemote: Boolean,
            wardId: String,
            initialTargets: Set<String>
        ): WaqfaAppPickerBottomSheet {
            return WaqfaAppPickerBottomSheet().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_IS_REMOTE, isRemote)
                    putString(ARG_WARD_ID, wardId)
                    putStringArrayList(ARG_INITIAL_TARGETS, ArrayList(initialTargets))
                }
            }
        }
    }
}
