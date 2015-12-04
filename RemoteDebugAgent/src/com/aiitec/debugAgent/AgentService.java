package com.aiitec.debugAgent;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;




/**
 * Connect to server , report device info and accept remote debug request. 
 * 
 * @author dev Aiitec.inc
 * {@link www.aiitec.com}
 * 
 * 
 */
public class AgentService extends Service {
	public static final String SERVER_IP = "122.138.45.6";
	private static final int retryInterval = 5000;
	private static final String TAG = "AgentService";
	public static final String ACTION = "com.aiitec.debugAgent.AgentService";
	public static final String BROADCAST_CONNECTION_CHANGED = "com.aiitec.debugAgent.AgentService.CONNECTION_CHANGED",BROADCAST_SETTING_CHANGED = "com.aiitec.debugAgent.AgentService.SETTING_CHANGED";
	static Handler handler = new Handler();
	private Socket serverSocket;
	private boolean isConnected = false,isFirstConnect = true;
	private boolean hasRight;
	private BroadcastReceiver boardcastReciever;
	private int heartbeatInterval = 30000;
	private Thread serverSocketThread;
	private boolean hasNetwork;
	private String ip;

	@Override
	public IBinder onBind(Intent intent) {
		Log.v(TAG, "AgentService onBind");

		return new MsgBinder();
	}

	@Override
	public void onCreate() {
		Log.v(TAG, "AgentService onCreate");
		super.onCreate();
		observe();
		PackageManager pm = this.getPackageManager();
		hasRight = (PackageManager.PERMISSION_GRANTED) == pm.checkPermission(
				"android.permission.WRITE_SECURE_SETTINGS",
				this.getPackageName());
		IntentFilter f = new IntentFilter();
		f.addAction(BROADCAST_SETTING_CHANGED);
		f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
		boardcastReciever = new BroadcastReceiver(){

			@Override
			public void onReceive(Context context, Intent intent) {
				startUpdate();
				
			}
			
		};
		this.registerReceiver(boardcastReciever, f);
	}

	public void setupNotification(Context context, String title,
			String content, String msg) {
		SharedPreferences sp = this.getSharedPreferences("remoteDebug", 0);
		NotificationManager mNotificationManager = (NotificationManager) context
				.getSystemService(NOTIFICATION_SERVICE);
		Intent intent = new Intent(context, ControlerPanelActivity.class);
		PendingIntent pendingIntent = PendingIntent.getActivity(context, 1,
				intent, Notification.FLAG_SHOW_LIGHTS
						| Notification.FLAG_ONGOING_EVENT
						| Notification.FLAG_ONLY_ALERT_ONCE
						| Notification.FLAG_NO_CLEAR
						| Notification.FLAG_FOREGROUND_SERVICE);

		NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(
				context);
		mBuilder.setContentTitle(title)// 设置通知栏标题
				.setContentText(content) // 设置通知栏显示内容
				.setContentIntent(pendingIntent) // 设置通知栏点击意图
				// .setNumber(number) //设置通知集合的数量
				.setTicker(msg) // 通知首次出现在通知栏，带上升动画效果的
				
				// .setWhen(System.currentTimeMillis())//通知产生的时间，会在通知信息里显示，一般是系统获取到的时间
				// .setPriority(Notification.PRIORITY_HIGH) //设置该通知优先级
				// .setAutoCancel(true)//设置这个标志当用户单击面板就可以让通知将自动取消
				.setOngoing(true)// ture，设置他为一个正在进行的通知。他们通常是用来表示一个后台任务,用户积极参与(如播放音乐)或以某种方式正在等待,因此占用设备(如一个文件下载,同步操作,主动网络连接)
				.setDefaults(sp.getBoolean("isShakeEnable",true)?Notification.DEFAULT_VIBRATE:Notification.DEFAULT_LIGHTS)// 向通知添加声音、闪灯和振动效果的最简单、最一致的方式是使用当前的用户默认设置，使用defaults属性，可以组合
				// Notification.DEFAULT_ALL Notification.DEFAULT_SOUND 添加声音 //
				// requires VIBRATE permission
				.setSmallIcon(R.drawable.ic_launcher);// 设置通知小ICON
		
		mNotificationManager.notify(1, mBuilder.build());
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		this.unregisterReceiver(this.boardcastReciever);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.v(TAG, "AgentService onStartCommand");
		return super.onStartCommand(intent, flags, startId);
	}

