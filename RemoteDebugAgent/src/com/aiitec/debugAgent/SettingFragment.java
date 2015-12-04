package com.aiitec.debugAgent;

import java.net.ServerSocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.widget.Toast;

/**
 * 
 * @author dev Aiitec.inc
 * {@link www.aiitec.com}
 *
 */
public class SettingFragment extends PreferenceFragment {
	private static final String TAG = "SettingFragment";
	Handler handler = new Handler();
	private String adbPort, ip;
	private EditTextPreference editTextPref;
	private AgentService msgService;
	ServiceConnection conn = new ServiceConnection() {

		@Override
		public void onServiceDisconnected(ComponentName name) {
			msgService = null;
		}

		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			// 返回一个MsgService对象
			Log.i(TAG, "onServiceConnected:ServiceConnection");
			msgService = ((AgentService.MsgBinder) service).getService();
			

		}
	};
	private CheckBoxPreference checkboxDebugEnable;
	private CheckBoxPreference checkboxRemoteDebugEnable;
	private BroadcastReceiver receiver;
	private boolean hasRight;
	private CheckBoxPreference checkboxInvokedable;
	private EditTextPreference prefServerIp;
	private EditTextPreference prefServerPort;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		this.setPreferenceScreen(createPreferenceScreen());

	}

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);
		Intent intent = new Intent(AgentService.ACTION);
		activity.startService(intent);
		
		activity.bindService(intent, conn, Context.BIND_AUTO_CREATE);
		IntentFilter filter = new IntentFilter();
		filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
		filter.addAction(AgentService.BROADCAST_CONNECTION_CHANGED);
		receiver = new BroadcastReceiver() {

			@Override
			public void onReceive(Context context, Intent intent) {
				
				String action = intent.getAction();
				if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
					Log.d(TAG, "网络状态改变");
					ConnectivityManager c = (ConnectivityManager) getActivity()
							.getSystemService(Context.CONNECTIVITY_SERVICE);
					NetworkInfo info = c.getActiveNetworkInfo();
					if(info==null){
						editTextPref.setSummary("未能获取网络连接信息");
					}else if (info.getType() == ConnectivityManager.TYPE_WIFI) {
						updateIp(adbPort);
					}

				}else if(action.equals(AgentService.BROADCAST_CONNECTION_CHANGED)){
					handler.post(new Runnable(){
						public void run(){
							update() ;
							checkboxDebugEnable.setEnabled(true);
//							updateRemoteCheckBox(intent.getBooleanExtra("isOk", true));
						}
					});
					
				}

			}
		};
		activity.registerReceiver(receiver, filter);
		//判断是否有修改的权限
		PackageManager pm = this.getActivity().getPackageManager();
		hasRight = (PackageManager.PERMISSION_GRANTED)==pm.checkPermission("android.permission.WRITE_SECURE_SETTINGS", this.getActivity().getPackageName());

		
				
	}

	@Override
	public void onStart() {
		super.onStart();
		
		observe() ;
		if(!hasRight){
//			checkboxDebugEnable.setEnabled(false);
			checkboxDebugEnable.setSummary("未获得系统权限的设备需要跳转到'开发人员选项'中设置");
			checkboxInvokedable.setEnabled(false);
			checkboxInvokedable.setSummary("未获得系统权限的设备不能被网络控制启停ADB");
		}
		handler.postDelayed(new Runnable(){
			public void run(){
				refreshRight();
				update();					
			}
		},1000);
//		handler.post(new Runnable(){
//			public void run(){
//
//				
//			}
//		});
//		update();
	}

	public void refreshRight() {
		try{
			this.msgService.suExec("");
			this.hasRight = true;
			this.update();
			checkboxInvokedable.setSummary("允许未经用户确认就能被远程启用远程调试");
			checkboxInvokedable.setEnabled(false);//暂时禁用
		}catch(Throwable e){
			checkboxInvokedable.setSummary("未获得系统权限的设备不能被网络控制启停ADB");
			checkboxInvokedable.setEnabled(false);
			Log.d(TAG, "获取权限失败",e);
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		this.getActivity().unbindService(this.conn);
		this.getActivity().unregisterReceiver(receiver);
	}

	private PreferenceScreen createPreferenceScreen() {
		getPreferenceManager().setSharedPreferencesName("remoteDebug");
		PreferenceScreen root = getPreferenceManager().createPreferenceScreen(
				this.getActivity());
		// Inline preferences
		PreferenceCategory inlinePrefCat1 = new PreferenceCategory(
				this.getActivity());
		inlinePrefCat1.setTitle("基本设置");
		root.addPreference(inlinePrefCat1);
		
		checkboxDebugEnable = new CheckBoxPreference(
				this.getActivity());
		checkboxDebugEnable.setTitle("启动ADB（包括USB调试）");
		checkboxDebugEnable.setSummary("未启动");
		inlinePrefCat1.addPreference(checkboxDebugEnable);
		checkboxDebugEnable.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {

			@Override
			public boolean onPreferenceChange(Preference preference,
					Object newValue) {
				
				if(hasRight){
					try{
						Settings.Secure.putInt(getActivity().getContentResolver(),
								android.provider.Settings.Global.ADB_ENABLED, newValue.toString().equalsIgnoreCase("TRUE")?1:0);
						checkboxDebugEnable.setEnabled(false);
					}catch(Throwable e){
						Log.i(TAG, "设置ADB出错",e);
						SettingFragment.this.update();
					}
					
					sendSettingChangedBoradcast();
					
					return true;
				}
				if(newValue.toString().equalsIgnoreCase("TRUE")){
					toastLong("没有权限启用ADB，请到开发人员设置界面设置勾选'USB调试'");					
				}else{
					toastLong("没有权限停止ADB，请到开发人员设置界面设置取消勾选'USB调试'");
				}
				Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
				startActivity(intent);
				return false;
				
			}
		});
		
		CheckBoxPreference checkboxShakge = new CheckBoxPreference(
				this.getActivity());
		checkboxShakge.setTitle("通知消息带振动");
		checkboxShakge.setKey("isShakeEnable");
		checkboxShakge.setDefaultValue(true);
		checkboxShakge.setSummary("如果有相关的通知则增加振动");
		inlinePrefCat1.addPreference(checkboxShakge);
		
		
		PreferenceCategory inlinePrefCat = new PreferenceCategory(
				this.getActivity());
		inlinePrefCat.setTitle("远程调试");
		root.addPreference(inlinePrefCat);
		
		
		checkboxRemoteDebugEnable = new CheckBoxPreference(
				this.getActivity());
		checkboxRemoteDebugEnable.setKey("remoteDebugEnable");
		checkboxRemoteDebugEnable.setTitle("使用远程调试");
		checkboxRemoteDebugEnable.setSummary("未启用");
		inlinePrefCat.addPreference(checkboxRemoteDebugEnable);
		checkboxRemoteDebugEnable.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {

			@Override
			public boolean onPreferenceChange(Preference preference,
					Object newValue) {
				if(newValue.toString().equalsIgnoreCase("TRUE")){
					checkboxRemoteDebugEnable.setEnabled(false);
					checkboxRemoteDebugEnable.setSummary("处理中……");
					SettingFragment.this.asynStartRemoteDebug();
				}else{
					checkboxRemoteDebugEnable.setEnabled(false);
					checkboxRemoteDebugEnable.setSummary("处理中……");
					sendSettingChangedBoradcast();
				}
				return true;
			}
		});

		checkboxInvokedable = new CheckBoxPreference(this.getActivity());
		checkboxInvokedable.setKey("adbdInvokedable");
		checkboxInvokedable.setTitle("允许被网络唤醒远程调试");
		checkboxInvokedable.setSummary("允许未经用户确认就能被远程启用远程调试");
		checkboxInvokedable.setChecked(true);
		inlinePrefCat.addPreference(checkboxInvokedable);

		SharedPreferences sp = inlinePrefCat.getPreferenceManager()
				.getSharedPreferences();
		adbPort = sp.getString("adbdPort", "4555");
		editTextPref = new EditTextPreference(this.getActivity());
		editTextPref.setDialogTitle("ADBD端口");
		editTextPref.setKey("adbdPort");
		editTextPref.setTitle("ADBD端口");
		editTextPref.getEditText().setInputType(
				InputType.TYPE_NUMBER_FLAG_DECIMAL);
		editTextPref
				.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {

					@Override
					public boolean onPreferenceChange(Preference preference,
							Object newValue) {
						if (newValue == null) {
							return false;
						}
						if (!newValue.toString().matches("\\d{4,5}")) {
							alert("请输入1001~65534范围的端口号");
							return false;
						}
						int x = Integer.parseInt(newValue.toString());
						if (x > 1000 && x < 65535) {
							adbPort = newValue.toString();
							updateIp(adbPort);
							sendSettingChangedBoradcast();
							return true;
						} else {
							alert("请输入1001~65534范围的端口号");
							return false;
						}

					}
				});
		updateIp(sp.getString("adbdPort", "4555"));
		inlinePrefCat.addPreference(editTextPref);
		
		prefServerIp = new EditTextPreference(this.getActivity());
		prefServerIp.setDialogTitle("服务器IP");
		prefServerIp.setKey("serverIp");
		prefServerIp.setDefaultValue(AgentService.SERVER_IP);
		prefServerIp.setTitle("服务器IP");
		prefServerIp.getEditText().setInputType(
				InputType.TYPE_CLASS_TEXT);
		prefServerIp
				.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {

					@Override
					public boolean onPreferenceChange(Preference preference,
							Object newValue) {
						if (newValue == null) {
							return false;
						}
						if (!newValue.toString().matches("[0-9a-zA-Z._-]{3,64}")) {
							alert("请输入正确的URL或者IP格式格式");
							return false;
						}
						updateServerIp(newValue.toString());
						sendSettingChangedBoradcast();
						return true;
					}
				});
		this.updateServerIp(sp.getString("serverIp", AgentService.SERVER_IP));
		inlinePrefCat.addPreference(prefServerIp);
		
		prefServerPort = new EditTextPreference(this.getActivity());
		prefServerPort.setDialogTitle("服务器端口");
		prefServerPort.setDefaultValue("4555");
		prefServerPort.setKey("serverPort");
		prefServerPort.setTitle("服务器端口");
		prefServerPort.getEditText().setInputType(
				InputType.TYPE_CLASS_NUMBER);
		prefServerPort
				.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {

					@Override
					public boolean onPreferenceChange(Preference preference,
							Object newValue) {
						if (newValue == null) {
							return false;
						}
						if (!newValue.toString().matches("\\d{4,5}")) {
							alert("请输入1001~65534范围的端口号");
							return false;
						}
						int x = Integer.parseInt(newValue.toString());
						if (x > 1000 && x < 65535) {
							updateServerPort(newValue.toString());
							sendSettingChangedBoradcast();
							return true;
						} else {
							alert("请输入1001~65534范围的端口号");
							return false;
						}

					}
				});

		this.updateServerPort(sp.getString("serverPort", "4555"));
		inlinePrefCat.addPreference(prefServerPort);
		
		return root;
	}

	protected void sendSettingChangedBoradcast() {
		Log.i(TAG, "发送了广播BROADCAST_SETTING_CHANGED");
		getActivity().sendBroadcast(new Intent(AgentService.BROADCAST_SETTING_CHANGED));
	}

	private void observe() {
		ContentResolver resolver = this.getActivity().getContentResolver();
		resolver.registerContentObserver(Settings.Secure
				.getUriFor(android.provider.Settings.Global.ADB_ENABLED),
				false, new ContentObserver(handler) {
					@Override
					public void onChange(boolean selfChange) {
						update();
						
					}
				});
	}
	  
	
	  
	private   void  update() {  
	    ContentResolver resolver = this.getActivity().getContentResolver();  
	    boolean mAdbEnabled = Settings.Secure.getInt(resolver,  
	    		android.provider.Settings.Global.ADB_ENABLED, 0 ) !=  0 ;
	   
	    String desc;
	    if(this.hasRight){
	    	desc = mAdbEnabled?"已启动":"未启动";
	    }else{
	    	desc = mAdbEnabled?"已启动":"未启动，请点击进入开发人员选项中启动USB调试";
	    }
	    checkboxDebugEnable.setChecked(mAdbEnabled);
	    checkboxDebugEnable.setSummary(desc);
	    checkboxDebugEnable.setEnabled(true);
	    checkboxRemoteDebugEnable.setEnabled(true);
		if(this.msgService.isServerConnected()&&checkboxRemoteDebugEnable.isChecked()&&mAdbEnabled){
			checkboxRemoteDebugEnable.setSummary("已经连接到服务器，可以开始远程调试");
		}else if(checkboxRemoteDebugEnable.isChecked()&&!mAdbEnabled){
			checkboxRemoteDebugEnable.setSummary("未启用ADB，远程调试未启动");
		}else if(mAdbEnabled&&checkboxRemoteDebugEnable.isChecked()&&this.msgService.isServerConnecting()){
			checkboxRemoteDebugEnable.setSummary("正在尝试连接服务器……");
		}else if(checkboxRemoteDebugEnable.isChecked()&&this.msgService.isServerConnecting()&&!this.msgService.isServerConnected()){
			checkboxRemoteDebugEnable.setSummary("服务器无响应，也并没有启动ADB");
		}else if(checkboxRemoteDebugEnable.isChecked()&&!this.msgService.isHasNetwork()){
			checkboxRemoteDebugEnable.setSummary("没有网络，远程调试不可用");
		}else{
			checkboxRemoteDebugEnable.setSummary("未启用");
		}
//	    updateRemoteCheckBox(this.msgService.isServerConnected());
	}  
	private void updateServerIp(String ip) {
		prefServerIp.setSummary(ip);
//		prefServerIp.setEnabled(checkboxRemoteDebugEnable.isChecked()); //允许用户在连接前修改配置
		
	}
	private void updateServerPort(String port) {
		prefServerPort.setSummary(port);
//		prefServerPort.setEnabled(checkboxRemoteDebugEnable.isChecked());
	}
	private void updateIp(String port) {
		WifiManager wifiManager = (WifiManager) this.getActivity()
				.getSystemService(Context.WIFI_SERVICE);
		if (!wifiManager.isWifiEnabled()) {
			ip = "未启用WIFI";
		} else {
			ip = intToip(wifiManager.getConnectionInfo().getIpAddress());
		}
		editTextPref.setSummary(ip + ":" + this.adbPort);
	}

	private String intToip(int i) {
		return (i & 0xFF) + "." + ((i >> 8) & 0xff) + "." + ((i >> 16) & 0xff)
				+ "." + ((i >> 24) & 0xff);
	}

	private void alert(final String msg) {
		handler.post(new Runnable() {
			public void run() {
				new AlertDialog.Builder(SettingFragment.this.getActivity()).setTitle(msg)
				.setPositiveButton("确定", null).create().show();
			}
		});
		
	}
	private String toastLong(final String msg) {
		handler.post(new Runnable() {
			public void run() {
				Toast.makeText(SettingFragment.this.getActivity(), msg, Toast.LENGTH_LONG)
						.show();
			}
		});
		return msg;

	}
	
	private void asynStartRemoteDebug() {
		new Thread(){
			public void run(){
				try {
					if(!hasRight){ //没有权限，就无法app自己启动无线调试，就需要检测
						try{
							ServerSocket server = new ServerSocket(Integer.parseInt(adbPort));
							server.close();
							//没有被占用，需要提示
							Intent intent = new Intent();
							intent.setClass(SettingFragment.this.getActivity(), NetworkAdbActivity.class);
							startActivity(intent);
							
						}catch(Throwable e){//被占用就会有异常，可以说是好事,不用提示手工启动网络ADB，只需要启动服务器连接就可以
							sendSettingChangedBoradcast();
						}
					}else{
						sendSettingChangedBoradcast();
					}
					
				} catch (Exception e) {
					alert(e.getMessage());
				}
				
			}
		}.start();
		
	}
	

	private void asynEnableCheckBox(final CheckBoxPreference cb,final String summery) {
		handler.postDelayed(new Runnable(){
			public void run(){
				cb.setSummary(summery);
				cb.setEnabled(true);
			}
		},100);
	}

	private void updateRemoteCheckBox(boolean isServerConnected) {
		checkboxRemoteDebugEnable.setEnabled(true);
		if(isServerConnected&&checkboxRemoteDebugEnable.isChecked()){
			checkboxRemoteDebugEnable.setSummary("已经连接到服务器，可以开始远程调试");
		}else if(checkboxRemoteDebugEnable.isChecked()){
			checkboxRemoteDebugEnable.setSummary("服务器无响应，正在尝试连接服务器");
		}else{
			checkboxRemoteDebugEnable.setSummary("未启用");
		}
	}
	

}
