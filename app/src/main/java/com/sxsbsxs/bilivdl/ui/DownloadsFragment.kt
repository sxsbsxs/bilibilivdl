package com.sxsbsxs.bilivdl.ui

import android.app.DownloadManager
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.databinding.FragmentDownloadBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray

class DownloadsFragment:Fragment(R.layout.fragment_download) {
    private var _binding:FragmentDownloadBinding?=null
    private val b get()=_binding!!
    private val prefs by lazy{requireContext().getSharedPreferences("download_queue",Context.MODE_PRIVATE)}
    private val manager by lazy{requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager}
    override fun onViewCreated(view:View,savedInstanceState:Bundle?){
        super.onViewCreated(view,savedInstanceState);_binding=FragmentDownloadBinding.bind(view);refreshTasks()
        viewLifecycleOwner.lifecycleScope.launch{while(true){delay(1000);if(_binding!=null)refreshTasks()}}
    }
    private fun refreshTasks(){
        val arr=JSONArray(prefs.getString("tasks","[]"))
        if(arr.length()==0){b.downloadTasks.text="还没有下载任务\n打开任意视频，在播放页点击“下载”即可添加任务";return}
        val lines=mutableListOf<String>()
        for(i in 0 until arr.length()){
            val item=arr.optJSONObject(i)?:continue
            val id=item.optLong("id");val title=item.optString("title","视频");val quality=item.optString("quality","")
            val cursor=manager.query(DownloadManager.Query().setFilterById(id))
            cursor.use{
                if(it==null||!it.moveToFirst()){lines.add("• $title\n  任务记录不存在");return@use}
                val state=it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done=it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total=it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val label=when(state){
                    DownloadManager.STATUS_PENDING->"等待下载"
                    DownloadManager.STATUS_RUNNING->if(total>0)"下载中 · ${done*100/total}%" else "正在下载"
                    DownloadManager.STATUS_PAUSED->"已暂停"
                    DownloadManager.STATUS_SUCCESSFUL->"已完成 · Movies/BiliVdl"
                    DownloadManager.STATUS_FAILED->"下载失败（错误码 ${it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))}）"
                    else->"处理中"
                }
                lines.add("• $title（$quality）\n  $label")
            }
        }
        b.downloadTasks.text=lines.joinToString("\n\n")
    }
    override fun onDestroyView(){_binding=null;super.onDestroyView()}
}