	private void connectServer() {

		try {
			serverSocket.setSoTimeout(0);
			serverSocket.connect(getServerAddress(),5000);
			
			Log.d(TAG, "建立与服务器的连接："+serverSocket.getRemoteSocketAddress());
			InputStream is = serverSocket.getInputStream();
			OutputStream os = serverSocket.getOutputStream();
			DataOutputStream dos = new DataOutputStream(os);
			DataInputStream dis = new DataInputStream(is);

			String msg = getReportMsg();
			sendString(dos, msg);

			msg = readMsg(dis);
			
			JSONObject jo = new JSONObject(msg);
			boolean isOk = jo.getBoolean("isOk");
			msg = jo.getString("msg");

			if (!isConnected) { // 如果此前未连接则发送连接成功广播
				this.isConnected = true;
				this.sendBroadcast(new Intent(BROADCAST_CONNECTION_CHANGED)
						.putExtra("isOk", isOk).putExtra("msg", msg));

			} else { // 如果此前已经连接成功，说明很可能是断开重连
				Log.i(TAG, this.toast("远程调试已重新连接到服务器"));
				handler.postDelayed(new Runnable(){
					public void run(){
						sendConnectionChangedBoardcast(true);
					}
				},1000);
				
			}
			// 开始等待上位机调试的连接
			heartbeatForWaiting(this.serverSocket,dis,dos); //心跳等待连接，如果心跳包正常则一直等待
			//如果是连接信息则开始后面的连接流程，如果有任何异常情况，都会跳出去，这个链接就会关闭，只能重连

			// 上位机
			SharedPreferences sp = this.getSharedPreferences("remoteDebug", 0); 
			boolean isRemoteDebugEnable = sp.getBoolean("remoteDebugEnable",
					false);
			if (!isRemoteDebugEnable) { // 如果未启用，则返回未启用信息，并断开连接（为了防止通讯异常导致死连接）
				Log.i(TAG, "未启用远程调试");
				jo = new JSONObject();
				jo.put("isOk", false);
				jo.put("msg", "未启用远程调试");
				this.sendString(dos, jo.toString());
				dos.flush();
				return;
			}
			jo = new JSONObject();
			jo.put("isOk", true);
			jo.put("msg", "已经准备就绪等待ADB连接");
			this.sendString(dos, jo.toString());
			
			serverSocket.setSoTimeout(0); //之后的事，就控制不了，还原为不超时的长连接
			sendConnectionChangedBoardcast(true);

		} catch (IOException e) {
			if(!this.isConnected&&isFirstConnect){ //如果未连接，应该寄予充分提示
				Log.e(TAG, this.toastLong("与服务器网络通讯出错"), e);
			}else{ //如果是
				Log.d(TAG,"与服务器网络通讯出错",e);
			}
		} catch (JSONException e1) {
			Log.e(TAG, this.toastLong("JSON格式错"), e1);
		} finally {
			IOUtils.closeQuietly(this.serverSocket);
			isFirstConnect = false;
			this.sendConnectionChangedBoardcast(false);
		}
	}

