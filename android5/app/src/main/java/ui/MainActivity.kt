/*
 * This file is part of Blokada.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright © 2021 Blocka AB. All rights reserved.
 *
 * @author Karol Gusak (karol@blocka.net)
 */

package ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.akexorcist.localizationactivity.ui.LocalizationActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import model.Tab
import org.blokada.R
import repository.Repos
import service.*
import ui.home.ActivatedFragment
import ui.home.FirstTimeFragment
import ui.home.HelpFragment
import ui.home.HomeFragmentDirections
import ui.settings.SettingsFragmentDirections
import ui.settings.SettingsNavigation
import ui.utils.now
import ui.web.WebService
import utils.ExpiredNotification
import utils.Links
import utils.Logger


class MainActivity : LocalizationActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    private lateinit var accountVM: AccountViewModel
    private lateinit var tunnelVM: TunnelViewModel
    private lateinit var settingsVM: SettingsViewModel
    private lateinit var statsVM: StatsViewModel
    private lateinit var blockaRepoVM: BlockaRepoViewModel
    private lateinit var activationVM: ActivationViewModel

    private val navRepo by lazy { Repos.nav }
    private val paymentRepo by lazy { Repos.payment }

    private val sheet = Services.sheet

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.v("MainActivity", "onCreate: $this")

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ContextService.setActivityContext(this)
        TranslationService.setup()
        Services.sheet.onShowFragment = { fragment ->
            fragment.show(supportFragmentManager, null)
        }
        setupEvents()

        settingsVM.getTheme()?.let { setTheme(it) }

        setContentView(R.layout.activity_main)

        val navView: BottomNavigationView = findViewById(R.id.nav_view)
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        val fragmentContainer: ViewGroup = findViewById(R.id.container_fragment)

        setSupportActionBar(toolbar)
        setInsets(toolbar)

        val navController = findNavController(R.id.nav_host_fragment)
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_home,
                R.id.navigation_activity,
                R.id.advancedFragment,
                R.id.navigation_settings
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        // Set the fragment inset as needed (home fragment has no inset)
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val shouldInset = when (destination.id) {
                R.id.navigation_home -> false
                else -> true
            }
            setFragmentInset(fragmentContainer, shouldInset)

            // An ugly hack to hide jumping fragments when switching tabs
            fragmentContainer.visibility = View.INVISIBLE
            lifecycleScope.launch(Dispatchers.Main) {
                delay(100)
                fragmentContainer.visibility = View.VISIBLE
            }
        }


        // Needed for dynamic translation of the bottom bar
        val selectionListener = BottomNavigationView.OnNavigationItemSelectedListener { item ->
            val (nav, title) = when (item.itemId) {
                R.id.navigation_activity -> R.id.navigation_activity to getString(R.string.main_tab_activity)
                R.id.advancedFragment -> R.id.advancedFragment to getString(R.string.main_tab_advanced)
                R.id.navigation_settings -> R.id.navigation_settings to getString(R.string.main_tab_settings)
                else -> R.id.navigation_home to getString(R.string.main_tab_home)
            }
            navController.navigate(nav)
            item.title = title

            // Also emit tab change in NavRepo
            lifecycleScope.launch {
                val tab = when(item.itemId) {
                    R.id.navigation_activity -> Tab.Activity
                    R.id.advancedFragment -> Tab.Advanced
                    R.id.navigation_settings -> Tab.Settings
                    else -> Tab.Home
                }
                navRepo.setActiveTab(tab)
            }

            true
        }
        navView.setOnNavigationItemSelectedListener(selectionListener)

        // Needed for dynamic translation of the top bar
        navController.addOnDestinationChangedListener { controller, destination, arguments ->
            Logger.v("Navigation", destination.toString())

            val translationId = when (destination.id) {
                R.id.navigation_activity -> R.string.main_tab_activity
                R.id.activityDetailFragment -> R.string.main_tab_activity
                R.id.navigation_packs -> getString(R.string.advanced_section_header_packs)
                R.id.packDetailFragment -> R.string.advanced_section_header_packs
                R.id.advancedFragment -> R.string.main_tab_advanced
                R.id.userDeniedFragment -> R.string.userdenied_section_header
                R.id.settingsNetworksFragment -> R.string.networks_section_header
                R.id.networksDetailFragment -> R.string.networks_section_header
                R.id.appsFragment -> R.string.apps_section_header
                R.id.navigation_settings -> R.string.main_tab_settings
                R.id.navigation_settings_account -> R.string.account_action_my_account
                R.id.settingsLogoutFragment -> R.string.account_header_logout
                R.id.settingsAppFragment -> R.string.app_settings_section_header
                R.id.leasesFragment -> R.string.account_action_devices
                else -> null
            }
            toolbar.title = translationId?.let {
                if (it is Int) getString(it)
                else it.toString()
            } ?: run { toolbar.title }
        }

        intent?.let {
            handleIntent(it)
        }
    }

    private fun setupEvents() {
        accountVM = ViewModelProvider(app()).get(AccountViewModel::class.java)
        tunnelVM = ViewModelProvider(app()).get(TunnelViewModel::class.java)
        settingsVM = ViewModelProvider(app()).get(SettingsViewModel::class.java)
        statsVM = ViewModelProvider(app()).get(StatsViewModel::class.java)
        blockaRepoVM = ViewModelProvider(app()).get(BlockaRepoViewModel::class.java)
        activationVM = ViewModelProvider(this).get(ActivationViewModel::class.java)

        var expiredDialogShown = false
        activationVM.state.observe(this, Observer { state ->
            when (state) {
                ActivationViewModel.ActivationState.JUST_PURCHASED -> {
                    accountVM.refreshAccount()
                    val nav = findNavController(R.id.nav_host_fragment)
                    nav.navigateUp()
                }
                ActivationViewModel.ActivationState.JUST_ACTIVATED -> {
                    val fragment = ActivatedFragment.newInstance()
                    fragment.show(supportFragmentManager, null)
                }
                ActivationViewModel.ActivationState.JUST_EXPIRED -> {
                    if (!expiredDialogShown) {
                        expiredDialogShown = true
                        AlertDialogService.showAlert(getString(R.string.error_vpn_expired),
                            title = getString(R.string.alert_vpn_expired_header),
                            onDismiss = {
                                lifecycleScope.launch {
                                    activationVM.setInformedUserAboutExpiration()
                                    NotificationService.cancel(ExpiredNotification())
                                    tunnelVM.clearLease()
                                    accountVM.refreshAccount()
                                }
                            })
                    }
                }
            }
        })

        accountVM.accountExpiration.observe(this, Observer { activeUntil ->
            activationVM.setExpiration(activeUntil)
        })

        tunnelVM.tunnelStatus.observe(this, Observer { status ->
            if (status.active) {
                val firstTime = !(settingsVM.syncableConfig.value?.notFirstRun ?: true)
                if (firstTime) {
                    settingsVM.setFirstTimeSeen()
                    val fragment = FirstTimeFragment.newInstance()
                    fragment.show(supportFragmentManager, null)
                }
            }
        })

        lifecycleScope.launch {
            Repos.account.hackyAccount()
            onPaymentSuccessful_UpdateAccount()
        }
    }

    private suspend fun onPaymentSuccessful_UpdateAccount() {
        paymentRepo.successfulPurchasesHot
        .collect {
            if (it != null) {
                Logger.v("Payment", "Received account after payment")
                accountVM.restoreAccount(it.first.id)
                if (it.first.isActive()) {
                    delay(1000)
                    sheet.showSheet(Sheet.Activated)
                }
            }
        }
    }

    private fun setInsets(toolbar: Toolbar) {
        val root = findViewById<ViewGroup>(R.id.container)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            topInset = insets.top

            // Apply the insets as a margin to the view. Here the system is setting
            // only the bottom, left, and right dimensions, but apply whichever insets are
            // appropriate to your layout. You can also update the view padding
            // if that's more appropriate.
            view.layoutParams =  (view.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = insets.left
                bottomMargin = insets.bottom
                rightMargin = insets.right
            }

            // Also move down the toolbar
            toolbar.layoutParams = (toolbar.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = insets.top
            }

            // Return CONSUMED if you don't want want the window insets to keep being
            // passed down to descendant views.
            WindowInsetsCompat.CONSUMED
        }
    }

    private var topInset = 0
    private val actionBarHeight: Int
        get() = theme.obtainStyledAttributes(intArrayOf(android.R.attr.actionBarSize))
        .let { attrs -> attrs.getDimension(0, 0F).toInt().also { attrs.recycle() } }

    private fun setFragmentInset(fragment: ViewGroup, shouldInset: Boolean) {
        fragment.layoutParams = (fragment.layoutParams as LinearLayout.LayoutParams).apply {
            topMargin = if (shouldInset) topInset + actionBarHeight else 0
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        lifecycleScope.launch {
            delay(1000) // So user sees the transition
            intent.extras?.getString(ACTION)?.let { action ->
                when (action) {
                    ACC_MANAGE -> {
                        accountVM.account.value?.let { account ->
                            Logger.w("MainActivity", "Navigating to account manage screen")
                            val nav = findNavController(R.id.nav_host_fragment)
                            nav.navigate(R.id.navigation_home)
                            nav.navigate(
                                HomeFragmentDirections.actionNavigationHomeToWebFragment(
                                    Links.manageSubscriptions(account.id), getString(R.string.universal_action_upgrade)
                                ))
                        } ?: Logger.e("MainActivity", "No account while received action $ACC_MANAGE")
                    }
                }
            }
        }
    }

    private var lastOnResume = 0L
    override fun onResume() {
        super.onResume()
        Repos.stage.onForeground()

        // Avoid multiple consecutive quick onResume events
        if (lastOnResume + 5 * 1000 > now()) return
        lastOnResume = now()

        Logger.w("MainActivity", "onResume: $this")
        tunnelVM.refreshStatus()
        blockaRepoVM.maybeRefreshRepo()
        lifecycleScope.launch {
            statsVM.refresh()
        }

        accountVM.maybeRefreshAccount()
    }

    override fun onPause() {
        Logger.w("MainActivity", "onPause: $this")
        super.onPause()
        Repos.stage.onBackground()
        tunnelVM.goToBackground()
    }

    override fun onDestroy() {
        Logger.w("MainActivity", "onDestroy: $this")
        super.onDestroy()
        Repos.stage.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (WebService.goBack()) return true
        val navController = findNavController(R.id.nav_host_fragment)
        return navController.navigateUp()
    }

    override fun onBackPressed() {
        if (WebService.goBack()) return
        super.onBackPressed()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        VpnPermissionService.resultReturned(resultCode)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        NetworkMonitorPermissionService.resultReturned(grantResults)
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val navController = findNavController(R.id.nav_host_fragment)
        SettingsNavigation.handle(navController, pref.key, accountVM.account.value?.id)
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.help_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.help_help -> {
                val fragment = HelpFragment.newInstance()
                fragment.show(supportFragmentManager, null)
            }
            R.id.help_logs -> LogService.showLog()
            R.id.help_sharelog -> LogService.shareLog()
            R.id.help_marklog -> LogService.markLog()
            R.id.help_settings -> {
                val nav = findNavController(R.id.nav_host_fragment)
                nav.navigate(R.id.navigation_settings)
                nav.navigate(
                    SettingsFragmentDirections.actionNavigationSettingsToSettingsAppFragment()
                )
            }
//            R.id.help_debug -> AlertDialogService.showChoiceAlert(
//                choices = listOf(
//                    "> Test app crash (it should restart)" to {
//                        throw BlokadaException("This is a test of a fatal crash")
//                    }
//                ),
//                title = "Debug Tools"
//            )
            else -> return false
        }
        return true
    }

    companion object {
        val ACTION = "action"
    }

}