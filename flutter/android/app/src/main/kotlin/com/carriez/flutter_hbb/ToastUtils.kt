package com.carriez.flutter_hbb

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast

/**
 * 自定义Toast工具类
 */
object ToastUtils {
    
    /**
     * 显示"已就绪"提示Toast
     */
    fun showReadyToast(context: Context) {
        try {
            val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            val layout: View = inflater.inflate(R.layout.toast_high_performance, null)
            
            val toast = Toast(context)
            // 设置为屏幕底部
            toast.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 100)
            toast.duration = Toast.LENGTH_SHORT
            toast.view = layout
            toast.show()
        } catch (e: Exception) {
            // 如果自定义Toast失败，回退到标准Toast
            Toast.makeText(context, "已就绪", Toast.LENGTH_SHORT).show()
        }
    }
} 