package cl.shoppersa.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import cl.shoppersa.R
import cl.shoppersa.ui.LoginActivity
import cl.shoppersa.api.TokenManager
import cl.shoppersa.databinding.ActivityAdminBinding
import cl.shoppersa.ui.fragments.OrdersAdminFragment
import cl.shoppersa.ui.fragments.ProductAdminFragment
import cl.shoppersa.ui.fragments.UsersAdminFragment
import cl.shoppersa.ui.fragments.AddProductFragment
import cl.shoppersa.ui.fragments.ProfileFragment

class AdminActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAdminBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.topAppBar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        // Menu acciones: Perfil y Cerrar sesión
        binding.topAppBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_profile -> {
                    // Abre el perfil dentro del contenedor del Admin
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
                    // Limpia token y vuelve a Login
                    TokenManager(this).clear()
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                    true
                }
                else -> false
            }
        }

        if (savedInstanceState == null) {
            replaceFor(R.id.menu_admin_products, commitNow = true)
            binding.bottomNav.selectedItemId = R.id.menu_admin_products
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            // Limpiar backstack para garantizar volver al listado
            supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            replaceFor(item.itemId, commitNow = false)
            true
        }
        binding.bottomNav.setOnItemReselectedListener { item ->
            supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            replaceFor(item.itemId, commitNow = false)
        }
    }

    private fun replaceFor(itemId: Int, commitNow: Boolean) {
        val fragment: Fragment = when (itemId) {
            R.id.menu_admin_products -> ProductAdminFragment()
            R.id.menu_admin_users -> UsersAdminFragment()
            R.id.menu_admin_orders -> OrdersAdminFragment()
            R.id.menu_admin_profile -> ProfileFragment()
            R.id.menu_admin_create -> AddProductFragment()
            else -> ProductAdminFragment()
        }
        val current = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (current != null && current::class == fragment::class) return

        val tx = supportFragmentManager.beginTransaction().replace(R.id.fragmentContainer, fragment)
        if (commitNow) tx.commitNow() else tx.commit()
    }
}