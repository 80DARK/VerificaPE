package com.notibridge.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import com.notibridge.app.db.AppDatabase
import com.notibridge.app.ui.AppsFragment
import com.notibridge.app.ui.HomeFragment
import com.notibridge.app.ui.HistoryFragment
import com.notibridge.app.ui.PairFragment
import com.notibridge.app.ui.SettingsFragment
import com.notibridge.app.ui.SpamFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Actividad principal con el MENÚ LATERAL IZQUIERDO VERTICAL Y RETRÁCTIL
 * (DrawerLayout + NavigationView): se despliega desde el borde izquierdo
 * y se recoge deslizando, con el botón ☰ o tocando fuera del menú.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var drawer: DrawerLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var navView: NavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawer = findViewById(R.id.drawer_layout)
        toolbar = findViewById(R.id.toolbar)
        navView = findViewById(R.id.nav_view)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { toggleDrawer() }

        navView.setNavigationItemSelectedListener { item ->
            navigateTo(item.itemId)
            drawer.closeDrawer(GravityCompat.START)
            true
        }

        // Migración v1.x → v1.2: convierte el emparejamiento único antiguo
        // al formato multi-canal sin perderlo.
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.get(applicationContext)
            val legacy = db.pairing().getAll().firstOrNull()
            SecureStore.migrateLegacy(applicationContext, legacy?.channelId)
        }

        // Tras actualizar la app, Android suele DESENGANCHAR el lector de
        // notificaciones aunque el permiso siga activo. Al abrir VerificaPE
        // forzamos el reenganche (componente off/on + requestRebind).
        if (NotiAccess.isEnabled(this)) {
            ListenerKick.kick(this)
        }

        // Guardián: servicio en primer plano para que el sistema no mate
        // el proceso (y el lector) al cerrar la app o bloquear el teléfono.
        if (Prefs.get(this).getBoolean(Prefs.LISTENING, true)) {
            GuardianService.start(this)
        }

        // Reenviar notificaciones que quedaron pendientes (red bloqueada,
        // servidor caído, etc.): la cola se vacía al abrir la app.
        lifecycleScope.launch(Dispatchers.IO) {
            Sender.reintentarPendientes(applicationContext)
        }

        // Android 13+: pedir permiso para mostrar las notificaciones
        // (la persistente del Guardián y la de "Probar lector")
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100
            )
        }

        if (savedInstanceState == null) navigateTo(R.id.nav_home)
    }

    private fun toggleDrawer() {
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START)
        } else {
            drawer.openDrawer(GravityCompat.START)
        }
    }

    /** Cambia de sección usando el menú (también lo llaman los botones internos). */
    fun navigateTo(menuId: Int) {
        val (fragment: Fragment, titleRes: Int) = when (menuId) {
            R.id.nav_home -> HomeFragment() to R.string.menu_home
            R.id.nav_spam -> SpamFragment() to R.string.menu_spam
            R.id.nav_apps -> AppsFragment() to R.string.menu_apps
            R.id.nav_pair -> PairFragment() to R.string.menu_pair
            R.id.nav_history -> HistoryFragment() to R.string.menu_history
            R.id.nav_settings -> SettingsFragment() to R.string.menu_settings
            else -> HomeFragment() to R.string.menu_home
        }
        navView.setCheckedItem(menuId)
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
        toolbar.setTitle(titleRes)
    }
}