	private void heartbeatForWaiting(Socket socket,DataInputStream dis, DataOutputStream dos) throws IOException {
		JSONObject jo = null;
		long time;
		try {
			while(true){
				jo = new JSONObject();
				time = System.currentTimeMillis();
//				jo.put("action", "heartBeat");
//				jo.put("time", time);
//				Log.d(TAG, "发送心跳报文");
//				this.sendString(dos, jo.toString()); //发送心跳
//				Log.d(TAG, "接收心跳报文");
				jo = new JSONObject(this.readMsg(dis));  //接收一笔信息，这个信息有可能不一定是心跳返回报文
				if(isHeartbeatResponse(jo, time)){ //心跳包，继续跳
					
					while (dis.available() <= 0) {
						if(socket.isClosed()){
							Log.i(TAG, "连接关闭，心跳报文停止");
							IOUtils.closeQuietly(dis);
							IOUtils.closeQuietly(dos);
							return;
						}
						Thread.sleep(10);
						if(System.currentTimeMillis()-time>this.heartbeatInterval){
							break;
						}
					}
					
					if(dis.available()<=0){ //没有数据的话，继续发送心跳报文,重新开始循环
						continue;
					}
					//到了这里应该是有数据，开始后面的流程了					
				}
//				String msg = this.readMsg(dis);
//				if(!this.checkConnctionMsg(msg)){
//					throw new RuntimeException("无法识别来自服务器的数据");
//				}
				return; //返回之后，就开始桥接ADB了
			}
			
			
		} catch (JSONException e) {
			throw new RuntimeException("json错误",e);
		} catch (IOException e) {
			throw new IOException("心跳包发送出错",e);
		} catch (InterruptedException e) {
			throw new RuntimeException("心跳包线程被打断",e);
		}
		
	}

	private boolean isHeartbeatResponse(JSONObject jo, long time)
			throws JSONException {
		return "heartBeat".equalsIgnoreCase(jo.getString("action"))&&time==jo.getLong("time");
	}

	private void sendString(DataOutputStream dos, String msg)
			throws  IOException {
		Log.d(TAG, "发送信息："+msg);
		byte[] buff = msg.getBytes("UTF-8");
		dos.writeInt(buff.length);
		dos.write(buff);
	}

	private InetSocketAddress getServerAddress() {
		return new InetSocketAddress(SERVER_IP, 5556);//
	}




	private String readMsg(DataInputStream dis) throws IOException {
		byte[] buff;
		int length ;
		try{
			length = dis.readInt();
		}catch(SocketException e){
			if(e.getMessage().contains("ECONNREST")){
				length = dis.readInt();
			}else{
				throw e;
			}
		}
		if (length > 32767) {
			throw new IllegalStateException("返回的信息长度超限：" + length);
		}
		buff = new byte[length];
		dis.readFully(buff);
		String msg = new String(buff, "UTF-8");
		Log.d(TAG, "接收信息："+msg);;
		return msg;
	}

	private String getReportMsg() throws JSONException {
		JSONObject jo = new JSONObject();
		jo.put("isOk", true);
		DeviceDesc dd = new DeviceDesc();
		JSONObject device = new JSONObject();
		
		dd.setTag(android.os.Build.SERIAL);
		dd.setBand(android.os.Build.BRAND);
		dd.setModel(android.os.Build.MODEL);
		dd.setStatus("在线");
		dd.setVersion(android.os.Build.VERSION.RELEASE);
		dd.setIp(this.ip);
		device.put("tag", dd.getTag());
		device.put("band", dd.getBand());
		device.put("model", dd.getModel());
		device.put("status", dd.getStatus());
		device.put("ip", dd.getIp());
		device.put("version",dd.getVersion());
		
		
		jo.put("device", device);
		
		
		return jo.toString();
	}


	private void asynCopy(final InputStream is, final OutputStream os) {
		new Thread() {
			public void run() {
				try {
					copyLarge(is, os);
				} catch (IOException e) {
					IOUtils.closeQuietly(is);
					IOUtils.closeQuietly(os);
				} finally {
					try {
						Thread.sleep(200);// 给点时间反应
					} catch (InterruptedException e) {
					}

				}
			}
		}.start();
	}

	private static long copyLarge(InputStream input, OutputStream output)
			throws IOException {
		byte[] buffer = new byte[32768];
		long count = 0;
		int n = 0;
		while (-1 != (n = input.read(buffer))) {
			output.write(buffer, 0, n);
			count += n;
		}
		return count;
	}

	private int execCommand(String... commands) throws IOException {
		Log.i("TAT", "执行指令");
		for (String str : commands) {
			Log.i(TAG, str);
		}
		Runtime runtime = Runtime.getRuntime();
		Process proc = runtime.exec(commands);
		try {
			proc.waitFor();
		} catch (InterruptedException e) {
			Log.e(TAG,"进程等待被打断",e);
		}
		return proc.exitValue();
	}

