package cl.shoppersa.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import cl.shoppersa.R
import cl.shoppersa.api.TokenManager
import android.content.Intent
import cl.shoppersa.ui.LoginActivity
import cl.shoppersa.databinding.ActivityHomeBinding
import cl.shoppersa.ui.fragments.CartFragment
import cl.shoppersa.ui.fragments.ProductsFragment
import cl.shoppersa.ui.fragments.ProfileFragment

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.topAppBar)
        binding.topAppBar.logo =
            ResourcesCompat.getDrawable(resources, R.drawable.logo_shoppersa, theme)
        supportActionBar?.title = ""

        // Acciones del menú: Perfil y Cerrar sesión
        binding.topAppBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_profile -> {
                    val current = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
                    if (current !is ProfileFragment) {
                        supportFragmentManager.beginTransaction()
                            .replace(R.id.fragmentContainer, ProfileFragment())
                            .addToBackStack(null)
                            .commit()
                    }
                    true
                }
                R.id.action_logout -> {
                    TokenManager(this).clear()
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                    true
                }
                else -> false
            }
        }

        val initialTabId = when (intent.getStringExtra("dest")) {
            "cart"    -> R.id.menu_cart
            "profile" -> R.id.menu_profile
            "orders"  -> R.id.menu_orders
            else      -> R.id.menu_products
        }

        if (savedInstanceState == null) {
            replaceFor(initialTabId, commitNow = true)
            binding.bottomNav.selectedItemId = initialTabId
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            replaceFor(item.itemId, commitNow = false)
            true
        }
        binding.bottomNav.setOnItemReselectedListener { }
    }

    // Maneja intents entrantes cuando la actividad ya está viva
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        // Solo actuar si viene el extra "dest"
        val dest = intent.getStringExtra("dest") ?: return

        val targetId = when (dest) {
            "cart"    -> R.id.menu_cart
            "profile" -> R.id.menu_profile
            "orders"  -> R.id.menu_orders
            else      -> R.id.menu_products
        }

        binding.bottomNav.selectedItemId = targetId
        replaceFor(targetId, commitNow = false)
    }

    private fun replaceFor(itemId: Int, commitNow: Boolean) {
        val fragment: Fragment = when (itemId) {
            R.id.menu_cart    -> CartFragment()
            R.id.menu_profile -> ProfileFragment()
            R.id.menu_orders  -> cl.shoppersa.ui.fragments.OrdersUserFragment()
            else              -> ProductsFragment()
        }

        val current = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (current != null && current::class == fragment::class) return

        val tx = supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
        if (commitNow) tx.commitNow() else tx.commit()
    }
}
