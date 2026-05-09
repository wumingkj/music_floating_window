package com.wuming.musicFW.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    val fragments = arrayOfNulls<Fragment>(3)

    override fun getItemCount() = 3

    override fun createFragment(pos: Int): Fragment {
        val f: Fragment = when (pos) {
            0 -> MusicFragment()
            1 -> LogFragment()
            2 -> SettingsFragment()
            else -> MusicFragment()
        }
        fragments[pos] = f
        return f
    }

    fun getMusic() = fragments[0] as? MusicFragment
    fun getLog() = fragments[1] as? LogFragment
    fun getSettings() = fragments[2] as? SettingsFragment
}