	public void suExec(String str) {
		try {
			// 权限设置
			Process p = Runtime.getRuntime().exec("su");
			// 获取输出流
			OutputStream outputStream = p.getOutputStream();
			DataOutputStream dataOutputStream = new DataOutputStream(
					outputStream);
			// 将命令写入
			dataOutputStream.writeBytes(str);
			// 提交命令
			dataOutputStream.flush();
			// 关闭流操作
			dataOutputStream.close();
			outputStream.close();
		} catch (Throwable t) {
			throw new RuntimeException("执行SU命令失败");
		}
	}

	private String execCommandForResult(String command) throws IOException {
		// start the ls command running
		// String[] args = new String[]{"sh", "-c", command};
		Runtime runtime = Runtime.getRuntime();
		Process proc = runtime.exec(command); // 这句话就是shell与高级语言间的调用
		// 如果有参数的话可以用另外一个被重载的exec方法
		// 实际上这样执行时启动了一个子进程,它没有父进程的控制台
		// 也就看不到输出,所以我们需要用输出流来得到shell执行后的输出
		InputStream inputstream = proc.getInputStream();
		InputStreamReader inputstreamreader = new InputStreamReader(inputstream);
		BufferedReader bufferedreader = new BufferedReader(inputstreamreader);
		// read the ls output
		String retMsg = "";
		String line = "";
		StringBuilder sb = new StringBuilder(line);
		while ((line = bufferedreader.readLine()) != null) {
			if (command.equals(line)) {
				continue;
			}
			sb.append(line);
			sb.append('\n');
		}
		// tv.setText(sb.toString());
		// 使用exec执行不会等执行成功以后才返回,它会立即返回
		// 所以在某些情况下是很要命的(比如复制文件的时候)
		// 使用wairFor()可以等待命令执行完成以后才返回
		try {
			proc.waitFor();
			retMsg = "exit value = " + proc.exitValue();
			Log.i(TAG, retMsg);
		} catch (InterruptedException e) {
			System.err.println(e);
		}
		String result = sb.toString();
		Log.d(TAG, result);
		toast(retMsg, result);
		return result;
	}

	private String toast(final String msg) {
		handler.post(new Runnable() {
			public void run() {
				Toast.makeText(AgentService.this, msg, Toast.LENGTH_SHORT)
						.show();
			}
		});
		return msg;
	}

	private String toastLong(final String msg) {
		handler.post(new Runnable() {
			public void run() {
				Toast.makeText(AgentService.this, msg, Toast.LENGTH_LONG)
						.show();
			}
		});
		return msg;

	}

	private void toast(final String retMsg, final String result) {
		toast(retMsg + "\n" + result);

	}

	public class MsgBinder extends Binder {
		/**
		 * 获取当前Service的实例
		 * 
		 * @return
		 */
		public AgentService getService() {
			return AgentService.this;
		}

	}

	private void observe() {
		ContentResolver resolver = this.getContentResolver();
		resolver.registerContentObserver(Settings.Secure
				.getUriFor(android.provider.Settings.Global.ADB_ENABLED),
				false, new ContentObserver(null) {
					@Override
					public void onChange(boolean selfChange) {
						startUpdate();
					}
				});
	}
	private void startUpdate() {
		update();

	}

	private String intToip(int i) {
		return (i & 0xFF) + "." + ((i >> 8) & 0xff) + "." + ((i >> 16) & 0xff)
				+ "." + ((i >> 24) & 0xff);
	}
	private void update() {
		Log.i(TAG, "更新状态");
		WifiManager wifiManager = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);
		if (!wifiManager.isWifiEnabled()) {
			ip = "未启用WIFI";
		} else {
			ip = intToip(wifiManager.getConnectionInfo().getIpAddress());
		}
		hasRight = (PackageManager.PERMISSION_GRANTED) == this.getPackageManager().checkPermission(
				"android.permission.WRITE_SECURE_SETTINGS",
				this.getPackageName());
		ContentResolver resolver = this.getContentResolver();
		boolean mAdbEnabled = Settings.Secure.getInt(resolver,
				android.provider.Settings.Global.ADB_ENABLED, 0) != 0;
		
