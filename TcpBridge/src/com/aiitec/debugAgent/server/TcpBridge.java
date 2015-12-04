package com.aiitec.debugAgent.server;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.aiitec.debugAgent.DeviceDesc;
import com.aiitec.debugAgent.OnCloseCallback;
import com.aiitec.debugAgent.StreamUtils;


public class TcpBridge implements Runnable{

	static Logger log = LogManager.getLogger(TcpBridge.class);
	int port = 5556;
	private ServerSocket mobileServerSocket;
	private ServerSocket adbServerSocket;
	private ArrayList<MobileConnection> mobileConnectionList = new ArrayList<MobileConnection>();
	private ArrayList<AdbConnection> adbConnectionList = new ArrayList<AdbConnection>();
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		TcpBridge tcp  = new TcpBridge();
		new Thread(tcp).start();
		
	}
	
	public void mobileServerRun() {
		try {
			mobileServerSocket = new ServerSocket(port);
			log.info("服务器设备方向开始侦听");
			while (true) {
				
				Socket s = mobileServerSocket.accept();
				handlerMobileSocket(s);
			}

		} catch (IOException e) {
			log.error("通讯异常",e);
			if (mobileServerSocket.isClosed()) {
				log.info("设备方向服务端口关闭");
			} else {
				log.info("设备方向启动失败");
				
			}
		}
	}
	private void adbServerRun() {
		try {
			adbServerSocket = new ServerSocket(4555);
			log.info("Adb方向服务器开始侦听");
			while (true) {
				
				Socket s = adbServerSocket.accept();
				handlerAdbSocket(s);
			}

		} catch (Throwable e) {
			if (adbServerSocket.isClosed()) {
				log.info("Adb方向服务端口关闭");
			} else {
				log.info("Adb方向启动失败");
				e.printStackTrace();
			}
		}
	}


	private void handlerAdbSocket(Socket adbSocket) {
		log.info("ADB AGENT 客户端已连接"+adbSocket.getRemoteSocketAddress());
		InputStream is;
		try {
			is = adbSocket.getInputStream();
			OutputStream os = adbSocket.getOutputStream();
			DataOutputStream dos = new DataOutputStream(os);
			DataInputStream dis = new DataInputStream(is);
			
			JSONObject jo = JSONObject.fromObject(readStringMsg(dis));
			if(jo.getBoolean("isOk")){				
				log.info("user="+jo.getString("user"));
			}
			sendMsg(dos, getServerNotice());
			//然后等待之后的处理
			addAdbConnection(adbSocket);
		} catch (IOException e) {
	
			log.error("处理ADB AGENT登陆出错",e);
			IOUtils.closeQuietly(adbSocket);
		}
		
			
	}
	private void tryStartBridge(final Socket adbSocket,final MobileConnection mobileConnection) {
		try {
			log.info("尝试停止心跳");
			final Socket mobileSocket = mobileConnection.stopHeartBeat();
			
			log.info("开始建立桥接");
			sendMsg(new DataOutputStream(mobileSocket.getOutputStream()),genStartAdbMsg(adbSocket.getRemoteSocketAddress()));
			JSONObject jo = readJson(mobileSocket);
			if(jo.has("action")&&"heartBeat".equalsIgnoreCase(jo.getString("action"))){ //碰巧对方在发心跳包，先忽略
				jo = readJson(mobileSocket);
			}
			if(!jo.getBoolean("isOk")){
				writeConfirmResponse(adbSocket,false,jo.getString("msg")); //返回给客户端失败信息，然后后面进行断开处理
				throw new Exception("服务器未能完成ADB桥接："+jo.getString("msg"));
			}
			log.info("设备返回："+jo.getString("msg"));
			log.info("开始桥接");
			StreamUtils.asynCopy(adbSocket.getInputStream(), mobileSocket.getOutputStream(),new OnCloseCallback(){
				@Override
				public void onClose() {
					log.info("关闭adb连接"+adbSocket);
					log.info("关闭设备连接"+mobileSocket);
					IOUtils.closeQuietly(adbSocket);
					releaseConnection(mobileConnection);
					
				}

				@Override
				public void onException(Throwable e) {
					log.error("桥接通讯异常",e);
				}
			});
			StreamUtils.synCopy(mobileSocket.getInputStream(), adbSocket.getOutputStream());
			log.info("桥接完成退出");
		} catch (IOException e) {
			log.error("通讯异常",e);
		} catch (Exception e) {
			log.error("创建ADB桥接失败",e);
		} finally{
			log.debug("断开ADB连接："+adbSocket);
			IOUtils.closeQuietly(adbSocket);
			releaseConnection(mobileConnection);
		}
//		log.info("连接断开:" + s);

	}

	private JSONObject readJson(final Socket mobileSocket)
			throws UnsupportedEncodingException, IOException {
		return JSONObject.fromObject(readStringMsg(new DataInputStream(mobileSocket.getInputStream())));
	}
	protected void waitForAdbClient(Socket adbSocket2) {
		// TODO Auto-generated method stub
		
	}
	private void writeConfirmResponse(Socket socket,boolean isOk,String msg) throws IOException{
		JSONObject jo = new JSONObject();
		jo.put("isOk",isOk);
		jo.put("msg",msg);
		sendMsg(new DataOutputStream(socket.getOutputStream()),jo.toString());
	}
	protected String getServerNotice() {
		JSONObject jo = new JSONObject();
		jo.put("isOk",true);
		jo.put("msg","欢迎登陆到服务器");
		return jo.toString();
	}
	private static byte[] readMsg(DataInputStream dis) throws IOException {
		byte[] buff;
		int length = dis.readInt();
		if (length > 32767) {
			byte[] dump= new byte[64];
			int len = dis.read(dump);
			StringBuilder sb = new StringBuilder();
			for(int i=0;i<len;i++){
				sb.append(dump[i]).append(" ");
			}
			log.error("长度超限："+length+"+"+sb.toString());
			throw new IllegalStateException("返回的信息长度超限：" + length);
		}
		buff = new byte[length];
		dis.readFully(buff);
		return buff;
	}
	private void handlerMobileSocket(final Socket s) {
		log.info("设备已连接："+s.getRemoteSocketAddress());
		InputStream is;
		try {
			is = s.getInputStream();
			OutputStream os = s.getOutputStream();
			DataOutputStream dos = new DataOutputStream(os);
			DataInputStream dis = new DataInputStream(is);
			
			JSONObject jo = JSONObject.fromObject(readStringMsg(dis));
			if(jo.getBoolean("isOk")){		
				
			}
			sendMsg(dos, getServerNotice());
			//然后等待之后的处理
			DeviceDesc desc =(DeviceDesc)JSONObject.toBean(jo.getJSONObject("device"), DeviceDesc.class);
			addMobileConnection(s,desc);
		} catch (IOException e) {
			e.printStackTrace();
			IOUtils.closeQuietly(s);
		}
		
	}
	private static void sendMsg(DataOutputStream dos, String msg)
			throws IOException {
		log.info("发送消息："+msg);
		byte[] buff = msg.getBytes("UTF-8");
		dos.writeInt(buff.length);
		dos.write(buff);
	}
	private static String readStringMsg(DataInputStream dis)
			throws UnsupportedEncodingException, IOException {
		String msg = new String(readMsg(dis),"UTF-8");
		log.info("接收到信息："+msg);
		return msg;
	}
	private synchronized void addAdbConnection(Socket s) {
		log.info("ADB AGENT已加入到列表："+s.getRemoteSocketAddress());
		AdbConnection c= new AdbConnection(s);
		adbConnectionList.add(c);
		c.start();
	}
	private synchronized void addMobileConnection(Socket s,DeviceDesc desc) {
		log.info("设备已加入到列表："+s.getRemoteSocketAddress());
		MobileConnection c= new MobileConnection(s);
		c.deviceDesc = desc;
		mobileConnectionList.add(c);
		c.start();
	}
	private synchronized MobileConnection pickupConnection(String ip){
		MobileConnection result = null;
		for(MobileConnection c:this.mobileConnectionList){
			if(c.socket!=null&&((InetSocketAddress)c.socket.getRemoteSocketAddress()).getHostName().equals(ip)){
				c.isStartAdb = true; //让心跳停止
				result = c;
				break;
			}
		}
		if(result!=null){
			this.mobileConnectionList.remove(result);
			return result;
		}
		return null; //
	}

	/**
	 * 断掉连接，让设备自己重连上来。
	 * @param c
	 */
	private synchronized void releaseConnection(MobileConnection c) {
		log.info("设备已断开连接："+c.socket.getInetAddress());
		IOUtils.closeQuietly(c.socket);
		
	}
	private synchronized void removeAdbConnection(String ip){
		for(int i=0;i<this.adbConnectionList.size();i++){
			AdbConnection c = adbConnectionList.get(i);
			if(c.socket.getInetAddress().getHostName().equals(ip)){
				log.debug("移除ADB连接："+ip);
				if(c.isAlive()){
					c.isShuttingDown = true;
					log.debug("断开ADB连接："+ip);
					IOUtils.closeQuietly(c.socket);
				}
				adbConnectionList.remove(i);
				return;
			}
		}
	}
	private synchronized void removeMobileConnection(String ip){
		for(int i=0;i<this.mobileConnectionList.size();i++){
			MobileConnection c = mobileConnectionList.get(i);
			if(c.socket.getInetAddress().getHostName().equals(ip)){
				log.debug("移除连接设备："+ip);
				if(c.isAlive()){
					c.isShuttingDown = true;
					log.debug("断开设备连接："+ip);
					IOUtils.closeQuietly(c.socket);
				}
				mobileConnectionList.remove(i);
				return;
			}
		}
	}
	@Override
	public void run() {
		MobileConnection m = new MobileConnection(null);
		m.deviceDesc = new DeviceDesc();
		m.deviceDesc.band="testBand";
		m.deviceDesc.model="testModel";
		m.deviceDesc.status="tStatus";
		m.deviceDesc.tag="testTag";
		m.deviceDesc.ip="127.0.0.1";
		m.deviceDesc.version="4.4";
		this.mobileConnectionList.add(m);
		new Thread(){
			public void run(){
				adbServerRun();
			}
		}.start();
		new Thread(){
			public void run(){
				mobileServerRun();
			}
		}.start();
		
	}
	private synchronized ArrayList<DeviceDesc> getDevicesTagList(){
		ArrayList<DeviceDesc> result = new ArrayList<DeviceDesc>();
		for(MobileConnection c:mobileConnectionList){
			result.add(c.deviceDesc);
		}
		return result;
	}
	public class AdbConnection extends Thread{
		
		public void run(){
			try {
				DataOutputStream dos = new DataOutputStream(this.socket.getOutputStream());
				DataInputStream dis = new DataInputStream(this.socket.getInputStream());
				while(!isStartAdb){
					if(dis.available()<=0){
						Thread.sleep(10);
						continue;
					}
					if(isStartAdb){
						break;
					}
					JSONObject jo = JSONObject.fromObject(readStringMsg(dis)); //接收心跳
					if(isHeartbeatResponse(jo)){ 
						sendMsg(dos,jo.toString()); //心跳回响
					}else if("startAdb".equalsIgnoreCase(jo.getString("action"))){
						
						MobileConnection  c = pickupConnection(jo.getString("device"));
						if(c!=null){
							isStartAdb = true;
							jo = new JSONObject();
							jo.put("isOk", true);
							sendMsg(dos,jo.toString());
							tryStartBridge(this.socket,c);
							break;
						}
						//如果找不到设备，可能是被占用了，返回信息后，断开连接（如果是一些异常情况，希望这种重连能够解决）
						jo = new JSONObject();
						jo.put("isOk", false);
						jo.put("msg", "找不到设备，可能已经被占用，请重试？");
						sendMsg(dos,jo.toString());
						releaseConnection(c);
					}else if("getDevices".equalsIgnoreCase(jo.getString("action"))){
						jo = new JSONObject();
						jo.put("isOk", true);
						JSONArray array = new JSONArray();
						array.addAll(getDevicesTagList());
						jo.put("result", array);
						sendMsg(dos,jo.toString());
						continue;//继续循环
					}
				}
				
			} catch (IOException e) {
				if(!isShuttingDown){
					log.error("客户端连接异常："+this.socket,e);
				}
				//如果isShuttingDown 那么就不吭声了
				removeAdbConnection(this.socket.getInetAddress().getHostName());;
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			
		}
		public AdbConnection(Socket s) {
			this.socket = s;
		}

		public Socket socket;
		private boolean isShuttingDown = false;
		private boolean isStartAdb = false;
	}
	public class MobileConnection extends Thread{
		DeviceDesc deviceDesc;
		
		public synchronized Socket stopHeartBeat(){
			isStartAdb = true;
//			if(this.isAlive()){
//				log.info("心跳线程未推出");
//			}
//			while(this.isAlive()){
//				try {
//					Thread.sleep(1);
//				} catch (InterruptedException e) {
//					e.printStackTrace();
//				}
//			}
//			log.info("心跳线程退出");
			return this.socket;
		}
		public void run(){
			try {
				DataOutputStream dos = new DataOutputStream(this.socket.getOutputStream());
				DataInputStream dis = new DataInputStream(this.socket.getInputStream());
				while(!isStartAdb){
					if(dis.available()<=0){
						Thread.sleep(10);
						continue;
					}
					if(isStartAdb){
						break;
					}
					JSONObject jo = JSONObject.fromObject(readStringMsg(dis)); //接收心跳
					if(isHeartbeatResponse(jo)){ 
						sendMsg(dos,jo.toString()); //心跳回响
					}
				}
				log.info(this.deviceDesc.tag+"心跳循环停止");
			} catch (IOException e) {
				log.error("客户端连接异常："+this.socket,e);
				if(!isShuttingDown){
					
				}else{
					//如果isShuttingDown 那么就不吭声了
					removeMobileConnection(((InetSocketAddress)this.socket.getRemoteSocketAddress()).getHostName());;
				}
				
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			
		}
		public MobileConnection(Socket s) {
			this.socket = s;
		}

		private Socket socket;
		private boolean isShuttingDown = false;
		private boolean isStartAdb = false;
	}
	private static boolean isHeartbeatResponse(JSONObject jo){
		if(jo.has("action")){
			return "heartBeat".equalsIgnoreCase(jo.getString("action"));
		}else{
			return false;
		}
		
	}
	private String genStartAdbMsg(SocketAddress socketAddress) {
		JSONObject jo = new JSONObject();
		jo.put("action","startAdb");
		jo.put("from",socketAddress.toString());
		return jo.toString();
	}
	
}
