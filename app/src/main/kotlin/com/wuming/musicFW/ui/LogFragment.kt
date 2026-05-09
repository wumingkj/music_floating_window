package com.wuming.musicFW.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.wuming.musicFW.R
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class LogFragment : Fragment() {
    lateinit var debugLog: TextView
    lateinit var statusTv: TextView
    private lateinit var btnCopy: Button
    private lateinit var btnSave: Button
    private lateinit var btnClear: Button

    override fun onCreateView(inf: LayoutInflater, vg: ViewGroup?, si: Bundle?): View {
        val v = inf.inflate(R.layout.fragment_log, vg, false)
        debugLog = v.findViewById(R.id.debugLogTextView)
        statusTv = v.findViewById(R.id.statusTextView)
        btnCopy = v.findViewById(R.id.btnCopyLog)
        btnSave = v.findViewById(R.id.btnSaveLog)
        btnClear = v.findViewById(R.id.btnClearLog)

        btnCopy.setOnClickListener { copyLog() }
        btnSave.setOnClickListener { saveLog() }
        btnClear.setOnClickListener { debugLog.text = ""; toast("日志已清空") }
        return v
    }

    private fun copyLog() {
        val text = debugLog.text.toString()
        if (text.isEmpty()) { toast("日志为空"); return }
        val clip = ClipData.newPlainText("MusicFW Log", text)
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(clip)
        toast("日志已复制到剪贴板")
    }

    private fun saveLog() {
        val text = debugLog.text.toString()
        if (text.isEmpty()) { toast("日志为空"); return }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "MusicFW_Log_$ts.txt"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 MediaStore
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = requireContext().contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                uri?.let {
                    requireContext().contentResolver.openOutputStream(it)?.use { os ->
                        os.write(text.toByteArray())
                    }
                    toast("已保存到 下载/$fileName")
                }
            } else {
                // 低版本直接写文件
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val file = File(dir, fileName)
                FileOutputStream(file).use { it.write(text.toByteArray()) }
                toast("已保存到 下载/$fileName")
            }
        } catch (e: Exception) {
            toast("保存失败: ${e.message}")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }
}