		if(!mAdbEnabled){
			this.closeServerConnection();
			setupNotification(this, "远程调试AGENT", "本服务关闭，请先开启'USB调试'",
					this.toastLong("远程调试AGENT关闭，请先开启'USB调试'"));
			return;
		}
		//检测是否WIFI网络，否则断开连接
		ConnectivityManager c = (ConnectivityManager) AgentService.this
				.getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo info = c.getActiveNetworkInfo();
		if(info==null){
			this.hasNetwork = false;
			this.closeServerConnection();
			setupNotification(this, "远程调试AGENT", "本服务关闭，请先通过WIFI连接到网络",
					this.toastLong("远程调试AGENT服务关闭，请先通过WIFI连接到网络"));
			return;
		}else if(info.getType() != ConnectivityManager.TYPE_WIFI){
			this.closeServerConnection();
			if(this.isOccupiedPort()){ //
				if(!disableDebug()){ //如果无法在非时候关闭无线ADB，则提示用户关闭ADB，防止被互联网攻击入侵
					this.toastLong("远程调试AGENT没有权限关闭无线ADB，为了避免接收到互联网的连接和攻击，请务必关闭ADB");
					Intent intent = new Intent(
							Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
					intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
					startActivity(intent);
					return;
				}
			}else{
				//说明只是没有网络而已，已经没有网络ADB的情况，那就休眠吧
				this.hasNetwork = false;
				this.closeServerConnection();
				return;
			}
		}
		this.hasNetwork = true;
		//下面开始处理有WIFI网络连接的情况
		SharedPreferences sp = getSharedPreferences("remoteDebug", 0);
		boolean isRemoteDebugEnable = sp.getBoolean("remoteDebugEnable",true);
		if((!isRemoteDebugEnable)&&this.isServerConnecting()){ //如果不应该启动连接而正在启动，则终止
			this.closeServerConnection();
			setupNotification(this, "远程调试AGENT", "已从服务器断开，如果需要开启请进入界面",
					this.toastLong("远程调试AGENT已从服务器断开，如果需要开启请进入界面"));
			return;
		}else if(isRemoteDebugEnable&&this.isServerConnecting()&&!mAdbEnabled){ //是连接状态下关闭ADB
			this.closeServerConnection();
			setupNotification(this, "远程调试AGENT", "已从服务器断开，如果需要开启请进入界面",
					this.toastLong("远程调试AGENT已从服务器断开，如果需要开启请进入界面"));
			return;
		}else if((!isRemoteDebugEnable)&&(!this.isServerConnecting())){ //如果不应该启动则更新信息提示启动方法
			setupNotification(this, "远程调试AGENT", "未连接到服务器，请进入界面中开启",
					this.toastLong("远程调试AGENT未连接到服务器，请进入界面中开启"));
			sendConnectionChangedBoardcast(false);
			return;
		}else if(isRemoteDebugEnable&&!this.isServerConnecting()){ //如果应该启动服务器连接而未连接则，则启动
			try{
				
				String port = sp.getString("adbdPort", "5555"); // 如果端口为0 ，则仅启动USB调试
				try {
					if (this.hasRight) { // 如果有权限，就自己设置端口和重启服务,实现免人工操作
						disableDebug();
						suExec("setprop service.adb.tcp.port " + port);
						Settings.Secure.putInt(getContentResolver(),
								android.provider.Settings.Global.ADB_ENABLED, 1); //重启ADB
						startConnectServer(100); //开始相关服务器连接
					} else {// 如果没有权限，则检查是否已经有相关的ADB TCPIP 服务，如果没有则弹出相关的吐司提示，并且以默认方式启动，等待用户手工操作
						if(!isOccupiedPort()){
							setupNotification(this, "远程调试AGENT", "ADB已开启，请确保已经执行'ADB TCPIP "+ port,
									this.toastLong("请确保手机已经执行'ADB TCPIP "+ port
											+ "'，否则上位机无法连接到本设备"));
							
						}else{
							setupNotification(this, "远程调试AGENT", "已启动远程调试功能，点击进入设置",
									this.toastLong("已启动远程调试功能，可以接收来自服务器的测试指令"));
						}
						startConnectServer(1);
						
					}
				} catch (Throwable e) {
					Log.e(TAG, "启动ADB出错，请确认手机已经被root", e);
					throw new Exception("启动ADB出错，请确认手机已经被root");
				}
			}catch(Throwable e){
				setupNotification(this, "远程调试AGENT", "启动远程调试服务出错", 
						this.toastLong("远程调试AGENT启动远程调试服务出错"));
			}
		}else{
			//什么都不用干
			Log.e(TAG, "落到这个分支");
			this.sendConnectionChangedBoardcast(this.isServerConnected());
//			this.closeServerConnection();
		}
	}

