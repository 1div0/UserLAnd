package tech.ula.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.widget.LinearLayoutManager
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ContextMenu
import android.view.View
import android.view.Menu
import android.view.MenuItem
import android.view.MenuInflater
import android.widget.Toast
import android.widget.EditText
import android.widget.RadioButton
import androidx.navigation.fragment.NavHostFragment
import kotlinx.android.synthetic.main.frag_app_list.* // ktlint-disable no-wildcard-imports
import org.jetbrains.anko.bundleOf
import org.jetbrains.anko.defaultSharedPreferences
import org.jetbrains.anko.find
import tech.ula.R
import tech.ula.ServerService
import tech.ula.model.entities.App
import tech.ula.model.entities.Filesystem
import tech.ula.model.entities.Session
import tech.ula.model.remote.GithubAppsFetcher
import tech.ula.model.repositories.AppsRepository
import tech.ula.model.repositories.RefreshStatus
import tech.ula.model.repositories.UlaDatabase
import tech.ula.model.state.* // ktlint-disable no-wildcard-imports
import tech.ula.utils.* // ktlint-disable no-wildcard-imports
import tech.ula.viewmodel.AppListViewModel
import tech.ula.viewmodel.AppListViewModelFactory

class AppListFragment : Fragment(),
        AppListAdapter.OnAppsItemClicked,
        AppListAdapter.OnAppsCreateContextMenu {

    private lateinit var activityContext: Activity
    private val permissionRequestCode: Int by lazy {
        activityContext.resources.getString(R.string.permission_request_code).toInt()
    }

    private val appAdapter by lazy {
        AppListAdapter(activityContext, this, this)
    }

    private val unselectedApp = App(name = "unselected")
    private var lastSelectedApp = unselectedApp

    private var refreshStatus = RefreshStatus.INACTIVE

    private val appsPreferences by lazy {
        AppsPreferences(activityContext.getSharedPreferences("apps", Context.MODE_PRIVATE))
    }

    private val appsListViewModel: AppListViewModel by lazy {
        val ulaDatabase = UlaDatabase.getInstance(activityContext)
        val appsDao = ulaDatabase.appsDao()
        val githubFetcher = GithubAppsFetcher("${activityContext.filesDir}")
        val appsStartupMachine = AppsStartupFsm(ulaDatabase, appsPreferences)

        val appsRepository = AppsRepository(appsDao, githubFetcher, appsPreferences)
        ViewModelProviders.of(this, AppListViewModelFactory(appsStartupMachine, appsRepository))
                .get(AppListViewModel::class.java)
    }

    // TODO move refreshing into state machine
    private val appsObserver = Observer<List<App>> {
        it?.let { list ->
            appAdapter.updateApps(list)
            if (list.isEmpty() || userlandIsNewVersion()) {
//                doRefresh()
            }
        }
    }

    private val refreshStatusObserver = Observer<RefreshStatus> {
        it?.let {
            refreshStatus = it
            swipe_refresh.isRefreshing = refreshStatus == RefreshStatus.ACTIVE

            if (refreshStatus == RefreshStatus.FAILED) showRefreshUnavailableDialog()
        }
    }

    private val startupStateObserver = Observer<AppsStartupState> {
        it?.let { startupState ->
            when (startupState) {
                is WaitingForAppSelection -> {} // TODO appsAreClickable
                is AppsListIsEmpty -> { doRefresh() }
                is SingleSessionPermitted -> { showSingleSessionAlert() }
                is AppsFilesystemRequiresCredentials -> getCredentials(startupState.app, startupState.filesystem)
                is AppRequiresServiceTypePreference -> getServiceTypePreference(startupState.app)
                is AppCanBeStarted -> startAppSession(startupState.appSession, startupState.appsFilesystem)
                is AppCanBeRestarted -> restartAppSession(startupState.appSession)
                is AppsHaveActivated -> { appAdapter.updateActiveApps(startupState.activeApps) }
                is AppsHaveDeactivated -> { appAdapter.updateActiveApps(listOf()) } // TODO deactivate apps appropriately. Once adapter can accept single apps for activation/deactivation
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu?, inflater: MenuInflater?) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater?.inflate(R.menu.menu_refresh, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        return when {
            item?.itemId == R.id.menu_item_refresh -> {
                swipe_refresh.isRefreshing = true
                doRefresh()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onAppsItemClicked(appsItemClicked: AppsListItem) {
        appAdapter.setLastSelectedContextItem(appsItemClicked)
        when (appsItemClicked) {
            is AppSeparatorItem -> {
            }
            is AppItem -> {
                doAppItemClicked(appsItemClicked.app)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.frag_app_list, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        activityContext = activity!!
        appsListViewModel.getAppsList().observe(viewLifecycleOwner, appsObserver)
        appsListViewModel.getRefreshStatus().observe(viewLifecycleOwner, refreshStatusObserver)
        appsListViewModel.getAppsStartupState().observe(viewLifecycleOwner, startupStateObserver)

        registerForContextMenu(list_apps)
        list_apps.layoutManager = LinearLayoutManager(list_apps.context)
        list_apps.adapter = appAdapter

        swipe_refresh.setOnRefreshListener { doRefresh() }
        swipe_refresh.setColorSchemeResources(
                R.color.holo_blue_light,
                R.color.holo_green_light,
                R.color.holo_orange_light,
                R.color.holo_red_light)
    }

    private fun doRefresh() {
        appsListViewModel.refreshAppsList()
        setLatestUpdateUserlandVersion()
    }

    private fun doAppItemClicked(selectedApp: App) {
        lastSelectedApp = selectedApp
        if (arePermissionsGranted(activityContext)) {
            handleAppSelection(lastSelectedApp)
        } else {
            showPermissionsNecessaryDialog()
        }
    }

    private fun handleAppSelection(selectedApp: App) {
        if (selectedApp == unselectedApp) return

        appsListViewModel.submitAppsStartupEvent(AppSelected(selectedApp))
    }

    private fun doContextItemSelected(app: App, item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_item_app_details -> showAppDetails(app)
            R.id.menu_item_stop_app -> stopAppSession(app)
            else -> super.onContextItemSelected(item)
        }
    }

    override fun onAppsCreateContextMenu(menu: ContextMenu, v: View, selectedListItem: AppsListItem) {
        appAdapter.setLastSelectedContextItem(selectedListItem)
        activityContext.menuInflater.inflate(R.menu.context_menu_apps, menu)
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val selectedItem = appAdapter.getLastSelectedContextItem()
        return when (selectedItem) {
            is AppSeparatorItem -> true
            is AppItem -> {
                val app = selectedItem.app
                doContextItemSelected(app, item)
            }
        }
    }

    private fun showAppDetails(app: App): Boolean {
        val bundle = bundleOf("app" to app)
        NavHostFragment.findNavController(this).navigate(R.id.menu_item_app_details, bundle)
        return true
    }

    private fun startAppSession(appSession: Session, appsFilesystem: Filesystem) {
        val startAppIntent = Intent(activityContext, ServerService::class.java)
                .putExtra("type", "start")
                .putExtra("session", appSession)
                .putExtra("filesystem", appsFilesystem)
        activityContext.startService(startAppIntent)
    }

    private fun restartAppSession(appSession: Session) {
        val serviceIntent = Intent(activityContext, ServerService::class.java)
                .putExtra("type", "restartRunningSession")
                .putExtra("session", appSession)
        activityContext.startService(serviceIntent)
    }

    private fun stopAppSession(app: App): Boolean {
        val serviceIntent = Intent(activityContext, ServerService::class.java)
                .putExtra("type", "stopApp")
                .putExtra("app", app)
        activityContext.startService(serviceIntent)
        return true
    }

    private fun getCredentials(app: App, filesystem: Filesystem) {
        val dialog = AlertDialog.Builder(activityContext)
        val dialogView = activityContext.layoutInflater.inflate(R.layout.dia_app_credentials, null)
        dialog.setView(dialogView)
        dialog.setCancelable(true)
        dialog.setPositiveButton(R.string.button_continue, null)
        val customDialog = dialog.create()

        customDialog.setOnShowListener { _ ->
            customDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { _ ->
                val username = customDialog.find<EditText>(R.id.text_input_username).text.toString()
                val password = customDialog.find<EditText>(R.id.text_input_password).text.toString()
                val vncPassword = customDialog.find<EditText>(R.id.text_input_vnc_password).text.toString()

                if (validateCredentials(username, password, vncPassword)) {
                    customDialog.dismiss()
                    appsListViewModel.submitAppsStartupEvent(SubmitAppsFilesystemCredentials(app, filesystem, username, password, vncPassword))
                }
            }
        }
        customDialog.setOnCancelListener {
            /* TODO submit event */
        }
        customDialog.show()
    }

    private fun getServiceTypePreference(app: App) {
        val dialog = AlertDialog.Builder(activityContext)
        val dialogView = layoutInflater.inflate(R.layout.dia_app_select_client, null)
        dialog.setView(dialogView)
        dialog.setCancelable(true)
        dialog.setPositiveButton(R.string.button_continue, null)
        val customDialog = dialog.create()

        customDialog.setOnShowListener { _ ->
            customDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { _ ->
                customDialog.dismiss()
                val sshTypePreference = customDialog.find<RadioButton>(R.id.ssh_radio_button)
                val selectedPreference =
                        if (sshTypePreference.isChecked) SshTypePreference else VncTypePreference
                appsListViewModel.submitAppsStartupEvent(SubmitAppServicePreference(app, selectedPreference))
            }
        }
        customDialog.setOnCancelListener {
            /* TODO submit event */
        }
        customDialog.show()
    }

    // TODO the view shouldn't be responsible for validation
    private fun validateCredentials(username: String, password: String, vncPassword: String): Boolean {
        val validator = ValidationUtility()
        var allCredentialsAreValid = false

        when {
            username.isEmpty() || password.isEmpty() || vncPassword.isEmpty() -> {
                Toast.makeText(activityContext, R.string.error_empty_field, Toast.LENGTH_LONG).show()
            }
            vncPassword.length > 8 || vncPassword.length < 6 -> {
                Toast.makeText(activityContext, R.string.error_vnc_password_length_incorrect, Toast.LENGTH_LONG).show()
            }
            !validator.isUsernameValid(username) -> {
                Toast.makeText(activityContext, R.string.error_username_invalid, Toast.LENGTH_LONG).show()
            }
            !validator.isPasswordValid(password) -> {
                Toast.makeText(activityContext, R.string.error_password_invalid, Toast.LENGTH_LONG).show()
            }
            !validator.isPasswordValid(vncPassword) -> {
                Toast.makeText(activityContext, R.string.error_vnc_password_invalid, Toast.LENGTH_LONG).show()
            }
            else -> {
                allCredentialsAreValid = true
                return allCredentialsAreValid
            }
        }
        return allCredentialsAreValid
    }

    private fun showPermissionsNecessaryDialog() {
        val builder = AlertDialog.Builder(activityContext)
        builder.setMessage(R.string.alert_permissions_necessary_message)
                .setTitle(R.string.alert_permissions_necessary_title)
                .setPositiveButton(R.string.button_ok) {
                    dialog, _ ->
                    requestPermissions(arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                            permissionRequestCode)
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.alert_permissions_necessary_cancel_button) {
                    dialog, _ ->
                    dialog.dismiss()
                }
        builder.create().show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            permissionRequestCode -> {

                val grantedPermissions = (grantResults.isNotEmpty() &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                        grantResults[1] == PackageManager.PERMISSION_GRANTED)

                if (grantedPermissions) {
                    handleAppSelection(lastSelectedApp)
                } else {
                    showPermissionsNecessaryDialog()
                }
            }
        }
    }

    private fun showSingleSessionAlert() {
        Toast.makeText(activityContext, R.string.single_session_supported, Toast.LENGTH_LONG).show()
    }

    private fun showRefreshUnavailableDialog() {
        AlertDialog.Builder(activityContext)
                .setMessage(R.string.alert_network_required_for_refresh)
                .setTitle(R.string.general_error_title)
                .setPositiveButton(R.string.button_ok) {
                    dialog, _ ->
                    dialog.dismiss()
                }
                .create().show()
    }

    private fun userlandIsNewVersion(): Boolean {
        val version = getUserlandVersion()
        val lastUpdatedVersion = activityContext.defaultSharedPreferences.getString("lastAppsUpdate", "")
        return version != lastUpdatedVersion
    }

    private fun setLatestUpdateUserlandVersion() {
        val version = getUserlandVersion()
        with(activityContext.defaultSharedPreferences.edit()) {
            putString("lastAppsUpdate", version)
            apply()
        }
    }

    private fun getUserlandVersion(): String {
        val info = activityContext.packageManager.getPackageInfo(activityContext.packageName, 0)
        return info.versionName
    }
}