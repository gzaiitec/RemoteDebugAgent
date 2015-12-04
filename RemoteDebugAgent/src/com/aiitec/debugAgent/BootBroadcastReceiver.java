package com.aiitec.debugAgent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 
 * @author dev Aiitec.inc
 * {@link www.aiitec.com}
 *
 */
public class BootBroadcastReceiver extends BroadcastReceiver {
	// 重写onReceive方法
	@Override
	public void onReceive(Context context, Intent intent) {
		// 后边的XXX.class就是要启动的服务
		Intent service = new Intent(context, AgentService.class);
		context.startService(service);
		
		Log.i("TAG", "开机自动服务自动启动.....");
		
	}

}