	public boolean isHasNetwork() {
		return hasNetwork;
	}

	/**
	 * 
	 * @return
	 */
	private boolean disableDebug()  {

		boolean enableAdb = (Settings.Secure.getInt(getContentResolver(),
				android.provider.Settings.Global.ADB_ENABLED, 0) > 0);
		if (enableAdb && this.hasRight) {
			Settings.Secure.putInt(getContentResolver(),
					android.provider.Settings.Global.ADB_ENABLED, 0);
			this.closeServerConnection();
			return true;
		} else if (enableAdb) {
			this.toast("没有权限关闭ADB,如需要重启ADB，请到开发者设置界面中设置");
		}
		
		return false;
	}

	public void closeServerConnection() {
		if(this.serverSocket==null&&serverSocketThread == null){
			sendConnectionChangedBoardcast(false);
			return;
		}
		if(this.serverSocket!=null&&!this.serverSocket.isClosed()){
			try{
//				IOUtils.closeQuietly(this.serverSocket.getInputStream());
//				IOUtils.closeQuietly(this.serverSocket.getOutputStream());
				this.serverSocket.close();
			}catch(Throwable e){
				e.printStackTrace();
			}
		}
		
//		IOUtils.closeQuietly(this.serverSocket);
		if(this.serverSocket!=null&&!this.serverSocket.isClosed()){
			Log.e(TAG, "关不了Socket？");
			serverSocketThread.interrupt();
			IOUtils.closeQuietly(this.serverSocket);
		}
		this.serverSocket = null;
		long startTime = System.currentTimeMillis();
		try {
			while(serverSocketThread.isAlive()){
				
				if(System.currentTimeMillis()-startTime>10000){
					throw new RuntimeException("关闭失败");
				}
				Thread.sleep(10);
			}
		} catch (InterruptedException e) {
			
		}
		this.serverSocket = null;
		Log.i(TAG, "与服务器的连接已断开");
		this.toast("与服务器的连接已断开");
		
		
		serverSocketThread = null;
		sendConnectionChangedBoardcast(false);
	}
	


//	public void enableRemoteDebug() throws Exception {
//		
//		String port = getPortFromPref();
//		try {
//			if (this.hasRight) { // 如果有权限，就自己设置端口和重启服务
//				
//				suExec("setprop service.adb.tcp.port " + port);
//				Settings.Secure.putInt(getContentResolver(),
//						android.provider.Settings.Global.ADB_ENABLED, 1);
//				startConnectServer(100);
//
//			} else {// 如果没有权限，则弹出相关的吐司提示，并且以默认方式启动
//				this.toastLong("请确保手机已经执行'ADB TCPIP "+ port
//						+ "'，否则上位机无法连接到本设备");
//				startConnectServer(1);
//			}
//		} catch (Throwable e) {
//			Log.e(TAG, "启动ADB出错，请确认手机已经被root", e);
//			throw new Exception("启动ADB出错，请确认手机已经被root");
//		}
//	}
	
	

	public void startConnectServer(final int delay) {
		if(this.serverSocket!=null||serverSocketThread!=null){
			throw new RuntimeException("请先断开服务器连接"); //一定是某些地方存在状态不对
		}
		this.serverSocket = new Socket();
		serverSocketThread = new Thread() {
			public void run() {
				try {
					this.setName("startConnectServer");
					Thread.sleep(delay);
					isFirstConnect = true;
					while(recreateSocket()){
						// 上位机
						SharedPreferences sp = getSharedPreferences("remoteDebug", 0);
						boolean shouldConnectServer = sp.getBoolean("remoteDebugEnable",
								true);
						if(!shouldConnectServer){
							break;
						}
						this.setName("connectServer");
						connectServer();
						if(serverSocket!=null&&!serverSocket.isClosed()){
							Thread.sleep(retryInterval);//休眠15秒后重试
						}
						
					}
				} catch( Throwable e){
					Log.e(TAG, "与服务器连接出错",e);
				}finally{
					AgentService.this.isConnected = false;
					isFirstConnect = true;
					sendConnectionChangedBoardcast(false);
//					closeServerConnection();
				}

			}
		};
		serverSocketThread.start();
	}
	/**
	 * 再次创建serverSocket
	 * @return
	 */
	private boolean recreateSocket(){
		if(serverSocket!=null){
			serverSocket = new Socket();
			return true;
		}
		return false;
	}

	/**
	 * 判断服务器是否正在连接（正在连接不代表一定接受远程调试，可以仅仅表示手机正在网络内而已）
	 * 
	 * @return
	 */
	public synchronized boolean isServerConnecting() {
		return (this.serverSocket != null&&this.serverSocketThread!=null);
	}
	
	public synchronized boolean isServerConnected() {
		return (this.serverSocket != null&&this.serverSocket.isConnected());
	}

	/**
	 * 判断是否启用了远程调试（启用了不代表正在使用，可以仅仅表示允许）
	 * 
	 * @return
	 */
	public boolean isRemoteDebugEnable() {
		SharedPreferences sp = this.getSharedPreferences("remoteDebug", 0);
		return sp.getBoolean("remoteDebugEnable", true);
	}

	public boolean isRemoteDebuging() {
		return false;
	}
	
	private synchronized  boolean isOccupiedPort(){
		String port = getPortFromPref();
		try {
			ServerSocket server = new ServerSocket(Integer.parseInt(port));			
			IOUtils.closeQuietly(server);
		} catch (NumberFormatException e) {
			throw new RuntimeException("端口信息错",e);
		} catch (IOException e) {
			return true;
		}
		return false;
	}

	private String getPortFromPref() {
		SharedPreferences sp = this.getSharedPreferences("remoteDebug", 0);
		String port = sp.getString("adbdPort", "4555");
		return port;
	}

	private String getPid() throws IOException {
		String msg = execCommandForResult("ps | grep adbd");
		BufferedReader reader = new BufferedReader(new StringReader(msg));
		String line = reader.readLine();

		if (line.startsWith("USER")) {
			line = reader.readLine();
		}
		reader.close();
		Matcher m = Pattern.compile("\\d+").matcher(line);
		if (!m.find()) {
			return null;
		} else {
			return m.group();
		}
	}

	private void restartRemoteDebug(String port) throws IOException {

		suExec("stop adbd");
		suExec("setprop service.adb.tcp.port " + port);
		suExec("start adbd ");
		// android.os.Process.killProcess(pid);

	}

	private void startRemoteDebug(String port) {
		suExec("setprop service.adb.tcp.port " + port);
		suExec("start adbd ");
	}

	private void alert(final String msg) {
		handler.post(new Runnable() {
			public void run() {
				new AlertDialog.Builder(AgentService.this).setTitle(msg)
						.setPositiveButton("确定", null).create().show();
			}
		});

	}

	private void alertAndLog(String msg) {
		this.alertAndLog(msg, null);
	}

	private void alertAndLog(String msg, Throwable e) {
		Log.e(TAG, msg, e);
		alert(msg);
	}

	private void sendConnectionChangedBoardcast(boolean isOk) {
		sendBroadcast(new Intent(BROADCAST_CONNECTION_CHANGED).putExtra("isOk", isOk));
	}
}
